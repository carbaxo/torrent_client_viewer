// Proceso principal de Electron: arranca el servidor Node embebido y abre
// una ventana apuntando a http://localhost:PORT. Así la app de escritorio
// reutiliza TODO el backend y frontend existentes sin cambios.
import { app, BrowserWindow, shell, dialog } from 'electron'
import path from 'node:path'
import fs from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'
import net from 'node:net'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const isDev = !app.isPackaged

// Datos del usuario (sesiones, perfiles, historial) en la carpeta estándar de
// la plataforma; las descargas de Real-Debrid, en Descargas del sistema.
const userData = app.getPath('userData')
process.env.DATA_DIR = process.env.DATA_DIR || path.join(userData, 'data')
process.env.DOWNLOAD_DIR = process.env.DOWNLOAD_DIR || path.join(app.getPath('downloads'), 'TorrentViewer')
// Sesión estable entre reinicios (si el usuario no definió una)
process.env.SESSION_SECRET = process.env.SESSION_SECRET || 'electron-' + app.getPath('userData')
// Proyecto de Firebase (mismo que public/config.js; el projectId NO es secreto)
// para que el servidor local pueda verificar el login con Google.
process.env.FIREBASE_PROJECT_ID = process.env.FIREBASE_PROJECT_ID || 'torrent-7dd4b'

let mainWindow = null
let serverPort = Number(process.env.PORT) || 3000

// Busca un puerto libre a partir del preferido
function findFreePort (start) {
  return new Promise((resolve) => {
    const srv = net.createServer()
    srv.once('error', () => resolve(findFreePort(start + 1)))
    srv.once('listening', () => { const { port } = srv.address(); srv.close(() => resolve(port)) })
    srv.listen(start, '127.0.0.1')
  })
}

async function startServer () {
  serverPort = await findFreePort(serverPort)
  process.env.PORT = String(serverPort)
  // server.js arranca el Express al importarse. En Windows, import() de una
  // ruta absoluta necesita una URL file:// (si no: "Received protocol 'c:'").
  await import(pathToFileURL(path.join(__dirname, '..', 'server.js')).href)
  // Pequeña espera a que el listener esté activo
  await new Promise((r) => setTimeout(r, 600))
}

function createWindow () {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 900,
    minHeight: 600,
    backgroundColor: '#0c0b11',
    autoHideMenuBar: true,
    title: 'Torrent Viewer',
    icon: path.join(__dirname, '..', 'build', 'icon.png'),
    webPreferences: { contextIsolation: true }
  })
  mainWindow.loadURL(`http://localhost:${serverPort}`)
  // Gestión de ventanas emergentes:
  //  - El popup de login de Google/Firebase debe abrirse DENTRO de Electron
  //    (si se manda al navegador del sistema, Firebase no puede comunicarse
  //    con él y falla con auth/popup-blocked).
  //  - El resto de enlaces externos, al navegador del sistema.
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith(`http://localhost:${serverPort}`)) return { action: 'allow' }
    const host = (() => { try { return new URL(url).hostname } catch { return '' } })()
    const isAuthPopup = host === 'accounts.google.com' || host === 'apis.google.com' ||
      host.endsWith('.firebaseapp.com') || host.endsWith('.google.com') || host.endsWith('.googleapis.com')
    if (isAuthPopup) {
      return {
        action: 'allow',
        overrideBrowserWindowOptions: {
          width: 500, height: 650, autoHideMenuBar: true,
          webPreferences: { contextIsolation: true, nodeIntegration: false }
        }
      }
    }
    shell.openExternal(url)
    return { action: 'deny' }
  })
  if (isDev) mainWindow.webContents.openDevTools({ mode: 'detach' })
}

// --- Auto-actualización (paridad con Update.kt de la app Android) ---------
// La CI publica el MSI en la release "windows-latest" con "Build N" en el
// cuerpo, y estampa el nº de build en electron/build-info.json al compilar.
const UPDATE_REPO = 'carbaxo/torrent_client_viewer'
const UPDATE_TAG = 'windows-latest'

function localBuild () {
  try {
    return Number(JSON.parse(fs.readFileSync(path.join(__dirname, 'build-info.json'), 'utf8')).build) || 0
  } catch {
    return 0
  }
}

async function checkForUpdate () {
  if (!app.isPackaged) return // en desarrollo no molesta
  try {
    const res = await fetch(`https://api.github.com/repos/${UPDATE_REPO}/releases/tags/${UPDATE_TAG}`, {
      headers: { 'User-Agent': 'TorrentViewer', Accept: 'application/vnd.github+json' }
    })
    if (!res.ok) return
    const rel = await res.json()
    const m = /Build (\d+)/.exec(rel.body || '')
    const remote = m ? Number(m[1]) : 0
    const mine = localBuild()
    if (!remote || !mine || remote <= mine) return
    const asset = (rel.assets || []).find((a) => a.name && a.name.endsWith('.msi'))
    if (!asset) return
    const { response } = await dialog.showMessageBox(mainWindow, {
      type: 'info',
      buttons: ['Actualizar ahora', 'Más tarde'],
      defaultId: 0,
      cancelId: 1,
      title: 'Actualización disponible',
      message: `Hay una versión nueva de Torrent Viewer (build ${remote}; la instalada es ${mine}).`,
      detail: 'Se descargará el instalador y se abrirá para actualizar. La app se cerrará.'
    })
    if (response !== 0) return
    const dl = await fetch(asset.browser_download_url, { headers: { 'User-Agent': 'TorrentViewer' } })
    if (!dl.ok) throw new Error('descarga: HTTP ' + dl.status)
    const dest = path.join(app.getPath('temp'), 'TorrentViewer-Setup.msi')
    fs.writeFileSync(dest, Buffer.from(await dl.arrayBuffer()))
    await shell.openPath(dest)
    app.quit()
  } catch (err) {
    console.error('[update]', err.message)
  }
}

// Instancia única
if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', () => {
    if (mainWindow) { if (mainWindow.isMinimized()) mainWindow.restore(); mainWindow.focus() }
  })

  app.whenReady().then(async () => {
    try {
      await startServer()
    } catch (err) {
      dialog.showErrorBox('Error al iniciar', 'No se pudo arrancar el servidor:\n' + err.message)
      app.quit()
      return
    }
    createWindow()
    checkForUpdate() // en segundo plano; avisa solo si hay build nueva
    app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow() })
  })

  app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit() })
}
