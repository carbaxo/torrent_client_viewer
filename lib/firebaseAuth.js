// Verificación de ID tokens de Firebase Auth sin dependencias externas.
// Son JWT RS256 firmados por Google; validamos firma (certificados x509
// públicos de Google, cacheados), expiración, audiencia (projectId) e issuer.

import crypto from 'node:crypto'

const CERTS_URL = 'https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com'
const CERTS_TTL_MS = 60 * 60 * 1000 // 1 hora

export class FirebaseAuthError extends Error {
  constructor (code, message) {
    super(message)
    this.code = code
    this.status = 401
  }
}

const b64json = (part) => JSON.parse(Buffer.from(part, 'base64url').toString('utf8'))

// Acepta certificados x509 o claves públicas PEM (esto último para tests)
function toPublicKey (pem) {
  try {
    return new crypto.X509Certificate(pem).publicKey
  } catch {
    return crypto.createPublicKey(pem)
  }
}

export function createFirebaseVerifier ({ projectId, fetchImpl, certsTtlMs = CERTS_TTL_MS } = {}) {
  const doFetch = fetchImpl || globalThis.fetch
  let certsCache = { certs: null, expires: 0 }

  async function getCerts () {
    if (certsCache.certs && certsCache.expires > Date.now()) return certsCache.certs
    const res = await doFetch(CERTS_URL)
    if (!res.ok) throw new FirebaseAuthError('CERTS', 'No se pudieron obtener los certificados de Google.')
    const certs = await res.json()
    certsCache = { certs, expires: Date.now() + certsTtlMs }
    return certs
  }

  // Devuelve el payload del token si es válido; lanza FirebaseAuthError si no.
  async function verify (idToken) {
    if (!projectId) throw new FirebaseAuthError('NO_PROJECT', 'FIREBASE_PROJECT_ID no configurado en el servidor.')
    if (typeof idToken !== 'string' || idToken.split('.').length !== 3) {
      throw new FirebaseAuthError('MALFORMED', 'Token malformado.')
    }
    const [h, p, sig] = idToken.split('.')
    let header, payload
    try {
      header = b64json(h)
      payload = b64json(p)
    } catch {
      throw new FirebaseAuthError('MALFORMED', 'Token malformado.')
    }
    if (header.alg !== 'RS256') throw new FirebaseAuthError('ALG', 'Algoritmo de firma no admitido.')

    const certs = await getCerts()
    const pem = certs[header.kid]
    if (!pem) throw new FirebaseAuthError('KID', 'Certificado de firma desconocido.')

    const ok = crypto.verify(
      'RSA-SHA256',
      Buffer.from(`${h}.${p}`),
      toPublicKey(pem),
      Buffer.from(sig, 'base64url')
    )
    if (!ok) throw new FirebaseAuthError('SIGNATURE', 'Firma del token inválida.')

    const now = Math.floor(Date.now() / 1000)
    if (typeof payload.exp !== 'number' || payload.exp < now) throw new FirebaseAuthError('EXPIRED', 'El token ha caducado.')
    if (typeof payload.iat !== 'number' || payload.iat > now + 300) throw new FirebaseAuthError('IAT', 'Token emitido en el futuro.')
    if (payload.aud !== projectId) throw new FirebaseAuthError('AUD', 'El token no pertenece a este proyecto.')
    if (payload.iss !== `https://securetoken.google.com/${projectId}`) throw new FirebaseAuthError('ISS', 'Issuer inválido.')
    if (!payload.sub || typeof payload.sub !== 'string') throw new FirebaseAuthError('SUB', 'Token sin uid.')

    return payload
  }

  return { verify }
}
