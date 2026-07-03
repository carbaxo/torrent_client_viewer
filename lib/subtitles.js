// Soporte de subtítulos:
//  - Detecta ficheros .srt/.vtt/.ass incluidos en el propio torrent.
//  - Convierte SRT -> WebVTT al vuelo (lo que consume <track>).
//  - (Opcional) extrae pistas de subtítulos embebidas en mkv con ffmpeg.

import { spawn } from 'child_process'
import path from 'path'

const FFMPEG = process.env.FFMPEG_PATH || 'ffmpeg'
const FFPROBE = process.env.FFPROBE_PATH || 'ffprobe'
const SUB_EXT = new Set(['.srt', '.vtt', '.ass', '.ssa', '.sub'])

export const isSubtitle = (name) => SUB_EXT.has(path.extname(name).toLowerCase())

// Convierte texto SRT a WebVTT. Es tolerante: si ya parece VTT, lo devuelve.
export function srtToVtt (srt) {
  const text = String(srt).replace(/\r+/g, '')
  if (/^WEBVTT/.test(text.trimStart())) return text
  const body = text
    // 00:00:20,000 --> 00:00:24,400  =>  usa punto decimal
    .replace(/(\d{2}:\d{2}:\d{2}),(\d{3})/g, '$1.$2')
    // quita los índices numéricos de cada bloque
    .replace(/^\d+\s*$/gm, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
  return `WEBVTT\n\n${body}\n`
}

// Sondea las pistas de subtítulos embebidas de un vídeo con ffprobe.
// Devuelve [{ index, lang, title }] (index relativo a las pistas 's').
export function probeSubtitleTracks (inputStream) {
  return new Promise((resolve) => {
    const args = [
      '-v', 'error',
      '-select_streams', 's',
      '-show_entries', 'stream=index:stream_tags=language,title',
      '-of', 'json',
      '-i', 'pipe:0'
    ]
    let out = ''
    let proc
    try {
      proc = spawn(FFPROBE, args)
    } catch {
      return resolve([])
    }
    inputStream.pipe(proc.stdin)
    proc.stdin.on('error', () => {})
    proc.stdout.on('data', (d) => { out += d })
    proc.on('error', () => { inputStream.destroy(); resolve([]) })
    proc.on('close', () => {
      inputStream.destroy()
      try {
        const json = JSON.parse(out)
        resolve((json.streams || []).map((s, i) => ({
          index: i,
          lang: (s.tags && s.tags.language) || 'und',
          title: (s.tags && s.tags.title) || `Pista ${i + 1}`
        })))
      } catch {
        resolve([])
      }
    })
  })
}

// Extrae la pista de subtítulos `index` de un stream de vídeo a WebVTT.
// Devuelve el proceso ffmpeg; su stdout es el .vtt.
export function extractEmbeddedVtt (inputStream, trackIndex, res, onSpawn) {
  const args = [
    '-hide_banner', '-loglevel', 'error',
    '-i', 'pipe:0',
    '-map', `0:s:${trackIndex}`,
    '-f', 'webvtt',
    'pipe:1'
  ]
  const ff = spawn(FFMPEG, args)
  if (onSpawn) onSpawn(ff)

  let done = false
  const cleanup = () => {
    if (done) return
    done = true
    inputStream.destroy()
    ff.kill('SIGKILL')
  }

  inputStream.on('error', cleanup)
  inputStream.pipe(ff.stdin)
  ff.stdin.on('error', () => {})
  ff.stdout.pipe(res)
  ff.stderr.on('data', (d) => { const m = d.toString().trim(); if (m) console.error('[subs]', m) })
  ff.on('error', (err) => {
    console.error('[subs] ffmpeg:', err.message)
    if (!res.headersSent) res.status(500).end('Error extrayendo subtítulos.')
    else res.end()
    cleanup()
  })
  ff.on('close', () => { if (!res.writableEnded) res.end() })
  res.on('close', cleanup)
  return ff
}
