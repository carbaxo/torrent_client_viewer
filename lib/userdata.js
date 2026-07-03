// Estado por usuario con PERFILES: cada cuenta tiene varios perfiles (p.ej.
// "Principal", "Niños" con modo infantil) y cada perfil su propio estado
// (favoritos, progreso de reproducción y ajustes). Persistencia JSON atómica.
// Estructura (un documento por usuario, pensado para espejarse en Firestore):
//   data[userId] = {
//     profiles: [{ id, name, kids, createdAt }],
//     states: { [profileId]: { favorites: [], progress: {}, settings: {} } }
//   }

import fs from 'fs'
import path from 'path'
import crypto from 'crypto'

export const MAX_FAVORITES = 500
export const MAX_PROGRESS = 200
export const MAX_PROFILES = 5
export const WATCHED_THRESHOLD = 0.95
export const DEFAULT_PROFILE_ID = 'default'
const MAX_SETTINGS_KEYS = 40
const MAX_SETTINGS_VALUE = 500
const MAX_AVATAR = 120000 // ~90KB en base64: imágenes ya redimensionadas en el cliente
export const DEFAULT_AVATARS = ['🍿', '🎬', '🎮', '🦄', '🐱', '🐶', '🦊', '🐼', '👾', '🚀', '⚽', '🌈', '🧸', '🎧', '🦁', '🐸']

const progressKey = (infoHash, fileIndex) => `${infoHash}:${fileIndex}`

const emptyState = () => ({ favorites: [], progress: {}, settings: {} })

function sanitizeProfileName (name) {
  return String(name || '')
    .replace(/<[^>]*>/g, '')
    .replace(/[<>]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 24)
}

// Avatar válido: emoji/texto corto, imagen data-URL, o URL https. null si no.
function sanitizeAvatar (avatar) {
  if (typeof avatar !== 'string') return null
  const v = avatar.trim()
  if (!v) return null
  if (v.startsWith('data:image/')) return v.length <= MAX_AVATAR ? v : null
  if (/^https:\/\//i.test(v)) return v.slice(0, 500)
  return v.slice(0, 8) // emoji o inicial
}

export function createUserData (filePath) {
  let data = {}
  try {
    const parsed = JSON.parse(fs.readFileSync(filePath, 'utf8'))
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) data = parsed
  } catch {
    // fichero inexistente o corrupto -> empezamos vacío
  }

  let timer = null
  function writeNow () {
    clearTimeout(timer)
    timer = null
    try {
      fs.mkdirSync(path.dirname(filePath), { recursive: true })
      fs.writeFileSync(filePath + '.tmp', JSON.stringify(data, null, 2))
      fs.renameSync(filePath + '.tmp', filePath) // escritura atómica
    } catch (err) {
      console.error('[userdata] no se pudo guardar:', err.message)
    }
  }
  function persist () {
    clearTimeout(timer)
    timer = setTimeout(writeNow, 400)
    if (timer.unref) timer.unref()
  }

  // Documento del usuario, migrando el formato antiguo (estado en la raíz)
  function userDoc (userId) {
    let doc = data[userId]
    if (!doc || typeof doc !== 'object') doc = data[userId] = {}
    if (!Array.isArray(doc.profiles) || !doc.profiles.length) {
      doc.profiles = [{ id: DEFAULT_PROFILE_ID, name: 'Principal', kids: false, avatar: '🍿', createdAt: new Date().toISOString() }]
    }
    if (!doc.states || typeof doc.states !== 'object') doc.states = {}
    // Datos a nivel de CUENTA (no de perfil): secretos como el token de
    // Real-Debrid. Solo los usa el servidor; nunca se envían al navegador.
    if (!doc.account || typeof doc.account !== 'object') doc.account = {}
    // Migración: favoritos/progreso/ajustes del formato sin perfiles
    if (Array.isArray(doc.favorites) || doc.progress || doc.settings) {
      doc.states[DEFAULT_PROFILE_ID] = {
        favorites: Array.isArray(doc.favorites) ? doc.favorites : [],
        progress: doc.progress && typeof doc.progress === 'object' ? doc.progress : {},
        settings: doc.settings && typeof doc.settings === 'object' ? doc.settings : {}
      }
      delete doc.favorites
      delete doc.progress
      delete doc.settings
      persist()
    }
    return doc
  }

  // Estado de un perfil; si el perfil no existe usa el perfil por defecto
  function bucket (userId, profileId) {
    const doc = userDoc(userId)
    const pid = doc.profiles.find((p) => p.id === profileId) ? profileId : doc.profiles[0].id
    if (!doc.states[pid]) doc.states[pid] = emptyState()
    const b = doc.states[pid]
    b.favorites = Array.isArray(b.favorites) ? b.favorites : []
    b.progress = b.progress && typeof b.progress === 'object' ? b.progress : {}
    b.settings = b.settings && typeof b.settings === 'object' ? b.settings : {}
    return b
  }

  return {
    // --- Perfiles ---------------------------------------------------------
    getProfiles (userId) {
      return userDoc(userId).profiles.map((p) => ({ ...p }))
    },
    // id opcional: permite espejar un perfil creado en otro dispositivo
    // (sincronización) conservando su identificador. Idempotente por id.
    addProfile (userId, { id, name, kids, avatar } = {}) {
      const doc = userDoc(userId)
      const clean = sanitizeProfileName(name)
      if (!clean) return null
      const pid = String(id || '').replace(/[^\w-]/g, '').slice(0, 32)
      if (pid) {
        const existing = doc.profiles.find((p) => p.id === pid)
        if (existing) return { ...existing }
      }
      if (doc.profiles.length >= MAX_PROFILES) return null
      const profile = {
        id: pid || crypto.randomUUID().slice(0, 8),
        name: clean,
        kids: !!kids,
        avatar: sanitizeAvatar(avatar) || (kids ? '🧒' : '🍿'),
        createdAt: new Date().toISOString()
      }
      doc.profiles.push(profile)
      doc.states[profile.id] = emptyState()
      persist()
      return { ...profile }
    },
    updateProfile (userId, profileId, patch = {}) {
      const doc = userDoc(userId)
      const p = doc.profiles.find((x) => x.id === profileId)
      if (!p) return null
      if (patch.name !== undefined) {
        const clean = sanitizeProfileName(patch.name)
        if (clean) p.name = clean
      }
      if (patch.kids !== undefined) p.kids = !!patch.kids
      if (patch.avatar !== undefined) {
        const av = sanitizeAvatar(patch.avatar)
        if (av) p.avatar = av
      }
      persist()
      return { ...p }
    },
    removeProfile (userId, profileId) {
      const doc = userDoc(userId)
      if (doc.profiles.length <= 1) return false // siempre queda al menos uno
      const before = doc.profiles.length
      doc.profiles = doc.profiles.filter((p) => p.id !== profileId)
      if (doc.profiles.length === before) return false
      delete doc.states[profileId]
      persist()
      return true
    },

    // --- Favoritos (títulos de catálogo/búsqueda) -----------------------
    getFavorites (userId, profileId) {
      return bucket(userId, profileId).favorites.map((f) => ({ ...f }))
    },
    // fav: { id, title, year?, poster?, type?, rating? }. Devuelve el
    // favorito saneado o null si es inválido. Idempotente (mueve al frente).
    addFavorite (userId, profileId, fav) {
      if (!fav || !fav.id || !fav.title) return null
      const clean = {
        id: String(fav.id).slice(0, 64),
        title: String(fav.title).slice(0, 200),
        year: String(fav.year || '').slice(0, 8),
        // Solo pósters de la CDN de TMDB (coherente con la CSP del frontend)
        poster: typeof fav.poster === 'string' && fav.poster.startsWith('https://image.tmdb.org/')
          ? fav.poster.slice(0, 300)
          : null,
        type: fav.type === 'series' ? 'series' : 'movie',
        rating: Number.isFinite(Number(fav.rating)) ? Number(fav.rating) : null,
        addedAt: new Date().toISOString()
      }
      const b = bucket(userId, profileId)
      b.favorites = b.favorites.filter((f) => f.id !== clean.id)
      b.favorites.unshift(clean)
      if (b.favorites.length > MAX_FAVORITES) b.favorites.length = MAX_FAVORITES
      persist()
      return clean
    },
    removeFavorite (userId, profileId, id) {
      const b = bucket(userId, profileId)
      const before = b.favorites.length
      b.favorites = b.favorites.filter((f) => f.id !== String(id))
      const removed = b.favorites.length !== before
      if (removed) persist()
      return removed
    },

    // --- Progreso de reproducción ---------------------------------------
    // Lista ordenada por más reciente (para "continuar viendo").
    getProgress (userId, profileId) {
      return Object.values(bucket(userId, profileId).progress)
        .map((p) => ({ ...p }))
        .sort((a, b) => (b.updatedAt || '').localeCompare(a.updatedAt || ''))
    },
    getProgressFor (userId, profileId, infoHash, fileIndex) {
      const p = bucket(userId, profileId).progress[progressKey(infoHash, fileIndex)]
      return p ? { ...p } : null
    },
    // entry: { infoHash, fileIndex, name?, position, duration, titleId? }
    setProgress (userId, profileId, entry) {
      if (!entry || !entry.infoHash) return null
      const fileIndex = Number(entry.fileIndex)
      const position = Number(entry.position)
      const duration = Number(entry.duration)
      if (!Number.isInteger(fileIndex) || fileIndex < 0) return null
      if (!Number.isFinite(position) || position < 0) return null
      const key = progressKey(String(entry.infoHash).slice(0, 64), fileIndex)
      const b = bucket(userId, profileId)
      const prev = b.progress[key]
      const clean = {
        key,
        infoHash: String(entry.infoHash).slice(0, 64),
        fileIndex,
        name: String(entry.name || prev?.name || '').slice(0, 300),
        titleId: String(entry.titleId || prev?.titleId || '').slice(0, 64) || null,
        position: Math.round(position),
        duration: Number.isFinite(duration) && duration > 0 ? Math.round(duration) : (prev?.duration || 0),
        updatedAt: new Date().toISOString()
      }
      clean.watched = (prev?.watched === true) ||
        (clean.duration > 0 && clean.position / clean.duration >= WATCHED_THRESHOLD)
      b.progress[key] = clean
      // Poda las entradas más antiguas para acotar el fichero
      const keys = Object.keys(b.progress)
      if (keys.length > MAX_PROGRESS) {
        keys.sort((a, c) => (b.progress[a].updatedAt || '').localeCompare(b.progress[c].updatedAt || ''))
        for (const k of keys.slice(0, keys.length - MAX_PROGRESS)) delete b.progress[k]
      }
      persist()
      return { ...clean }
    },
    removeProgress (userId, profileId, key) {
      const b = bucket(userId, profileId)
      if (!(key in b.progress)) return false
      delete b.progress[key]
      persist()
      return true
    },
    // ¿Está visto este archivo en ALGÚN perfil del usuario? (para limpiar buffer)
    isWatchedByAnyProfile (userId, infoHash, fileIndex) {
      const doc = userDoc(userId)
      const key = progressKey(infoHash, fileIndex)
      return Object.values(doc.states).some((s) => s.progress && s.progress[key] && s.progress[key].watched)
    },

    // --- Ajustes ---------------------------------------------------------
    getSettings (userId, profileId) {
      return { ...bucket(userId, profileId).settings }
    },
    // Fusión superficial; valores escalares cortos para acotar el tamaño.
    setSettings (userId, profileId, patch) {
      if (!patch || typeof patch !== 'object' || Array.isArray(patch)) return null
      const b = bucket(userId, profileId)
      for (const [k, v] of Object.entries(patch)) {
        if (typeof k !== 'string' || k.length > 64) continue
        if (v === null) { delete b.settings[k]; continue }
        if (['string', 'number', 'boolean'].includes(typeof v)) {
          b.settings[k] = typeof v === 'string' ? v.slice(0, MAX_SETTINGS_VALUE) : v
        }
      }
      const keys = Object.keys(b.settings)
      for (const k of keys.slice(MAX_SETTINGS_KEYS)) delete b.settings[k]
      persist()
      return { ...b.settings }
    },

    // --- Cuenta (secretos por usuario, p.ej. token de Real-Debrid) -------
    getAccount (userId) {
      return { ...userDoc(userId).account }
    },
    setAccount (userId, patch) {
      if (!patch || typeof patch !== 'object' || Array.isArray(patch)) return null
      const acc = userDoc(userId).account
      for (const [k, v] of Object.entries(patch)) {
        if (typeof k !== 'string' || k.length > 64) continue
        if (v === null) { delete acc[k]; continue }
        if (typeof v === 'string') acc[k] = v.slice(0, MAX_SETTINGS_VALUE)
      }
      persist()
      return { ...acc }
    },

    // Vuelca a disco de forma síncrona (tests y apagado limpio)
    flush () { writeNow() }
  }
}
