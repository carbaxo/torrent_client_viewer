// Catálogos de streaming al estilo del addon "Streaming Catalogs" de Stremio.
// Usa TMDB (discover + watch providers) para listar títulos disponibles en
// cada plataforma (Netflix, Prime Video, HBO/Max, Disney+). Los pósters se
// sirven desde la CDN de TMDB directamente al cliente.

export const IMG_BASE = 'https://image.tmdb.org/t/p/w342'

// IDs de proveedores de TMDB (varían por región; se pueden ajustar).
export const PLATFORMS = [
  { key: 'netflix', name: 'Netflix', color: '#e50914', providers: [8] },
  { key: 'prime', name: 'Prime Video', color: '#00a8e1', providers: [9, 119] },
  { key: 'hbomax', name: 'HBO Max', color: '#a020f0', providers: [384, 1899, 118] },
  { key: 'disney', name: 'Disney+', color: '#113ccf', providers: [337] }
]

// Géneros TMDB aptos para el modo infantil
const KIDS_GENRES = { movie: '10751|16', tv: '10762|16|10751' } // Familia/Animación/Kids

export function normalizeLang (lang) {
  return ['es-ES', 'en-US'].includes(lang) ? lang : 'es-ES'
}

function mapItem (raw, mediaType) {
  const type = mediaType || raw.media_type || (raw.title ? 'movie' : 'series')
  const isMovie = type === 'movie'
  const date = isMovie ? raw.release_date : raw.first_air_date
  return {
    tmdbId: raw.id,
    title: isMovie ? (raw.title || raw.original_title) : (raw.name || raw.original_name),
    year: date ? String(date).slice(0, 4) : '',
    poster: raw.poster_path ? IMG_BASE + raw.poster_path : null,
    rating: raw.vote_average ? Math.round(raw.vote_average * 10) / 10 : null,
    popularity: raw.popularity || 0,
    type: isMovie ? 'movie' : 'series'
  }
}

export function createCatalog ({ fetchImpl, tmdbKey, cache, region = 'ES', timeoutMs = 10000 } = {}) {
  const doFetch = fetchImpl || globalThis.fetch
  const store = cache || new Map()
  const TTL = 6 * 60 * 60 * 1000 // 6 horas

  async function fetchJson (url) {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), timeoutMs)
    try {
      const res = await doFetch(url, { signal: controller.signal })
      if (!res.ok) throw new Error(`TMDB respondió ${res.status}`)
      return await res.json()
    } finally {
      clearTimeout(timer)
    }
  }

  // Un catálogo (plataforma + tipo). mediaType: 'movie' | 'series'
  // opts: { lang: 'es-ES'|'en-US', kids: bool (solo géneros infantiles) }
  async function getCatalog (platformKey, mediaType = 'movie', opts = {}) {
    if (!tmdbKey) {
      const e = new Error('Falta TMDB_API_KEY en el servidor.'); e.code = 'NO_KEY'; e.status = 500; throw e
    }
    const platform = PLATFORMS.find((p) => p.key === platformKey)
    if (!platform) { const e = new Error('Plataforma desconocida.'); e.status = 404; throw e }
    const lang = normalizeLang(opts.lang)
    const kids = !!opts.kids

    const key = `${platformKey}:${mediaType}:${region}:${lang}:${kids ? 'kids' : 'all'}`
    const hit = store.get(key)
    if (hit && hit.expires > Date.now()) return { ...hit.data, cached: true }

    const tmdbType = mediaType === 'series' ? 'tv' : 'movie'
    const url = `https://api.themoviedb.org/3/discover/${tmdbType}` +
      `?api_key=${encodeURIComponent(tmdbKey)}` +
      `&with_watch_providers=${platform.providers.join('|')}` +
      `&watch_region=${encodeURIComponent(region)}` +
      `&language=${encodeURIComponent(lang)}` +
      (kids ? `&with_genres=${encodeURIComponent(KIDS_GENRES[tmdbType])}` : '') +
      '&with_watch_monetization_types=flatrate' +
      '&sort_by=popularity.desc&page=1'

    const data = await fetchJson(url)
    const items = (data.results || []).map((r) => mapItem(r, mediaType)).filter((i) => i.title)
    const result = { platform: platform.key, name: platform.name, color: platform.color, type: mediaType, items }
    store.set(key, { expires: Date.now() + TTL, data: result })
    return result
  }

  // Recomendaciones a partir de títulos semilla (favoritos/vistos).
  // seeds: [{ tmdbId, type: 'movie'|'series' }] (máx. 6)
  async function getRecommendations (seeds, opts = {}) {
    if (!tmdbKey) {
      const e = new Error('Falta TMDB_API_KEY en el servidor.'); e.code = 'NO_KEY'; e.status = 500; throw e
    }
    const lang = normalizeLang(opts.lang)
    const clean = (Array.isArray(seeds) ? seeds : [])
      .filter((s) => s && Number.isInteger(Number(s.tmdbId)))
      .map((s) => ({ tmdbId: Number(s.tmdbId), type: s.type === 'series' ? 'series' : 'movie' }))
      .slice(0, 6)
    if (!clean.length) return { items: [] }

    const key = 'recs:' + clean.map((s) => `${s.type}${s.tmdbId}`).sort().join(',') + ':' + lang
    const hit = store.get(key)
    if (hit && hit.expires > Date.now()) return { ...hit.data, cached: true }

    const settled = await Promise.allSettled(clean.map((s) => {
      const tmdbType = s.type === 'series' ? 'tv' : 'movie'
      const url = `https://api.themoviedb.org/3/${tmdbType}/${s.tmdbId}/recommendations` +
        `?api_key=${encodeURIComponent(tmdbKey)}&language=${encodeURIComponent(lang)}&page=1`
      return fetchJson(url).then((d) => (d.results || []).map((r) => mapItem(r, s.type)))
    }))

    const seedIds = new Set(clean.map((s) => s.tmdbId))
    const seen = new Set()
    const items = settled
      .filter((r) => r.status === 'fulfilled')
      .flatMap((r) => r.value)
      .filter((i) => i.title && !seedIds.has(i.tmdbId) && !seen.has(i.tmdbId) && seen.add(i.tmdbId))
      .sort((a, b) => b.popularity - a.popularity)
      .slice(0, 20)

    const result = { items }
    store.set(key, { expires: Date.now() + TTL, data: result })
    return result
  }

  // Todos los catálogos de un tipo, en paralelo (los fallos no tumban el resto)
  async function getAllCatalogs (mediaType = 'movie', opts = {}) {
    const settled = await Promise.allSettled(PLATFORMS.map((p) => getCatalog(p.key, mediaType, opts)))
    const catalogs = []
    const warnings = []
    settled.forEach((r, i) => {
      if (r.status === 'fulfilled') catalogs.push(r.value)
      else warnings.push(`${PLATFORMS[i].name}: ${r.reason.message}`)
    })
    if (!catalogs.length && warnings.length) {
      const e = new Error(warnings[0]); e.status = 502; throw e
    }
    return { catalogs, ...(warnings.length ? { warnings } : {}) }
  }

  return { getCatalog, getAllCatalogs, getRecommendations, platforms: PLATFORMS }
}
