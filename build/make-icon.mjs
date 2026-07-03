// Genera build/icon.png (512x512): cuadrado redondeado morado con un
// triángulo de "play" blanco. Sin dependencias (PNG a mano con zlib).
import zlib from 'node:zlib'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const S = 512
const buf = Buffer.alloc(S * S * 4)

// Degradado morado (8f6bff -> 5b3df0) con esquinas redondeadas y play blanco
const R = 96 // radio esquina
function inRoundedRect (x, y) {
  const nx = Math.min(x, S - 1 - x)
  const ny = Math.min(y, S - 1 - y)
  if (nx >= R || ny >= R) return true
  const dx = R - nx; const dy = R - ny
  return dx * dx + dy * dy <= R * R
}
// Triángulo de play centrado
const cx = S * 0.44; const halfH = S * 0.20; const topY = S / 2 - halfH; const botY = S / 2 + halfH
const leftX = S * 0.36; const rightX = S * 0.66
function inTriangle (x, y) {
  if (y < topY || y > botY) return false
  const t = (y - topY) / (botY - topY)
  const edge = leftX + (rightX - leftX) * (1 - Math.abs(2 * t - 1))
  return x >= leftX && x <= edge
}

for (let y = 0; y < S; y++) {
  for (let x = 0; x < S; x++) {
    const i = (y * S + x) * 4
    if (!inRoundedRect(x, y)) { buf[i + 3] = 0; continue }
    if (inTriangle(x, y)) {
      buf[i] = 255; buf[i + 1] = 255; buf[i + 2] = 255; buf[i + 3] = 255
    } else {
      const g = y / S
      buf[i] = Math.round(0x8f + (0x5b - 0x8f) * g)
      buf[i + 1] = Math.round(0x6b + (0x3d - 0x6b) * g)
      buf[i + 2] = Math.round(0xff + (0xf0 - 0xff) * g)
      buf[i + 3] = 255
    }
  }
}

// Empaqueta como PNG
function chunk (type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length)
  const t = Buffer.from(type)
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([t, data])) >>> 0)
  return Buffer.concat([len, t, data, crc])
}
function crc32 (b) {
  let c = ~0
  for (let i = 0; i < b.length; i++) {
    c ^= b[i]
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xEDB88320 & -(c & 1))
  }
  return ~c
}
const ihdr = Buffer.alloc(13)
ihdr.writeUInt32BE(S, 0); ihdr.writeUInt32BE(S, 4)
ihdr[8] = 8; ihdr[9] = 6 // 8 bits, RGBA
const raw = Buffer.alloc((S * 4 + 1) * S)
for (let y = 0; y < S; y++) {
  raw[y * (S * 4 + 1)] = 0
  buf.copy(raw, y * (S * 4 + 1) + 1, y * S * 4, (y + 1) * S * 4)
}
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
  chunk('IHDR', ihdr),
  chunk('IDAT', zlib.deflateSync(raw)),
  chunk('IEND', Buffer.alloc(0))
])
fs.writeFileSync(path.join(__dirname, 'icon.png'), png)
console.log('build/icon.png generado (512x512)')
