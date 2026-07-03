// ===================== Estado / config =====================
let CONFIG = { ffmpeg: false, search: false }

fetch('/api/config').then((r) => r.json()).then((c) => {
  CONFIG = c
  if (!c.search) {
    searchStatus('El buscador está desactivado: configura OMDB_API_KEY en el servidor.', true)
  }
}).catch(() => {})

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

// ===================== Buscador =====================
const searchForm = document.getElementById('search-form')
const searchInput = document.getElementById('search-input')
const searchType = document.getElementById('search-type')
const searchBtn = document.getElementById('search-btn')
const searchStatusEl = document.getElementById('search-status')
const searchLoader = document.getElementById('search-loader')
const searchResults = document.getElementById('search-results')

function searchStatus (msg, isError = false) {
  searchStatusEl.textContent = msg
  searchStatusEl.classList.toggle('error', isError)
}

searchForm.addEventListener('submit', async (e) => {
  e.preventDefault()
  const query = searchInput.value.replace(/<[^>]*>/g, '').trim()
  if (!query) { searchStatus('Escribe algo para buscar.', true); return }

  searchStatus('')
  searchResults.innerHTML = ''
  searchLoader.classList.remove('hidden')
  searchBtn.disabled = true

  try {
    const url = `/api/search?query=${encodeURIComponent(query)}&type=${searchType.value}`
    const res = await fetch(url)
    const data = await res.json()
    if (!res.ok || !data.success) {
      throw new Error(data.error || 'No se pudo completar la búsqueda.')
    }
    if (!data.streams.length) {
      searchStatus('Sin resultados de torrents para este título.')
    } else {
      searchStatus(`${data.streams.length} resultados para "${data.title || query}" (${data.imdbId})${data.cached ? ' · desde caché' : ''}`)
    }
    renderResults(data.streams)
  } catch (err) {
    searchStatus(err.message, true)
  } finally {
    searchLoader.classList.add('hidden')
    searchBtn.disabled = false
  }
})

function renderResults (streams) {
  searchResults.innerHTML = streams.map((s, i) => {
    const qClass = 'q-' + s.quality.toLowerCase().replace(/[^a-z0-9]/g, '')
    return `
      <div class="result-card" style="animation-delay:${Math.min(i * 40, 400)}ms">
        <div class="result-name">${escapeHtml(s.filename)}</div>
        <div class="result-badges">
          <span class="badge quality ${qClass}">${escapeHtml(s.quality)}</span>
          <span class="badge size">${escapeHtml(s.size)}</span>
          <span class="badge seeders">▲ ${s.seeders} seeders</span>
        </div>
        <div class="result-actions">
          <button class="btn-download" data-magnet="${escapeHtml(s.url)}">⬇ Descargar aquí</button>
          <a class="btn-magnet" href="${escapeHtml(s.url)}">🧲 Abrir Magnet</a>
        </div>
      </div>`
  }).join('')

  searchResults.querySelectorAll('[data-magnet]').forEach((btn) => {
    btn.addEventListener('click', () => addMagnetFromSearch(btn.dataset.magnet, btn))
  })
}

async function addMagnetFromSearch (magnet, btn) {
  btn.disabled = true
  btn.textContent = 'Añadiendo…'
  try {
    const res = await fetch('/api/torrents', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ magnet })
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || 'Error')
    btn.textContent = '✓ En descargas'
    addStatus(`Añadido a descargas: ${data.name || 'torrent'}`)
    render()
    document.getElementById('torrent-list').scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  } catch (err) {
    btn.disabled = false
    btn.textContent = '⬇ Descargar aquí'
    addStatus('No se pudo añadir: ' + err.message, true)
  }
}

// ===================== Añadir manual =====================
const magnetForm = document.getElementById('magnet-form')
const magnetInput = document.getElementById('magnet-input')
const uploadForm = document.getElementById('upload-form')
const torrentFile = document.getElementById('torrent-file')
const fileNameLabel = document.getElementById('file-name')
const addStatusEl = document.getElementById('add-status')

function addStatus (msg, isError = false) {
  addStatusEl.textContent = msg
  addStatusEl.classList.toggle('error', isError)
}

magnetForm.addEventListener('submit', async (e) => {
  e.preventDefault()
  const magnet = magnetInput.value.trim()
  if (!magnet) return
  addStatus('Añadiendo magnet y obteniendo metadatos…')
  try {
    const res = await fetch('/api/torrents', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ magnet })
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || 'Error desconocido')
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
  addStatus('Subiendo .torrent y obteniendo metadatos…')
  const fd = new FormData()
  fd.append('torrent', f)
  try {
    const res = await fetch('/api/torrents/upload', { method: 'POST', body: fd })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || 'Error desconocido')
    torrentFile.value = ''
    fileNameLabel.textContent = 'Elegir archivo .torrent…'
    addStatus(`Añadido: ${data.name || 'torrent'}`)
    render()
  } catch (err) { addStatus(err.message, true) }
})

// ===================== Acciones de torrents =====================
async function removeTorrent (infoHash) {
  const withFiles = confirm('¿Eliminar también los archivos descargados del disco?\n\nAceptar = borrar archivos · Cancelar = mantener archivos')
  try {
    await fetch(`/api/torrents/${infoHash}?files=${withFiles}`, { method: 'DELETE' })
    render()
  } catch (err) { addStatus('No se pudo eliminar: ' + err.message, true) }
}

async function togglePause (infoHash, paused) {
  try {
    await fetch(`/api/torrents/${infoHash}/${paused ? 'resume' : 'pause'}`, { method: 'POST' })
    render()
  } catch (err) { addStatus('No se pudo cambiar el estado: ' + err.message, true) }
}

// ===================== Reproductor =====================
const overlay = document.getElementById('player-overlay')
const player = document.getElementById('player')
const playerTitle = document.getElementById('player-title')
const playerNote = document.getElementById('player-note')
const playerClose = document.getElementById('player-close')

function openPlayer (file, transcode) {
  playerTitle.textContent = file.name
  player.src = transcode ? file.transcodeUrl : file.streamUrl
  overlay.classList.remove('hidden')
  if (transcode) {
    playerNote.textContent = 'Convirtiendo con ffmpeg en tiempo real (H.264/AAC). No se puede adelantar durante la conversión en vivo.'
  } else if (!file.nativePlayable) {
    playerNote.textContent = 'Este formato puede no reproducirse de forma nativa. Si no ves imagen, prueba "Convertir" (requiere ffmpeg) o ábrelo en VLC. El archivo se descarga igualmente.'
  } else {
    playerNote.textContent = 'Reproduciendo en streaming mientras se descarga. Puedes adelantar y se priorizan las piezas necesarias.'
  }
  player.play().catch(() => {})
}

function closePlayer () {
  overlay.classList.add('hidden')
  player.pause()
  player.removeAttribute('src')
  player.load()
}

playerClose.addEventListener('click', closePlayer)
overlay.addEventListener('click', (e) => { if (e.target === overlay) closePlayer() })
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && !overlay.classList.contains('hidden')) closePlayer()
})

// ===================== Render de descargas =====================
const listEl = document.getElementById('torrent-list')
const globalStatsEl = document.getElementById('global-stats')

function render () {
  fetch('/api/torrents').then((r) => r.json()).then((torrents) => {
    updateGlobalStats(torrents)
    if (!torrents.length) {
      listEl.innerHTML = '<p class="empty">No hay descargas todavía. Busca arriba o añade un magnet/.torrent.</p>'
      return
    }
    listEl.innerHTML = torrents.map(cardHtml).join('')
    listEl.querySelectorAll('[data-remove]').forEach((b) =>
      b.addEventListener('click', () => removeTorrent(b.dataset.remove)))
    listEl.querySelectorAll('[data-pause]').forEach((b) =>
      b.addEventListener('click', () => togglePause(b.dataset.pause, b.dataset.paused === 'true')))
    listEl.querySelectorAll('[data-play]').forEach((b) =>
      b.addEventListener('click', () => {
        const t = torrents.find((x) => x.infoHash === b.dataset.play)
        const file = t && t.files[Number(b.dataset.fileindex)]
        if (file) openPlayer(file, b.dataset.mode === 'transcode')
      }))
  }).catch(() => {})
}

function updateGlobalStats (torrents) {
  const down = torrents.reduce((a, t) => a + t.downloadSpeed, 0)
  const up = torrents.reduce((a, t) => a + t.uploadSpeed, 0)
  const active = torrents.filter((t) => !t.done && !t.paused).length
  globalStatsEl.innerHTML = torrents.length
    ? `<span>↓ <b>${fmtSpeed(down)}</b></span><span>↑ ${fmtSpeed(up)}</span><span>${active} activos · ${torrents.length} total</span>`
    : ''
}

function cardHtml (t) {
  const pct = (t.progress * 100).toFixed(1)
  const filesHtml = t.files.map((f) => {
    const fpct = (f.progress * 100).toFixed(0)
    let buttons = ''
    if (f.isVideo) {
      if (f.nativePlayable) {
        buttons += `<button class="btn-play" data-play="${t.infoHash}" data-fileindex="${f.index}" data-mode="stream">▶ Ver</button>`
      } else {
        buttons += `<button class="btn-play" data-play="${t.infoHash}" data-fileindex="${f.index}" data-mode="stream">▶ Ver</button>`
        if (CONFIG.ffmpeg) {
          buttons += `<button class="btn-play alt" data-play="${t.infoHash}" data-fileindex="${f.index}" data-mode="transcode">⚙ Convertir</button>`
        }
      }
    }
    return `
      <div class="file-row">
        <span class="fname">${escapeHtml(f.name)}</span>
        <span class="fmeta">${fmtBytes(f.length)} · ${fpct}%</span>
        ${buttons}
      </div>`
  }).join('')

  return `
    <div class="torrent-card">
      <div class="torrent-head">
        <div class="torrent-name">${escapeHtml(t.name || 'Obteniendo metadatos…')}${t.paused ? ' <span class="tag">⏸ en pausa</span>' : ''}</div>
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

// Refresco periódico
render()
setInterval(render, 1000)
