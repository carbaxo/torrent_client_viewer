// ===================== Config / API =====================
// API_BASE permite alojar el frontend en GitHub Pages apuntando a un backend
// Node en otro dominio. Se define en public/config.js (window.TCV_API_BASE)
// o en localStorage; por defecto, mismo origen ('').
const API_BASE = (window.TCV_API_BASE || localStorage.getItem('tcv_api_base') || '').replace(/\/$/, '')
const api = (path, opts = {}) => fetch(API_BASE + path, { credentials: 'include', ...opts })

let CONFIG = { ffmpeg: false, search: false, catalogs: false, allowRegistration: true }
let CURRENT_USER = null
let LAST_RESULTS = []
let QUALITY_FILTER = 'all'
let CATALOG_TYPE = 'movie'
let pollTimer = null

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
function escapeHtml (str) {
  return String(str)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}
function $ (id) { return document.getElementById(id) }

// ===================== Navegación (sidebar) =====================
const VIEW_TITLES = { discover: 'Descubrir', search: 'Buscar', library: 'Mi biblioteca', add: 'Añadir' }
let CURRENT_VIEW = 'discover'

function switchView (view) {
  CURRENT_VIEW = view
  document.querySelectorAll('.nav-item').forEach((b) => b.classList.toggle('active', b.dataset.view === view))
  document.querySelectorAll('.view').forEach((v) => v.classList.toggle('hidden', v.id !== 'view-' + view))
  $('view-title').textContent = VIEW_TITLES[view] || ''
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
  appView.classList.remove('hidden')
  $('user-name').textContent = user.username
  $('user-avatar').textContent = (user.username || '?').charAt(0).toUpperCase()
  await loadConfig()
  switchView(CONFIG.catalogs ? 'discover' : 'search')
  render()
  if (!pollTimer) pollTimer = setInterval(render, 1000)
  if (CONFIG.catalogs) loadCatalogs()
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

$('logout-btn').addEventListener('click', async () => {
  try { await api('/api/auth/logout', { method: 'POST' }) } catch {}
  CURRENT_USER = null
  $('search-results').innerHTML = ''
  $('catalogs').innerHTML = ''
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
    const res = await api(`/api/catalogs?type=${CATALOG_TYPE}`)
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
  const playIcon = '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M8 5v14l11-7z"/></svg>'
  catalogsEl.innerHTML = catalogs.map((cat) => `
    <div class="catalog-row">
      <div class="catalog-head"><span class="dot" style="background:${cat.color};color:${cat.color}"></span>${escapeHtml(cat.name)}</div>
      <div class="poster-row">
        ${cat.items.map((it) => `
          <button class="poster-card" data-title="${escapeHtml(it.title)}" data-type="${it.type}" data-year="${escapeHtml(it.year)}">
            <div class="poster-img">
              ${it.poster ? `<img loading="lazy" src="${escapeHtml(it.poster)}" alt="${escapeHtml(it.title)}" />` : '<div class="poster-ph">🎬</div>'}
              <span class="poster-play">${playIcon}</span>
            </div>
            <div class="poster-meta">
              <span class="poster-title">${escapeHtml(it.title)}</span>
              <span class="poster-year">${escapeHtml(it.year)}${it.rating ? ' · ⭐ ' + it.rating : ''}</span>
            </div>
          </button>`).join('')}
      </div>
    </div>`).join('')

  catalogsEl.querySelectorAll('.poster-card').forEach((c) => {
    c.addEventListener('click', () => {
      $('search-input').value = c.dataset.title
      $('search-type').value = c.dataset.type
      toggleSeasonFields()
      switchView('search')
      $('search-form').dispatchEvent(new Event('submit', { cancelable: true }))
    })
  })
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

searchType.addEventListener('change', toggleSeasonFields)
function toggleSeasonFields () {
  $('se-fields').classList.toggle('hidden', searchType.value !== 'series')
}

searchForm.addEventListener('submit', async (e) => {
  e.preventDefault()
  const query = searchInput.value.replace(/<[^>]*>/g, '').trim()
  if (!query) { searchStatus('Escribe algo para buscar.', true); return }

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

function renderResults (streams) {
  const filtered = QUALITY_FILTER === 'all' ? streams : streams.filter((s) => s.quality === QUALITY_FILTER)
  if (!filtered.length) { searchResults.innerHTML = '<p class="empty">No hay resultados con ese filtro.</p>'; return }
  searchResults.innerHTML = filtered.map((s, i) => {
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
          <button class="btn-download" data-magnet="${escapeHtml(s.url)}">⬇ Descargar y ver</button>
          <button class="btn-copy" data-magnet="${escapeHtml(s.url)}">📋 Copiar</button>
          <a class="btn-peerflix" href="peerflix://${escapeHtml(s.url)}">▶ Peerflix</a>
        </div>
      </div>`
  }).join('')

  searchResults.querySelectorAll('.btn-download').forEach((b) =>
    b.addEventListener('click', () => addMagnetFromSearch(b.dataset.magnet, b)))
  searchResults.querySelectorAll('.btn-copy').forEach((b) =>
    b.addEventListener('click', () => copyMagnet(b.dataset.magnet, b)))
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
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ magnet })
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

async function openPlayer (torrentHash, file, transcode) {
  playerTitle.textContent = file.name
  playerSubs.innerHTML = ''
  clearTracks() // limpiar tracks previos

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

function closePlayer () {
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
    updateGlobalStats(torrents)
    if (!torrents.length) { listEl.innerHTML = '<p class="empty">Tu biblioteca está vacía.<br>Usa <b>Descubrir</b> o <b>Buscar</b> para encontrar contenido, o <b>Añadir</b> para un magnet/.torrent.</p>'; return }
    listEl.innerHTML = torrents.map(cardHtml).join('')
    listEl.querySelectorAll('[data-remove]').forEach((b) => b.addEventListener('click', () => removeTorrent(b.dataset.remove)))
    listEl.querySelectorAll('[data-pause]').forEach((b) => b.addEventListener('click', () => togglePause(b.dataset.pause, b.dataset.paused === 'true')))
    listEl.querySelectorAll('[data-play]').forEach((b) => b.addEventListener('click', () => {
      const t = torrents.find((x) => x.infoHash === b.dataset.play)
      const file = t && t.files[Number(b.dataset.fileindex)]
      if (file) openPlayer(t.infoHash, file, b.dataset.mode === 'transcode')
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
    const subTag = f.isSubtitle ? '<span class="tag">CC</span>' : ''
    return `<div class="file-row"><span class="fname">${escapeHtml(f.name)} ${subTag}</span><span class="fmeta">${fmtBytes(f.length)} · ${fpct}%</span>${buttons}</div>`
  }).join('')

  return `
    <div class="torrent-card">
      <div class="torrent-head">
        <div class="torrent-name">${escapeHtml(t.name || 'Obteniendo metadatos…')}${t.paused ? ' <span class="tag">⏸ pausa</span>' : ''}</div>
        <div class="torrent-actions">
          <button class="btn-icon" data-pause="${t.infoHash}" data-paused="${t.paused}" title="${t.paused ? 'Reanudar' : 'Pausar'}">${t.paused ? '▶' : '⏸'}</button>
          <button class="btn-icon danger" data-remove="${t.infoHash}" title="Eliminar">🗑</button>
        </div>
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

// ===================== Init =====================
toggleSeasonFields()
checkSession()
