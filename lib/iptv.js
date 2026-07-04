// Reproductor IPTV genérico (como VLC/Kodi): el usuario aporta la fuente
// (lista M3U o credenciales Xtream Codes) y el servidor la parsea; el navegador
// reproduce vía /iptv/play (remux con ffmpeg). NO incluye ningún canal.

const TIMEOUT = 20000

function fetchText (url) {
  const controller = new AbortController()
  const t = setTimeout(() => controller.abort(), TIMEOUT)
  return globalThis.fetch(url, {
    signal: controller.signal,
    headers: { 'User-Agent': 'Mozilla/5.0 TorrentBox' }
  }).then(async (res) => {
    if (!res.ok) { const e = new Error(`El servidor respondió ${res.status}`); e.status = 502; throw e }
    return res.text()
  }).finally(() => clearTimeout(t))
}

function fetchJson (url) {
  return fetchText(url).then((txt) => {
    const s = txt.trimStart()
    if (!s.startsWith('[') && !s.startsWith('{')) return []
    try { return JSON.parse(txt) } catch { return [] }
  })
}

// --- M3U ---
function attr (line, key) {
  const m = new RegExp(key + '="([^"]*)"', 'i').exec(line)
  return m ? m[1] : null
}

export function parseM3u (text) {
  const out = []
  const lines = String(text || '').split(/\r?\n/)
  let name = ''
  let logo = null
  let group = 'General'
  for (const raw of lines) {
    const line = raw.trim()
    if (/^#EXTINF/i.test(line)) {
      logo = attr(line, 'tvg-logo')
      group = attr(line, 'group-title') || 'General'
      name = attr(line, 'tvg-name') || line.split(',').pop().trim()
    } else if (line && !line.startsWith('#')) {
      if (!name) name = line.split('/').pop() || 'Canal'
      out.push({ name, url: line, logo: logo || null, group: group || 'General' })
      name = ''; logo = null; group = 'General'
    }
  }
  return out
}

export async function loadM3u (url) {
  const text = await fetchText(url)
  return parseM3u(text)
}

// --- Xtream Codes ---
function normHost (h) {
  let s = String(h || '').trim()
  if (!/^https?:\/\//i.test(s)) s = 'http://' + s
  return s.replace(/\/+$/, '')
}

export async function testXtream ({ host, user, pass }) {
  const base = `${normHost(host)}/player_api.php?username=${encodeURIComponent(user)}&password=${encodeURIComponent(pass)}`
  const data = await fetchJson(base)
  const auth = data && data.user_info && data.user_info.auth
  return auth === 1
}

export async function loadXtream ({ host, user, pass }) {
  const h = normHost(host)
  const base = `${h}/player_api.php?username=${encodeURIComponent(user)}&password=${encodeURIComponent(pass)}`
  const cats = {}
  try {
    const arr = await fetchJson(`${base}&action=get_live_categories`)
    for (const c of (Array.isArray(arr) ? arr : [])) cats[c.category_id] = c.category_name || 'General'
  } catch { /* categorías opcionales */ }
  const arr = await fetchJson(`${base}&action=get_live_streams`)
  const out = []
  for (const s of (Array.isArray(arr) ? arr : [])) {
    const id = s.stream_id
    if (id == null) continue
    const ext = (s.container_extension || 'ts') || 'ts'
    out.push({
      name: s.name || `Canal ${id}`,
      url: `${h}/${encodeURIComponent(user)}/${encodeURIComponent(pass)}/${id}.${ext}`,
      logo: s.stream_icon || null,
      group: cats[s.category_id] || 'General'
    })
  }
  return out
}

// Seguridad: solo permitimos proxiar/reproducir http(s) para no exponer
// ficheros locales ni otros esquemas.
export function isSafeStreamUrl (url) {
  try { const u = new URL(url); return u.protocol === 'http:' || u.protocol === 'https:' } catch { return false }
}
