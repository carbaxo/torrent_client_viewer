// ===================== Config / API =====================
// API_BASE permite alojar el frontend en GitHub Pages apuntando a un backend
// Node en otro dominio. Se define en public/config.js (window.TCV_API_BASE)
// o en localStorage; por defecto, mismo origen ('').
const API_BASE = (window.TCV_API_BASE || localStorage.getItem('tcv_api_base') || '').replace(/\/$/, '')
// Todas las llamadas llevan el perfil activo en la cabecera x-profile
const api = (path, opts = {}) => fetch(API_BASE + path, {
  credentials: 'include',
  ...opts,
  headers: {
    ...(ACTIVE_PROFILE ? { 'x-profile': ACTIVE_PROFILE.id } : {}),
    ...(opts.headers || {})
  }
})

let CONFIG = { ffmpeg: false, search: false, catalogs: false, allowRegistration: true }
let CURRENT_USER = null
let LAST_RESULTS = []
let QUALITY_FILTER = 'all'
let CATALOG_TYPE = 'movie'
let pollTimer = null

// Estado por usuario sincronizado con el servidor (favoritos, progreso, ajustes)
let MY = { favorites: [], progress: [], settings: {} }
let FAV_IDS = new Set()
let LAST_CATALOGS = []
let LAST_TORRENTS = []
let PROGRESS_MAP = {}

// Perfiles de la cuenta y contexto de navegación
let PROFILES = []
let ACTIVE_PROFILE = null
let RD = { configured: false }
let SEARCH_CONTEXT = null // { wid: 'movie:123', title } del póster clicado
const KIDS = () => !!(ACTIVE_PROFILE && ACTIVE_PROFILE.kids)
// Títulos completamente vistos (wid = 'movie:123' | 'series:456')
const watchedWids = () => new Set(MY.progress.filter((p) => p.watched && p.titleId).map((p) => p.titleId))

// ===================== Firebase (Google + Firestore) =====================
// El SDK se sirve autoalojado desde /vendor. Si no hay configuración
// (window.TCV_FIREBASE) la app funciona igual con cuentas locales.
let FB = null // { auth, db }

function initFirebase () {
  if (!window.TCV_FIREBASE || typeof firebase === 'undefined') return
  try {
    firebase.initializeApp(window.TCV_FIREBASE)
    FB = { auth: firebase.auth(), db: firebase.firestore() }
    $('login-alt').classList.remove('hidden')
  } catch (err) {
    console.error('[firebase] init:', err)
  }
}

// Espera a que Firebase restaure (o no) la sesión guardada
function fbUserReady () {
  return new Promise((resolve) => {
    if (!FB) return resolve(null)
    const off = FB.auth.onAuthStateChanged((u) => { off(); resolve(u) })
  })
}

// --- Sincronización del estado con Firestore ---
// Documento por usuario: { profiles: [...], states: { [profileId]: {favorites, progress, settings} } }
let cloudRef = null
let cloudSaveTimer = null
let CLOUD_DOC = null

// Lee el documento del usuario (perfiles + estados de todos los perfiles)
async function connectCloudDoc () {
  cloudRef = null
  CLOUD_DOC = null
  if (!FB) return
  const fbUser = await fbUserReady()
  if (!fbUser) return // sesión local sin Google: solo almacenamiento local
  cloudRef = FB.db.collection('users').doc(fbUser.uid)
  try {
    const snap = await cloudRef.get()
    if (snap.exists) {
      CLOUD_DOC = snap.data() || {}
      // Migración del formato antiguo (estado sin perfiles en la raíz)
      if (Array.isArray(CLOUD_DOC.favorites) || CLOUD_DOC.progress || (CLOUD_DOC.settings && !CLOUD_DOC.states)) {
        CLOUD_DOC = {
          profiles: [],
          states: {
            default: {
              favorites: CLOUD_DOC.favorites || [],
              progress: CLOUD_DOC.progress || [],
              settings: CLOUD_DOC.settings || {}
            }
          }
        }
      }
      if (Array.isArray(CLOUD_DOC.profiles) && CLOUD_DOC.profiles.length) {
        PROFILES = CLOUD_DOC.profiles
      }
    }
  } catch (err) {
    console.error('[cloud] lectura:', err)
  }
}

// Al elegir perfil: si la nube tiene estado para ese perfil, gana la nube
function adoptCloudState () {
  if (!cloudRef || !ACTIVE_PROFILE) return
  const s = CLOUD_DOC && CLOUD_DOC.states && CLOUD_DOC.states[ACTIVE_PROFILE.id]
  if (s) {
    MY = {
      favorites: Array.isArray(s.favorites) ? s.favorites : [],
      progress: Array.isArray(s.progress) ? s.progress : [],
      settings: s.settings && typeof s.settings === 'object' ? s.settings : {}
    }
    FAV_IDS = new Set(MY.favorites.map((f) => f.id))
  } else {
    cloudSave() // primer dispositivo con este perfil: sube el estado local
  }
}

// Guardado debounced del documento completo (perfiles + estado del perfil activo)
function cloudSave () {
  if (!cloudRef) return
  clearTimeout(cloudSaveTimer)
  cloudSaveTimer = setTimeout(() => {
    CLOUD_DOC = CLOUD_DOC || {}
    CLOUD_DOC.profiles = PROFILES
    CLOUD_DOC.states = { ...(CLOUD_DOC.states || {}) }
    if (ACTIVE_PROFILE) {
      CLOUD_DOC.states[ACTIVE_PROFILE.id] = { favorites: MY.favorites, progress: MY.progress, settings: MY.settings }
    }
    cloudRef.set({
      profiles: CLOUD_DOC.profiles,
      states: CLOUD_DOC.states,
      updatedAt: new Date().toISOString()
    }).catch((err) => console.error('[cloud] guardado:', err))
  }, 800)
}

// ===================== Utilidades =====================
function fmtBytes (bytes) {
  if (!bytes || bytes < 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0; let n = bytes
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++ }
  return `${n.toFixed(n >= 10 || i === 0 ? 0 : 1)} ${units[i]}`
}
const fmtSpeed = (bps) => fmtBytes(bps) + '/s'
function fmtEta (ms) {
  if (!isFinite(ms) || ms <= 0) return '—'
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60); const rem = s % 60
  if (m < 60) return `${m}m ${rem}s`
  const h = Math.floor(m / 60)
  return `${h}h ${m % 60}m`
}
// Segundos -> "1:23:45" o "23:45"
function fmtTime (s) {
  s = Math.max(0, Math.round(s || 0))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = String(s % 60).padStart(2, '0')
  return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}
function escapeHtml (str) {
  return String(str)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}
function $ (id) { return document.getElementById(id) }

// ===================== Navegación (sidebar) =====================
const VIEW_TITLES = { discover: 'Descubrir', search: 'Buscar', favorites: 'Favoritos', library: 'Mi biblioteca', add: 'Añadir', settings: 'Ajustes', detail: 'Detalle' }
let CURRENT_VIEW = 'discover'

function switchView (view) {
  CURRENT_VIEW = view
  document.querySelectorAll('.nav-item').forEach((b) => b.classList.toggle('active', b.dataset.view === view))
  document.querySelectorAll('.view').forEach((v) => v.classList.toggle('hidden', v.id !== 'view-' + view))
  $('view-title').textContent = VIEW_TITLES[view] || ''
  if (view === 'favorites') renderFavoritesView()
  window.scrollTo({ top: 0 })
}
document.querySelectorAll('.nav-item').forEach((b) => b.addEventListener('click', () => switchView(b.dataset.view)))

// ===================== Toasts =====================
function toast (msg, isError = false) {
  const el = document.createElement('div')
  el.className = 'toast' + (isError ? ' error' : '')
  el.textContent = msg
  $('toasts').appendChild(el)
  requestAnimationFrame(() => el.classList.add('show'))
  setTimeout(() => {
    el.classList.remove('show')
    setTimeout(() => el.remove(), 300)
  }, 3200)
}

// ===================== Autenticación =====================
const loginView = $('login-view')
const appView = $('app-view')

async function checkSession () {
  try {
    const res = await api('/api/auth/me')
    if (res.ok) {
      const { user } = await res.json()
      onLoggedIn(user)
      return
    }
    // Un backend real responde 401 aquí; un 404 significa que detrás de este
    // origen no hay backend (p.ej. frontend en GitHub Pages sin configurar).
    if (res.status === 404) backendMissing()
  } catch {
    backendMissing()
  }
  showLogin()
}

function backendMissing () {
  const st = $('login-status')
  st.classList.add('error')
  st.textContent = 'No hay conexión con el backend. Despliega el servidor Node y pon su URL en config.js (window.TCV_API_BASE). Ver SETUP.md.'
}

function showLogin () {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
  appView.classList.add('hidden')
  loginView.classList.remove('hidden')
}

async function onLoggedIn (user) {
  CURRENT_USER = user
  loginView.classList.add('hidden')
  $('user-name').textContent = user.username
  $('user-avatar').textContent = (user.username || '?').charAt(0).toUpperCase()
  await loadConfig()
  // Perfiles: primero la nube (si hay sesión Google), después el servidor local
  await connectCloudDoc()
  if (!PROFILES.length) await loadProfilesLocal()
  syncProfilesToLocal()
  const savedId = localStorage.getItem('tcv_profile_' + user.id)
  const saved = PROFILES.find((p) => p.id === savedId)
  if (saved) selectProfile(saved)
  else if (PROFILES.length === 1) selectProfile(PROFILES[0])
  else showProfilePicker()
}

// ===================== Perfiles =====================
async function loadProfilesLocal () {
  try {
    const res = await api('/api/me/profiles')
    if (res.ok) PROFILES = (await res.json()).profiles || []
  } catch {}
}

// Crea en el servidor local los perfiles que solo existen en la nube
// (conservando su id) para que el estado local use los mismos buckets.
function syncProfilesToLocal () {
  api('/api/me/profiles').then((r) => r.ok ? r.json() : { profiles: [] }).then(({ profiles }) => {
    const localIds = new Set((profiles || []).map((p) => p.id))
    for (const p of PROFILES) {
      if (!localIds.has(p.id)) {
        api('/api/me/profiles', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ id: p.id, name: p.name, kids: p.kids })
        }).catch(() => {})
      }
    }
  }).catch(() => {})
}

async function selectProfile (profile) {
  ACTIVE_PROFILE = profile
  localStorage.setItem('tcv_profile_' + CURRENT_USER.id, profile.id)
  hideProfilePicker()
  appView.classList.remove('hidden')
  $('user-name').textContent = `${CURRENT_USER.username} · ${profile.name}`
  $('user-avatar').textContent = profile.kids ? '🧒' : (profile.name || '?').charAt(0).toUpperCase()
  applyKidsMode()
  await loadMyState()
  adoptCloudState()
  applySettings()
  renderMyList()
  renderFavoritesView()
  switchView(CONFIG.catalogs ? 'discover' : (KIDS() ? 'library' : 'search'))
  render()
  if (!pollTimer) pollTimer = setInterval(render, 1000)
  if (CONFIG.catalogs) { loadCatalogs(); loadRecommendations() }
  if (!KIDS()) { loadRdStatus(); loadServerDirs() }
  renderProfilesSettings()
}

function applyKidsMode () {
  document.body.classList.toggle('kids-mode', KIDS())
}

function showProfilePicker () {
  renderProfileCards()
  $('profile-overlay').classList.remove('hidden')
}
function hideProfilePicker () {
  $('profile-overlay').classList.add('hidden')
}

function renderProfileCards () {
  const el = $('profile-cards')
  el.innerHTML = PROFILES.map((p) => `
    <button class="profile-card" data-pid="${escapeHtml(p.id)}">
      <span class="profile-avatar ${p.kids ? 'kids' : ''}">${p.kids ? '🧒' : escapeHtml((p.name || '?').charAt(0).toUpperCase())}</span>
      <span class="profile-name">${escapeHtml(p.name)}</span>
      ${p.kids ? '<span class="profile-tag">infantil</span>' : ''}
    </button>`).join('') + (PROFILES.length < 5
    ? '<button class="profile-card add" id="profile-add-btn"><span class="profile-avatar plus">＋</span><span class="profile-name">Nuevo perfil</span></button>'
    : '')
  el.querySelectorAll('.profile-card[data-pid]').forEach((c) => c.addEventListener('click', () => {
    const p = PROFILES.find((x) => x.id === c.dataset.pid)
    if (p) selectProfile(p)
  }))
  const addBtn = $('profile-add-btn')
  if (addBtn) addBtn.addEventListener('click', () => $('profile-new').classList.toggle('hidden'))
}

$('profile-new').addEventListener('submit', async (e) => {
  e.preventDefault()
  const name = $('profile-new-name').value.trim()
  if (!name) return
  const kids = $('profile-new-kids').checked
  try {
    const res = await api('/api/me/profiles', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name, kids })
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || 'Error')
    PROFILES.push(data.profile)
    cloudSave()
    $('profile-new-name').value = ''
    $('profile-new-kids').checked = false
    $('profile-new').classList.add('hidden')
    renderProfileCards()
    renderProfilesSettings()
  } catch (err) { toast(err.message, true) }
})

// El avatar abre el selector de perfiles
$('user-avatar').addEventListener('click', () => { if (CURRENT_USER) showProfilePicker() })

// ===================== Estado por usuario =====================
async function loadMyState () {
  try {
    const res = await api('/api/me/state')
    if (res.ok) {
      const data = await res.json()
      MY = { favorites: data.favorites || [], progress: data.progress || [], settings: data.settings || {} }
      FAV_IDS = new Set(MY.favorites.map((f) => f.id))
    }
  } catch {}
}

function applySettings () {
  const s = MY.settings || {}
  if (s.searchType === 'movie' || s.searchType === 'series') $('search-type').value = s.searchType
  if (['all', 'torrentio', 'peerflix'].includes(s.searchSource)) $('search-source').value = s.searchSource
  if (s.catalogType === 'movie' || s.catalogType === 'series') {
    CATALOG_TYPE = s.catalogType
    $('catalog-type').querySelectorAll('button').forEach((x) => x.classList.toggle('active', x.dataset.ctype === CATALOG_TYPE))
  }
  $('setting-lang').value = ['es-ES', 'en-US'].includes(s.language) ? s.language : 'es-ES'
  toggleSeasonFields()
}

function saveSettings (patch) {
  Object.assign(MY.settings, patch)
  api('/api/me/settings', {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(patch)
  }).catch(() => {})
  cloudSave()
}

async function loadConfig () {
  try { CONFIG = await (await api('/api/config')).json() } catch {}
  // Ajusta UI según capacidades
  if (!CONFIG.catalogs) {
    $('catalog-panel').classList.add('hidden')
    const navDiscover = document.querySelector('.nav-item[data-view="discover"]')
    if (navDiscover) navDiscover.classList.add('hidden')
  }
}

// Tabs login/register
let authMode = 'login'
$('tab-login').addEventListener('click', () => setAuthMode('login'))
$('tab-register').addEventListener('click', () => setAuthMode('register'))
function setAuthMode (mode) {
  authMode = mode
  $('tab-login').classList.toggle('active', mode === 'login')
  $('tab-register').classList.toggle('active', mode === 'register')
  $('login-submit').textContent = mode === 'login' ? 'Entrar' : 'Crear cuenta'
  $('login-status').textContent = ''
}

$('login-form').addEventListener('submit', async (e) => {
  e.preventDefault()
  const username = $('login-user').value.trim()
  const password = $('login-pass').value
  const statusEl = $('login-status')
  statusEl.classList.remove('error')
  statusEl.textContent = authMode === 'login' ? 'Entrando…' : 'Creando cuenta…'
  try {
    const res = await api('/api/auth/' + authMode, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || 'Error')
    statusEl.textContent = ''
    $('login-pass').value = ''
    onLoggedIn(data.user)
  } catch (err) {
    statusEl.classList.add('error')
    statusEl.textContent = err.message
  }
})

// Login con Google (Firebase) -> ID token -> sesión propia del backend
$('google-btn').addEventListener('click', async () => {
  if (!FB) return
  const statusEl = $('login-status')
  statusEl.classList.remove('error')
  statusEl.textContent = 'Abriendo Google…'
  try {
    const cred = await FB.auth.signInWithPopup(new firebase.auth.GoogleAuthProvider())
    const idToken = await cred.user.getIdToken()
    const res = await api('/api/auth/firebase', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ idToken })
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || 'Error')
    statusEl.textContent = ''
    onLoggedIn(data.user)
  } catch (err) {
    if (err && (err.code === 'auth/popup-closed-by-user' || err.code === 'auth/cancelled-popup-request')) {
      statusEl.textContent = ''
      return
    }
    statusEl.classList.add('error')
    statusEl.textContent = err.message || 'No se pudo iniciar sesión con Google.'
  }
})

$('logout-btn').addEventListener('click', async () => {
  try { await api('/api/auth/logout', { method: 'POST' }) } catch {}
  if (FB) { try { await FB.auth.signOut() } catch {} }
  cloudRef = null
  CLOUD_DOC = null
  CURRENT_USER = null
  PROFILES = []
  ACTIVE_PROFILE = null
  MY = { favorites: [], progress: [], settings: {} }
  FAV_IDS = new Set()
  LAST_CATALOGS = []
  PROGRESS_MAP = {}
  lastContinueHtml = ''
  document.body.classList.remove('kids-mode')
  $('search-results').innerHTML = ''
  $('catalogs').innerHTML = ''
  $('my-list').innerHTML = ''
  $('recs-row').innerHTML = ''
  $('favorites-grid').innerHTML = ''
  $('continue-row').innerHTML = ''
  hideProfilePicker()
  showLogin()
})

// ===================== Catálogos (TMDB) =====================
const catalogsEl = $('catalogs')
const catalogStatus = $('catalog-status')
const catalogLoader = $('catalog-loader')

$('catalog-type').querySelectorAll('button').forEach((b) => {
  b.addEventListener('click', () => {
    CATALOG_TYPE = b.dataset.ctype
    $('catalog-type').querySelectorAll('button').forEach((x) => x.classList.toggle('active', x === b))
    saveSettings({ catalogType: CATALOG_TYPE })
    loadCatalogs()
  })
})
$('catalog-reload').addEventListener('click', () => loadCatalogs())

async function loadCatalogs () {
  if (!CONFIG.catalogs) return
  catalogStatus.textContent = ''
  catalogStatus.classList.remove('error')
  catalogsEl.innerHTML = ''
  catalogLoader.classList.remove('hidden')
  try {
    const params = new URLSearchParams({ type: CATALOG_TYPE })
    if (MY.settings.language) params.set('lang', MY.settings.language)
    if (KIDS()) params.set('kids', '1')
    const res = await api('/api/catalogs?' + params.toString())
    const data = await res.json()
    if (!res.ok || !data.success) throw new Error(data.error || 'No se pudieron cargar los catálogos.')
    renderCatalogs(data.catalogs)
    if (data.warnings) catalogStatus.textContent = 'Aviso: ' + data.warnings.join(' · ')
  } catch (err) {
    catalogStatus.classList.add('error')
    catalogStatus.textContent = err.message
  } finally {
    catalogLoader.classList.add('hidden')
  }
}

function renderCatalogs (catalogs) {
  LAST_CATALOGS = catalogs
  catalogsEl.innerHTML = catalogs.map((cat) => `
    <div class="catalog-row">
      <div class="catalog-head"><span class="dot" style="background:${cat.color};color:${cat.color}"></span>${escapeHtml(cat.name)}</div>
      <div class="poster-row">${cat.items.map(posterCardHtml).join('')}</div>
    </div>`).join('')
  wirePosterCards(catalogsEl)
}

// ===================== Pósters + favoritos =====================
const PLAY_ICON = '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M8 5v14l11-7z"/></svg>'
const HEART_ICON = '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/></svg>'

// Identificador estable de un título (para favoritos)
const favId = (it) => it.id || (it.tmdbId ? 'tmdb:' + it.tmdbId : `${it.type}:${it.title}:${it.year || ''}`)

// Identificador tipo:tmdbId para enlazar título <-> torrent <-> visto
const widOf = (it) => {
  const type = it.type === 'series' ? 'series' : 'movie'
  if (it.tmdbId) return `${type}:${it.tmdbId}`
  if (it.id && /^tmdb:\d+$/.test(it.id)) return `${type}:${it.id.slice(5)}`
  return ''
}

function posterCardHtml (it) {
  const id = favId(it)
  const isFav = FAV_IDS.has(id)
  const wid = widOf(it)
  const watched = wid && watchedWids().has(wid)
  return `
    <button class="poster-card" data-id="${escapeHtml(id)}" data-title="${escapeHtml(it.title)}"
      data-type="${it.type === 'series' ? 'series' : 'movie'}" data-year="${escapeHtml(it.year || '')}"
      data-poster="${escapeHtml(it.poster || '')}" data-rating="${it.rating ?? ''}" data-wid="${escapeHtml(wid)}">
      <div class="poster-img">
        ${it.poster ? `<img loading="lazy" src="${escapeHtml(it.poster)}" alt="${escapeHtml(it.title)}" />` : '<div class="poster-ph">🎬</div>'}
        <span class="poster-play">${PLAY_ICON}</span>
        <span class="poster-fav ${isFav ? 'active' : ''}" role="button" title="${isFav ? 'Quitar de favoritos' : 'Añadir a favoritos'}">${HEART_ICON}</span>
        ${watched ? '<span class="poster-watched" title="Ya lo has visto">✓ Visto</span>' : ''}
      </div>
      <div class="poster-meta">
        <span class="poster-title">${escapeHtml(it.title)}</span>
        <span class="poster-year">${escapeHtml(it.year || '')}${it.rating ? ' · ⭐ ' + it.rating : ''}</span>
      </div>
    </button>`
}

function wirePosterCards (root) {
  root.querySelectorAll('.poster-card').forEach((c) => {
    c.addEventListener('click', (e) => {
      if (e.target.closest('.poster-fav')) { toggleFavorite(c); return }
      openDetail({
        wid: c.dataset.wid || null,
        title: c.dataset.title,
        type: c.dataset.type,
        year: c.dataset.year,
        poster: c.dataset.poster,
        rating: c.dataset.rating
      })
    })
  })
}

// ===================== Ficha de detalle (estilo Stremio) =====================
let PREV_VIEW = 'discover'
let DETAIL = null // { tmdbId, type, title, seasons?, season? }

const fmtRuntime = (min) => min >= 60 ? `${Math.floor(min / 60)}h ${min % 60}min` : `${min}min`

async function openDetail (d) {
  const m = /^(movie|series):(\d+)$/.exec(d.wid || '')
  if (!m) {
    // Sin id de TMDB (p.ej. búsqueda manual): cae a la búsqueda clásica
    if (KIDS()) { toast('En el perfil infantil no se puede buscar ni descargar.'); return }
    SEARCH_CONTEXT = { wid: null, title: d.title }
    $('search-input').value = d.title
    $('search-type').value = d.type
    toggleSeasonFields()
    switchView('search')
    $('search-form').dispatchEvent(new Event('submit', { cancelable: true }))
    return
  }
  const type = m[1]
  const tmdbId = Number(m[2])
  if (CURRENT_VIEW !== 'detail') PREV_VIEW = CURRENT_VIEW
  SEARCH_CONTEXT = { wid: d.wid, title: d.title }
  DETAIL = { type, tmdbId, title: d.title }
  switchView('detail')
  $('view-title').textContent = d.title
  $('detail-content').innerHTML = '<div class="loader"><span></span><span></span><span></span></div>'
  try {
    const params = MY.settings.language ? '?lang=' + encodeURIComponent(MY.settings.language) : ''
    const res = await api(`/api/title/${type}/${tmdbId}` + params)
    const data = await res.json()
    if (!res.ok || !data.success) throw new Error(data.error || 'No se pudo cargar la ficha.')
    DETAIL = { ...DETAIL, ...data, title: data.title || d.title }
    SEARCH_CONTEXT = { wid: d.wid, title: DETAIL.title }
    renderDetail(DETAIL)
    if (type === 'movie' && !KIDS()) loadDetailSources({})
    else if (type === 'series' && DETAIL.seasons && DETAIL.seasons.length) selectSeason(DETAIL.seasons[0].season)
  } catch (err) {
    $('detail-content').innerHTML = `<p class="empty">${escapeHtml(err.message)}</p>`
  }
}

function renderDetail (d) {
  const favIdVal = 'tmdb:' + d.tmdbId
  const isFav = FAV_IDS.has(favIdVal)
  const watched = watchedWids().has(`${d.type}:${d.tmdbId}`)
  const meta = [
    d.type === 'series' ? 'Serie' : 'Película',
    d.year,
    d.rating ? '⭐ ' + d.rating : '',
    d.runtime ? fmtRuntime(d.runtime) : '',
    ...(d.genres || [])
  ].filter(Boolean)

  $('detail-content').innerHTML = `
    <div class="detail-hero" ${d.backdrop ? `style="background-image:linear-gradient(to top, var(--bg) 2%, rgba(12,11,17,.55) 55%, rgba(12,11,17,.25) 100%), url('${escapeHtml(d.backdrop)}')"` : ''}>
      <button id="detail-back" class="btn-ghost detail-back">← Volver</button>
      <div class="detail-head">
        ${d.poster ? `<img class="detail-poster" src="${escapeHtml(d.poster)}" alt="" />` : ''}
        <div class="detail-info">
          <h2 class="detail-title">${escapeHtml(d.title)}${watched ? ' <span class="poster-watched static">✓ Visto</span>' : ''}</h2>
          <div class="detail-meta">${meta.map((x) => `<span>${escapeHtml(String(x))}</span>`).join('')}</div>
          ${d.tagline ? `<p class="detail-tagline">${escapeHtml(d.tagline)}</p>` : ''}
          <p class="detail-overview">${escapeHtml(d.overview || 'Sin descripción disponible.')}</p>
          <div class="detail-actions">
            <button id="detail-fav" class="btn-ghost">${isFav ? '❤ En favoritos' : '♡ Añadir a favoritos'}</button>
          </div>
        </div>
      </div>
    </div>
    ${d.type === 'series' && d.seasons && d.seasons.length ? `
      <div class="detail-block">
        <h3 class="row-title">Temporadas</h3>
        <div id="season-chips" class="quality-filters">
          ${d.seasons.map((s) => `<button class="chip" data-season="${s.season}" title="${escapeHtml(s.name)} · ${s.episodes} ep.">T${s.season}</button>`).join('')}
        </div>
        <div id="episodes-list" class="episodes-list"></div>
      </div>` : ''}
    ${KIDS() ? '' : `
      <div class="detail-block">
        <h3 class="row-title" id="sources-title">Fuentes</h3>
        <p id="detail-sources-status" class="status"></p>
        <div id="detail-sources-loader" class="loader hidden"><span></span><span></span><span></span></div>
        <div id="detail-sources" class="results-grid"></div>
      </div>`}
  `

  $('detail-back').addEventListener('click', () => switchView(PREV_VIEW || 'discover'))
  $('detail-fav').addEventListener('click', async () => {
    await toggleFavorite({
      dataset: {
        id: favIdVal,
        title: d.title,
        year: d.year || '',
        type: d.type,
        poster: d.poster || '',
        rating: d.rating != null ? String(d.rating) : ''
      }
    })
    $('detail-fav').textContent = FAV_IDS.has(favIdVal) ? '❤ En favoritos' : '♡ Añadir a favoritos'
  })
  const chips = $('season-chips')
  if (chips) {
    chips.querySelectorAll('.chip').forEach((c) =>
      c.addEventListener('click', () => selectSeason(Number(c.dataset.season))))
  }
}

async function selectSeason (n) {
  if (!DETAIL) return
  DETAIL.season = n
  const chips = $('season-chips')
  if (chips) chips.querySelectorAll('.chip').forEach((c) => c.classList.toggle('active', Number(c.dataset.season) === n))
  const list = $('episodes-list')
  list.innerHTML = '<div class="loader"><span></span><span></span><span></span></div>'
  try {
    const params = MY.settings.language ? '?lang=' + encodeURIComponent(MY.settings.language) : ''
    const res = await api(`/api/title/series/${DETAIL.tmdbId}/season/${n}` + params)
    const data = await res.json()
    if (!res.ok || !data.success) throw new Error(data.error || 'No se pudieron cargar los episodios.')
    list.innerHTML = data.episodes.map((e) => `
      <button class="episode-row" data-ep="${e.episode}">
        ${e.still ? `<img class="episode-still" loading="lazy" src="${escapeHtml(e.still)}" alt="" />` : '<div class="episode-still ph">🎬</div>'}
        <div class="episode-info">
          <span class="episode-name">${e.episode}. ${escapeHtml(e.name)}${e.rating ? ` <span class="episode-rating">⭐ ${e.rating}</span>` : ''}</span>
          <span class="episode-overview">${escapeHtml(e.overview)}</span>
        </div>
        ${KIDS() ? '' : '<span class="episode-cta">Fuentes ›</span>'}
      </button>`).join('') || '<p class="empty">Esta temporada no tiene episodios listados.</p>'
    if (!KIDS()) {
      list.querySelectorAll('.episode-row').forEach((r) => r.addEventListener('click', () => {
        list.querySelectorAll('.episode-row').forEach((x) => x.classList.toggle('active', x === r))
        loadDetailSources({ season: n, episode: Number(r.dataset.ep) })
      }))
    }
  } catch (err) {
    list.innerHTML = `<p class="empty">${escapeHtml(err.message)}</p>`
  }
}

// Busca torrents para la película o para un episodio concreto y los pinta
// en la sección "Fuentes" de la ficha (botones Ver / Descargar / RD).
async function loadDetailSources ({ season, episode } = {}) {
  const el = $('detail-sources')
  if (!el || !DETAIL) return
  const st = $('detail-sources-status')
  const ld = $('detail-sources-loader')
  $('sources-title').textContent = episode ? `Fuentes · T${season} E${episode}` : 'Fuentes'
  st.textContent = ''
  st.classList.remove('error')
  el.innerHTML = ''
  ld.classList.remove('hidden')
  // Se busca con el título original (los torrents se publican con él)
  const params = new URLSearchParams({ query: DETAIL.originalTitle || DETAIL.title, type: DETAIL.type, source: 'all' })
  if (season) params.set('season', season)
  if (episode) params.set('episode', episode)
  try {
    const res = await api('/api/search?' + params.toString())
    const data = await res.json()
    if (!res.ok || !data.success) throw new Error(data.error || 'No se pudieron cargar las fuentes.')
    if (!data.streams.length) { st.textContent = 'Sin fuentes disponibles.'; return }
    el.innerHTML = data.streams.map(resultCardHtml).join('')
    wireResultActions(el)
    let msg = `${data.streams.length} fuentes`
    if (data.warnings) msg += ' · aviso: ' + data.warnings.join(', ')
    st.textContent = msg
    if (episode) $('sources-title').scrollIntoView({ behavior: 'smooth', block: 'start' })
  } catch (err) {
    st.classList.add('error')
    st.textContent = err.message
  } finally {
    ld.classList.add('hidden')
  }
}

// ===================== Vista Favoritos =====================
function renderFavoritesView () {
  const el = $('favorites-grid')
  if (!MY.favorites.length) {
    el.innerHTML = '<p class="empty">Aún no tienes favoritos.<br>Marca el ❤ de cualquier póster en <b>Descubrir</b> para guardarlo aquí.</p>'
    return
  }
  el.innerHTML = MY.favorites.map(posterCardHtml).join('')
  wirePosterCards(el)
}

// ===================== Recomendaciones =====================
async function loadRecommendations () {
  const el = $('recs-row')
  if (!CONFIG.catalogs || KIDS()) { el.innerHTML = ''; return }
  // Semillas: favoritos con tmdbId + títulos vistos, los más recientes primero
  const seeds = []
  for (const f of MY.favorites) { const w = widOf(f); if (w) seeds.push(w) }
  for (const w of watchedWids()) seeds.push(w)
  const unique = [...new Set(seeds)].slice(0, 6)
  if (!unique.length) { el.innerHTML = ''; return }
  try {
    const params = new URLSearchParams({ ids: unique.join(',') })
    if (MY.settings.language) params.set('lang', MY.settings.language)
    const res = await api('/api/recommendations?' + params.toString())
    const data = await res.json()
    if (!res.ok || !data.success || !data.items.length) { el.innerHTML = ''; return }
    el.innerHTML = `
      <div class="catalog-row">
        <div class="catalog-head"><span class="dot" style="background:#a78bfa;color:#a78bfa"></span>Recomendado para ti</div>
        <div class="poster-row">${data.items.map(posterCardHtml).join('')}</div>
      </div>`
    wirePosterCards(el)
  } catch { el.innerHTML = '' }
}

async function toggleFavorite (card) {
  const id = card.dataset.id
  try {
    if (FAV_IDS.has(id)) {
      FAV_IDS.delete(id)
      MY.favorites = MY.favorites.filter((f) => f.id !== id)
      await api('/api/me/favorites/' + encodeURIComponent(id), { method: 'DELETE' })
    } else {
      const fav = {
        id,
        title: card.dataset.title,
        year: card.dataset.year,
        type: card.dataset.type,
        poster: card.dataset.poster || null,
        rating: card.dataset.rating ? Number(card.dataset.rating) : null
      }
      FAV_IDS.add(id)
      MY.favorites = [fav, ...MY.favorites.filter((f) => f.id !== id)]
      await api('/api/me/favorites', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(fav)
      })
    }
  } catch {}
  cloudSave()
  renderMyList()
  refreshFavHearts()
  if (CURRENT_VIEW === 'favorites') renderFavoritesView()
}

// Actualiza los corazones de todos los pósters visibles sin re-renderizar
function refreshFavHearts () {
  document.querySelectorAll('.poster-card').forEach((c) => {
    const isFav = FAV_IDS.has(c.dataset.id)
    const heart = c.querySelector('.poster-fav')
    if (heart) {
      heart.classList.toggle('active', isFav)
      heart.title = isFav ? 'Quitar de Mi lista' : 'Añadir a Mi lista'
    }
  })
}

function renderMyList () {
  const el = $('my-list')
  if (!MY.favorites.length) { el.innerHTML = ''; return }
  el.innerHTML = `
    <div class="catalog-row">
      <div class="catalog-head"><span class="dot" style="background:#f43f5e;color:#f43f5e"></span>Mi lista</div>
      <div class="poster-row">${MY.favorites.map(posterCardHtml).join('')}</div>
    </div>`
  wirePosterCards(el)
}

// ===================== Buscador =====================
const searchForm = $('search-form')
const searchInput = $('search-input')
const searchType = $('search-type')
const searchSource = $('search-source')
const searchBtn = $('search-btn')
const searchStatusEl = $('search-status')
const searchLoader = $('search-loader')
const searchResults = $('search-results')

function searchStatus (msg, isError = false) {
  searchStatusEl.textContent = msg
  searchStatusEl.classList.toggle('error', isError)
}

searchType.addEventListener('change', () => {
  toggleSeasonFields()
  saveSettings({ searchType: searchType.value })
})
searchSource.addEventListener('change', () => saveSettings({ searchSource: searchSource.value }))
function toggleSeasonFields () {
  $('se-fields').classList.toggle('hidden', searchType.value !== 'series')
}

searchForm.addEventListener('submit', async (e) => {
  e.preventDefault()
  const query = searchInput.value.replace(/<[^>]*>/g, '').trim()
  if (!query) { searchStatus('Escribe algo para buscar.', true); return }
  // El contexto de título (para marcar "visto") solo vale si la búsqueda no cambió
  if (SEARCH_CONTEXT && SEARCH_CONTEXT.title !== query) SEARCH_CONTEXT = null

  searchStatus('')
  searchResults.innerHTML = ''
  $('quality-filters').innerHTML = ''
  searchLoader.classList.remove('hidden')
  searchBtn.disabled = true

  const params = new URLSearchParams({ query, type: searchType.value, source: searchSource.value })
  if (searchType.value === 'series') {
    if ($('search-season').value) params.set('season', $('search-season').value)
    if ($('search-episode').value) params.set('episode', $('search-episode').value)
  }

  try {
    const res = await api('/api/search?' + params.toString())
    const data = await res.json()
    if (!res.ok || !data.success) throw new Error(data.error || 'No se pudo completar la búsqueda.')
    LAST_RESULTS = data.streams
    QUALITY_FILTER = 'all'
    renderQualityFilters(data.streams)
    renderResults(data.streams)
    let msg = data.streams.length
      ? `${data.streams.length} resultados para "${data.title || query}"${data.imdbId ? ' (' + data.imdbId + ')' : ''}${data.cached ? ' · caché' : ''}`
      : 'Sin resultados.'
    if (data.warnings) msg += ' · aviso: ' + data.warnings.join(', ')
    searchStatus(msg)
  } catch (err) {
    searchStatus(err.message, true)
  } finally {
    searchLoader.classList.add('hidden')
    searchBtn.disabled = false
  }
})

function renderQualityFilters (streams) {
  const present = ['4K', '1080p', '720p', '480p', 'SD', 'Unknown'].filter((q) => streams.some((s) => s.quality === q))
  if (present.length <= 1) { $('quality-filters').innerHTML = ''; return }
  const chips = ['all', ...present]
  $('quality-filters').innerHTML = chips.map((q) =>
    `<button class="chip ${q === QUALITY_FILTER ? 'active' : ''}" data-q="${q}">${q === 'all' ? 'Todas' : q}</button>`).join('')
  $('quality-filters').querySelectorAll('.chip').forEach((c) =>
    c.addEventListener('click', () => {
      QUALITY_FILTER = c.dataset.q
      $('quality-filters').querySelectorAll('.chip').forEach((x) => x.classList.toggle('active', x === c))
      renderResults(LAST_RESULTS)
    }))
}

// Tarjeta de una fuente (compartida entre Buscar y la ficha de detalle)
function resultCardHtml (s, i) {
  const qClass = 'q-' + s.quality.toLowerCase().replace(/[^a-z0-9]/g, '')
  const srcClass = s.source === 'peerflix' ? 'src-peerflix' : 'src-torrentio'
  return `
    <div class="result-card" style="animation-delay:${Math.min(i * 30, 300)}ms">
      <div class="result-top">
        <span class="badge source ${srcClass}">${escapeHtml(s.name || s.source)}</span>
        <span class="badge quality ${qClass}">${escapeHtml(s.quality)}</span>
      </div>
      <div class="result-name">${escapeHtml(s.filename)}</div>
      <div class="result-badges">
        <span class="badge size">${escapeHtml(s.size)}</span>
        <span class="badge seeders">▲ ${s.seeders} seeders</span>
      </div>
      <div class="result-actions">
        <button class="btn-watch" data-magnet="${escapeHtml(s.url)}">▶ Ver</button>
        <button class="btn-dl" data-magnet="${escapeHtml(s.url)}">⬇ Descargar</button>
        ${RD.configured ? `<button class="btn-rd" data-magnet="${escapeHtml(s.url)}">⚡ RD</button>` : ''}
        <button class="btn-copy" data-magnet="${escapeHtml(s.url)}" title="Copiar magnet">📋</button>
      </div>
    </div>`
}

function wireResultActions (root) {
  root.querySelectorAll('.btn-watch').forEach((b) =>
    b.addEventListener('click', () => watchFromSearch(b.dataset.magnet, b)))
  root.querySelectorAll('.btn-dl').forEach((b) =>
    b.addEventListener('click', () => addMagnetFromSearch(b.dataset.magnet, b)))
  root.querySelectorAll('.btn-rd').forEach((b) =>
    b.addEventListener('click', () => rdWatch(b.dataset.magnet, b)))
  root.querySelectorAll('.btn-copy').forEach((b) =>
    b.addEventListener('click', () => copyMagnet(b.dataset.magnet, b)))
}

function renderResults (streams) {
  const filtered = QUALITY_FILTER === 'all' ? streams : streams.filter((s) => s.quality === QUALITY_FILTER)
  if (!filtered.length) { searchResults.innerHTML = '<p class="empty">No hay resultados con ese filtro.</p>'; return }
  searchResults.innerHTML = filtered.map(resultCardHtml).join('')
  wireResultActions(searchResults)
}

// "Ver": descarga al buffer temporal y abre el reproductor en cuanto hay
// metadatos. El torrent se borra solo cuando lo termines de ver.
async function watchFromSearch (magnet, btn) {
  btn.disabled = true
  const old = btn.textContent
  btn.textContent = 'Preparando…'
  try {
    const res = await api('/api/torrents', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ magnet, mode: 'buffer', titleRef: SEARCH_CONTEXT ? SEARCH_CONTEXT.wid : null })
    })
    const t = await res.json()
    if (!res.ok) throw new Error(t.error || 'Error')
    const file = await waitForVideoFile(t.infoHash, 30000)
    btn.disabled = false
    btn.textContent = old
    if (!file) {
      toast('Aún obteniendo metadatos: en cuanto esté, podrás verlo desde Biblioteca.')
      return
    }
    openPlayer(t.infoHash, file, false, null, t.titleRef || (SEARCH_CONTEXT && SEARCH_CONTEXT.wid))
  } catch (err) {
    btn.disabled = false
    btn.textContent = old
    toast('No se pudo preparar la reproducción: ' + err.message, true)
  }
}

// Espera (con reintentos) a que el torrent tenga un archivo de vídeo
async function waitForVideoFile (infoHash, timeoutMs) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    try {
      const r = await api('/api/torrents/' + infoHash)
      if (r.ok) {
        const t = await r.json()
        const f = (t.files || []).find((x) => x.isVideo)
        if (f) return f
      }
    } catch {}
    await new Promise((r) => setTimeout(r, 1500))
  }
  return null
}

// "⚡ RD": Real-Debrid convierte el magnet en un stream HTTPS directo
async function rdWatch (magnet, btn) {
  btn.disabled = true
  const old = btn.textContent
  btn.textContent = '⚡ Preparando…'
  try {
    const res = await api('/api/rd/stream', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ magnet })
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || 'Error con Real-Debrid')
    if (!data.ready) {
      toast(`Real-Debrid lo está descargando en sus servidores (${Math.round(data.progress || 0)}%). Vuelve a pulsar ⚡ en un rato.`)
      return
    }
    openPlayerDirect(data.url, data.filename)
  } catch (err) {
    toast(err.message, true)
  } finally {
    btn.disabled = false
    btn.textContent = old
  }
}

async function copyMagnet (magnet, btn) {
  try {
    await navigator.clipboard.writeText(magnet)
    const old = btn.textContent; btn.textContent = '✓ Copiado'
    setTimeout(() => { btn.textContent = old }, 1500)
  } catch {
    // Fallback
    const ta = document.createElement('textarea'); ta.value = magnet
    document.body.appendChild(ta); ta.select()
    try { document.execCommand('copy') } catch {}
    document.body.removeChild(ta)
  }
}

async function addMagnetFromSearch (magnet, btn) {
  btn.disabled = true
  const old = btn.textContent
  btn.textContent = 'Añadiendo…'
  try {
    const res = await api('/api/torrents', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ magnet, mode: 'download', titleRef: SEARCH_CONTEXT ? SEARCH_CONTEXT.wid : null })
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || 'Error')
    btn.textContent = '✓ En biblioteca'
    toast(`Añadido a tu biblioteca: ${data.name || 'torrent'}`)
    render()
  } catch (err) {
    btn.disabled = false; btn.textContent = old
    toast('No se pudo añadir: ' + err.message, true)
  }
}

// ===================== Añadir manual =====================
const magnetForm = $('magnet-form')
const magnetInput = $('magnet-input')
const uploadForm = $('upload-form')
const torrentFile = $('torrent-file')
const fileNameLabel = $('file-name')
const addStatusEl = $('add-status')

function addStatus (msg, isError = false) {
  addStatusEl.textContent = msg
  addStatusEl.classList.toggle('error', isError)
}

magnetForm.addEventListener('submit', async (e) => {
  e.preventDefault()
  const magnet = magnetInput.value.trim()
  if (!magnet) return
  addStatus('Añadiendo magnet…')
  try {
    const res = await api('/api/torrents', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ magnet })
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || 'Error')
    magnetInput.value = ''
    addStatus(`Añadido: ${data.name || 'torrent'}`)
    render()
  } catch (err) { addStatus(err.message, true) }
})

torrentFile.addEventListener('change', () => {
  fileNameLabel.textContent = torrentFile.files[0] ? torrentFile.files[0].name : 'Elegir archivo .torrent…'
})

uploadForm.addEventListener('submit', async (e) => {
  e.preventDefault()
  const f = torrentFile.files[0]
  if (!f) { addStatus('Selecciona un archivo .torrent primero.', true); return }
  addStatus('Subiendo .torrent…')
  const fd = new FormData(); fd.append('torrent', f)
  try {
    const res = await api('/api/torrents/upload', { method: 'POST', body: fd })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || 'Error')
    torrentFile.value = ''; fileNameLabel.textContent = 'Elegir archivo .torrent…'
    addStatus(`Añadido: ${data.name || 'torrent'}`)
    render()
  } catch (err) { addStatus(err.message, true) }
})

// ===================== Acciones de torrents =====================
async function removeTorrent (infoHash) {
  const withFiles = confirm('¿Eliminar también los archivos del disco?\n\nAceptar = borrar archivos · Cancelar = solo quitar de mi biblioteca')
  try { await api(`/api/torrents/${infoHash}?files=${withFiles}`, { method: 'DELETE' }); render() } catch (err) { toast('No se pudo eliminar: ' + err.message, true) }
}
async function togglePause (infoHash, paused) {
  try { await api(`/api/torrents/${infoHash}/${paused ? 'resume' : 'pause'}`, { method: 'POST' }); render() } catch (err) { toast('No se pudo cambiar el estado: ' + err.message, true) }
}

// ===================== Reproductor + subtítulos =====================
const overlay = $('player-overlay')
const player = $('player')
const playerTitle = $('player-title')
const playerNote = $('player-note')
const playerSubs = $('player-subs')

let PLAYING = null // { infoHash, fileIndex, name } del vídeo en curso
let lastProgressSave = 0

async function openPlayer (torrentHash, file, transcode, resumeAt, titleRef) {
  playerTitle.textContent = file.name
  playerSubs.innerHTML = ''
  clearTracks() // limpiar tracks previos

  PLAYING = { infoHash: torrentHash, fileIndex: file.index, name: file.name, titleId: titleRef || null }
  lastProgressSave = Date.now()

  // Reanudar donde se quedó (solo en streaming directo; la transcodificación
  // en vivo no permite saltos). Ignora posiciones triviales o ya "vistas".
  if (resumeAt == null && !transcode) {
    const saved = MY.progress.find((p) => p.key === `${torrentHash}:${file.index}`)
    if (saved && !saved.watched && saved.position > 20 &&
        (!saved.duration || saved.position / saved.duration < 0.95)) {
      resumeAt = saved.position
    }
  }
  if (resumeAt != null && !transcode) {
    const target = resumeAt
    player.addEventListener('loadedmetadata', function seekOnce () {
      player.removeEventListener('loadedmetadata', seekOnce)
      try { player.currentTime = target } catch {}
    })
    toast(`Reanudando en ${fmtTime(resumeAt)}`)
  }

  player.src = API_BASE + (transcode ? file.transcodeUrl : file.streamUrl)
  overlay.classList.remove('hidden')

  // Subtítulos incluidos en el torrent (ficheros .srt/.vtt)
  const subs = file.subtitleFiles || []
  subs.forEach((s, i) => addTrack(`/subtitle/${torrentHash}/${s.index}`, s.name, i === 0))

  // Subtítulos embebidos (mkv) vía ffprobe/ffmpeg
  if (CONFIG.ffmpeg) {
    try {
      const info = await (await api(`/api/torrents/${torrentHash}/${file.index}/subinfo`)).json()
      ;(info.embedded || []).forEach((t, i) =>
        addTrack(`/subtitle-embedded/${torrentHash}/${file.index}/${t.index}`, `${t.title} (${t.lang})`, subs.length === 0 && i === 0))
    } catch {}
  }

  if (transcode) playerNote.textContent = 'Convirtiendo con ffmpeg (H.264/AAC). No se puede adelantar durante la conversión.'
  else if (!file.nativePlayable) playerNote.textContent = 'Formato no nativo: si no ves imagen, usa "⚙ Convertir". El archivo se descarga igualmente.'
  else playerNote.textContent = 'Reproduciendo mientras se descarga. Puedes adelantar.'

  player.play().catch(() => {})
}

function addTrack (path, label, isDefault) {
  const track = document.createElement('track')
  track.kind = 'subtitles'
  track.label = label
  track.src = API_BASE + path
  if (isDefault) track.default = true
  player.appendChild(track)
  if (isDefault) setTimeout(() => { try { if (player.textTracks[0]) player.textTracks[0].mode = 'showing' } catch {} }, 300)
}

function clearTracks () {
  Array.from(player.querySelectorAll('track')).forEach((t) => t.remove())
}

// Guarda la posición de reproducción en el servidor (throttled vía llamador)
function saveProgress () {
  if (!PLAYING || !CURRENT_USER) return
  const position = player.currentTime
  if (!position || position < 5) return
  const duration = isFinite(player.duration) ? player.duration : 0
  const body = { infoHash: PLAYING.infoHash, fileIndex: PLAYING.fileIndex, name: PLAYING.name, position, duration, titleId: PLAYING.titleId }
  api('/api/me/progress', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
  }).then((r) => (r.ok ? r.json() : null)).then((d) => {
    if (d && d.progress) {
      MY.progress = [d.progress, ...MY.progress.filter((p) => p.key !== d.progress.key)]
      cloudSave()
    }
  }).catch(() => {})
}

player.addEventListener('timeupdate', () => {
  if (!PLAYING || Date.now() - lastProgressSave < 10000) return
  lastProgressSave = Date.now()
  saveProgress()
})
player.addEventListener('pause', () => { if (PLAYING) saveProgress() })

// Reproducción de una URL directa (p.ej. streaming de Real-Debrid)
function openPlayerDirect (url, title) {
  playerTitle.textContent = title || 'Vídeo'
  playerSubs.innerHTML = ''
  clearTracks()
  PLAYING = null // sin seguimiento de progreso: no hay torrent local
  player.src = url
  overlay.classList.remove('hidden')
  playerNote.textContent = 'Streaming directo desde Real-Debrid.'
  player.play().catch(() => {})
}

function closePlayer () {
  saveProgress()
  PLAYING = null
  overlay.classList.add('hidden')
  player.pause()
  player.removeAttribute('src')
  clearTracks()
  player.load()
}
$('player-close').addEventListener('click', closePlayer)
overlay.addEventListener('click', (e) => { if (e.target === overlay) closePlayer() })
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !overlay.classList.contains('hidden')) closePlayer() })

// ===================== Render de biblioteca =====================
const listEl = $('torrent-list')
const globalStatsEl = $('global-stats')

function render () {
  if (!CURRENT_USER) return
  api('/api/torrents').then((r) => r.ok ? r.json() : []).then((torrents) => {
    if (!Array.isArray(torrents)) return
    LAST_TORRENTS = torrents
    PROGRESS_MAP = {}
    for (const p of MY.progress) PROGRESS_MAP[p.key] = p
    updateGlobalStats(torrents)
    renderContinueRow(torrents)
    if (!torrents.length) { listEl.innerHTML = '<p class="empty">Tu biblioteca está vacía.<br>Usa <b>Descubrir</b> o <b>Buscar</b> para encontrar contenido, o <b>Añadir</b> para un magnet/.torrent.</p>'; return }
    listEl.innerHTML = torrents.map(cardHtml).join('')
    listEl.querySelectorAll('[data-remove]').forEach((b) => b.addEventListener('click', () => removeTorrent(b.dataset.remove)))
    listEl.querySelectorAll('[data-pause]').forEach((b) => b.addEventListener('click', () => togglePause(b.dataset.pause, b.dataset.paused === 'true')))
    listEl.querySelectorAll('[data-play]').forEach((b) => b.addEventListener('click', () => {
      const t = torrents.find((x) => x.infoHash === b.dataset.play)
      const file = t && t.files[Number(b.dataset.fileindex)]
      if (file) openPlayer(t.infoHash, file, b.dataset.mode === 'transcode', null, t.titleRef)
    }))
  }).catch(() => {})
}

function updateGlobalStats (torrents) {
  const down = torrents.reduce((a, t) => a + t.downloadSpeed, 0)
  const up = torrents.reduce((a, t) => a + t.uploadSpeed, 0)
  const active = torrents.filter((t) => !t.done && !t.paused).length
  globalStatsEl.innerHTML = torrents.length
    ? `<span class="stat-chip">↓ <b>${fmtSpeed(down)}</b></span>
       <span class="stat-chip">↑ ${fmtSpeed(up)}</span>
       <span class="stat-chip">${active} activos · ${torrents.length} total</span>`
    : ''
  const badge = $('nav-lib-badge')
  badge.textContent = active
  badge.classList.toggle('hidden', active === 0)
}

// ---- Continuar viendo ----
let lastContinueHtml = ''

function renderContinueRow (torrents) {
  const el = $('continue-row')
  const items = MY.progress
    .filter((p) => !p.watched && p.position > 20 && (!p.duration || p.position / p.duration < 0.95))
    .slice(0, 12)
  let html = ''
  if (items.length) {
    html = '<h3 class="row-title">Continuar viendo</h3><div class="continue-list">' + items.map((p) => {
      const t = torrents.find((x) => x.infoHash === p.infoHash)
      const available = !!(t && t.files[p.fileIndex])
      const pct = p.duration ? Math.min(100, Math.round(p.position / p.duration * 100)) : 0
      return `
        <div class="continue-card${available ? '' : ' missing'}">
          <div class="continue-info">
            <span class="continue-name">${escapeHtml(p.name || 'Vídeo')}</span>
            <span class="continue-meta">${fmtTime(p.position)}${p.duration ? ` / ${fmtTime(p.duration)} · ${pct}%` : ''}${available ? '' : ' · ya no está en tu biblioteca'}</span>
            <div class="continue-bar"><div style="width:${pct}%"></div></div>
          </div>
          <div class="continue-actions">
            <button class="btn-play" data-resume="${escapeHtml(p.key)}" ${available ? '' : 'disabled'}>▶ Reanudar</button>
            <button class="btn-icon danger" data-forget="${escapeHtml(p.key)}" title="Quitar de continuar viendo">✕</button>
          </div>
        </div>`
    }).join('') + '</div>'
  }
  if (html !== lastContinueHtml) {
    lastContinueHtml = html
    el.innerHTML = html
    el.querySelectorAll('[data-resume]').forEach((b) => b.addEventListener('click', () => resumeFromProgress(b.dataset.resume)))
    el.querySelectorAll('[data-forget]').forEach((b) => b.addEventListener('click', () => forgetProgress(b.dataset.forget)))
  }
}

function resumeFromProgress (key) {
  const p = MY.progress.find((x) => x.key === key)
  const t = p && LAST_TORRENTS.find((x) => x.infoHash === p.infoHash)
  const file = t && t.files[p.fileIndex]
  if (!file) { toast('Ese vídeo ya no está en tu biblioteca.', true); return }
  openPlayer(t.infoHash, file, false, p.position, p.titleId || t.titleRef)
}

async function forgetProgress (key) {
  MY.progress = MY.progress.filter((p) => p.key !== key)
  lastContinueHtml = '' // fuerza re-render en el siguiente ciclo
  cloudSave()
  try { await api('/api/me/progress/' + encodeURIComponent(key), { method: 'DELETE' }) } catch {}
  render()
}

function cardHtml (t) {
  const pct = (t.progress * 100).toFixed(1)
  const filesHtml = t.files.map((f) => {
    const fpct = (f.progress * 100).toFixed(0)
    let buttons = ''
    if (f.isVideo) {
      buttons += `<button class="btn-play" data-play="${t.infoHash}" data-fileindex="${f.index}" data-mode="stream">▶ Ver</button>`
      if (!f.nativePlayable && CONFIG.ffmpeg) {
        buttons += `<button class="btn-play alt" data-play="${t.infoHash}" data-fileindex="${f.index}" data-mode="transcode">⚙ Convertir</button>`
      }
    }
    const prog = PROGRESS_MAP[`${t.infoHash}:${f.index}`]
    const subTag = f.isSubtitle ? '<span class="tag">CC</span>' : ''
    const watchedTag = prog && prog.watched ? '<span class="tag watched">✓ visto</span>' : ''
    return `<div class="file-row"><span class="fname">${escapeHtml(f.name)} ${subTag}${watchedTag}</span><span class="fmeta">${fmtBytes(f.length)} · ${fpct}%</span>${buttons}</div>`
  }).join('')

  return `
    <div class="torrent-card">
      <div class="torrent-head">
        <div class="torrent-name">${escapeHtml(t.name || 'Obteniendo metadatos…')}${t.mode === 'buffer' ? ' <span class="tag">⏳ temporal</span>' : ''}${t.paused ? ' <span class="tag">⏸ pausa</span>' : ''}</div>
        ${KIDS() ? '' : `<div class="torrent-actions">
          <button class="btn-icon" data-pause="${t.infoHash}" data-paused="${t.paused}" title="${t.paused ? 'Reanudar' : 'Pausar'}">${t.paused ? '▶' : '⏸'}</button>
          <button class="btn-icon danger" data-remove="${t.infoHash}" title="Eliminar">🗑</button>
        </div>`}
      </div>
      <div class="progress-outer"><div class="progress-inner" style="width:${pct}%"></div></div>
      <div class="stats">
        <span><b>${pct}%</b></span>
        <span>${fmtBytes(t.downloaded)} / ${fmtBytes(t.length)}</span>
        <span>↓ <b>${fmtSpeed(t.downloadSpeed)}</b></span>
        <span>↑ ${fmtSpeed(t.uploadSpeed)}</span>
        <span>${t.numPeers} peers</span>
        <span>${t.done ? '✅ completo' : (t.paused ? '⏸ pausado' : 'ETA: ' + fmtEta(t.timeRemaining))}</span>
      </div>
      ${t.files.length ? `<div class="files">${filesHtml}</div>` : ''}
    </div>`
}

// ===================== Ajustes =====================

// --- Perfiles (gestión) ---
function renderProfilesSettings () {
  const el = $('profiles-list')
  el.innerHTML = PROFILES.map((p) => `
    <div class="profile-row" data-pid="${escapeHtml(p.id)}">
      <span class="profile-avatar small ${p.kids ? 'kids' : ''}">${p.kids ? '🧒' : escapeHtml((p.name || '?').charAt(0).toUpperCase())}</span>
      <span class="profile-row-name">${escapeHtml(p.name)}${ACTIVE_PROFILE && ACTIVE_PROFILE.id === p.id ? ' <span class="tag">activo</span>' : ''}</span>
      <label class="check"><input type="checkbox" data-kids-toggle="${escapeHtml(p.id)}" ${p.kids ? 'checked' : ''}/> Infantil</label>
      <button class="btn-icon danger" data-del-profile="${escapeHtml(p.id)}" ${PROFILES.length <= 1 ? 'disabled' : ''} title="Eliminar perfil">✕</button>
    </div>`).join('')

  el.querySelectorAll('[data-kids-toggle]').forEach((c) => c.addEventListener('change', async () => {
    const pid = c.dataset.kidsToggle
    try {
      const res = await api('/api/me/profiles/' + encodeURIComponent(pid), {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ kids: c.checked })
      })
      const data = await res.json()
      if (!res.ok) throw new Error(data.error || 'Error')
      const p = PROFILES.find((x) => x.id === pid)
      if (p) p.kids = data.profile.kids
      if (ACTIVE_PROFILE && ACTIVE_PROFILE.id === pid) { ACTIVE_PROFILE.kids = data.profile.kids; applyKidsMode(); loadCatalogs() }
      cloudSave()
      toast(`Perfil "${data.profile.name}" ${data.profile.kids ? 'ahora es infantil' : 'ya no es infantil'}.`)
    } catch (err) { toast(err.message, true); c.checked = !c.checked }
  }))

  el.querySelectorAll('[data-del-profile]').forEach((b) => b.addEventListener('click', async () => {
    const pid = b.dataset.delProfile
    const p = PROFILES.find((x) => x.id === pid)
    if (!p || !confirm(`¿Eliminar el perfil "${p.name}" y todos sus favoritos e historial?`)) return
    try {
      const res = await api('/api/me/profiles/' + encodeURIComponent(pid), { method: 'DELETE' })
      const data = await res.json()
      if (!res.ok) throw new Error(data.error || 'Error')
      PROFILES = PROFILES.filter((x) => x.id !== pid)
      if (CLOUD_DOC && CLOUD_DOC.states) delete CLOUD_DOC.states[pid]
      cloudSave()
      renderProfilesSettings()
      if (ACTIVE_PROFILE && ACTIVE_PROFILE.id === pid) selectProfile(PROFILES[0])
    } catch (err) { toast(err.message, true) }
  }))
}

$('switch-profile-btn').addEventListener('click', showProfilePicker)

// --- Idioma de catálogos ---
$('setting-lang').addEventListener('change', () => {
  saveSettings({ language: $('setting-lang').value })
  loadCatalogs()
  loadRecommendations()
})

// --- Carpetas del servidor ---
async function loadServerDirs () {
  try {
    const res = await api('/api/settings/server')
    if (!res.ok) return
    const d = await res.json()
    $('setting-download-dir').value = d.downloadDir || ''
    $('setting-buffer-dir').value = d.bufferDir || ''
  } catch {}
}

$('save-dirs-btn').addEventListener('click', async () => {
  const statusEl = $('dirs-status')
  statusEl.classList.remove('error')
  statusEl.textContent = 'Guardando…'
  try {
    const res = await api('/api/settings/server', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        downloadDir: $('setting-download-dir').value.trim(),
        bufferDir: $('setting-buffer-dir').value.trim()
      })
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || 'Error')
    $('setting-download-dir').value = data.downloadDir
    $('setting-buffer-dir').value = data.bufferDir
    statusEl.textContent = 'Carpetas guardadas. Se aplican a los torrents nuevos.'
  } catch (err) {
    statusEl.classList.add('error')
    statusEl.textContent = err.message
  }
})

// --- Real-Debrid ---
async function loadRdStatus () {
  try {
    const res = await api('/api/rd/status')
    if (res.ok) RD = await res.json()
  } catch {}
  renderRdConfig()
}

function renderRdConfig () {
  const el = $('rd-config')
  if (RD.configured) {
    el.innerHTML = `
      <div class="settings-row">
        <span class="rd-badge">⚡ Conectado</span>
        <span class="mono">${escapeHtml(RD.tokenMask || '')}</span>
        <button id="rd-remove-btn" class="btn-ghost">Desconectar</button>
      </div>`
    $('rd-remove-btn').addEventListener('click', async () => {
      try {
        await api('/api/rd/token', { method: 'DELETE' })
        RD = { configured: false }
        renderRdConfig()
        toast('Token de Real-Debrid eliminado de tu cuenta.')
      } catch {}
    })
  } else {
    el.innerHTML = `
      <div class="settings-row">
        <input type="password" id="rd-token-input" placeholder="Pega aquí tu token de Real-Debrid" autocomplete="off" />
        <button id="rd-save-btn" class="btn-primary">Conectar</button>
      </div>`
    $('rd-save-btn').addEventListener('click', async () => {
      const statusEl = $('rd-status')
      statusEl.classList.remove('error')
      statusEl.textContent = 'Validando token con Real-Debrid…'
      try {
        const res = await api('/api/rd/token', {
          method: 'PUT', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ token: $('rd-token-input').value.trim() })
        })
        const data = await res.json()
        if (!res.ok) throw new Error(data.error || 'Error')
        RD = data
        renderRdConfig()
        statusEl.textContent = `Conectado como ${data.rdUser.username}${data.rdUser.premium ? ' (premium)' : ' (SIN premium: RD no funcionará)'}.`
      } catch (err) {
        statusEl.classList.add('error')
        statusEl.textContent = err.message
      }
    })
  }
}

// ===================== Init =====================
initFirebase()
toggleSeasonFields()
checkSession()
