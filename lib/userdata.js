// Estado por usuario: favoritos, progreso de reproducción ("continuar
// viendo" / vistos) y ajustes. Persistencia JSON atómica como store.js.
// Los datos se organizan como un documento por usuario, para poder migrar
// esta capa a un backend remoto (p.ej. Firestore) sin tocar las rutas.

import fs from 'fs'
import path from 'path'

export const MAX_FAVORITES = 500
export const MAX_PROGRESS = 200
export const WATCHED_THRESHOLD = 0.95
const MAX_SETTINGS_KEYS = 40
const MAX_SETTINGS_VALUE = 500

const progressKey = (infoHash, fileIndex) => `${infoHash}:${fileIndex}`

export function createUserData (filePath) {
  let data = {} // { [userId]: { favorites: [], progress: {}, settings: {} } }
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

  function bucket (userId) {
    if (!data[userId]) data[userId] = { favorites: [], progress: {}, settings: {} }
    const b = data[userId]
    b.favorites = Array.isArray(b.favorites) ? b.favorites : []
    b.progress = b.progress && typeof b.progress === 'object' ? b.progress : {}
    b.settings = b.settings && typeof b.settings === 'object' ? b.settings : {}
    return b
  }

  return {
    // --- Favoritos (títulos de catálogo/búsqueda) -----------------------
    getFavorites (userId) {
      return bucket(userId).favorites.map((f) => ({ ...f }))
    },
    // fav: { id, title, year?, poster?, type?, rating? }. Devuelve el
    // favorito saneado o null si es inválido. Idempotente (mueve al frente).
    addFavorite (userId, fav) {
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
      const b = bucket(userId)
      b.favorites = b.favorites.filter((f) => f.id !== clean.id)
      b.favorites.unshift(clean)
      if (b.favorites.length > MAX_FAVORITES) b.favorites.length = MAX_FAVORITES
      persist()
      return clean
    },
    removeFavorite (userId, id) {
      const b = bucket(userId)
      const before = b.favorites.length
      b.favorites = b.favorites.filter((f) => f.id !== String(id))
      const removed = b.favorites.length !== before
      if (removed) persist()
      return removed
    },

    // --- Progreso de reproducción ---------------------------------------
    // Lista ordenada por más reciente (para "continuar viendo").
    getProgress (userId) {
      return Object.values(bucket(userId).progress)
        .map((p) => ({ ...p }))
        .sort((a, b) => (b.updatedAt || '').localeCompare(a.updatedAt || ''))
    },
    getProgressFor (userId, infoHash, fileIndex) {
      const p = bucket(userId).progress[progressKey(infoHash, fileIndex)]
      return p ? { ...p } : null
    },
    // entry: { infoHash, fileIndex, name?, position, duration }
    setProgress (userId, entry) {
      if (!entry || !entry.infoHash) return null
      const fileIndex = Number(entry.fileIndex)
      const position = Number(entry.position)
      const duration = Number(entry.duration)
      if (!Number.isInteger(fileIndex) || fileIndex < 0) return null
      if (!Number.isFinite(position) || position < 0) return null
      const key = progressKey(String(entry.infoHash).slice(0, 64), fileIndex)
      const b = bucket(userId)
      const prev = b.progress[key]
      const clean = {
        key,
        infoHash: String(entry.infoHash).slice(0, 64),
        fileIndex,
        name: String(entry.name || prev?.name || '').slice(0, 300),
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
    removeProgress (userId, key) {
      const b = bucket(userId)
      if (!(key in b.progress)) return false
      delete b.progress[key]
      persist()
      return true
    },

    // --- Ajustes ---------------------------------------------------------
    getSettings (userId) {
      return { ...bucket(userId).settings }
    },
    // Fusión superficial; valores escalares cortos para acotar el tamaño.
    setSettings (userId, patch) {
      if (!patch || typeof patch !== 'object' || Array.isArray(patch)) return null
      const b = bucket(userId)
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

    // Vuelca a disco de forma síncrona (tests y apagado limpio)
    flush () { writeNow() }
  }
}
