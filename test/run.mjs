// Tests sin dependencias externas. Ejecutar: node test/run.mjs
import assert from 'node:assert'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {
  sanitizeQuery, normalizeType, normalizeSource, normalizeInt,
  extractQuality, extractSeeders, extractSize, humanSize,
  buildMagnet, isValidMagnet, isValidInfoHash,
  parseStream, processStreams, parseApibay, dedupeStreams,
  createSearch, SearchError
} from '../lib/search.js'
import crypto from 'node:crypto'
import { createStore } from '../lib/store.js'
import { createAuth, parseCookies } from '../lib/auth.js'
import { createUserData, MAX_PROGRESS } from '../lib/userdata.js'
import { createFirebaseVerifier } from '../lib/firebaseAuth.js'

let passed = 0
const pending = []
function t (name, fn) {
  try {
    const r = fn()
    if (r && r.then) { pending.push(r.then(() => { passed++; console.log('  ✓', name) }).catch((e) => { console.error('  ✗', name, '\n    ', e.message); process.exitCode = 1 })) } else { passed++; console.log('  ✓', name) }
  } catch (e) { console.error('  ✗', name, '\n    ', e.message); process.exitCode = 1 }
}
function tmp () { return fs.mkdtempSync(path.join(os.tmpdir(), 'tcv-')) }

console.log('sanitizeQuery / normalize')
t('elimina HTML', () => assert.equal(sanitizeQuery('<script>Inception</script>'), 'Inception'))
t('colapsa espacios', () => assert.equal(sanitizeQuery('  the   matrix  '), 'the matrix'))
t('vacío/null', () => { assert.equal(sanitizeQuery(''), ''); assert.equal(sanitizeQuery(null), '') })
t('normalizeSource', () => { assert.equal(normalizeSource('peerflix'), 'peerflix'); assert.equal(normalizeSource('x'), 'all') })
t('normalizeInt', () => { assert.equal(normalizeInt('3'), 3); assert.equal(normalizeInt('abc'), null); assert.equal(normalizeInt('-1'), null) })

console.log('extractores')
t('quality 4K/1080/720/unknown', () => {
  assert.equal(extractQuality('Movie 2160p'), '4K')
  assert.equal(extractQuality('Movie 1080p'), '1080p')
  assert.equal(extractQuality('Movie 720p'), '720p')
  assert.equal(extractQuality('Movie sin nada'), 'Unknown')
})
t('seeders emoji y texto', () => { assert.equal(extractSeeders('👤 152'), 152); assert.equal(extractSeeders('45 seeders'), 45); assert.equal(extractSeeders('nada'), 0) })
t('size', () => { assert.equal(extractSize('💾 2.18 GB'), '2.18 GB'); assert.equal(extractSize('2.1GB'), '2.1 GB') })
t('humanSize', () => { assert.equal(humanSize(2 * 1024 ** 3), '2.00 GB'); assert.equal(humanSize(700 * 1024 ** 2), '700 MB'); assert.equal(humanSize(0), 'Unknown') })

console.log('magnet / validación')
t('buildMagnet', () => { assert.ok(buildMagnet('ABC', 'Peli').startsWith('magnet:?xt=urn:btih:ABC')); assert.equal(buildMagnet('', 'x'), null) })
t('isValidMagnet', () => {
  assert.ok(isValidMagnet('magnet:?xt=urn:btih:' + 'a'.repeat(40)))
  assert.ok(!isValidMagnet('http://evil.com'))
  assert.ok(!isValidMagnet('magnet:?dn=x'))
})
t('isValidInfoHash', () => { assert.ok(isValidInfoHash('a'.repeat(40))); assert.ok(!isValidInfoHash('xyz')) })

console.log('parseStream (Torrentio)')
t('parsea stream real', () => {
  const s = parseStream({ name: 'Torrentio\n1080p', title: 'Inception.2010.1080p.BluRay.x264\n👤 152 💾 2.18 GB ⚙️ TPB', infoHash: 'ABCDEF', sources: ['tracker:udp://tr:1/announce'] })
  assert.equal(s.source, 'torrentio'); assert.equal(s.quality, '1080p'); assert.equal(s.seeders, 152); assert.equal(s.infoHash, 'abcdef')
  assert.equal(s.title, '1080p - 2.18 GB - 152 seeders')
})

console.log('parseApibay (Peerflix)')
t('parsea y filtra centinela/sin seeders', () => {
  const out = parseApibay([
    { name: 'Inception 2010 1080p BluRay', info_hash: 'AB'.repeat(20), seeders: '150', size: String(2 * 1024 ** 3), category: '207' },
    { name: 'nada', info_hash: '0'.repeat(40), seeders: '0' },
    { name: 'Serie S01', info_hash: 'CD'.repeat(20), seeders: '10', size: '100', category: '208' }
  ], 'movie')
  assert.equal(out.length, 1) // el de serie (cat 208) se excluye en 'movie'
  assert.equal(out[0].source, 'peerflix'); assert.equal(out[0].quality, '1080p'); assert.equal(out[0].seeders, 150)
})

console.log('dedupeStreams')
t('gana el de más seeders', () => {
  const out = dedupeStreams([{ infoHash: 'a', seeders: 10 }, { infoHash: 'a', seeders: 99 }, { infoHash: 'b', seeders: 5 }])
  assert.equal(out.length, 2); assert.equal(out[0].seeders, 99)
})

console.log('createSearch')
t('torrentio: OMDb -> Torrentio', async () => {
  const fetchImpl = async (url) => url.includes('omdbapi')
    ? { ok: true, json: async () => ({ Response: 'True', imdbID: 'tt1375666', Title: 'Inception' }) }
    : { ok: true, json: async () => ({ streams: [{ infoHash: 'h1', title: 'Inception 1080p\n👤 200 💾 2 GB' }] }) }
  const { search } = createSearch({ fetchImpl, omdbKey: 'KEY' })
  const r = await search('Inception', 'movie', 'torrentio')
  assert.equal(r.imdbId, 'tt1375666'); assert.equal(r.streams.length, 1); assert.equal(r.streams[0].seeders, 200)
})
t('peerflix: apibay', async () => {
  const fetchImpl = async () => ({ ok: true, json: async () => ([{ name: 'X 1080p', info_hash: 'AB'.repeat(20), seeders: '30', size: String(1024 ** 3), category: '207' }]) })
  const { search } = createSearch({ fetchImpl })
  const r = await search('X', 'movie', 'peerflix')
  assert.equal(r.streams.length, 1); assert.equal(r.streams[0].source, 'peerflix')
})
t('all: combina y deduplica', async () => {
  const fetchImpl = async (url) => {
    if (url.includes('omdbapi')) return { ok: true, json: async () => ({ Response: 'True', imdbID: 'tt1', Title: 'X' }) }
    if (url.includes('torrentio')) return { ok: true, json: async () => ({ streams: [{ infoHash: 'dup', title: 'X 1080p\n👤 50 💾 1 GB' }] }) }
    return { ok: true, json: async () => ([{ name: 'X 1080p', info_hash: 'DUP', seeders: '80', size: String(1024 ** 3), category: '207' }]) }
  }
  const { search } = createSearch({ fetchImpl, omdbKey: 'K' })
  const r = await search('X', 'movie', 'all')
  assert.equal(r.streams.length, 1) // mismo infoHash 'dup' -> deduplicado
  assert.equal(r.streams[0].seeders, 80) // gana peerflix (más seeders)
})
t('series con season/episode en Torrentio', async () => {
  let torrentioUrl = ''
  const fetchImpl = async (url) => {
    if (url.includes('omdbapi')) return { ok: true, json: async () => ({ Response: 'True', imdbID: 'tt99', Title: 'S' }) }
    torrentioUrl = url
    return { ok: true, json: async () => ({ streams: [] }) }
  }
  const { search } = createSearch({ fetchImpl, omdbKey: 'K' })
  await search('Show', 'series', 'torrentio', { season: 2, episode: 5 })
  assert.ok(torrentioUrl.includes('tt99:2:5'), torrentioUrl)
})
t('IMDb id directo salta OMDb', async () => {
  let calls = 0
  const fetchImpl = async () => { calls++; return { ok: true, json: async () => ({ streams: [] }) } }
  const { search } = createSearch({ fetchImpl })
  const r = await search('tt1375666', 'movie', 'torrentio')
  assert.equal(r.imdbId, 'tt1375666'); assert.equal(calls, 1)
})
t('NOT_FOUND', async () => {
  const fetchImpl = async () => ({ ok: true, json: async () => ({ Response: 'False' }) })
  const { search } = createSearch({ fetchImpl, omdbKey: 'K' })
  await assert.rejects(() => search('nope', 'movie', 'torrentio'), (e) => e.code === 'NOT_FOUND')
})
t('EMPTY', async () => {
  const { search } = createSearch({ fetchImpl: async () => ({}), omdbKey: 'K' })
  await assert.rejects(() => search('   ', 'movie', 'all'), (e) => e.code === 'EMPTY')
})
t('NO_KEY (torrentio sin key y no es id)', async () => {
  const { search } = createSearch({ fetchImpl: async () => ({}) })
  await assert.rejects(() => search('Inception', 'movie', 'torrentio'), (e) => e.code === 'NO_KEY')
})
t('caché evita segundo fetch', async () => {
  let n = 0
  const fetchImpl = async (url) => { n++; return url.includes('omdbapi') ? { ok: true, json: async () => ({ Response: 'True', imdbID: 'tt1', Title: 'X' }) } : { ok: true, json: async () => ({ streams: [] }) } }
  const { search } = createSearch({ fetchImpl, omdbKey: 'K' })
  await search('Matrix', 'movie', 'torrentio')
  const before = n
  const r2 = await search('Matrix', 'movie', 'torrentio')
  assert.equal(n, before); assert.equal(r2.cached, true)
})
t('TIMEOUT', async () => {
  const fetchImpl = (url, opts) => new Promise((_res, rej) => { opts.signal.addEventListener('abort', () => { const e = new Error('a'); e.name = 'AbortError'; rej(e) }) })
  const { search } = createSearch({ fetchImpl, omdbKey: 'K', timeoutMs: 40 })
  await assert.rejects(() => search('slow', 'movie', 'torrentio'), (e) => e.code === 'TIMEOUT')
})

console.log('store (persistencia + propietarios)')
t('owners: add/remove/isOwner', () => {
  const s = createStore(path.join(tmp(), 'torrents.json'))
  s.add({ infoHash: 'x', magnetURI: 'm', owners: ['u1'] })
  assert.ok(s.isOwner('x', 'u1'))
  s.addOwner('x', 'u2')
  assert.equal(s.getOwners('x').length, 2)
  assert.equal(s.removeOwner('x', 'u1'), 1)
  assert.ok(!s.isOwner('x', 'u1'))
})
t('persiste y recarga', () => {
  const dir = tmp(); const file = path.join(dir, 'torrents.json')
  const s1 = createStore(file); s1.add({ infoHash: 'z', owners: ['u'] }); s1.flush()
  const s2 = createStore(file)
  assert.equal(s2.all()[0].infoHash, 'z')
})

console.log('userdata (favoritos + progreso + ajustes)')
t('favoritos: add/dedupe/remove y saneado', () => {
  const u = createUserData(path.join(tmp(), 'userdata.json'))
  assert.equal(u.addFavorite('u1', {}), null) // sin id/título -> inválido
  const f = u.addFavorite('u1', { id: 'tmdb:1', title: 'Peli', year: 2020, poster: 'https://evil.com/x.jpg', rating: '7.5' })
  assert.equal(f.poster, null) // solo pósters de TMDB
  assert.equal(f.rating, 7.5)
  u.addFavorite('u1', { id: 'tmdb:1', title: 'Peli', poster: 'https://image.tmdb.org/t/p/w342/x.jpg' })
  assert.equal(u.getFavorites('u1').length, 1) // dedupe por id
  assert.ok(u.getFavorites('u1')[0].poster.startsWith('https://image.tmdb.org/'))
  assert.equal(u.getFavorites('u2').length, 0) // aislado por usuario
  assert.ok(u.removeFavorite('u1', 'tmdb:1'))
  assert.equal(u.getFavorites('u1').length, 0)
})
t('progreso: watched al 95% y es permanente', () => {
  const u = createUserData(path.join(tmp(), 'userdata.json'))
  assert.equal(u.setProgress('u1', {}), null)
  assert.equal(u.setProgress('u1', { infoHash: 'h', fileIndex: -1, position: 10 }), null)
  const p1 = u.setProgress('u1', { infoHash: 'h', fileIndex: 0, name: 'peli.mkv', position: 600, duration: 6000 })
  assert.equal(p1.watched, false)
  const p2 = u.setProgress('u1', { infoHash: 'h', fileIndex: 0, position: 5800, duration: 6000 })
  assert.equal(p2.watched, true)
  // Rebobinar no des-marca lo visto
  const p3 = u.setProgress('u1', { infoHash: 'h', fileIndex: 0, position: 100, duration: 6000 })
  assert.equal(p3.watched, true)
  assert.equal(u.getProgressFor('u1', 'h', 0).name, 'peli.mkv') // conserva nombre
  assert.ok(u.removeProgress('u1', 'h:0'))
  assert.equal(u.getProgress('u1').length, 0)
})
t('progreso: poda las entradas más antiguas', () => {
  const u = createUserData(path.join(tmp(), 'userdata.json'))
  for (let i = 0; i <= MAX_PROGRESS + 10; i++) {
    u.setProgress('u1', { infoHash: 'h' + i, fileIndex: 0, position: 100, duration: 1000 })
  }
  assert.equal(u.getProgress('u1').length, MAX_PROGRESS)
})
t('ajustes: fusión, borrado con null y solo escalares', () => {
  const u = createUserData(path.join(tmp(), 'userdata.json'))
  u.setSettings('u1', { searchType: 'series', volume: 0.8, obj: { nested: true } })
  const s = u.getSettings('u1')
  assert.equal(s.searchType, 'series')
  assert.equal(s.volume, 0.8)
  assert.ok(!('obj' in s)) // objetos anidados ignorados
  u.setSettings('u1', { searchType: null })
  assert.ok(!('searchType' in u.getSettings('u1')))
})
t('persiste y recarga', () => {
  const file = path.join(tmp(), 'userdata.json')
  const u1 = createUserData(file)
  u1.addFavorite('u1', { id: 'tmdb:9', title: 'Otra' })
  u1.setProgress('u1', { infoHash: 'h', fileIndex: 1, position: 50, duration: 100 })
  u1.flush()
  const u2 = createUserData(file)
  assert.equal(u2.getFavorites('u1')[0].id, 'tmdb:9')
  assert.equal(u2.getProgressFor('u1', 'h', 1).position, 50)
})

console.log('auth')
t('parseCookies', () => { assert.deepEqual(parseCookies('a=1; b=2'), { a: '1', b: '2' }) })
t('register + login + token roundtrip', () => {
  const dir = tmp()
  const a = createAuth({ usersFile: path.join(dir, 'users.json'), secretFile: path.join(dir, '.secret') })
  const u = a.register('alice', 'secret123')
  assert.equal(u.username, 'alice'); assert.ok(!u.passwordHash)
  const { token } = a.login('alice', 'secret123')
  const req = { headers: { cookie: `${a.COOKIE}=${encodeURIComponent(token)}` } }
  assert.equal(a.userFromRequest(req).username, 'alice')
})
t('login con contraseña incorrecta falla', () => {
  const dir = tmp()
  const a = createAuth({ usersFile: path.join(dir, 'users.json'), secretFile: path.join(dir, '.secret') })
  a.register('bob', 'password1')
  assert.throws(() => a.login('bob', 'wrong'), (e) => e.code === 'INVALID')
})
t('usuario duplicado / validaciones', () => {
  const dir = tmp()
  const a = createAuth({ usersFile: path.join(dir, 'users.json'), secretFile: path.join(dir, '.secret') })
  a.register('carol', 'password1')
  assert.throws(() => a.register('carol', 'password1'), (e) => e.code === 'EXISTS')
  assert.throws(() => a.register('x', 'password1'), (e) => e.code === 'BAD_USERNAME')
  assert.throws(() => a.register('validname', '123'), (e) => e.code === 'BAD_PASSWORD')
})
t('externalLogin: crea una vez y reutiliza; usuario saneado', () => {
  const dir = tmp()
  const a = createAuth({ usersFile: path.join(dir, 'users.json'), secretFile: path.join(dir, '.secret') })
  a.register('Ruben', 'password1') // ocupa el nombre
  const r1 = a.externalLogin('firebase', 'uid-123', 'Rubén!!')
  assert.equal(r1.user.provider, 'firebase')
  assert.equal(r1.user.username, 'Ruben2') // saneado (sin tilde/!) y único
  const r2 = a.externalLogin('firebase', 'uid-123', 'Otro Nombre')
  assert.equal(r2.user.id, r1.user.id) // mismo usuario en logins sucesivos
  assert.ok(r2.token)
  assert.throws(() => a.externalLogin('', ''), (e) => e.code === 'BAD_EXTERNAL')
  // La cuenta externa no permite login con contraseña
  assert.throws(() => a.login('Ruben2', 'loquesea'), (e) => e.code === 'INVALID')
})

t('firebase: verifica firma, aud, iss y expiración', async () => {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 })
  const pubPem = publicKey.export({ type: 'spki', format: 'pem' })
  const fetchImpl = async () => ({ ok: true, json: async () => ({ kid1: pubPem }) })
  const projectId = 'proj-test'
  const makeToken = (claims, kid = 'kid1') => {
    const now = Math.floor(Date.now() / 1000)
    const h = Buffer.from(JSON.stringify({ alg: 'RS256', kid })).toString('base64url')
    const p = Buffer.from(JSON.stringify({
      aud: projectId, iss: `https://securetoken.google.com/${projectId}`,
      sub: 'uid-1', iat: now, exp: now + 3600, ...claims
    })).toString('base64url')
    const sig = crypto.sign('RSA-SHA256', Buffer.from(`${h}.${p}`), privateKey).toString('base64url')
    return `${h}.${p}.${sig}`
  }
  const v = createFirebaseVerifier({ projectId, fetchImpl })
  const payload = await v.verify(makeToken({}))
  assert.equal(payload.sub, 'uid-1')
  await assert.rejects(v.verify(makeToken({ aud: 'otro-proyecto' })), (e) => e.code === 'AUD')
  await assert.rejects(v.verify(makeToken({ exp: Math.floor(Date.now() / 1000) - 10 })), (e) => e.code === 'EXPIRED')
  await assert.rejects(v.verify(makeToken({ iss: 'https://evil.com' })), (e) => e.code === 'ISS')
  const tampered = makeToken({}).slice(0, -6) + 'AAAAAA'
  await assert.rejects(v.verify(tampered), (e) => e.code === 'SIGNATURE')
  await assert.rejects(v.verify('no-es-un-jwt'), (e) => e.code === 'MALFORMED')
})

t('token manipulado se rechaza', () => {
  const dir = tmp()
  const a = createAuth({ usersFile: path.join(dir, 'users.json'), secretFile: path.join(dir, '.secret') })
  a.register('dave', 'password1')
  const { token } = a.login('dave', 'password1')
  const tampered = token.slice(0, -3) + 'xyz'
  assert.equal(a.userFromRequest({ headers: { cookie: `${a.COOKIE}=${tampered}` } }), null)
})

Promise.allSettled(pending).then(() => {
  setTimeout(() => {
    console.log(`\n${process.exitCode ? '❌ Fallos detectados' : '✅ Todos los tests pasaron'} (${passed} asserts ok)`)
  }, 50)
})
