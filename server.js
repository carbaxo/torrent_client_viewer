import express from 'express'
import cors from 'cors'
import multer from 'multer'
import WebTorrent from 'webtorrent'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

import { createStore } from './lib/store.js'
import { createSearch, SearchError } from './lib/search.js'
import { detectFfmpeg, transcodeToMp4 } from './lib/transcode.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

const PORT = process.env.PORT || 3000
const DOWNLOAD_DIR = process.env.DOWNLOAD_DIR || path.join(__dirname, 'downloads')
const UPLOAD_DIR = path.join(__dirname, 'uploads')
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data')
const TORRENT_META_DIR = path.join(DATA_DIR, 'torrents')
const OMDB_API_KEY = process.env.OMDB_API_KEY || ''

for (const dir of [DOWNLOAD_DIR, UPLOAD_DIR, DATA_DIR, TORRENT_META_DIR]) {
  fs.mkdirSync(dir, { recursive: true })
}

const metaPath = (infoHash) => path.join(TORRENT_META_DIR, `${infoHash}.torrent`)

const app = express()
const client = new WebTorrent()
const store = createStore(path.join(DATA_DIR, 'torrents.json'))
const search = createSearch({ omdbKey: OMDB_API_KEY, cache: new Map() })

let ffmpegAvailable = false
detectFfmpeg().then((ok) => {
  ffmpegAvailable = ok
  console.log(ok ? '[ffmpeg] disponible: transcodificación activada' : '[ffmpeg] no encontrado: transcodificación desactivada')
})

client.on('error', (err) => console.error('[webtorrent] error:', err.message))

const upload = multer({ dest: UPLOAD_DIR })

app.use(cors())
app.use(express.json())
app.use(express.static(path.join(__dirname, 'public')))

// --- Helpers ------------------------------------------------------------

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
  const files = torrent.files.map((file, index) => ({
    index,
    name: file.name,
    length: file.length,
    downloaded: file.downloaded,
    progress: file.progress,
    isVideo: isVideo(file.name),
    nativePlayable: NATIVE_PLAYABLE.has(ext(file.name)),
    streamUrl: `/stream/${torrent.infoHash}/${index}`,
    transcodeUrl: `/transcode/${torrent.infoHash}/${index}`
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
    files
  }
}

// Guarda el torrent en el almacén persistente (idempotente). Si ya hay
// metadatos, guarda también el .torrent para poder verificar el archivo
// en disco al reiniciar sin depender del enjambre.
function persistTorrent (torrent) {
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
  store.add({
    infoHash: torrent.infoHash,
    magnetURI: torrent.magnetURI,
    name: torrent.name || null,
    hasMeta,
    paused: !!torrent.paused,
    addedAt: store.all().find((e) => e.infoHash === torrent.infoHash)?.addedAt || new Date().toISOString()
  })
}

// --- API: config --------------------------------------------------------

app.get('/api/config', (req, res) => {
  res.json({
    ffmpeg: ffmpegAvailable,
    search: !!OMDB_API_KEY,
    downloadDir: DOWNLOAD_DIR
  })
})

// --- API: búsqueda (con rate limiting) ---------------------------------

const RATE_LIMIT = 10
const RATE_WINDOW = 60 * 1000
const rateHits = new Map()

function rateLimiter (req, res, next) {
  const ip = req.ip || req.connection?.remoteAddress || 'unknown'
  const now = Date.now()
  const hits = (rateHits.get(ip) || []).filter((t) => now - t < RATE_WINDOW)
  if (hits.length >= RATE_LIMIT) {
    const retry = Math.ceil((RATE_WINDOW - (now - hits[0])) / 1000)
    res.setHeader('Retry-After', retry)
    return res.status(429).json({
      success: false,
      error: `Demasiadas peticiones. Espera ${retry}s e inténtalo de nuevo.`
    })
  }
  hits.push(now)
  rateHits.set(ip, hits)
  next()
}

app.get('/api/search', rateLimiter, async (req, res) => {
  try {
    const result = await search.search(req.query.query, req.query.type)
    res.json(result)
  } catch (err) {
    if (err instanceof SearchError) {
      return res.status(err.status).json({ success: false, code: err.code, error: err.message })
    }
    console.error('[search] error inesperado:', err)
    res.status(500).json({ success: false, error: 'Error interno en la búsqueda.' })
  }
})

// --- API: torrents ------------------------------------------------------

app.post('/api/torrents', (req, res) => {
  const magnet = (req.body && req.body.magnet ? String(req.body.magnet) : '').trim()
  if (!magnet) return res.status(400).json({ error: 'Falta el enlace magnet.' })
  addTorrent(magnet, res)
})

app.post('/api/torrents/upload', upload.single('torrent'), (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'No se recibió ningún archivo .torrent.' })
  const filePath = req.file.path
  addTorrent(filePath, res, () => fs.unlink(filePath, () => {}))
})

function addTorrent (torrentId, res, cleanup) {
  let responded = false
  const fail = (msg, code = 400) => {
    if (cleanup) cleanup()
    if (!responded) { responded = true; res.status(code).json({ error: msg }) }
  }
  const respond = () => {
    if (cleanup) cleanup()
    persistTorrent(torrent)
    if (!responded) { responded = true; res.json(serializeTorrent(torrent)) }
  }

  let torrent
  try {
    torrent = client.add(torrentId, { path: DOWNLOAD_DIR })
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
  torrent.on('metadata', () => { persistTorrent(torrent); respond() })
  torrent.on('done', () => persistTorrent(torrent))

  if (torrent.ready || torrent.files.length) { respond(); return }

  const timer = setTimeout(respond, 4000)
  if (timer.unref) timer.unref()
}

app.get('/api/torrents', (req, res) => {
  res.json(client.torrents.map(serializeTorrent))
})

app.get('/api/torrents/:infoHash', (req, res) => {
  const torrent = client.get(req.params.infoHash)
  if (!torrent) return res.status(404).json({ error: 'Torrent no encontrado.' })
  res.json(serializeTorrent(torrent))
})

// Pausar / reanudar
app.post('/api/torrents/:infoHash/pause', (req, res) => {
  const torrent = client.get(req.params.infoHash)
  if (!torrent) return res.status(404).json({ error: 'Torrent no encontrado.' })
  torrent.pause()
  store.update(torrent.infoHash, { paused: true })
  res.json(serializeTorrent(torrent))
})

app.post('/api/torrents/:infoHash/resume', (req, res) => {
  const torrent = client.get(req.params.infoHash)
  if (!torrent) return res.status(404).json({ error: 'Torrent no encontrado.' })
  torrent.resume()
  store.update(torrent.infoHash, { paused: false })
  res.json(serializeTorrent(torrent))
})

app.delete('/api/torrents/:infoHash', (req, res) => {
  const torrent = client.get(req.params.infoHash)
  if (!torrent) return res.status(404).json({ error: 'Torrent no encontrado.' })
  const removeFiles = req.query.files === 'true'
  const infoHash = torrent.infoHash
  torrent.destroy({ destroyStore: removeFiles }, (err) => {
    if (err) return res.status(500).json({ error: err.message })
    store.remove(infoHash)
    fs.unlink(metaPath(infoHash), () => {})
    res.json({ ok: true })
  })
})

// --- Streaming con soporte de Range ------------------------------------

function getFile (req, res) {
  const torrent = client.get(req.params.infoHash)
  if (!torrent) { res.status(404).send('Torrent no encontrado.'); return null }
  const file = torrent.files[Number(req.params.fileIndex)]
  if (!file) { res.status(404).send('Archivo no encontrado.'); return null }
  return file
}

app.get('/stream/:infoHash/:fileIndex', (req, res) => {
  const file = getFile(req, res)
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
  stream.on('error', (err) => {
    console.error('[stream] error:', err.message)
    if (!res.headersSent) res.status(500)
    res.end()
  })
  req.on('close', () => stream.destroy())
  stream.pipe(res)
})

// --- Transcodificación en vivo a MP4 -----------------------------------

app.get('/transcode/:infoHash/:fileIndex', (req, res) => {
  if (!ffmpegAvailable) {
    return res.status(503).send('Transcodificación no disponible: ffmpeg no está instalado en el servidor.')
  }
  const file = getFile(req, res)
  if (!file) return

  res.setHeader('Content-Type', 'video/mp4')
  res.setHeader('Cache-Control', 'no-store')
  // La transcodificación en vivo no permite seek: se sirve desde el inicio.
  const input = file.createReadStream()
  transcodeToMp4(input, res)
})

// --- Reanudar torrents persistidos al arrancar -------------------------

function resumePersisted () {
  const entries = store.all()
  if (!entries.length) return
  console.log(`[persistencia] reanudando ${entries.length} torrent(s)…`)
  for (const entry of entries) {
    // Preferimos el .torrent guardado (verifica en disco sin peers);
    // si no, recurrimos al magnet.
    const meta = metaPath(entry.infoHash)
    const id = (entry.hasMeta && fs.existsSync(meta)) ? meta : (entry.magnetURI || entry.infoHash)
    if (!id) continue
    try {
      if (client.get(entry.infoHash)) continue
      const torrent = client.add(id, { path: DOWNLOAD_DIR })
      torrent.on('error', (err) => console.error('[persistencia] error al reanudar:', err.message))
      torrent.on('metadata', () => persistTorrent(torrent))
      if (entry.paused) torrent.once('ready', () => torrent.pause())
    } catch (err) {
      console.error('[persistencia] no se pudo reanudar', entry.infoHash, err.message)
    }
  }
}

app.listen(PORT, () => {
  console.log(`Torrent Client Viewer escuchando en http://localhost:${PORT}`)
  console.log(`Descargas en: ${DOWNLOAD_DIR}`)
  if (!OMDB_API_KEY) {
    console.log('[aviso] OMDB_API_KEY no configurada: el buscador necesita una API key de OMDb (https://www.omdbapi.com/apikey.aspx).')
  }
  resumePersisted()
})
