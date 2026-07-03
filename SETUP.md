# ⚙️ Guía de configuración y despliegue

Todo lo que necesitas para dejar la app funcionando. Marca cada paso ✅.

---

## 1. API keys que necesitas obtener

| Servicio | Para qué | Cómo obtenerla | Variable de entorno |
|----------|----------|----------------|---------------------|
| **OMDb** | Buscador Torrentio (nombre → IMDb ID) | Gratis en <https://www.omdbapi.com/apikey.aspx> (te llega por email) | `OMDB_API_KEY` |
| **TMDB** | Catálogos de streaming (Netflix, Prime, HBO Max, Disney+) | Gratis en <https://www.themoviedb.org/settings/api> (crea cuenta → API → *API Key (v3 auth)*) | `TMDB_API_KEY` |

> El **motor Peerflix** (apibay/The Pirate Bay) **no necesita API key**.
> Sin OMDb, el buscador Torrentio queda limitado. Sin TMDB, la sección de catálogos se oculta automáticamente. El resto de la app funciona igual.

### Dónde configurarlas
**Nunca** en el frontend. En local, lo más fácil es el archivo **`.env`** en la raíz del proyecto (el servidor lo carga automáticamente al arrancar; ya está en `.gitignore`):

```
OMDB_API_KEY=tu_key_de_omdb
TMDB_API_KEY=tu_key_de_tmdb
```

Hay una plantilla lista en `.env.example`. En un hosting (Render/Fly.io) se configuran como variables de entorno o secretos del panel — las variables de entorno reales tienen prioridad sobre `.env`.

---

## 2. Instalar ffmpeg (opcional pero recomendado)

Necesario para **⚙ Convertir** (reproducir mkv/avi en el navegador) y para **subtítulos embebidos**.

```bash
sudo apt-get install ffmpeg      # Debian/Ubuntu
brew install ffmpeg              # macOS
# Windows: https://ffmpeg.org/download.html
```

Si usas el `Dockerfile` incluido, **ffmpeg ya viene dentro**.

---

## 3. Ejecutar en local

```bash
npm install
# copia .env.example a .env y rellena tus claves
npm start
# abre http://localhost:3000
```

La primera vez, pulsa **Crear cuenta** para registrar tu usuario. Cada usuario tiene su propia biblioteca.

---

## 4. Variables de entorno (todas)

| Variable | Por defecto | Descripción |
|----------|-------------|-------------|
| `PORT` | `3000` | Puerto del servidor |
| `OMDB_API_KEY` | *(vacío)* | API key de OMDb (buscador) |
| `TMDB_API_KEY` | *(vacío)* | API key de TMDB (catálogos) |
| `TMDB_REGION` | `ES` | Región para los catálogos (proveedores por país) |
| `DOWNLOAD_DIR` | `./downloads` | Carpeta de descargas |
| `DATA_DIR` | `./data` | Usuarios, sesiones y estado de torrents |
| `FFMPEG_PATH` | `ffmpeg` | Ruta al binario de ffmpeg |
| `SESSION_SECRET` | *(autogenerado)* | Secreto para firmar sesiones. **Defínelo en producción** para que las sesiones sobrevivan a redeploys |
| `ALLOW_REGISTRATION` | `true` | Pon `false` para cerrar el registro tras crear tus cuentas |
| `SECURE_COOKIE` | `false` | `true` si sirves por HTTPS en el mismo dominio |
| `ALLOWED_ORIGINS` | *(vacío)* | Orígenes permitidos para CORS con credenciales (p.ej. tu URL de GitHub Pages). Al definirlo, las cookies pasan a `SameSite=None; Secure` |

---

## 5. Despliegue GRATIS (Opción elegida: frontend en GitHub Pages + backend Node)

> ⚠️ **Aviso honesto sobre "gratis":** los planes gratuitos de hosting Node tienen **disco efímero** y **se duermen por inactividad**. Eso significa que, tras un reinicio, se pueden **perder las descargas y las cuentas** salvo que uses un disco persistente. Para persistencia real y gratuita, **Fly.io** ofrece un volumen pequeño gratis (ver más abajo).

### 5.A Backend en Render (lo más sencillo)
1. Sube este repo a GitHub (ya está en `carbaxo/torrent_client_viewer`).
2. En <https://render.com> → **New → Web Service** → conecta el repo.
3. Environment: **Docker** (usará el `Dockerfile`, que incluye ffmpeg).
4. En **Environment Variables** añade: `OMDB_API_KEY`, `TMDB_API_KEY`, `SESSION_SECRET` (invéntate uno largo), y `ALLOWED_ORIGINS=https://carbaxo.github.io`.
5. Deploy. Anota la URL, p.ej. `https://torrent-client-viewer.onrender.com`.

> Persistencia en Render: el disco es efímero en el plan free. Para conservar datos necesitas un **Disk** (de pago) montado en `DATA_DIR` y `DOWNLOAD_DIR`.

### 5.B Backend en Fly.io (gratis CON persistencia)
El repo **ya incluye `fly.toml`** (con volumen persistente en `/data` e imagen `Dockerfile` con ffmpeg). Solo tienes que:
1. Instala flyctl. Edita `fly.toml` y cambia `app` por un nombre único tuyo.
2. Crea el volumen persistente:
   ```bash
   fly volumes create data --size 1 --region mad
   ```
3. Guarda los secretos (NO van en `fly.toml`):
   ```bash
   fly secrets set OMDB_API_KEY=... TMDB_API_KEY=... SESSION_SECRET=<algo-largo> \
     ALLOWED_ORIGINS=https://carbaxo.github.io
   ```
4. `fly deploy`.

> Por defecto la máquina se apaga en reposo (`min_machines_running = 0`) para ahorrar; eso **pausa las descargas** cuando no hay nadie. Ponlo a `1` en `fly.toml` si quieres que siga descargando.

### 5.C Frontend en GitHub Pages (automático)
El repo **ya incluye el workflow** `.github/workflows/deploy-pages.yml`, que publica `public/` en Pages en cada push.
1. Edita **`public/config.js`** y pon la URL de tu backend, y haz commit:
   ```js
   window.TCV_API_BASE = 'https://tu-backend.onrender.com'
   ```
2. En GitHub: **Settings → Pages → Build and deployment → Source: GitHub Actions**.
3. Haz push (o lánzalo a mano en **Actions → Deploy frontend to GitHub Pages → Run workflow**). Tu web quedará en `https://carbaxo.github.io/torrent_client_viewer/`.
4. En el backend, `ALLOWED_ORIGINS` debe incluir tu URL de Pages (`https://carbaxo.github.io`). Esto activa CORS con credenciales y cookies `SameSite=None; Secure`.

### 5.D Alternativa más simple: todo en un solo host
Si no quieres lidiar con CORS, **no uses GitHub Pages**: despliega solo el backend (5.A o 5.B) y ábrelo directamente — el servidor Node ya sirve el frontend. Cero configuración de CORS.

---

## 6. Firebase: login con Google + sincronización entre dispositivos

Opcional. Añade **"Continuar con Google"** al login y sincroniza favoritos,
historial ("continuar viendo", vistos) y ajustes en la nube (Firestore), de
modo que la misma cuenta funcione en varios dispositivos.

1. Crea un proyecto en <https://console.firebase.google.com>.
2. **Authentication → Sign-in method**: habilita **Google**.
3. **Firestore Database → Crear base de datos** (modo producción).
4. En **Configuración del proyecto → General → Tus apps**, crea una app web
   (`</>`) y copia el bloque `firebaseConfig`.
5. Pega esa configuración en **`public/config.js`** (`window.TCV_FIREBASE`).
6. Pon el mismo projectId en **`.env`**: `FIREBASE_PROJECT_ID=tu-proyecto`.
7. En **Firestore → Reglas**, pega y publica estas reglas (cada usuario solo
   puede leer/escribir su propio documento):

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{uid} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
  }
}
```

Notas:
- El SDK de Firebase se sirve **autoalojado** desde `public/vendor/` (la CSP
  no permite CDNs externas). La config de `config.js` no es secreta.
- El servidor verifica criptográficamente el ID token de Firebase
  (`lib/firebaseAuth.js`) y emite su sesión de siempre: los torrents siguen
  siendo por usuario.
- Si sirves la app desde un dominio distinto de `localhost`, añádelo en
  **Authentication → Settings → Authorized domains**.
- Sin `TCV_FIREBASE`/`FIREBASE_PROJECT_ID`, todo funciona igual con cuentas
  locales (sin sincronización en la nube).

---

## 7. App de escritorio para Windows (Electron → MSI / portable)

La app puede empaquetarse como programa de escritorio: Electron arranca el
servidor Node embebido y abre una ventana; no hace falta terminal ni navegador.

```bash
npm install          # instala también electron y electron-builder
npm run electron     # probar en modo escritorio (sin empaquetar)
npm run dist         # genera instalador MSI + portable en dist-app/
npm run dist:msi     # solo MSI
npm run dist:portable# solo .exe portable (sin instalar)
```

- Salida en `dist-app/`: `Torrent Viewer-1.0.0-x64.msi` (instalador) y
  `Torrent Viewer-1.0.0-portable.exe` (portable). Carpeta ignorada por git.
- Datos del usuario en `%APPDATA%/Torrent Viewer/data`; descargas en
  `Descargas/TorrentViewer`. Se pueden cambiar en Ajustes.
- Las API keys (OMDb/TMDB/Firebase) se leen de las variables de entorno o del
  `.env` junto al ejecutable; el token de Real-Debrid se guarda por cuenta.
- **ffmpeg**: la transcodificación y los subtítulos embebidos requieren ffmpeg
  en el PATH. El MSI no lo incluye; instálalo aparte o añade `ffmpeg-static`
  al empaquetado si lo necesitas (el resto de la app funciona sin él).
- El icono se genera con `node build/make-icon.mjs` (edítalo para cambiarlo).

## 8. App para Android (cliente del servidor)

⚠️ **Importante:** el motor de torrents (WebTorrent/Node) **no se ejecuta en
Android**. La vía realista es una app que actúe de **cliente del backend** que
corre en tu PC (o en un servidor): el PC descarga y sirve, el móvil ve el
catálogo, lanza descargas y reproduce en streaming. Con Real-Debrid, el vídeo
se reproduce por streaming directo desde sus servidores.

El frontend ya soporta apuntar a un backend remoto:
1. En **Ajustes → Servidor**, o en la pantalla de acceso, indica la URL del
   backend (p.ej. `http://192.168.1.50:3000` en tu red, o una URL pública).
2. En el servidor, define `ALLOWED_ORIGINS` con el origen de la app para
   permitir CORS con credenciales.

Para generar el APK (requiere **JDK 17** + **Android Studio / SDK**, que no
vienen en este repo) se incluye configuración de Capacitor:

```bash
npm install -D @capacitor/cli @capacitor/core @capacitor/android
npx cap add android      # crea el proyecto nativo android/
npx cap sync
npx cap open android     # compila/firma el APK desde Android Studio
```

`capacitor.config.json` empaqueta la carpeta `public/` como app; la URL del
backend se configura dentro de la app en el primer arranque.

---

## 9. Notas de seguridad

- Contraseñas hasheadas con **scrypt**; sesiones firmadas con **HMAC** en cookie `httpOnly`.
- Define `SESSION_SECRET` en producción (si no, se autogenera y se pierde al recrear el contenedor, cerrando todas las sesiones).
- Tras crear tus cuentas, pon `ALLOW_REGISTRATION=false` para que nadie más se registre.
- Cada usuario solo ve y accede a **su** biblioteca (aislamiento por propietario).
- Descarga solo contenido para el que tengas derechos.
