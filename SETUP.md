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
**Nunca** en el frontend. Se ponen como variables de entorno en el servidor:

```bash
export OMDB_API_KEY=tu_key_de_omdb
export TMDB_API_KEY=tu_key_de_tmdb
```

En local puedes crear un archivo `.env` (no lo subas a git) o exportarlas antes de `npm start`.

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
export OMDB_API_KEY=... TMDB_API_KEY=...
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
1. Instala flyctl y `fly launch` (detecta el `Dockerfile`).
2. Crea un volumen gratis y móntalo:
   ```bash
   fly volumes create data --size 1
   ```
   y en `fly.toml` monta el volumen en `/data`, con `DATA_DIR=/data` y `DOWNLOAD_DIR=/data/downloads`.
3. `fly secrets set OMDB_API_KEY=... TMDB_API_KEY=... SESSION_SECRET=... ALLOWED_ORIGINS=https://carbaxo.github.io`
4. `fly deploy`.

### 5.C Frontend en GitHub Pages
1. En este repo: **Settings → Pages → Deploy from a branch**, carpeta `/public` (o publica el contenido de `public/` con una GitHub Action).
2. Edita **`public/config.js`** y pon la URL de tu backend:
   ```js
   window.TCV_API_BASE = 'https://tu-backend.onrender.com'
   ```
3. Asegúrate de que en el backend `ALLOWED_ORIGINS` incluye tu URL de Pages (`https://carbaxo.github.io`). Esto activa CORS con credenciales y cookies `SameSite=None; Secure`.

### 5.D Alternativa más simple: todo en un solo host
Si no quieres lidiar con CORS, **no uses GitHub Pages**: despliega solo el backend (5.A o 5.B) y ábrelo directamente — el servidor Node ya sirve el frontend. Cero configuración de CORS.

---

## 6. Notas de seguridad

- Contraseñas hasheadas con **scrypt**; sesiones firmadas con **HMAC** en cookie `httpOnly`.
- Define `SESSION_SECRET` en producción (si no, se autogenera y se pierde al recrear el contenedor, cerrando todas las sesiones).
- Tras crear tus cuentas, pon `ALLOW_REGISTRATION=false` para que nadie más se registre.
- Cada usuario solo ve y accede a **su** biblioteca (aislamiento por propietario).
- Descarga solo contenido para el que tengas derechos.
