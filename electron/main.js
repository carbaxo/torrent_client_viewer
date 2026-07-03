// Proceso principal de Electron: arranca el servidor Node embebido y abre
// una ventana apuntando a http://localhost:PORT. Así la app de escritorio
// reutiliza TODO el backend y frontend existentes sin cambios.
import { app, BrowserWindow, shell, dialog } from 'electron'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import net from 'node:net'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const isDev = !app.isPackaged

// Datos del usuario (torrents, sesiones, userdata) en la carpeta estándar de
// la plataforma; las descargas/buffer, en la carpeta de descargas del sistema.
const userData = app.getPath('userData')
process.env.DATA_DIR = process.env.DATA_DIR || path.join(userData, 'data')
process.env.DOWNLOAD_DIR = process.env.DOWNLOAD_DIR || path.join(app.getPath('downloads'), 'TorrentViewer')
process.env.BUFFER_DIR = process.env.BUFFER_DIR || path.join(userData, 'buffer')
// Sesión estable entre reinicios (si el usuario no definió una)
process.env.SESSION_SECRET = process.env.SESSION_SECRET || 'electron-' + app.getPath('userData')

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
  // server.js arranca el Express al importarse
  await import(path.join(__dirname, '..', 'server.js'))
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
  // Los enlaces externos se abren en el navegador del sistema
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (!url.startsWith(`http://localhost:${serverPort}`)) { shell.openExternal(url); return { action: 'deny' } }
    return { action: 'allow' }
  })
  if (isDev) mainWindow.webContents.openDevTools({ mode: 'detach' })
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
    app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow() })
  })

  app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit() })
}
