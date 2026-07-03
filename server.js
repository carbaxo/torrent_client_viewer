import { loadEnv } from './lib/loadEnv.js'
loadEnv() // carga .env si existe (antes de leer process.env)

import express from 'express'
import cors from 'cors'
import multer from 'multer'
import WebTorrent from 'webtorrent'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

import { createStore } from './lib/store.js'
import { createSearch, SearchError, isValidMagnet, isValidInfoHash } from './lib/search.js'
import { detectFfmpeg, transcodeToMp4 } from './lib/transcode.js'
import { isSubtitle, srtToVtt, extractEmbeddedVtt, probeSubtitleTracks } from './lib/subtitles.js'
import { createAuth, AuthError } from './lib/auth.js'
import { createCatalog } from './lib/catalog.js'
import { createUserData, DEFAULT_AVATARS } from './lib/userdata.js'
import { createFirebaseVerifier, FirebaseAuthError } from './lib/firebaseAuth.js'
import { createRealDebrid, RdError } from './lib/realdebrid.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

const PORT = process.env.PORT || 3000
const DOWNLOAD_DIR = process.env.DOWNLOAD_DIR || path.join(__dirname, 'downloads')
const BUFFER_DIR = process.env.BUFFER_DIR || path.join(__dirname, 'buffer')
const UPLOAD_DIR = path.join(__dirname, 'uploads')
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data')
const TORRENT_META_DIR = path.join(DATA_DIR, 'torrents')
const OMDB_API_KEY = process.env.OMDB_API_KEY || ''
const TMDB_API_KEY = process.env.TMDB_API_KEY || ''
const FIREBASE_PROJECT_ID = process.env.FIREBASE_PROJECT_ID || ''
const TMDB_REGION = process.env.TMDB_REGION || 'ES'
const ALLOW_REGISTRATION = process.env.ALLOW_REGISTRATION !== 'false'
const SECURE_COOKIE = process.env.SECURE_COOKIE === 'true'
// Orígenes permitidos para CORS con credenciales (p.ej. tu URL de GitHub Pages)
const ALLOWED_ORIGINS = (process.env.ALLOWED_ORIGINS || '').split(',').map((s) => s.trim()).filter(Boolean)
const CROSS_SITE = ALLOWED_ORIGINS.length > 0

for (const dir of [DOWNLOAD_DIR, BUFFER_DIR, UPLOAD_DIR, DATA_DIR, TORRENT_META_DIR]) {
  fs.mkdirSync(dir, { recursive: true })
}

const metaPath = (infoHash) => path.join(TORRENT_META_DIR, `${infoHash}.torrent`)

// --- Ajustes del servidor (carpetas), editables desde la UI --------------
const SERVER_SETTINGS_FILE = path.join(DATA_DIR, 'server-settings.json')
let serverSettings = {}
try { serverSettings = JSON.parse(fs.readFileSync(SERVER_SETTINGS_FILE, 'utf8')) || {} } catch {}
const activeDownloadDir = () => serverSettings.downloadDir || DOWNLOAD_DIR
const activeBufferDir = () => serverSettings.bufferDir || BUFFER_DIR

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
// maxConns más alto acelera la descarga al permitir más peers por torrent
const client = new WebTorrent({ maxConns: 100 })
const store = createStore(path.join(DATA_DIR, 'torrents.json'))
const userData = createUserData(path.join(DATA_DIR, 'userdata.json'))
const search = createSearch({ omdbKey: OMDB_API_KEY, cache: new Map() })
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

let ffmpegAvailable = false
detectFfmpeg().then((ok) => {
  ffmpegAvailable = ok
  console.log(ok ? '[ffmpeg] disponible: transcodificación y subtítulos embebidos activados' : '[ffmpeg] no encontrado')
})

client.on('error', (err) => console.error('[webtorrent] error:', err.message))

const upload = multer({ dest: UPLOAD_DIR, limits: { fileSize: 5 * 1024 * 1024, files: 1 } })

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

// --- Helpers de torrents ------------------------------------------------

const VIDEO_EXT = new Set([
  '.mp4', '.m4v', '.webm', '.ogv', '.ogg', '.mkv', '.avi',
  '.mov', '.wmv', '.flv', '.mpg', '.mpeg', '.ts', '.3gp'
])
const NATIVE_PLAYABLE = new Set(['.mp4', '.m4v', '.webm', '.ogv', '.ogg'])
const MIME = {
  '.mp4': 'video/mp4', '.m4v': 'video/mp4', '.webm': 'video/webm',
  '.ogv': 'video/ogg', '.ogg': 'video/ogg', '.mkv': 'video/x-matroska',
  '.avi': 'video/x-msvideo', '.mov': 'video/quicktime', '.wmv': 'video/x-ms-wmv',
  '.flv': 'video/x-flv', '.mpg': 'video/mpeg', '.mpeg': 'video/mpeg',
  '.ts': 'video/mp2t', '.3gp': 'video/3gpp'
}
const ext = (name) => path.extname(name).toLowerCase()
const isVideo = (name) => VIDEO_EXT.has(ext(name))

function serializeTorrent (torrent) {
  const entry = store.all().find((e) => e.infoHash === torrent.infoHash)
  const subFiles = torrent.files
    .map((f, index) => ({ index, name: f.name }))
    .filter((f) => isSubtitle(f.name))

  const files = torrent.files.map((file, index) => ({
    index,
    name: file.name,
    length: file.length,
    downloaded: file.downloaded,
    progress: file.progress,
    isVideo: isVideo(file.name),
    isSubtitle: isSubtitle(file.name),
    nativePlayable: NATIVE_PLAYABLE.has(ext(file.name)),
    streamUrl: `/stream/${torrent.infoHash}/${index}`,
    transcodeUrl: `/transcode/${torrent.infoHash}/${index}`,
    subtitleFiles: isVideo(file.name) ? subFiles : []
  }))

  return {
    infoHash: torrent.infoHash,
    name: torrent.name,
    magnetURI: torrent.magnetURI,
    length: torrent.length,
    downloaded: torrent.downloaded,
    uploaded: torrent.uploaded,
    progress: torrent.progress,
    downloadSpeed: torrent.downloadSpeed,
    uploadSpeed: torrent.uploadSpeed,
    numPeers: torrent.numPeers,
    timeRemaining: torrent.timeRemaining,
    done: torrent.done,
    paused: torrent.paused,
    ready: torrent.ready,
    path: torrent.path,
    mode: entry?.mode || 'download',
    titleRef: entry?.titleRef || null,
    files
  }
}

// Guarda el torrent en el almacén persistente (idempotente) y le asigna
// propietario. Si hay metadatos, guarda el .torrent para reanudar offline.
function persistTorrent (torrent, userId, extra = {}) {
  if (!torrent || !torrent.infoHash) return
  let hasMeta = false
  if (torrent.torrentFile) {
    try {
      const p = metaPath(torrent.infoHash)
      if (!fs.existsSync(p)) fs.writeFileSync(p, torrent.torrentFile)
      hasMeta = true
    } catch (err) {
      console.error('[persistencia] no se pudo guardar .torrent:', err.message)
    }
  }
  const prev = store.all().find((e) => e.infoHash === torrent.infoHash)
  store.add({
    infoHash: torrent.infoHash,
    magnetURI: torrent.magnetURI,
    name: torrent.name || null,
    hasMeta,
    paused: !!torrent.paused,
    owners: userId ? [userId] : [],
    mode: extra.mode || prev?.mode || 'download',
    titleRef: extra.titleRef ?? prev?.titleRef ?? null,
    path: torrent.path || prev?.path || null,
    addedAt: prev?.addedAt || new Date().toISOString()
  })
}

// Elimina un torrent en modo buffer (y sus archivos) cuando todos sus vídeos
// han quedado marcados como vistos por el dueño. Las descargas normales no
// se tocan nunca.
function cleanupBufferTorrent (infoHash, userId) {
  const entry = store.all().find((e) => e.infoHash === infoHash)
  if (!entry || entry.mode !== 'buffer') return
  const torrent = client.get(infoHash)
  if (!torrent || !torrent.files.length) return
  const videos = torrent.files.map((f, i) => ({ i, name: f.name })).filter((f) => isVideo(f.name))
  if (!videos.length) return
  const allWatched = videos.every((v) => userData.isWatchedByAnyProfile(userId, infoHash, v.i))
  if (!allWatched) return
  // Si otro usuario también lo posee, solo lo soltamos nosotros
  if (store.getOwners(infoHash).some((o) => o !== userId)) {
    store.removeOwner(infoHash, userId)
    return
  }
  console.log('[buffer] vídeo visto: eliminando torrent temporal y archivos:', entry.name || infoHash)
  torrent.destroy({ destroyStore: true }, (err) => {
    if (err) console.error('[buffer] error al eliminar:', err.message)
  })
  store.remove(infoHash)
  fs.unlink(metaPath(infoHash), () => {})
}

// Devuelve el torrent si existe Y pertenece al usuario; si no, responde error.
function getOwnedTorrent (req, res) {
  const { infoHash } = req.params
  const torrent = client.get(infoHash)
  if (!torrent) { res.status(404).json({ error: 'Torrent no encontrado.' }); return null }
  if (!store.isOwner(infoHash, req.user.id)) {
    res.status(403).json({ error: 'No tienes acceso a este torrent.' }); return null
  }
  return torrent
}

function getOwnedFile (req, res) {
  const torrent = getOwnedTorrent(req, res)
  if (!torrent) return null
  const file = torrent.files[Number(req.params.fileIndex)]
  if (!file) { res.status(404).send('Archivo no encontrado.'); return null }
  return file
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
    ffmpeg: ffmpegAvailable,
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
app.use('/api/torrents', auth.requireAuth)
app.use('/stream', auth.requireAuth)
app.use('/transcode', auth.requireAuth)
app.use('/subtitle', auth.requireAuth)
app.use('/subtitle-embedded', auth.requireAuth)

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

// ========================================================================
// AJUSTES DEL SERVIDOR (carpetas de descarga y buffer)
// ========================================================================

app.use('/api/settings', auth.requireAuth)

app.get('/api/settings/server', (req, res) => {
  res.json({ downloadDir: activeDownloadDir(), bufferDir: activeBufferDir() })
})

app.put('/api/settings/server', (req, res) => {
  const { downloadDir, bufferDir } = req.body || {}
  const patch = {}
  try {
    if (downloadDir !== undefined) patch.downloadDir = ensureDir(downloadDir)
    if (bufferDir !== undefined) patch.bufferDir = ensureDir(bufferDir)
  } catch (err) {
    return res.status(400).json({ error: 'Carpeta no válida o sin permisos de escritura: ' + err.message })
  }
  if (patch.downloadDir && patch.bufferDir && patch.downloadDir === patch.bufferDir) {
    return res.status(400).json({ error: 'La carpeta de descargas y la de buffer deben ser distintas.' })
  }
  try {
    saveServerSettings(patch)
  } catch (err) {
    return res.status(500).json({ error: 'No se pudieron guardar los ajustes: ' + err.message })
  }
  res.json({ downloadDir: activeDownloadDir(), bufferDir: activeBufferDir(), note: 'Se aplican a los torrents nuevos.' })
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
  if (entry.watched) cleanupBufferTorrent(entry.infoHash, req.user.id)
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

// ========================================================================
// TORRENTS (por usuario)
// ========================================================================

app.post('/api/torrents', (req, res) => {
  const magnet = (req.body && req.body.magnet ? String(req.body.magnet) : '').trim()
  if (!magnet) return res.status(400).json({ error: 'Falta el enlace magnet.' })
  if (!isValidMagnet(magnet)) return res.status(400).json({ error: 'El enlace magnet no es válido.' })
  const mode = req.body.mode === 'buffer' ? 'buffer' : 'download'
  const titleRef = req.body.titleRef ? String(req.body.titleRef).slice(0, 64) : null
  addTorrent(magnet, req.user.id, res, null, { mode, titleRef })
})

app.post('/api/torrents/upload', upload.single('torrent'), (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'No se recibió ningún archivo .torrent.' })
  const filePath = req.file.path
  addTorrent(filePath, req.user.id, res, () => fs.unlink(filePath, () => {}))
})

function addTorrent (torrentId, userId, res, cleanup, opts = {}) {
  const extra = { mode: opts.mode || 'download', titleRef: opts.titleRef || null }
  let responded = false
  const fail = (msg, code = 400) => {
    if (cleanup) cleanup()
    if (!responded) { responded = true; res.status(code).json({ error: msg }) }
  }
  const respond = () => {
    if (cleanup) cleanup()
    persistTorrent(torrent, userId, extra)
    store.addOwner(torrent.infoHash, userId)
    if (!responded) { responded = true; res.json(serializeTorrent(torrent)) }
  }

  let torrent
  try {
    // El buffer temporal y las descargas definitivas viven en carpetas distintas
    torrent = client.add(torrentId, { path: extra.mode === 'buffer' ? activeBufferDir() : activeDownloadDir() })
  } catch (err) {
    return fail('No se pudo añadir el torrent: ' + err.message)
  }

  torrent.on('error', (err) => {
    if (/duplicate/i.test(err.message)) {
      const existing = client.get(torrent && torrent.infoHash)
      if (existing) { torrent = existing; respond(); return }
    }
    fail('Error en el torrent: ' + err.message, 500)
  })
  torrent.on('metadata', () => { persistTorrent(torrent, userId, extra); store.addOwner(torrent.infoHash, userId); respond() })
  torrent.on('done', () => persistTorrent(torrent, userId))

  if (torrent.ready || torrent.files.length) { respond(); return }
  const timer = setTimeout(respond, 4000)
  if (timer.unref) timer.unref()
}

app.get('/api/torrents', (req, res) => {
  const mine = client.torrents.filter((t) => store.isOwner(t.infoHash, req.user.id))
  res.json(mine.map(serializeTorrent))
})

app.get('/api/torrents/:infoHash', (req, res) => {
  const torrent = getOwnedTorrent(req, res)
  if (!torrent) return
  res.json(serializeTorrent(torrent))
})

app.post('/api/torrents/:infoHash/pause', (req, res) => {
  const torrent = getOwnedTorrent(req, res)
  if (!torrent) return
  torrent.pause()
  store.update(torrent.infoHash, { paused: true })
  res.json(serializeTorrent(torrent))
})

app.post('/api/torrents/:infoHash/resume', (req, res) => {
  const torrent = getOwnedTorrent(req, res)
  if (!torrent) return
  torrent.resume()
  store.update(torrent.infoHash, { paused: false })
  res.json(serializeTorrent(torrent))
})

app.delete('/api/torrents/:infoHash', (req, res) => {
  const torrent = getOwnedTorrent(req, res)
  if (!torrent) return
  const removeFiles = req.query.files === 'true'
  const infoHash = torrent.infoHash
  const remaining = store.removeOwner(infoHash, req.user.id)
  // Solo destruimos el torrent cuando ya no lo posee nadie
  if (remaining > 0) return res.json({ ok: true, keptForOthers: true })
  torrent.destroy({ destroyStore: removeFiles }, (err) => {
    if (err) return res.status(500).json({ error: err.message })
    store.remove(infoHash)
    fs.unlink(metaPath(infoHash), () => {})
    res.json({ ok: true })
  })
})

// ========================================================================
// STREAMING (Range)
// ========================================================================

app.get('/stream/:infoHash/:fileIndex', (req, res) => {
  const file = getOwnedFile(req, res)
  if (!file) return

  const total = file.length
  const range = req.headers.range
  res.setHeader('Accept-Ranges', 'bytes')
  res.setHeader('Content-Type', MIME[ext(file.name)] || 'application/octet-stream')

  let start = 0
  let end = total - 1
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

  const stream = file.createReadStream({ start, end })
  stream.on('error', (err) => { console.error('[stream]', err.message); if (!res.headersSent) res.status(500); res.end() })
  req.on('close', () => stream.destroy())
  stream.pipe(res)
})

// ========================================================================
// TRANSCODIFICACIÓN
// ========================================================================

app.get('/transcode/:infoHash/:fileIndex', (req, res) => {
  if (!ffmpegAvailable) return res.status(503).send('Transcodificación no disponible: ffmpeg no instalado.')
  const file = getOwnedFile(req, res)
  if (!file) return
  res.setHeader('Content-Type', 'video/mp4')
  res.setHeader('Cache-Control', 'no-store')
  transcodeToMp4(file.createReadStream(), res)
})

// ========================================================================
// SUBTÍTULOS
// ========================================================================

// Lista de pistas embebidas (requiere ffprobe)
app.get('/api/torrents/:infoHash/:fileIndex/subinfo', async (req, res) => {
  const file = getOwnedFile(req, res)
  if (!file) return
  if (!ffmpegAvailable) return res.json({ embedded: [] })
  const tracks = await probeSubtitleTracks(file.createReadStream())
  res.json({ embedded: tracks })
})

// Sirve un fichero de subtítulos incluido en el torrent, convertido a VTT
app.get('/subtitle/:infoHash/:fileIndex', (req, res) => {
  const file = getOwnedFile(req, res)
  if (!file) return
  if (!isSubtitle(file.name)) return res.status(400).send('El archivo no es un subtítulo.')
  res.setHeader('Content-Type', 'text/vtt; charset=utf-8')
  const chunks = []
  const stream = file.createReadStream()
  stream.on('data', (c) => chunks.push(c))
  stream.on('error', () => { if (!res.headersSent) res.status(500); res.end() })
  stream.on('end', () => {
    const text = Buffer.concat(chunks).toString('utf8')
    res.end(srtToVtt(text))
  })
})

// Extrae una pista de subtítulos embebida a VTT (requiere ffmpeg)
app.get('/subtitle-embedded/:infoHash/:fileIndex/:track', (req, res) => {
  if (!ffmpegAvailable) return res.status(503).send('ffmpeg no disponible.')
  const file = getOwnedFile(req, res)
  if (!file) return
  const track = Number(req.params.track)
  if (!Number.isInteger(track) || track < 0) return res.status(400).send('Pista inválida.')
  res.setHeader('Content-Type', 'text/vtt; charset=utf-8')
  res.setHeader('Cache-Control', 'no-store')
  extractEmbeddedVtt(file.createReadStream(), track, res)
})

// ========================================================================
// Reanudar torrents persistidos
// ========================================================================

function resumePersisted () {
  const entries = store.all()
  if (!entries.length) return
  console.log(`[persistencia] reanudando ${entries.length} torrent(s)…`)
  for (const entry of entries) {
    const meta = metaPath(entry.infoHash)
    const id = (entry.hasMeta && fs.existsSync(meta)) ? meta : (entry.magnetURI || entry.infoHash)
    if (!id) continue
    try {
      if (client.get(entry.infoHash)) continue
      // Cada torrent se reanuda en la carpeta donde se descargó
      const dir = entry.path || (entry.mode === 'buffer' ? activeBufferDir() : activeDownloadDir())
      const torrent = client.add(id, { path: dir })
      torrent.on('error', (err) => console.error('[persistencia] error al reanudar:', err.message))
      torrent.on('metadata', () => {
        if (torrent.torrentFile && !fs.existsSync(meta)) {
          try { fs.writeFileSync(meta, torrent.torrentFile) } catch {}
        }
      })
      if (entry.paused) torrent.once('ready', () => torrent.pause())
    } catch (err) {
      console.error('[persistencia] no se pudo reanudar', entry.infoHash, err.message)
    }
  }
}

app.listen(PORT, () => {
  console.log(`Torrent Client Viewer escuchando en http://localhost:${PORT}`)
  console.log(`Descargas en: ${DOWNLOAD_DIR}`)
  if (!OMDB_API_KEY) console.log('[aviso] OMDB_API_KEY sin configurar: el buscador Torrentio quedará limitado.')
  if (!TMDB_API_KEY) console.log('[aviso] TMDB_API_KEY sin configurar: los catálogos de streaming estarán desactivados.')
  resumePersisted()
})

// Apagado limpio: vuelca el estado a disco antes de salir
for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    try { store.flush() } catch {}
    try { userData.flush() } catch {}
    process.exit(0)
  })
}
