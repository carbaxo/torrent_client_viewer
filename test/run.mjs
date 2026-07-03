// Tests sin dependencias externas. Ejecutar: node test/run.mjs
import assert from 'node:assert'
import {
  sanitizeQuery, normalizeType, extractQuality, extractSeeders, extractSize,
  buildMagnet, parseStream, processStreams, createSearch, SearchError
} from '../lib/search.js'
import { createStore } from '../lib/store.js'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

let passed = 0
function t (name, fn) {
  try { fn(); passed++; console.log('  ✓', name) } catch (e) { console.error('  ✗', name, '\n    ', e.message); process.exitCode = 1 }
}

console.log('sanitizeQuery')
t('elimina etiquetas HTML', () => assert.equal(sanitizeQuery('<script>Inception</script>'), 'Inception'))
t('recorta espacios y colapsa', () => assert.equal(sanitizeQuery('  the   matrix  '), 'the matrix'))
t('vacío -> vacío', () => assert.equal(sanitizeQuery(''), ''))
t('null -> vacío', () => assert.equal(sanitizeQuery(null), ''))
t('limita longitud a 200', () => assert.equal(sanitizeQuery('a'.repeat(500)).length, 200))

console.log('normalizeType')
t('series se respeta', () => assert.equal(normalizeType('series'), 'series'))
t('cualquier otra cosa -> movie', () => assert.equal(normalizeType('xyz'), 'movie'))

console.log('extractQuality')
t('2160p -> 4K', () => assert.equal(extractQuality('Movie 2160p BluRay'), '4K'))
t('4k -> 4K', () => assert.equal(extractQuality('Movie 4k'), '4K'))
t('1080p', () => assert.equal(extractQuality('Movie 1080p x264'), '1080p'))
t('720p', () => assert.equal(extractQuality('Movie 720p'), '720p'))
t('sin resolución -> SD', () => assert.equal(extractQuality('Movie DVDRip'), 'SD'))

console.log('extractSeeders')
t('formato emoji 👤', () => assert.equal(extractSeeders('👤 152 💾 2.1 GB'), 152))
t('formato texto seeders', () => assert.equal(extractSeeders('45 seeders'), 45))
t('sin seeders -> 0', () => assert.equal(extractSeeders('nada'), 0))
t('miles con separador', () => assert.equal(extractSeeders('👤 1,234'), 1234))

console.log('extractSize')
t('💾 con espacio', () => assert.equal(extractSize('💾 2.18 GB'), '2.18 GB'))
t('sin espacio', () => assert.equal(extractSize('2.1GB'), '2.1 GB'))
t('MB', () => assert.equal(extractSize('700 MB'), '700 MB'))
t('desconocido', () => assert.equal(extractSize('nada'), 'Unknown'))

console.log('buildMagnet')
t('construye xt', () => assert.ok(buildMagnet('ABC123', 'Peli').startsWith('magnet:?xt=urn:btih:ABC123')))
t('incluye dn', () => assert.ok(buildMagnet('ABC', 'My Movie').includes('dn=My%20Movie')))
t('incluye trackers por defecto', () => assert.ok(buildMagnet('ABC', 'x').includes('tr=')))
t('añade trackers de sources', () => assert.ok(buildMagnet('ABC', 'x', ['tracker:udp://foo:1/announce', 'dht:ABC']).includes(encodeURIComponent('udp://foo:1/announce'))))
t('sin infoHash -> null', () => assert.equal(buildMagnet('', 'x'), null))

console.log('parseStream')
t('parsea stream real de Torrentio', () => {
  const s = parseStream({
    name: 'Torrentio\n1080p',
    title: 'Inception.2010.1080p.BluRay.x264\n👤 152 💾 2.18 GB ⚙️ ThePirateBay',
    infoHash: 'ABCDEF',
    sources: ['tracker:udp://tr.example:1337/announce', 'dht:ABCDEF']
  })
  assert.equal(s.quality, '1080p')
  assert.equal(s.size, '2.18 GB')
  assert.equal(s.seeders, 152)
  assert.equal(s.infoHash, 'abcdef')
  assert.equal(s.filename, 'Inception.2010.1080p.BluRay.x264')
  assert.ok(s.url.startsWith('magnet:'))
  assert.equal(s.title, '1080p - 2.18 GB - 152 seeders')
})
t('stream sin infoHash -> null', () => assert.equal(parseStream({ title: 'x' }), null))

console.log('processStreams')
t('ordena por seeders desc y filtra inválidos', () => {
  const out = processStreams([
    { infoHash: 'a', title: 'x\n👤 10 💾 1 GB' },
    { title: 'sin hash' },
    { infoHash: 'b', title: 'y\n👤 99 💾 2 GB' }
  ])
  assert.equal(out.length, 2)
  assert.equal(out[0].seeders, 99)
})

console.log('createSearch (fetch mockeado)')
t('flujo completo: OMDb -> Torrentio', async () => {
  const calls = []
  const fetchImpl = async (url) => {
    calls.push(url)
    if (url.includes('omdbapi')) return { ok: true, json: async () => ({ Response: 'True', imdbID: 'tt1375666', Title: 'Inception' }) }
    return { ok: true, json: async () => ({ streams: [{ infoHash: 'h1', title: 'Inception 1080p\n👤 200 💾 2 GB' }] }) }
  }
  const { search } = createSearch({ fetchImpl, omdbKey: 'KEY' })
  const r = await search('Inception', 'movie')
  assert.equal(r.success, true)
  assert.equal(r.imdbId, 'tt1375666')
  assert.equal(r.streams.length, 1)
  assert.equal(r.streams[0].seeders, 200)
  assert.ok(calls[0].includes('omdbapi'))
  assert.ok(calls[1].includes('torrentio'))
})
t('IMDb id directo salta OMDb', async () => {
  const calls = []
  const fetchImpl = async (url) => {
    calls.push(url)
    return { ok: true, json: async () => ({ streams: [] }) }
  }
  const { search } = createSearch({ fetchImpl }) // sin omdbKey
  const r = await search('tt1375666', 'movie')
  assert.equal(r.imdbId, 'tt1375666')
  assert.equal(calls.length, 1)
  assert.ok(calls[0].includes('torrentio'))
})
t('OMDb no encontrado -> SearchError NOT_FOUND', async () => {
  const fetchImpl = async () => ({ ok: true, json: async () => ({ Response: 'False' }) })
  const { search } = createSearch({ fetchImpl, omdbKey: 'KEY' })
  await assert.rejects(() => search('nope', 'movie'), (e) => e instanceof SearchError && e.code === 'NOT_FOUND' && e.status === 404)
})
t('query vacía -> SearchError EMPTY', async () => {
  const { search } = createSearch({ fetchImpl: async () => ({}), omdbKey: 'KEY' })
  await assert.rejects(() => search('   ', 'movie'), (e) => e.code === 'EMPTY')
})
t('sin API key y no es id -> SearchError NO_KEY', async () => {
  const { search } = createSearch({ fetchImpl: async () => ({}) })
  await assert.rejects(() => search('Inception', 'movie'), (e) => e.code === 'NO_KEY')
})
t('caché: segunda llamada no vuelve a hacer fetch', async () => {
  let n = 0
  const fetchImpl = async (url) => {
    n++
    if (url.includes('omdbapi')) return { ok: true, json: async () => ({ Response: 'True', imdbID: 'tt1', Title: 'X' }) }
    return { ok: true, json: async () => ({ streams: [{ infoHash: 'h', title: 'x\n👤 1 💾 1 GB' }] }) }
  }
  const { search } = createSearch({ fetchImpl, omdbKey: 'K' })
  await search('Matrix', 'movie')
  const before = n
  const r2 = await search('Matrix', 'movie')
  assert.equal(n, before) // no nuevas llamadas
  assert.equal(r2.cached, true)
})
t('timeout -> SearchError TIMEOUT', async () => {
  const fetchImpl = (url, opts) => new Promise((resolve, reject) => {
    opts.signal.addEventListener('abort', () => {
      const e = new Error('aborted'); e.name = 'AbortError'; reject(e)
    })
  })
  const { search } = createSearch({ fetchImpl, omdbKey: 'K', timeoutMs: 50 })
  await assert.rejects(() => search('slow', 'movie'), (e) => e.code === 'TIMEOUT')
})

console.log('store (persistencia)')
t('add/all/update/remove con persistencia en disco', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'store-'))
  const file = path.join(dir, 'torrents.json')
  const s = createStore(file)
  s.add({ infoHash: 'x', magnetURI: 'magnet:x', name: 'X' })
  s.add({ infoHash: 'x', name: 'X2' }) // actualiza, no duplica
  assert.equal(s.all().length, 1)
  assert.equal(s.all()[0].name, 'X2')
  s.update('x', { paused: true })
  assert.equal(s.all()[0].paused, true)
  s.remove('x')
  assert.equal(s.all().length, 0)
})
t('carga estado previo desde disco', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'store-'))
  const file = path.join(dir, 'torrents.json')
  fs.writeFileSync(file, JSON.stringify([{ infoHash: 'z', name: 'Z' }]))
  const s = createStore(file)
  assert.equal(s.all().length, 1)
  assert.equal(s.all()[0].infoHash, 'z')
})

// Resumen (esperamos a los async con un pequeño retardo)
setTimeout(() => {
  console.log(`\n${process.exitCode ? '❌ Fallos detectados' : '✅ Todos los tests pasaron'} (${passed} asserts ok)`)
}, 200)
