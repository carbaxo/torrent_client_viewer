import express from 'express'
import multer from 'multer'
import WebTorrent from 'webtorrent'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

const PORT = process.env.PORT || 3000
const DOWNLOAD_DIR = process.env.DOWNLOAD_DIR || path.join(__dirname, 'downloads')
const UPLOAD_DIR = path.join(__dirname, 'uploads')

for (const dir of [DOWNLOAD_DIR, UPLOAD_DIR]) {
  fs.mkdirSync(dir, { recursive: true })
}

const app = express()
const client = new WebTorrent()

client.on('error', (err) => {
  console.error('[webtorrent] error:', err.message)
})

// Guarda los .torrent subidos en disco para poder pasarlos a webtorrent
const upload = multer({ dest: UPLOAD_DIR })

app.use(express.json())
app.use(express.static(path.join(__dirname, 'public')))

// --- Helpers -------------------------------------------------------------

const VIDEO_EXT = new Set([
  '.mp4', '.m4v', '.webm', '.ogv', '.ogg', '.mkv', '.avi',
  '.mov', '.wmv', '.flv', '.mpg', '.mpeg', '.ts', '.3gp'
])
// Formatos que el navegador suele reproducir de forma nativa
const NATIVE_PLAYABLE = new Set(['.mp4', '.m4v', '.webm', '.ogv', '.ogg'])

const MIME = {
  '.mp4': 'video/mp4',
  '.m4v': 'video/mp4',
  '.webm': 'video/webm',
  '.ogv': 'video/ogg',
  '.ogg': 'video/ogg',
  '.mkv': 'video/x-matroska',
  '.avi': 'video/x-msvideo',
  '.mov': 'video/quicktime',
  '.wmv': 'video/x-ms-wmv',
  '.flv': 'video/x-flv',
  '.mpg': 'video/mpeg',
  '.mpeg': 'video/mpeg',
  '.ts': 'video/mp2t',
  '.3gp': 'video/3gpp'
}

function ext (name) {
  return path.extname(name).toLowerCase()
}

function isVideo (name) {
  return VIDEO_EXT.has(ext(name))
}

function serializeTorrent (torrent) {
  const files = torrent.files.map((file, index) => ({
    index,
    name: file.name,
    length: file.length,
    downloaded: file.downloaded,
    progress: file.progress,
    isVideo: isVideo(file.name),
    nativePlayable: NATIVE_PLAYABLE.has(ext(file.name)),
    streamUrl: `/stream/${torrent.infoHash}/${index}`
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

// --- API -----------------------------------------------------------------

// Añadir un torrent por magnet
app.post('/api/torrents', (req, res) => {
  const magnet = (req.body && req.body.magnet ? String(req.body.magnet) : '').trim()
  if (!magnet) {
    return res.status(400).json({ error: 'Falta el enlace magnet.' })
  }
  addTorrent(magnet, res)
})

// Añadir un torrent subiendo un archivo .torrent
app.post('/api/torrents/upload', upload.single('torrent'), (req, res) => {
  if (!req.file) {
    return res.status(400).json({ error: 'No se recibió ningún archivo .torrent.' })
  }
  const filePath = req.file.path
  addTorrent(filePath, res, () => {
    // Limpieza del archivo temporal una vez procesado
    fs.unlink(filePath, () => {})
  })
})

function addTorrent (torrentId, res, cleanup) {
  let responded = false
  const fail = (msg, code = 400) => {
    if (cleanup) cleanup()
    if (!responded) {
      responded = true
      res.status(code).json({ error: msg })
    }
  }

  const respond = () => {
    if (cleanup) cleanup()
    if (!responded) {
      responded = true
      res.json(serializeTorrent(torrent))
    }
  }

  let torrent
  try {
    torrent = client.add(torrentId, { path: DOWNLOAD_DIR })
  } catch (err) {
    return fail('No se pudo añadir el torrent: ' + err.message)
  }

  const onError = (err) => {
    // "duplicate torrent" -> ya existe, devolvemos el existente
    if (/duplicate/i.test(err.message)) {
      const existing = client.get(torrent && torrent.infoHash)
      if (existing) {
        torrent = existing
        respond()
        return
      }
    }
    fail('Error en el torrent: ' + err.message, 500)
  }

  torrent.on('error', onError)
  torrent.on('metadata', respond)

  // Si ya tiene metadata (p. ej. un .torrent completo), responde ya.
  if (torrent.ready || torrent.files.length) {
    respond()
    return
  }

  // El torrent ya está registrado en el cliente aunque aún no tenga
  // metadatos (magnet sin peers todavía). Respondemos rápido para no
  // dejar la petición colgada; el frontend actualizará por polling.
  const timer = setTimeout(respond, 4000)
  if (timer.unref) timer.unref()
}

// Listar todos los torrents
app.get('/api/torrents', (req, res) => {
  res.json(client.torrents.map(serializeTorrent))
})

// Detalle de un torrent
app.get('/api/torrents/:infoHash', (req, res) => {
  const torrent = client.get(req.params.infoHash)
  if (!torrent) return res.status(404).json({ error: 'Torrent no encontrado.' })
  res.json(serializeTorrent(torrent))
})

// Eliminar un torrent (opcionalmente con sus archivos)
app.delete('/api/torrents/:infoHash', (req, res) => {
  const torrent = client.get(req.params.infoHash)
  if (!torrent) return res.status(404).json({ error: 'Torrent no encontrado.' })
  const removeFiles = req.query.files === 'true'
  torrent.destroy({ destroyStore: removeFiles }, (err) => {
    if (err) return res.status(500).json({ error: err.message })
    res.json({ ok: true })
  })
})

// --- Streaming con soporte de Range ------------------------------------

app.get('/stream/:infoHash/:fileIndex', (req, res) => {
  const torrent = client.get(req.params.infoHash)
  if (!torrent) return res.status(404).send('Torrent no encontrado.')

  const index = Number(req.params.fileIndex)
  const file = torrent.files[index]
  if (!file) return res.status(404).send('Archivo no encontrado.')

  const total = file.length
  const range = req.headers.range
  const contentType = MIME[ext(file.name)] || 'application/octet-stream'

  res.setHeader('Accept-Ranges', 'bytes')
  res.setHeader('Content-Type', contentType)

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

app.listen(PORT, () => {
  console.log(`Torrent Client Viewer escuchando en http://localhost:${PORT}`)
  console.log(`Descargas en: ${DOWNLOAD_DIR}`)
})
