import { loadEnv } from './lib/loadEnv.js'
loadEnv() // carga .env si existe (antes de leer process.env)

import express from 'express'
import cors from 'cors'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

import { createSearch, SearchError, isValidMagnet } from './lib/search.js'
import { createAuth, AuthError } from './lib/auth.js'
import { createCatalog } from './lib/catalog.js'
import { createUserData, DEFAULT_AVATARS } from './lib/userdata.js'
import { createFirebaseVerifier, FirebaseAuthError } from './lib/firebaseAuth.js'
import { createRealDebrid, RdError } from './lib/realdebrid.js'
import { createRdDownloads } from './lib/rddownloads.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

const PORT = process.env.PORT || 3000
const DOWNLOAD_DIR = process.env.DOWNLOAD_DIR || path.join(__dirname, 'downloads')
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data')
const OMDB_API_KEY = process.env.OMDB_API_KEY || ''
const TMDB_API_KEY = process.env.TMDB_API_KEY || ''
const FIREBASE_PROJECT_ID = process.env.FIREBASE_PROJECT_ID || ''
const TMDB_REGION = process.env.TMDB_REGION || 'ES'
const ALLOW_REGISTRATION = process.env.ALLOW_REGISTRATION !== 'false'
const SECURE_COOKIE = process.env.SECURE_COOKIE === 'true'
// Orígenes permitidos para CORS con credenciales (p.ej. tu URL de GitHub Pages)
const ALLOWED_ORIGINS = (process.env.ALLOWED_ORIGINS || '').split(',').map((s) => s.trim()).filter(Boolean)
const CROSS_SITE = ALLOWED_ORIGINS.length > 0

for (const dir of [DOWNLOAD_DIR, DATA_DIR]) {
  fs.mkdirSync(dir, { recursive: true })
}

// --- Ajustes del servidor (carpeta de descargas), editable desde la UI ----
const SERVER_SETTINGS_FILE = path.join(DATA_DIR, 'server-settings.json')
let serverSettings = {}
try { serverSettings = JSON.parse(fs.readFileSync(SERVER_SETTINGS_FILE, 'utf8')) || {} } catch {}
const activeDownloadDir = () => serverSettings.downloadDir || DOWNLOAD_DIR

function saveServerSettings (patch) {
  serverSettings = { ...serverSettings, ...patch }
  fs.writeFileSync(SERVER_SETTINGS_FILE + '.tmp', JSON.stringify(serverSettings, null, 2))
  fs.renameSync(SERVER_SETTINGS_FILE + '.tmp', SERVER_SETTINGS_FILE)
}

// Valida que una ruta sea utilizable como carpeta (la crea si no existe)
function ensureDir (p) {
  const resolved = path.resolve(String(p || '').trim())
  fs.mkdirSync(resolved, { recursive: true })
  fs.accessSync(resolved, fs.constants.W_OK)
  return resolved
}

const app = express()
app.set('trust proxy', 1)
const userData = createUserData(path.join(DATA_DIR, 'userdata.json'))
const search = createSearch({ omdbKey: OMDB_API_KEY, tmdbKey: TMDB_API_KEY, cache: new Map() })
const catalog = createCatalog({ tmdbKey: TMDB_API_KEY, region: TMDB_REGION, cache: new Map() })
const auth = createAuth({
  usersFile: path.join(DATA_DIR, 'users.json'),
  secretFile: path.join(DATA_DIR, '.session-secret'),
  envSecret: process.env.SESSION_SECRET,
  allowRegistration: ALLOW_REGISTRATION,
  secureCookie: SECURE_COOKIE,
  crossSite: CROSS_SITE
})
const firebaseVerifier = createFirebaseVerifier({ projectId: FIREBASE_PROJECT_ID })
const realDebrid = createRealDebrid({})
const rdDownloads = createRdDownloads({ file: path.join(DATA_DIR, 'rd-downloads.json') })

// --- Middleware ---------------------------------------------------------

app.use((req, res, next) => {
  res.setHeader('X-Content-Type-Options', 'nosniff')
  res.setHeader('X-Frame-Options', 'SAMEORIGIN')
  res.setHeader('Referrer-Policy', 'no-referrer')
  // connect-src/frame-src: endpoints de Firebase Auth y Firestore (login
  // Google + sincronización). El SDK se sirve desde /vendor (script-src 'self').
  res.setHeader('Content-Security-Policy', [
    "default-src 'self'",
    "img-src 'self' data: https://image.tmdb.org https://lh3.googleusercontent.com",
    // real-debrid: streaming directo desde sus servidores (si el usuario lo configura)
    "media-src 'self' blob: data: https://*.download.real-debrid.com",
    "style-src 'self' 'unsafe-inline'",
    // apis.google.com: lo carga el SDK de Firebase Auth para el popup de Google
    "script-src 'self' https://apis.google.com",
    "connect-src 'self' https://identitytoolkit.googleapis.com https://securetoken.googleapis.com https://firestore.googleapis.com https://www.googleapis.com",
    "frame-src 'self' https://*.firebaseapp.com https://accounts.google.com",
    "frame-ancestors 'self'"
  ].join('; '))
  next()
})
// CORS: si hay orígenes permitidos, habilita credenciales (cookies) solo
// para ellos; si no, CORS abierto sin credenciales (mismo origen).
app.use(cors(ALLOWED_ORIGINS.length
  ? { origin: ALLOWED_ORIGINS, credentials: true }
  : {}))
app.use(express.json({ limit: '256kb' }))
app.use(express.static(path.join(__dirname, 'public')))

// --- Rate limiting ------------------------------------------------------

function makeRateLimiter (max, windowMs, message) {
  const hits = new Map()
  return (req, res, next) => {
    const ip = req.ip || 'unknown'
    const now = Date.now()
    const list = (hits.get(ip) || []).filter((t) => now - t < windowMs)
    if (list.length >= max) {
      const retry = Math.ceil((windowMs - (now - list[0])) / 1000)
      res.setHeader('Retry-After', retry)
      return res.status(429).json({ success: false, error: message || `Demasiadas peticiones. Espera ${retry}s.` })
    }
    list.push(now)
    hits.set(ip, list)
    next()
  }
}
const searchLimiter = makeRateLimiter(20, 60 * 1000, 'Demasiadas búsquedas. Espera un momento.')
const authLimiter = makeRateLimiter(10, 60 * 1000, 'Demasiados intentos. Espera un minuto.')

// --- MIME de los vídeos descargados con Real-Debrid ----------------------

const MIME = {
  '.mp4': 'video/mp4', '.m4v': 'video/mp4', '.webm': 'video/webm',
  '.ogv': 'video/ogg', '.ogg': 'video/ogg', '.mkv': 'video/x-matroska',
  '.avi': 'video/x-msvideo', '.mov': 'video/quicktime', '.wmv': 'video/x-ms-wmv',
  '.flv': 'video/x-flv', '.mpg': 'video/mpeg', '.mpeg': 'video/mpeg',
  '.ts': 'video/mp2t', '.3gp': 'video/3gpp'
}
const ext = (name) => path.extname(name).toLowerCase()

// Título al que pertenece una descarga, para guardar el progreso al verla:
// { tmdbId, type, season, episode, name, poster }. Se sanea porque viene del
// cliente y se guarda tal cual en disco.
function sanitizeTitleRef (t) {
  if (!t || typeof t !== 'object' || Array.isArray(t)) return null
  const tmdbId = Number(t.tmdbId)
  if (!Number.isInteger(tmdbId) || tmdbId <= 0) return null
  const int = (v) => (Number.isInteger(Number(v)) && Number(v) > 0 ? Number(v) : -1)
  return {
    tmdbId,
    type: t.type === 'series' ? 'series' : 'movie',
    season: int(t.season),
    episode: int(t.episode),
    name: String(t.name || '').slice(0, 300),
    poster: String(t.poster || '').slice(0, 500) || null
  }
}

// ========================================================================
// AUTENTICACIÓN
// ========================================================================

app.post('/api/auth/register', authLimiter, (req, res) => {
  if (!auth.allowRegistration) return res.status(403).json({ error: 'El registro está desactivado.' })
  try {
    const { username, password } = req.body || {}
    const user = auth.register(username, password)
    const { token } = auth.login(username, password)
    auth.setSessionCookie(res, token)
    res.json({ user })
  } catch (err) {
    if (err instanceof AuthError) return res.status(err.status).json({ error: err.message, code: err.code })
    console.error('[auth] register:', err); res.status(500).json({ error: 'Error en el registro.' })
  }
})

app.post('/api/auth/login', authLimiter, (req, res) => {
  try {
    const { username, password } = req.body || {}
    const { user, token } = auth.login(username, password)
    auth.setSessionCookie(res, token)
    res.json({ user })
  } catch (err) {
    if (err instanceof AuthError) return res.status(err.status).json({ error: err.message, code: err.code })
    console.error('[auth] login:', err); res.status(500).json({ error: 'Error en el login.' })
  }
})

// Login con Google vía Firebase: el frontend envía el ID token de Firebase,
// lo verificamos criptográficamente y emitimos nuestra sesión de siempre.
app.post('/api/auth/firebase', authLimiter, async (req, res) => {
  if (!FIREBASE_PROJECT_ID) return res.status(501).json({ error: 'Firebase no está configurado en el servidor.' })
  try {
    const { idToken } = req.body || {}
    const payload = await firebaseVerifier.verify(idToken)
    const { user, token } = auth.externalLogin('firebase', payload.sub, payload.name || (payload.email || '').split('@')[0])
    auth.setSessionCookie(res, token)
    res.json({ user })
  } catch (err) {
    if (err instanceof FirebaseAuthError) return res.status(err.status).json({ error: err.message, code: err.code })
    if (err instanceof AuthError) return res.status(err.status).json({ error: err.message, code: err.code })
    console.error('[auth] firebase:', err)
    res.status(500).json({ error: 'Error en el login con Google.' })
  }
})

app.post('/api/auth/logout', (req, res) => {
  auth.clearSessionCookie(res)
  res.json({ ok: true })
})

app.get('/api/auth/me', (req, res) => {
  const user = auth.userFromRequest(req)
  if (!user) return res.status(401).json({ error: 'No autenticado.' })
  res.json({ user })
})

// Config pública (no requiere sesión)
app.get('/api/config', (req, res) => {
  res.json({
    search: !!OMDB_API_KEY,
    catalogs: !!TMDB_API_KEY,
    firebase: !!FIREBASE_PROJECT_ID,
    allowRegistration: auth.allowRegistration,
    region: TMDB_REGION,
    avatars: DEFAULT_AVATARS
  })
})

// A partir de aquí, todo requiere sesión válida
app.use('/api/me', auth.requireAuth)
app.use('/api/search', auth.requireAuth)
app.use('/api/catalogs', auth.requireAuth)

// ========================================================================
// BÚSQUEDA
// ========================================================================

app.get('/api/search', searchLimiter, async (req, res) => {
  try {
    const { query, type, source, season, episode } = req.query
    const result = await search.search(query, type, source, { season, episode })
    res.json(result)
  } catch (err) {
    if (err instanceof SearchError) {
      return res.status(err.status).json({ success: false, code: err.code, error: err.message })
    }
    console.error('[search] error:', err)
    res.status(500).json({ success: false, error: 'Error interno en la búsqueda.' })
  }
})

// ========================================================================
// CATÁLOGOS (TMDB)
// ========================================================================

app.get('/api/catalogs', searchLimiter, async (req, res) => {
  try {
    const mediaType = req.query.type === 'series' ? 'series' : 'movie'
    const data = await catalog.getAllCatalogs(mediaType, { lang: req.query.lang, kids: req.query.kids === '1' })
    res.json({ success: true, ...data })
  } catch (err) {
    res.status(err.status || 500).json({ success: false, code: err.code, error: err.message })
  }
})

// Ficha de un título y episodios por temporada (para la vista de detalle)
app.use('/api/title', auth.requireAuth)

app.get('/api/title/series/:id/season/:n', searchLimiter, async (req, res) => {
  try {
    const data = await catalog.getSeason(req.params.id, req.params.n, { lang: req.query.lang })
    res.json({ success: true, ...data })
  } catch (err) {
    res.status(err.status || 500).json({ success: false, code: err.code, error: err.message })
  }
})

app.get('/api/title/:type/:id', searchLimiter, async (req, res) => {
  try {
    const data = await catalog.getDetails(req.params.type, req.params.id, { lang: req.query.lang })
    res.json({ success: true, ...data })
  } catch (err) {
    res.status(err.status || 500).json({ success: false, code: err.code, error: err.message })
  }
})

// Géneros de TMDB para los filtros de categoría
app.use('/api/genres', auth.requireAuth)
app.get('/api/genres', searchLimiter, async (req, res) => {
  try {
    const data = await catalog.getGenres(req.query.type, { lang: req.query.lang })
    res.json({ success: true, ...data })
  } catch (err) {
    res.status(err.status || 500).json({ success: false, code: err.code, error: err.message })
  }
})

// Explorar (paginado) por plataforma y/o género: ?type=&provider=&genre=&page=&lang=
app.use('/api/discover', auth.requireAuth)
app.get('/api/discover', searchLimiter, async (req, res) => {
  try {
    const data = await catalog.discover({
      type: req.query.type,
      providerKey: req.query.provider,
      genreId: req.query.genre,
      page: req.query.page,
      lang: req.query.lang,
      kids: req.query.kids === '1'
    })
    res.json({ success: true, ...data })
  } catch (err) {
    res.status(err.status || 500).json({ success: false, code: err.code, error: err.message })
  }
})

// Recomendaciones según favoritos/vistos: ?ids=movie:123,series:456&lang=
app.use('/api/recommendations', auth.requireAuth)
app.get('/api/recommendations', searchLimiter, async (req, res) => {
  try {
    const seeds = String(req.query.ids || '').split(',').map((s) => {
      const [type, tmdbId] = s.split(':')
      return { type, tmdbId }
    })
    const data = await catalog.getRecommendations(seeds, { lang: req.query.lang })
    res.json({ success: true, ...data })
  } catch (err) {
    res.status(err.status || 500).json({ success: false, code: err.code, error: err.message })
  }
})

// ========================================================================
// REAL-DEBRID (token privado por cuenta; nunca sale del servidor)
// ========================================================================

app.use('/api/rd', auth.requireAuth)

app.get('/api/rd/status', (req, res) => {
  const token = userData.getAccount(req.user.id).realDebridToken
  if (!token) return res.json({ configured: false })
  res.json({ configured: true, tokenMask: '····' + token.slice(-4) })
})

// Guarda el token del usuario tras validarlo contra la API de RD
app.put('/api/rd/token', authLimiter, async (req, res) => {
  const token = String((req.body || {}).token || '').trim()
  if (token.length < 10) return res.status(400).json({ error: 'Token inválido. Cópialo de https://real-debrid.com/apitoken' })
  try {
    const rdUser = await realDebrid.getUser(token)
    userData.setAccount(req.user.id, { realDebridToken: token })
    res.json({ configured: true, tokenMask: '····' + token.slice(-4), rdUser })
  } catch (err) {
    if (err instanceof RdError) return res.status(err.status).json({ error: err.message, code: err.code })
    console.error('[rd] token:', err)
    res.status(502).json({ error: 'No se pudo validar el token con Real-Debrid.' })
  }
})

app.delete('/api/rd/token', (req, res) => {
  userData.setAccount(req.user.id, { realDebridToken: null })
  res.json({ configured: false })
})

// magnet -> enlace directo reproducible (o estado si RD aún lo descarga)
app.post('/api/rd/stream', searchLimiter, async (req, res) => {
  const token = userData.getAccount(req.user.id).realDebridToken
  if (!token) return res.status(400).json({ error: 'Configura tu token de Real-Debrid en Ajustes.', code: 'NO_TOKEN' })
  const magnet = String((req.body || {}).magnet || '').trim()
  if (!isValidMagnet(magnet)) return res.status(400).json({ error: 'El enlace magnet no es válido.' })
  try {
    const result = await realDebrid.streamMagnet(token, magnet)
    res.json({ success: true, ...result })
  } catch (err) {
    if (err instanceof RdError) return res.status(err.status).json({ error: err.message, code: err.code })
    console.error('[rd] stream:', err)
    res.status(502).json({ error: 'Error al preparar el streaming con Real-Debrid.' })
  }
})

// magnet -> descarga el archivo a disco desde el enlace directo de RD
// (paridad con "⚡ Descargar RD" de la app Android)
app.post('/api/rd/download', searchLimiter, async (req, res) => {
  const token = userData.getAccount(req.user.id).realDebridToken
  if (!token) return res.status(400).json({ error: 'Configura tu token de Real-Debrid en Ajustes.', code: 'NO_TOKEN' })
  const magnet = String((req.body || {}).magnet || '').trim()
  if (!isValidMagnet(magnet)) return res.status(400).json({ error: 'El enlace magnet no es válido.' })
  try {
    const result = await realDebrid.streamMagnet(token, magnet)
    // RD sigue descargándolo en sus servidores: el cliente reintentará
    if (!result.ready) return res.json({ success: true, ...result })
    const entry = rdDownloads.add({
      userId: req.user.id,
      url: result.url,
      filename: result.filename,
      magnet,
      title: sanitizeTitleRef((req.body || {}).title),
      dir: activeDownloadDir()
    })
    res.json({ success: true, ready: true, download: rdDownloads.toPublic(entry) })
  } catch (err) {
    if (err instanceof RdError) return res.status(err.status).json({ error: err.message, code: err.code })
    console.error('[rd] download:', err)
    res.status(502).json({ error: 'Error al preparar la descarga con Real-Debrid.' })
  }
})

app.get('/api/rd/downloads', (req, res) => {
  res.json({ downloads: rdDownloads.listFor(req.user.id) })
})

// Reanuda una descarga interrumpida o fallida; si el enlace directo caducó,
// lo regenera a partir del magnet guardado.
app.post('/api/rd/downloads/:id/retry', searchLimiter, async (req, res) => {
  const entry = rdDownloads.get(req.params.id, req.user.id)
  if (!entry) return res.status(404).json({ error: 'Descarga no encontrada.' })
  const token = userData.getAccount(req.user.id).realDebridToken
  let freshUrl = null
  if (token && entry.magnet) {
    try {
      const r = await realDebrid.streamMagnet(token, entry.magnet)
      if (r.ready) freshUrl = r.url
    } catch {}
  }
  res.json({ success: true, download: rdDownloads.resume(entry.id, req.user.id, freshUrl) })
})

app.delete('/api/rd/downloads/:id', (req, res) => {
  const deleteFiles = req.query.files === '1' || req.query.files === 'true'
  const ok = rdDownloads.remove(req.params.id, req.user.id, deleteFiles)
  if (!ok) return res.status(404).json({ error: 'Descarga no encontrada.' })
  res.json({ success: true })
})

// Reproduce un archivo ya descargado de RD (con soporte Range)
app.get('/rd-file/:id', auth.requireAuth, (req, res) => {
  const entry = rdDownloads.get(req.params.id, req.user.id)
  if (!entry) return res.status(404).json({ error: 'Descarga no encontrada.' })
  let stat
  try { stat = fs.statSync(entry.path) } catch { return res.status(404).json({ error: 'El archivo ya no está en el disco.' }) }
  const total = stat.size
  res.setHeader('Content-Type', MIME[ext(entry.name)] || 'application/octet-stream')
  res.setHeader('Accept-Ranges', 'bytes')
  let start = 0
  let end = total - 1
  const range = req.headers.range
  if (range) {
    const match = /bytes=(\d*)-(\d*)/.exec(range)
    if (match) {
      if (match[1]) start = parseInt(match[1], 10)
      if (match[2]) end = parseInt(match[2], 10)
    }
    if (isNaN(start) || isNaN(end) || start > end || start < 0 || end >= total) {
      res.setHeader('Content-Range', `bytes */${total}`)
      return res.status(416).end()
    }
    res.status(206)
    res.setHeader('Content-Range', `bytes ${start}-${end}/${total}`)
  } else {
    res.status(200)
  }
  res.setHeader('Content-Length', end - start + 1)
  if (req.method === 'HEAD') return res.end()
  const stream = fs.createReadStream(entry.path, { start, end })
  stream.on('error', (err) => { console.error('[rd-file]', err.message); if (!res.headersSent) res.status(500); res.end() })
  req.on('close', () => stream.destroy())
  stream.pipe(res)
})

// ========================================================================
// AJUSTES DEL SERVIDOR (carpeta donde se guardan las descargas de RD)
// ========================================================================

app.use('/api/settings', auth.requireAuth)

app.get('/api/settings/server', (req, res) => {
  res.json({ downloadDir: activeDownloadDir() })
})

app.put('/api/settings/server', (req, res) => {
  const { downloadDir } = req.body || {}
  const patch = {}
  try {
    if (downloadDir !== undefined) patch.downloadDir = ensureDir(downloadDir)
  } catch (err) {
    return res.status(400).json({ error: 'Carpeta no válida o sin permisos de escritura: ' + err.message })
  }
  try {
    saveServerSettings(patch)
  } catch (err) {
    return res.status(500).json({ error: 'No se pudieron guardar los ajustes: ' + err.message })
  }
  res.json({ downloadDir: activeDownloadDir(), note: 'Se aplica a las descargas nuevas.' })
})

// ========================================================================
// ESTADO POR USUARIO (perfiles, favoritos, progreso, ajustes)
// ========================================================================

// Perfil activo: lo indica el frontend en la cabecera x-profile
const profileOf = (req) => String(req.headers['x-profile'] || 'default').replace(/[^\w-]/g, '').slice(0, 32) || 'default'

// Todo el estado del perfil activo en una sola llamada (al iniciar sesión)
app.get('/api/me/state', (req, res) => {
  const p = profileOf(req)
  res.json({
    profiles: userData.getProfiles(req.user.id),
    favorites: userData.getFavorites(req.user.id, p),
    progress: userData.getProgress(req.user.id, p),
    settings: userData.getSettings(req.user.id, p)
  })
})

// --- Perfiles ---
app.get('/api/me/profiles', (req, res) => {
  res.json({ profiles: userData.getProfiles(req.user.id) })
})

app.post('/api/me/profiles', (req, res) => {
  const profile = userData.addProfile(req.user.id, req.body || {})
  if (!profile) return res.status(400).json({ error: 'Perfil inválido (nombre vacío o límite alcanzado).' })
  res.json({ profile })
})

app.put('/api/me/profiles/:id', (req, res) => {
  const profile = userData.updateProfile(req.user.id, req.params.id, req.body || {})
  if (!profile) return res.status(404).json({ error: 'Perfil no encontrado.' })
  res.json({ profile })
})

app.delete('/api/me/profiles/:id', (req, res) => {
  const ok = userData.removeProfile(req.user.id, req.params.id)
  if (!ok) return res.status(400).json({ error: 'No se puede eliminar (¿es el último perfil?).' })
  res.json({ ok: true })
})

// --- Favoritos / progreso / ajustes del perfil activo ---
app.post('/api/me/favorites', (req, res) => {
  const fav = userData.addFavorite(req.user.id, profileOf(req), req.body || {})
  if (!fav) return res.status(400).json({ error: 'Favorito inválido: faltan id o título.' })
  res.json({ favorite: fav })
})

app.delete('/api/me/favorites/:id', (req, res) => {
  userData.removeFavorite(req.user.id, profileOf(req), req.params.id)
  res.json({ ok: true })
})

app.post('/api/me/progress', (req, res) => {
  const entry = userData.setProgress(req.user.id, profileOf(req), req.body || {})
  if (!entry) return res.status(400).json({ error: 'Progreso inválido.' })
  res.json({ progress: entry })
})

app.delete('/api/me/progress/:key', (req, res) => {
  userData.removeProgress(req.user.id, profileOf(req), req.params.key)
  res.json({ ok: true })
})

app.put('/api/me/settings', (req, res) => {
  const settings = userData.setSettings(req.user.id, profileOf(req), req.body || {})
  if (!settings) return res.status(400).json({ error: 'Ajustes inválidos.' })
  res.json({ settings })
})

app.listen(PORT, () => {
  console.log(`Torrent Client Viewer (modo Real-Debrid) escuchando en http://localhost:${PORT}`)
  console.log(`Descargas en: ${activeDownloadDir()}`)
  if (!OMDB_API_KEY) console.log('[aviso] OMDB_API_KEY sin configurar: el buscador Torrentio quedará limitado.')
  if (!TMDB_API_KEY) console.log('[aviso] TMDB_API_KEY sin configurar: los catálogos de streaming estarán desactivados.')
})

// Apagado limpio: vuelca el estado a disco antes de salir
for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    try { userData.flush() } catch {}
    try { rdDownloads.flush() } catch {}
    process.exit(0)
  })
}
