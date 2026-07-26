// Pruebas de las reglas de Firestore contra el emulador.
// Requiere el emulador en 127.0.0.1:8080.
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing'
import { doc, getDoc, setDoc } from 'firebase/firestore'
import fs from 'node:fs'

const env = await initializeTestEnvironment({
  projectId: 'torrent-7dd4b',
  firestore: { rules: fs.readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8'), host: '127.0.0.1', port: 8080 }
})

const ana = env.authenticatedContext('ana').firestore()
const luis = env.authenticatedContext('luis').firestore()
const anon = env.unauthenticatedContext().firestore()

// Documento típico que escriben la web y la app Android
const docOk = {
  profiles: [{ id: 'default', name: 'Principal', kids: false }],
  states: { default: { favorites: [], progress: [], settings: { language: 'es-ES' } } },
  account: { rdToken: 'TOKEN-SECRETO-DE-REAL-DEBRID' },
  updatedAt: new Date().toISOString()
}

let ok = 0
let bad = 0
async function t (name, fn) {
  try { await fn(); ok++; console.log('  ✓', name) } catch (e) { bad++; console.log('  ✗', name, '\n     ', e.message) }
}

console.log('reglas de Firestore')

await t('el dueño puede escribir su documento', () =>
  assertSucceeds(setDoc(doc(ana, 'users/ana'), docOk)))

await t('el dueño puede leer su documento', () =>
  assertSucceeds(getDoc(doc(ana, 'users/ana'))))

await t('OTRO usuario NO puede leer tu documento (ni tu token de RD)', () =>
  assertFails(getDoc(doc(luis, 'users/ana'))))

await t('OTRO usuario NO puede escribir en tu documento', () =>
  assertFails(setDoc(doc(luis, 'users/ana'), { account: { rdToken: 'robado' } })))

await t('sin iniciar sesión NO se puede leer', () =>
  assertFails(getDoc(doc(anon, 'users/ana'))))

await t('sin iniciar sesión NO se puede escribir', () =>
  assertFails(setDoc(doc(anon, 'users/ana'), docOk)))

await t('se rechaza una clave desconocida (no vale como almacén gratuito)', () =>
  assertFails(setDoc(doc(ana, 'users/ana'), { ...docOk, basuraEnorme: 'x'.repeat(1000) })))

await t('se aceptan las claves del formato ANTIGUO (cuentas sin migrar)', () =>
  assertSucceeds(setDoc(doc(ana, 'users/ana'), {
    favorites: [], progress: [], settings: { language: 'es-ES' }, updatedAt: new Date().toISOString()
  })))

await t('otras colecciones están denegadas', () =>
  assertFails(setDoc(doc(ana, 'otra/cosa'), { a: 1 })))

await env.cleanup()
console.log(`\n${bad ? '❌ ' + bad + ' fallo(s)' : '✅ todas las reglas se comportan como se espera'} (${ok} ok)`)
process.exitCode = bad ? 1 : 0
