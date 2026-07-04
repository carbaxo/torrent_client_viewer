// Transcodificación en tiempo real con ffmpeg para reproducir en el
// navegador formatos no nativos (mkv, avi, etc.) mientras se descargan.

import { spawn } from 'child_process'

const FFMPEG = process.env.FFMPEG_PATH || 'ffmpeg'

let available = null

// Detecta si ffmpeg está disponible en el sistema (se cachea el resultado).
export function detectFfmpeg () {
  if (available !== null) return Promise.resolve(available)
  return new Promise((resolve) => {
    let proc
    try {
      proc = spawn(FFMPEG, ['-version'])
    } catch {
      available = false
      return resolve(false)
    }
    proc.on('error', () => { available = false; resolve(false) })
    proc.on('close', (code) => { available = (code === 0); resolve(available) })
  })
}

// Remuxea/transcodifica una URL de entrada (IPTV: HLS .m3u8 o MPEG-TS) a MP4
// fragmentado que reproduce cualquier <video>. Reconecta si la red falla.
export function transcodeUrlToMp4 (url, res, onSpawn) {
  const args = [
    '-hide_banner', '-loglevel', 'error',
    // Reconexión para streams de red inestables
    '-reconnect', '1', '-reconnect_streamed', '1', '-reconnect_delay_max', '5',
    '-user_agent', 'Mozilla/5.0 TorrentBox',
    '-i', url,
    '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '23',
    '-pix_fmt', 'yuv420p', '-profile:v', 'main', '-level', '4.0',
    '-c:a', 'aac', '-b:a', '128k', '-ac', '2',
    '-movflags', 'frag_keyframe+empty_moov+default_base_moof',
    '-f', 'mp4', 'pipe:1'
  ]

  const ff = spawn(FFMPEG, args)
  if (onSpawn) onSpawn(ff)

  let killed = false
  const cleanup = () => { if (killed) return; killed = true; ff.kill('SIGKILL') }

  ff.stdout.pipe(res)
  ff.stderr.on('data', (d) => { const m = d.toString().trim(); if (m) console.error('[ffmpeg iptv]', m) })
  ff.on('error', (err) => {
    console.error('[iptv] no se pudo lanzar ffmpeg:', err.message)
    if (!res.headersSent) res.status(500).end('Error de transcodificación (¿ffmpeg instalado?).')
    else res.end()
    cleanup()
  })
  ff.on('close', () => { if (!res.writableEnded) res.end() })
  res.on('close', cleanup)
  return ff
}

// Transcodifica un stream de entrada a MP4 fragmentado (H.264 + AAC),
// apto para <video> en cualquier navegador. Salida por streaming (sin seek).
export function transcodeToMp4 (inputStream, res, onSpawn) {
  const args = [
    '-hide_banner',
    '-loglevel', 'error',
    '-i', 'pipe:0',
    '-c:v', 'libx264',
    '-preset', 'veryfast',
    '-crf', '23',
    // yuv420p + perfil compatible: obligatorio para que reproduzca cualquier
    // navegador (evita 4:4:4 / 10-bit que <video> no sabe decodificar).
    '-pix_fmt', 'yuv420p',
    '-profile:v', 'main',
    '-level', '4.0',
    '-c:a', 'aac',
    '-b:a', '128k',
    '-ac', '2',
    // MP4 fragmentado reproducible en streaming sin índice al final
    '-movflags', 'frag_keyframe+empty_moov+default_base_moof',
    '-f', 'mp4',
    'pipe:1'
  ]

  const ff = spawn(FFMPEG, args)
  if (onSpawn) onSpawn(ff)

  let killed = false
  const cleanup = () => {
    if (killed) return
    killed = true
    inputStream.destroy()
    ff.kill('SIGKILL')
  }

  inputStream.on('error', cleanup)
  inputStream.pipe(ff.stdin)

  // EPIPE cuando ffmpeg cierra stdin antes de tiempo: lo ignoramos
  ff.stdin.on('error', () => {})

  ff.stdout.pipe(res)
  ff.stderr.on('data', (d) => {
    const msg = d.toString().trim()
    if (msg) console.error('[ffmpeg]', msg)
  })

  ff.on('error', (err) => {
    console.error('[transcode] no se pudo lanzar ffmpeg:', err.message)
    if (!res.headersSent) res.status(500).end('Error de transcodificación (¿ffmpeg instalado?).')
    else res.end()
    cleanup()
  })

  ff.on('close', () => { if (!res.writableEnded) res.end() })

  res.on('close', cleanup)

  return ff
}
