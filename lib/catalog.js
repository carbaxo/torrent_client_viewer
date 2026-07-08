// Catálogos de streaming al estilo del addon "Streaming Catalogs" de Stremio.
// Usa TMDB (discover + watch providers) para listar títulos disponibles en
// cada plataforma (Netflix, Prime Video, HBO/Max, Disney+). Los pósters se
// sirven desde la CDN de TMDB directamente al cliente.

export const IMG_BASE = 'https://image.tmdb.org/t/p/w342'
export const BACKDROP_BASE = 'https://image.tmdb.org/t/p/w1280'
export const STILL_BASE = 'https://image.tmdb.org/t/p/w300'

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

// Tráiler de YouTube: prioriza Trailer oficial > Trailer > Teaser (como en
// la app Android)
function pickTrailer (videos) {
  const list = (Array.isArray(videos) ? videos : []).filter((v) => v && v.site === 'YouTube' && v.key)
  const v = list.find((x) => x.type === 'Trailer' && x.official) ||
    list.find((x) => x.type === 'Trailer') ||
    list.find((x) => x.type === 'Teaser')
  return v ? `https://www.youtube.com/watch?v=${v.key}` : null
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

  // Ficha de un título (película o serie): sinopsis, backdrop, géneros y,
  // para series, la lista de temporadas.
  async function getDetails (mediaType, tmdbId, opts = {}) {
    if (!tmdbKey) {
      const e = new Error('Falta TMDB_API_KEY en el servidor.'); e.code = 'NO_KEY'; e.status = 500; throw e
    }
    const type = mediaType === 'series' ? 'series' : 'movie'
    const id = Number(tmdbId)
    if (!Number.isInteger(id) || id <= 0) { const e = new Error('Id inválido.'); e.status = 400; throw e }
    const lang = normalizeLang(opts.lang)

    const key = `detail:${type}:${id}:${lang}`
    const hit = store.get(key)
    if (hit && hit.expires > Date.now()) return { ...hit.data, cached: true }

    const tmdbType = type === 'series' ? 'tv' : 'movie'
    const d = await fetchJson(`https://api.themoviedb.org/3/${tmdbType}/${id}` +
      `?api_key=${encodeURIComponent(tmdbKey)}&language=${encodeURIComponent(lang)}` +
      `&append_to_response=videos&include_video_language=${encodeURIComponent(lang.slice(0, 2))},en,null`)

    const isMovie = type === 'movie'
    const date = isMovie ? d.release_date : d.first_air_date
    const result = {
      tmdbId: d.id,
      type,
      title: isMovie ? (d.title || d.original_title) : (d.name || d.original_name),
      // Título original (normalmente inglés): los torrents se indexan con él
      originalTitle: isMovie ? (d.original_title || d.title) : (d.original_name || d.name),
      year: date ? String(date).slice(0, 4) : '',
      overview: d.overview || '',
      tagline: d.tagline || '',
      backdrop: d.backdrop_path ? BACKDROP_BASE + d.backdrop_path : null,
      poster: d.poster_path ? IMG_BASE + d.poster_path : null,
      rating: d.vote_average ? Math.round(d.vote_average * 10) / 10 : null,
      genres: (d.genres || []).map((g) => g.name).slice(0, 5),
      runtime: isMovie ? (d.runtime || null) : null,
      trailer: pickTrailer(d.videos && d.videos.results),
      // Último episodio emitido (para avisos de episodios nuevos)
      lastEpisode: !isMovie && d.last_episode_to_air
        ? {
            season: d.last_episode_to_air.season_number,
            episode: d.last_episode_to_air.episode_number,
            name: d.last_episode_to_air.name || '',
            airDate: d.last_episode_to_air.air_date || ''
          }
        : null,
      seasons: isMovie
        ? null
        : (d.seasons || [])
            .filter((s) => s.season_number > 0 && s.episode_count > 0)
            .map((s) => ({ season: s.season_number, name: s.name, episodes: s.episode_count }))
    }
    store.set(key, { expires: Date.now() + TTL, data: result })
    return result
  }

  // Episodios de una temporada de una serie
  async function getSeason (tmdbId, seasonNumber, opts = {}) {
    if (!tmdbKey) {
      const e = new Error('Falta TMDB_API_KEY en el servidor.'); e.code = 'NO_KEY'; e.status = 500; throw e
    }
    const id = Number(tmdbId)
    const season = Number(seasonNumber)
    if (!Number.isInteger(id) || id <= 0 || !Number.isInteger(season) || season <= 0) {
      const e = new Error('Parámetros inválidos.'); e.status = 400; throw e
    }
    const lang = normalizeLang(opts.lang)

    const key = `season:${id}:${season}:${lang}`
    const hit = store.get(key)
    if (hit && hit.expires > Date.now()) return { ...hit.data, cached: true }

    const d = await fetchJson(`https://api.themoviedb.org/3/tv/${id}/season/${season}` +
      `?api_key=${encodeURIComponent(tmdbKey)}&language=${encodeURIComponent(lang)}`)

    const result = {
      season,
      name: d.name || `Temporada ${season}`,
      episodes: (d.episodes || []).map((e) => ({
        episode: e.episode_number,
        name: e.name || `Episodio ${e.episode_number}`,
        overview: e.overview || '',
        still: e.still_path ? STILL_BASE + e.still_path : null,
        airDate: e.air_date || '',
        rating: e.vote_average ? Math.round(e.vote_average * 10) / 10 : null
      }))
    }
    store.set(key, { expires: Date.now() + TTL, data: result })
    return result
  }

  // Lista de géneros de TMDB (para los filtros de categoría)
  async function getGenres (mediaType = 'movie', opts = {}) {
    if (!tmdbKey) { const e = new Error('Falta TMDB_API_KEY en el servidor.'); e.code = 'NO_KEY'; e.status = 500; throw e }
    const type = mediaType === 'series' ? 'series' : 'movie'
    const lang = normalizeLang(opts.lang)
    const cacheKey = `genres:${type}:${lang}`
    const hit = store.get(cacheKey)
    if (hit && hit.expires > Date.now()) return { ...hit.data, cached: true }
    const tmdbType = type === 'series' ? 'tv' : 'movie'
    const d = await fetchJson(`https://api.themoviedb.org/3/genre/${tmdbType}/list` +
      `?api_key=${encodeURIComponent(tmdbKey)}&language=${encodeURIComponent(lang)}`)
    const result = { type, genres: (d.genres || []).map((g) => ({ id: g.id, name: g.name })) }
    store.set(cacheKey, { expires: Date.now() + TTL, data: result })
    return result
  }

  // Explorar (paginado) por plataforma y/o género. opts: { type, providerKey,
  // genreId, page, lang, kids }. Devuelve { items, page, totalPages }.
  async function discover (opts = {}) {
    if (!tmdbKey) { const e = new Error('Falta TMDB_API_KEY en el servidor.'); e.code = 'NO_KEY'; e.status = 500; throw e }
    const mediaType = opts.type === 'series' ? 'series' : 'movie'
    const tmdbType = mediaType === 'series' ? 'tv' : 'movie'
    const lang = normalizeLang(opts.lang)
    const page = Math.min(Math.max(1, Number(opts.page) || 1), 500)

    const params = new URLSearchParams({
      api_key: tmdbKey, language: lang, sort_by: 'popularity.desc', page: String(page),
      'vote_count.gte': '30'
    })
    const provider = opts.providerKey && PLATFORMS.find((p) => p.key === opts.providerKey)
    if (provider) {
      params.set('with_watch_providers', provider.providers.join('|'))
      params.set('watch_region', region)
      params.set('with_watch_monetization_types', 'flatrate')
    }
    // En modo infantil forzamos géneros familiares (ignora el género elegido).
    if (opts.kids) params.set('with_genres', KIDS_GENRES[tmdbType])
    else if (opts.genreId && Number.isInteger(Number(opts.genreId))) params.set('with_genres', String(Number(opts.genreId)))

    const cacheKey = 'disc:' + params.toString()
    const hit = store.get(cacheKey)
    if (hit && hit.expires > Date.now()) return { ...hit.data, cached: true }

    const d = await fetchJson(`https://api.themoviedb.org/3/discover/${tmdbType}?` + params.toString())
    const items = (d.results || []).map((r) => mapItem(r, mediaType)).filter((i) => i.title)
    const result = { type: mediaType, items, page: d.page || page, totalPages: Math.min(d.total_pages || 1, 500) }
    store.set(cacheKey, { expires: Date.now() + TTL, data: result })
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

  return { getCatalog, getAllCatalogs, getRecommendations, getDetails, getSeason, getGenres, discover, platforms: PLATFORMS }
}
