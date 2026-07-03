// Autenticación y perfiles de usuario.
//  - Contraseñas hasheadas con scrypt (crypto nativo, sin dependencias).
//  - Sesiones mediante token HMAC-SHA256 firmado (stateless, sobrevive a
//    reinicios); se entrega en una cookie httpOnly + SameSite=Lax.

import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'

const SCRYPT_KEYLEN = 64
const SESSION_TTL_MS = 7 * 24 * 60 * 60 * 1000 // 7 días
const USERNAME_RE = /^[a-zA-Z0-9._-]{3,32}$/
const MIN_PASSWORD = 6

export class AuthError extends Error {
  constructor (code, message, status = 400) {
    super(message)
    this.code = code
    this.status = status
  }
}

// --- Hash de contraseñas -----------------------------------------------

function hashPassword (password) {
  const salt = crypto.randomBytes(16)
  const hash = crypto.scryptSync(password, salt, SCRYPT_KEYLEN)
  return `scrypt$${salt.toString('hex')}$${hash.toString('hex')}`
}

function verifyPassword (password, stored) {
  try {
    const [scheme, saltHex, hashHex] = String(stored).split('$')
    if (scheme !== 'scrypt') return false
    const salt = Buffer.from(saltHex, 'hex')
    const expected = Buffer.from(hashHex, 'hex')
    const actual = crypto.scryptSync(password, salt, expected.length)
    return actual.length === expected.length && crypto.timingSafeEqual(actual, expected)
  } catch {
    return false
  }
}

// --- Tokens de sesión (HMAC firmado) -----------------------------------

const b64url = (buf) => Buffer.from(buf).toString('base64url')

function sign (payload, secret) {
  return crypto.createHmac('sha256', secret).update(payload).digest('base64url')
}

function issueToken (uid, secret) {
  const payload = b64url(JSON.stringify({ uid, exp: Date.now() + SESSION_TTL_MS }))
  return `${payload}.${sign(payload, secret)}`
}

function verifyToken (token, secret) {
  if (typeof token !== 'string' || !token.includes('.')) return null
  const [payload, sig] = token.split('.')
  const expected = sign(payload, secret)
  // Comparación en tiempo constante
  const a = Buffer.from(sig || '')
  const b = Buffer.from(expected)
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null
  try {
    const { uid, exp } = JSON.parse(Buffer.from(payload, 'base64url').toString())
    if (!uid || typeof exp !== 'number' || exp < Date.now()) return null
    return uid
  } catch {
    return null
  }
}

// Parseo sencillo de la cabecera Cookie (evita añadir cookie-parser)
export function parseCookies (header) {
  const out = {}
  if (!header) return out
  for (const part of header.split(';')) {
    const i = part.indexOf('=')
    if (i < 0) continue
    out[part.slice(0, i).trim()] = decodeURIComponent(part.slice(i + 1).trim())
  }
  return out
}

// --- Persistencia de usuarios ------------------------------------------

function loadUsers (file) {
  try {
    const parsed = JSON.parse(fs.readFileSync(file, 'utf8'))
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function saveUsers (file, users) {
  fs.mkdirSync(path.dirname(file), { recursive: true })
  fs.writeFileSync(file + '.tmp', JSON.stringify(users, null, 2))
  fs.renameSync(file + '.tmp', file)
}

// Secreto de sesión: variable de entorno o generado y persistido (modo 600)
function resolveSecret (envSecret, secretFile) {
  if (envSecret) return envSecret
  try {
    return fs.readFileSync(secretFile, 'utf8')
  } catch {
    const secret = crypto.randomBytes(32).toString('hex')
    fs.mkdirSync(path.dirname(secretFile), { recursive: true })
    fs.writeFileSync(secretFile, secret, { mode: 0o600 })
    return secret
  }
}

const COOKIE = 'tcv_session'

export function createAuth ({ usersFile, secretFile, envSecret, allowRegistration = true, secureCookie = false, crossSite = false } = {}) {
  const secret = resolveSecret(envSecret, secretFile)
  let users = loadUsers(usersFile)

  const publicUser = (u) => ({ id: u.id, username: u.username, createdAt: u.createdAt, provider: u.provider || 'local' })

  // Nombre de usuario válido y único a partir de un nombre visible externo
  function uniqueUsername (base, fallback) {
    let name = String(base || '').normalize('NFKD').replace(/[̀-ͯ]/g, '')
      .replace(/[^a-zA-Z0-9._-]/g, '').slice(0, 28)
    if (name.length < 3) name = fallback
    let candidate = name
    let n = 2
    while (users.find((u) => u.username.toLowerCase() === candidate.toLowerCase())) {
      candidate = `${name}${n++}`
    }
    return candidate
  }

  function register (username, password) {
    username = String(username || '').trim()
    password = String(password || '')
    if (!USERNAME_RE.test(username)) {
      throw new AuthError('BAD_USERNAME', 'El usuario debe tener 3-32 caracteres (letras, números, . _ -).')
    }
    if (password.length < MIN_PASSWORD) {
      throw new AuthError('BAD_PASSWORD', `La contraseña debe tener al menos ${MIN_PASSWORD} caracteres.`)
    }
    if (users.find((u) => u.username.toLowerCase() === username.toLowerCase())) {
      throw new AuthError('EXISTS', 'Ese nombre de usuario ya existe.', 409)
    }
    const user = {
      id: crypto.randomUUID(),
      username,
      passwordHash: hashPassword(password),
      createdAt: new Date().toISOString()
    }
    users.push(user)
    saveUsers(usersFile, users)
    return publicUser(user)
  }

  function login (username, password) {
    username = String(username || '').trim()
    const user = users.find((u) => u.username.toLowerCase() === username.toLowerCase())
    // Verificamos siempre (incluso sin usuario) para no filtrar tiempos
    const ok = user ? verifyPassword(password, user.passwordHash) : verifyPassword(password, 'scrypt$00$00')
    if (!user || !ok) {
      throw new AuthError('INVALID', 'Usuario o contraseña incorrectos.', 401)
    }
    return { user: publicUser(user), token: issueToken(user.id, secret) }
  }

  // Login con identidad externa verificada (p.ej. Firebase/Google).
  // Crea el usuario local la primera vez; después lo reutiliza.
  function externalLogin (provider, sub, displayName) {
    provider = String(provider || '').trim()
    sub = String(sub || '').trim()
    if (!provider || !sub) throw new AuthError('BAD_EXTERNAL', 'Identidad externa inválida.')
    const externalId = `${provider}:${sub}`
    let user = users.find((u) => u.externalId === externalId)
    if (!user) {
      user = {
        id: crypto.randomUUID(),
        username: uniqueUsername(displayName, `${provider}-${sub.slice(0, 8)}`),
        externalId,
        provider,
        createdAt: new Date().toISOString()
      }
      users.push(user)
      saveUsers(usersFile, users)
    }
    return { user: publicUser(user), token: issueToken(user.id, secret) }
  }

  function getUserById (id) {
    const u = users.find((x) => x.id === id)
    return u ? publicUser(u) : null
  }

  function userFromRequest (req) {
    const cookies = parseCookies(req.headers.cookie)
    const uid = verifyToken(cookies[COOKIE], secret)
    return uid ? getUserById(uid) : null
  }

  // Middleware: exige sesión válida
  function requireAuth (req, res, next) {
    const user = userFromRequest(req)
    if (!user) return res.status(401).json({ error: 'No autenticado.', code: 'UNAUTH' })
    req.user = user
    next()
  }

  function setSessionCookie (res, token) {
    // Cross-site (frontend en GitHub Pages + backend en otro dominio) exige
    // SameSite=None; Secure para que el navegador envíe la cookie.
    res.cookie(COOKIE, token, {
      httpOnly: true,
      sameSite: crossSite ? 'none' : 'lax',
      secure: crossSite ? true : secureCookie,
      maxAge: SESSION_TTL_MS,
      path: '/'
    })
  }

  function clearSessionCookie (res) {
    res.clearCookie(COOKIE, { path: '/' })
  }

  return {
    register,
    login,
    externalLogin,
    getUserById,
    userFromRequest,
    requireAuth,
    setSessionCookie,
    clearSessionCookie,
    hasUsers: () => users.length > 0,
    allowRegistration,
    COOKIE
  }
}
