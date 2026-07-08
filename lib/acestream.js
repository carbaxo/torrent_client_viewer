// AceStream para la web/Electron (paridad con AceStream.kt de Android).
// Lista y busca canales P2P (search-ace.stream), detecta el AceStream Engine
// local (127.0.0.1:6878) y resuelve el content-id a una URL reproducible.
// La reproducción real se abre en la app de AceStream (acestream://<id>),
// igual que hace la app móvil de forma fiable.

const ENGINE = 'http://127.0.0.1:6878'
const PLAYLIST_URL = 'https://search-ace.stream/playlist'
const SEARCH_URL = 'https://search-ace.stream/search'
const SITE = 'https://acestreamid.com'
const CONTENT_ID = /[0-9a-fA-F]{40}/

const CATEGORY_ES = {
  sport: '⚽ Deportes', sports: '⚽ Deportes', movies: '🎬 Películas', movie: '🎬 Películas',
  series: '📺 Series', music: '🎵 Música', kids: '🧒 Infantil', children: '🧒 Infantil',
  documentaries: '📚 Documentales', documentary: '📚 Documentales', news: '📰 Noticias',
  informational: '📰 Información', entertaining: '😂 Entretenimiento', entertainment: '😂 Entretenimiento',
  comedy: '😂 Comedia', educational: '🎓 Educación', regional: '📍 Regional', ethnic: '🌍 Étnico',
  religion: '⛪ Religión', fashion: '👗 Moda', adult: '🔞 Adultos', cyber_games: '🎮 Videojuegos',
  webcam: '📷 Webcam', general: '📺 General'
}

const ISO_NAME = {
  ES: '🇪🇸 España', MX: '🇲🇽 México', AR: '🇦🇷 Argentina', CO: '🇨🇴 Colombia', CL: '🇨🇱 Chile',
  PE: '🇵🇪 Perú', VE: '🇻🇪 Venezuela', EC: '🇪🇨 Ecuador', UY: '🇺🇾 Uruguay', US: '🇺🇸 EE. UU.',
  GB: '🇬🇧 Reino Unido', UK: '🇬🇧 Reino Unido', PT: '🇵🇹 Portugal', FR: '🇫🇷 Francia', IT: '🇮🇹 Italia',
  DE: '🇩🇪 Alemania', BR: '🇧🇷 Brasil', NL: '🇳🇱 Países Bajos', TR: '🇹🇷 Turquía', GR: '🇬🇷 Grecia',
  PL: '🇵🇱 Polonia', RU: '🇷🇺 Rusia', RO: '🇷🇴 Rumanía', MA: '🇲🇦 Marruecos'
}
const KEYWORDS = [
  ['España', 'ES'], ['Spain', 'ES'], ['Latino', 'MX'], ['México', 'MX'], ['Mexico', 'MX'],
  ['Argentina', 'AR'], ['Colombia', 'CO'], ['Chile', 'CL'], ['Portugal', 'PT'],
  ['Brasil', 'BR'], ['Brazil', 'BR'], ['Italia', 'IT'], ['France', 'FR'], ['Deutsch', 'DE']
]

function categoryLabel (raw) {
  const key = String(raw || '').trim().toLowerCase()
  return CATEGORY_ES[key] || (String(raw || '').trim() || '📺 Otros')
}

// País a partir de una bandera emoji (dos "regional indicator") o keyword
function flagIso (s) {
  const cps = [...String(s || '')].map((c) => c.codePointAt(0))
  for (let i = 0; i < cps.length - 1; i++) {
    const a = cps[i]; const b = cps[i + 1]
    if (a >= 0x1F1E6 && a <= 0x1F1FF && b >= 0x1F1E6 && b <= 0x1F1FF) {
      return String.fromCharCode(65 + (a - 0x1F1E6)) + String.fromCharCode(65 + (b - 0x1F1E6))
    }
  }
  return null
}

function detectCountry (name) {
  const iso = flagIso(name)
  if (iso && ISO_NAME[iso]) return ISO_NAME[iso]
  const m = /^\s*[[(]?([A-Z]{2})[\])| :]/.exec(name || '')
  if (m && ISO_NAME[m[1]]) return ISO_NAME[m[1]]
  for (const [kw, code] of KEYWORDS) {
    if (String(name || '').toLowerCase().includes(kw.toLowerCase()) && ISO_NAME[code]) return ISO_NAME[code]
  }
  return '🌐 Otros'
}

function m3uAttr (line, key) {
  const m = new RegExp(`${key}="([^"]*)"`, 'i').exec(line)
  return m ? m[1] : null
}

export function extractContentId (input) {
  const m = CONTENT_ID.exec(String(input || ''))
  return m ? m[0].toLowerCase() : null
}

export function pageUrl (contentId) {
  return `${SITE}/?an=0&q=${contentId}`
}

// Parsea el M3U de search-ace.stream en canales {name, contentId, category, country, rawUrl, isInfohash}
export function parsePlaylist (text) {
  const out = []
  let name = ''
  let category = 'General'
  for (const raw of String(text || '').split('\n')) {
    const line = raw.trim()
    if (/^#EXTINF/i.test(line)) {
      category = m3uAttr(line, 'group-title') || 'General'
      name = m3uAttr(line, 'tvg-name') || (line.includes(',') ? line.slice(line.lastIndexOf(',') + 1).trim() : '')
    } else if (line && !line.startsWith('#')) {
      const id = extractContentId(line)
      if (id) {
        if (!name) name = `AceStream ${id.slice(0, 8)}…`
        out.push({
          name,
          contentId: id,
          category: categoryLabel(category),
          country: detectCountry(name),
          rawUrl: line,
          isInfohash: /infohash/i.test(line)
        })
      }
      name = ''
      category = 'General'
    }
  }
  return out
}

export function createAceStream ({ fetchImpl, timeoutMs = 15000 } = {}) {
  const doFetch = fetchImpl || globalThis.fetch
  const UA = 'Mozilla/5.0 (Windows NT 10.0) TorrentViewer'

  async function fetchText (url, headers = {}) {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), timeoutMs)
    try {
      const res = await doFetch(url, { headers: { 'User-Agent': UA, ...headers }, signal: ctrl.signal })
      if (!res.ok) { const e = new Error(`respondió ${res.status}`); e.status = res.status; throw e }
      return await res.text()
    } finally {
      clearTimeout(timer)
    }
  }

  async function loadPlaylist () {
    return parsePlaylist(await fetchText(PLAYLIST_URL))
  }

  // Búsqueda por texto: array JSON con content_id/infohash + name
  async function searchApi (query) {
    const body = (await fetchText(`${SEARCH_URL}?query=${encodeURIComponent(String(query || '').trim())}`,
      { Accept: 'application/json' })).trim()
    let arr = []
    if (body.startsWith('[')) arr = JSON.parse(body)
    else if (body.startsWith('{')) {
      const j = JSON.parse(body)
      arr = (j.result && j.result.results) || j.results || []
    }
    const out = []
    for (const o of Array.isArray(arr) ? arr : []) {
      if (!o) continue
      const infohash = o.infohash || ''
      const contentId = o.content_id || ''
      const cid = contentId || infohash
      if (!cid || !/^[0-9a-f]{40}$/i.test(cid)) continue
      const name = (o.name || o.translated_name || 'Canal')
      out.push({
        name,
        contentId: cid.toLowerCase(),
        category: '🔎 Búsqueda',
        country: detectCountry(name),
        rawUrl: '',
        isInfohash: !contentId && !!infohash
      })
    }
    return out
  }

  // ¿Está corriendo el AceStream Engine local?
  async function engineStatus () {
    try {
      const txt = await fetchText(`${ENGINE}/webui/api/service?method=get_version`)
      const j = JSON.parse(txt)
      const version = j && j.result && j.result.version
      return { running: !!version, version: version || null }
    } catch {
      return { running: false, version: null }
    }
  }

  // Resuelve el content-id contra el engine local -> playback_url
  async function resolve (contentId) {
    const id = extractContentId(contentId)
    if (!id) { const e = new Error('Enlace AceStream no válido.'); e.status = 400; throw e }
    const txt = await fetchText(`${ENGINE}/ace/getstream?id=${id}&format=json`)
    const j = JSON.parse(txt)
    const playback = j && j.response && j.response.playback_url
    if (playback) return { playbackUrl: playback }
    const err = j && j.error
    const e = new Error(err ? `AceStream: ${err}` : 'El engine no devolvió reproducción.')
    e.status = 502
    throw e
  }

  return { loadPlaylist, searchApi, engineStatus, resolve }
}
