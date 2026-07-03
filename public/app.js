const magnetForm = document.getElementById('magnet-form')
const magnetInput = document.getElementById('magnet-input')
const uploadForm = document.getElementById('upload-form')
const torrentFile = document.getElementById('torrent-file')
const fileNameLabel = document.getElementById('file-name')
const statusEl = document.getElementById('status')
const listEl = document.getElementById('torrent-list')

const overlay = document.getElementById('player-overlay')
const player = document.getElementById('player')
const playerTitle = document.getElementById('player-title')
const playerNote = document.getElementById('player-note')
const playerClose = document.getElementById('player-close')

// --- Utilidades ---------------------------------------------------------

function fmtBytes (bytes) {
  if (!bytes || bytes < 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let n = bytes
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++ }
  return `${n.toFixed(n >= 10 || i === 0 ? 0 : 1)} ${units[i]}`
}

function fmtSpeed (bps) {
  return fmtBytes(bps) + '/s'
}

function fmtEta (ms) {
  if (!isFinite(ms) || ms <= 0) return '—'
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  const rem = s % 60
  if (m < 60) return `${m}m ${rem}s`
  const h = Math.floor(m / 60)
  return `${h}h ${m % 60}m`
}

function setStatus (msg, isError = false) {
  statusEl.textContent = msg
  statusEl.classList.toggle('error', isError)
}

// --- Añadir torrents ----------------------------------------------------

magnetForm.addEventListener('submit', async (e) => {
  e.preventDefault()
  const magnet = magnetInput.value.trim()
  if (!magnet) return
  setStatus('Añadiendo magnet y obteniendo metadatos…')
  try {
    const res = await fetch('/api/torrents', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ magnet })
    })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || 'Error desconocido')
    magnetInput.value = ''
    setStatus(`Añadido: ${data.name}`)
    render()
  } catch (err) {
    setStatus(err.message, true)
  }
})

torrentFile.addEventListener('change', () => {
  fileNameLabel.textContent = torrentFile.files[0]
    ? torrentFile.files[0].name
    : 'Elegir archivo .torrent…'
})

uploadForm.addEventListener('submit', async (e) => {
  e.preventDefault()
  const f = torrentFile.files[0]
  if (!f) { setStatus('Selecciona un archivo .torrent primero.', true); return }
  setStatus('Subiendo .torrent y obteniendo metadatos…')
  const fd = new FormData()
  fd.append('torrent', f)
  try {
    const res = await fetch('/api/torrents/upload', { method: 'POST', body: fd })
    const data = await res.json()
    if (!res.ok) throw new Error(data.error || 'Error desconocido')
    torrentFile.value = ''
    fileNameLabel.textContent = 'Elegir archivo .torrent…'
    setStatus(`Añadido: ${data.name}`)
    render()
  } catch (err) {
    setStatus(err.message, true)
  }
})

// --- Eliminar -----------------------------------------------------------

async function removeTorrent (infoHash) {
  const withFiles = confirm('¿Eliminar también los archivos descargados del disco?\n\nAceptar = borrar archivos · Cancelar = mantener archivos')
  try {
    await fetch(`/api/torrents/${infoHash}?files=${withFiles}`, { method: 'DELETE' })
    render()
  } catch (err) {
    setStatus('No se pudo eliminar: ' + err.message, true)
  }
}

// --- Reproductor --------------------------------------------------------

function openPlayer (file) {
  playerTitle.textContent = file.name
  player.src = file.streamUrl
  overlay.classList.remove('hidden')
  if (!file.nativePlayable) {
    playerNote.textContent =
      'Nota: este formato (' + (file.name.split('.').pop() || '') +
      ') puede no reproducirse de forma nativa en el navegador. ' +
      'El archivo se sigue descargando a tu disco de todos modos.'
  } else {
    playerNote.textContent = 'Reproduciendo en streaming mientras se descarga. Puedes adelantar y las piezas necesarias se priorizan.'
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

// --- Render de la lista --------------------------------------------------

function render () {
  fetch('/api/torrents')
    .then((r) => r.json())
    .then((torrents) => {
      if (!torrents.length) {
        listEl.innerHTML = '<p style="color:var(--muted);text-align:center">No hay torrents todavía. Añade un magnet o sube un .torrent.</p>'
        return
      }
      listEl.innerHTML = torrents.map(cardHtml).join('')
      // Enlazar botones
      listEl.querySelectorAll('[data-remove]').forEach((btn) => {
        btn.addEventListener('click', () => removeTorrent(btn.dataset.remove))
      })
      listEl.querySelectorAll('[data-play]').forEach((btn) => {
        btn.addEventListener('click', () => {
          const t = torrents.find((x) => x.infoHash === btn.dataset.play)
          const file = t && t.files[Number(btn.dataset.fileindex)]
          if (file) openPlayer(file)
        })
      })
    })
    .catch(() => {})
}

function cardHtml (t) {
  const pct = (t.progress * 100).toFixed(1)
  const videoCount = t.files.filter((f) => f.isVideo).length

  const filesHtml = t.files.map((f) => {
    const fpct = (f.progress * 100).toFixed(0)
    const playBtn = f.isVideo
      ? `<button class="btn-play" data-play="${t.infoHash}" data-fileindex="${f.index}">▶ Ver</button>`
      : ''
    return `
      <div class="file-row">
        <span class="fname">${escapeHtml(f.name)}</span>
        <span class="fmeta">${fmtBytes(f.length)} · ${fpct}%</span>
        ${playBtn}
      </div>`
  }).join('')

  return `
    <div class="torrent-card">
      <div class="torrent-head">
        <div class="torrent-name">${escapeHtml(t.name || 'Obteniendo metadatos…')}</div>
        <button class="btn-remove" data-remove="${t.infoHash}">Eliminar</button>
      </div>
      <div class="progress-outer"><div class="progress-inner" style="width:${pct}%"></div></div>
      <div class="stats">
        <span><b>${pct}%</b></span>
        <span>${fmtBytes(t.downloaded)} / ${fmtBytes(t.length)}</span>
        <span>↓ <b>${fmtSpeed(t.downloadSpeed)}</b></span>
        <span>↑ ${fmtSpeed(t.uploadSpeed)}</span>
        <span>${t.numPeers} peers</span>
        <span>ETA: ${t.done ? '✅ completo' : fmtEta(t.timeRemaining)}</span>
      </div>
      ${videoCount ? `<div class="files">${filesHtml}</div>` : (t.files.length ? `<div class="files">${filesHtml}</div>` : '')}
    </div>`
}

function escapeHtml (str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

// Refresco periódico del progreso
render()
setInterval(render, 1000)
