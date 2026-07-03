// Búsqueda de torrents: nombre -> IMDb ID (OMDb) -> streams (Torrentio).
// El fetch se inyecta para poder testear sin red y para reusar la config.

const DEFAULT_TRACKERS = [
  'udp://tracker.opentrackr.org:1337/announce',
  'udp://open.tracker.cl:1337/announce',
  'udp://tracker.torrent.eu.org:451/announce',
  'udp://exodus.desync.com:6969/announce',
  'udp://open.stealth.si:80/announce'
]

// --- Sanitización y validación -----------------------------------------

export function sanitizeQuery (raw) {
  return String(raw == null ? '' : raw)
    .replace(/<[^>]*>/g, '') // eliminar etiquetas HTML
    .replace(/[\u0000-\u001F\u007F]/g, "") // caracteres de control
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 200)
}

export function normalizeType (type) {
  return type === 'series' ? 'series' : 'movie'
}

// --- Extractores del título de Torrentio -------------------------------
// Torrentio usa un formato tipo:
//   name:  "Torrentio\n1080p"
//   title: "Peli.2010.1080p.BluRay.x264\n👤 152 💾 2.18 GB ⚙️ ThePirateBay"

export function extractQuality (text) {
  const t = String(text || '')
  if (/(?:^|\b)(4k|2160p|uhd)\b/i.test(t)) return '4K'
  if (/\b1080p\b/i.test(t)) return '1080p'
  if (/\b720p\b/i.test(t)) return '720p'
  if (/\b(480p|360p|sd|dvdrip|cam|ts)\b/i.test(t)) return 'SD'
  return 'SD'
}

export function extractSeeders (text) {
  const t = String(text || '')
  // Formato emoji de Torrentio (👤 152) o texto ("152 seeders")
  const emoji = t.match(/👤\s*([\d,.]+)/)
  if (emoji) return parseInt(emoji[1].replace(/[.,]/g, ''), 10) || 0
  const words = t.match(/([\d,.]+)\s*seeders?/i)
  if (words) return parseInt(words[1].replace(/[.,]/g, ''), 10) || 0
  return 0
}

export function extractSize (text) {
  const t = String(text || '')
  // 💾 2.18 GB  ó  2.1GB
  const m = t.match(/([\d.]+)\s*(TB|GB|MB|KB)/i)
  return m ? `${m[1]} ${m[2].toUpperCase()}` : 'Unknown'
}

// Nombre/archivo del torrent = primera línea del título de Torrentio
function extractFilename (title) {
  return String(title || '').split('\n')[0].trim()
}

// --- Construcción del magnet -------------------------------------------

export function buildMagnet (infoHash, displayName, sources) {
  if (!infoHash) return null
  const params = [`xt=urn:btih:${infoHash}`]
  if (displayName) params.push(`dn=${encodeURIComponent(displayName)}`)

  const trackers = new Set(DEFAULT_TRACKERS)
  for (const s of sources || []) {
    if (typeof s === 'string' && s.startsWith('tracker:')) {
      trackers.add(s.slice('tracker:'.length))
    }
  }
  for (const tr of trackers) params.push(`tr=${encodeURIComponent(tr)}`)
  return `magnet:?${params.join('&')}`
}

// --- Procesado de un stream de Torrentio -------------------------------

export function parseStream (stream) {
  if (!stream || !stream.infoHash) return null
  const combined = `${stream.name || ''}\n${stream.title || ''}`
  const filename = extractFilename(stream.title) || stream.name || stream.infoHash
  const quality = extractQuality(combined)
  const size = extractSize(combined)
  const seeders = extractSeeders(combined)
  const infoHash = String(stream.infoHash).toLowerCase()

  return {
    name: 'Torrentio',
    title: `${quality} - ${size} - ${seeders} seeders`,
    filename,
    url: buildMagnet(infoHash, filename, stream.sources),
    infoHash,
    quality,
    size,
    seeders
  }
}

export function processStreams (streams) {
  return (Array.isArray(streams) ? streams : [])
    .map(parseStream)
    .filter(Boolean)
    .sort((a, b) => b.seeders - a.seeders)
}

// --- Errores tipados ----------------------------------------------------

export class SearchError extends Error {
  constructor (code, message, status = 400) {
    super(message)
    this.code = code
    this.status = status
  }
}

// --- fetch con timeout --------------------------------------------------

export async function fetchJson (fetchImpl, url, timeoutMs = 10000) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const res = await fetchImpl(url, { signal: controller.signal })
    if (!res.ok) {
      throw new SearchError('UPSTREAM', `El servicio respondió ${res.status}`, 502)
    }
    return await res.json()
  } catch (err) {
    if (err.name === 'AbortError') {
      throw new SearchError('TIMEOUT', 'La petición tardó demasiado (timeout).', 504)
    }
    if (err instanceof SearchError) throw err
    throw new SearchError('NETWORK', 'No se pudo contactar con el servicio externo.', 502)
  } finally {
    clearTimeout(timer)
  }
}

// --- Orquestador --------------------------------------------------------

export function createSearch ({ fetchImpl, omdbKey, cache, timeoutMs = 10000 } = {}) {
  const doFetch = fetchImpl || globalThis.fetch
  const store = cache || new Map()
  const TTL = 60 * 60 * 1000 // 1 hora

  async function resolveImdbId (query, type) {
    // Si ya es un IMDb ID, lo usamos directamente
    if (/^tt\d+$/i.test(query)) return { imdbId: query.toLowerCase(), title: query }

    if (!omdbKey) {
      throw new SearchError(
        'NO_KEY',
        'Falta la API key de OMDb. Configura la variable de entorno OMDB_API_KEY en el servidor.',
        500
      )
    }

    const url = `https://www.omdbapi.com/?apikey=${encodeURIComponent(omdbKey)}` +
      `&t=${encodeURIComponent(query)}&type=${type}`
    const data = await fetchJson(doFetch, url, timeoutMs)

    if (!data || data.Response === 'False' || !data.imdbID) {
      throw new SearchError('NOT_FOUND', `No se encontró la ${type === 'series' ? 'serie' : 'película'}: "${query}".`, 404)
    }
    return { imdbId: data.imdbID, title: data.Title || query }
  }

  async function search (rawQuery, rawType) {
    const query = sanitizeQuery(rawQuery)
    const type = normalizeType(rawType)
    if (!query) {
      throw new SearchError('EMPTY', 'La búsqueda no puede estar vacía.', 400)
    }

    const cacheKey = `${type}:${query.toLowerCase()}`
    const hit = store.get(cacheKey)
    if (hit && hit.expires > Date.now()) {
      return { ...hit.data, cached: true }
    }

    const { imdbId, title } = await resolveImdbId(query, type)
    const torrentioUrl = `https://torrentio.strem.fun/stream/${type}/${imdbId}.json`
    const data = await fetchJson(doFetch, torrentioUrl, timeoutMs)
    const streams = processStreams(data && data.streams)

    const result = { success: true, imdbId, title, type, streams }
    store.set(cacheKey, { expires: Date.now() + TTL, data: result })
    return result
  }

  return { search, resolveImdbId, cache: store }
}
