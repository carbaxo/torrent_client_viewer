// Cliente de Real-Debrid (https://api.real-debrid.com). El token es POR
// CUENTA: lo guarda el servidor en userdata (nivel account) y jamás sale
// al navegador. Flujo de streaming: addMagnet -> selectFiles -> poll hasta
// "downloaded" -> unrestrict del enlace -> URL HTTPS directa reproducible.

const API = 'https://api.real-debrid.com/rest/1.0'

export class RdError extends Error {
  constructor (code, message, status = 502) {
    super(message)
    this.code = code
    this.status = status
  }
}

const VIDEO_RE = /\.(mp4|mkv|avi|m4v|webm|mov|wmv|mpg|mpeg|ts)$/i

export function createRealDebrid ({ fetchImpl, pollMs = 1500, pollTries = 10 } = {}) {
  const doFetch = fetchImpl || globalThis.fetch

  async function rd (token, method, path, form) {
    const res = await doFetch(API + path, {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(form ? { 'Content-Type': 'application/x-www-form-urlencoded' } : {})
      },
      body: form ? new URLSearchParams(form).toString() : undefined
    })
    if (res.status === 401) throw new RdError('BAD_TOKEN', 'Token de Real-Debrid inválido o caducado.', 401)
    if (res.status === 403) throw new RdError('NO_PREMIUM', 'La cuenta de Real-Debrid no es premium o está bloqueada.', 403)
    if (!res.ok && res.status !== 204) {
      let detail = ''
      try { detail = (await res.json()).error || '' } catch {}
      throw new RdError('RD_ERROR', `Real-Debrid respondió ${res.status}${detail ? ': ' + detail : ''}.`)
    }
    if (res.status === 204) return null
    try { return await res.json() } catch { return null }
  }

  // Valida el token y devuelve datos básicos de la cuenta
  async function getUser (token) {
    const u = await rd(token, 'GET', '/user')
    return { username: u.username, premium: u.type === 'premium', expiration: u.expiration }
  }

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

  // magnet -> URL directa del vídeo principal (o estado si RD aún descarga).
  // Devuelve { ready: true, url, filename } o { ready: false, status, progress }.
  async function streamMagnet (token, magnet) {
    const added = await rd(token, 'POST', '/torrents/addMagnet', { magnet })
    const id = added && added.id
    if (!id) throw new RdError('ADD_FAILED', 'Real-Debrid no aceptó el magnet.')

    let info = await rd(token, 'GET', `/torrents/info/${id}`)
    if (info.status === 'waiting_files_selection') {
      // Seleccionamos solo los archivos de vídeo (o todos si no hay match)
      const videos = (info.files || []).filter((f) => VIDEO_RE.test(f.path))
      const files = videos.length ? videos.map((f) => f.id).join(',') : 'all'
      await rd(token, 'POST', `/torrents/selectFiles/${id}`, { files })
    }

    for (let i = 0; i < pollTries; i++) {
      info = await rd(token, 'GET', `/torrents/info/${id}`)
      if (info.status === 'downloaded') break
      if (['magnet_error', 'error', 'virus', 'dead'].includes(info.status)) {
        throw new RdError('RD_TORRENT_ERROR', `Real-Debrid no pudo procesar el torrent (${info.status}).`)
      }
      await sleep(pollMs)
    }

    if (info.status !== 'downloaded') {
      // RD sigue descargándolo en sus servidores: no es un error
      return { ready: false, status: info.status, progress: info.progress || 0, rdId: id }
    }

    const link = (info.links || [])[0]
    if (!link) throw new RdError('NO_LINKS', 'Real-Debrid no devolvió enlaces.')
    const un = await rd(token, 'POST', '/unrestrict/link', { link })
    if (!un || !un.download) throw new RdError('UNRESTRICT_FAILED', 'No se pudo generar el enlace directo.')
    return { ready: true, url: un.download, filename: un.filename || info.filename || 'video' }
  }

  return { getUser, streamMagnet }
}
