# 🎬 Torrent Client Viewer — modo Real-Debrid

App web multiusuario para **buscar películas y series y verlas por streaming directo**, con catálogos de plataformas y perfiles independientes. Hay además una **app Android/Android TV** nativa en [`android-standalone/`](./android-standalone).

> ⚡ **Requiere una cuenta de Real-Debrid.** El proyecto **no lleva motor BitTorrent**: no descarga por torrent ni se conecta a ningún peer. Lo único que hace con un magnet es entregárselo a Real-Debrid, que lo resuelve en sus servidores y devuelve una URL HTTPS normal que el navegador reproduce **directamente desde el CDN de RD** — el vídeo no pasa por tu servidor.

## Funciones

- 🔐 **Login por usuario/contraseña** (scrypt + sesión firmada) o **con Google** (Firebase). Cada usuario tiene su biblioteca privada.
- 👨‍👩‍👧 **Perfiles por cuenta** (hasta 5), con **modo infantil** (solo catálogos familiares, sin buscar).
- ☁️ **Sincronización** de favoritos, historial y ajustes entre dispositivos (Firestore) al entrar con Google.
- ❤️ **Favoritos**, ⏯️ **continuar viendo** (reanuda donde lo dejaste), ✓ **vistos** automáticos y 🎯 **recomendaciones** TMDB según lo que ves.
- ⚡ **Real-Debrid**: ▶️ **Ver** (streaming HTTPS directo) y ⬇️ **Descargar** a disco desde sus servidores, con tu token privado (se sincroniza entre el PC, la web y el móvil).
- 🍿 **Catálogos de streaming** (TMDB): Netflix, Prime Video, HBO Max, Disney+ con pósters; clic en un título → busca fuentes. Ficha con **tráiler** de YouTube.
- 🔎 **Buscador con 2 motores**: **Torrentio** (IMDb vía OMDb **o TMDB**) y **Peerflix** (apibay/TPB), con filtros de calidad (4K/1080p/720p/SD), **idioma de la fuente** (banderas) ordenado por tu preferencia, y selección de temporada/episodio.
- ⏭️ **Siguiente episodio** automático en el reproductor y 🔔 **avisos de episodios nuevos** de tus series favoritas.
- 📱 **Responsive** (móvil y escritorio), tema oscuro.
- 📥 **Ofrece descargar el APK**: al entrar desde Android sale un aviso (descartable) para instalar la app nativa, y en **Ajustes** hay siempre un enlace. Se configura con `window.TCV_APK_URL` en `public/config.js`.

## Puesta en marcha rápida

```bash
npm install
cp .env.example .env      # y rellena OMDB_API_KEY y TMDB_API_KEY dentro
npm start                 # http://localhost:3000
```

Luego, en **Ajustes → Real-Debrid**, pega tu token de <https://real-debrid.com/apitoken>. Sin él no se puede ver ni descargar nada.

No hace falta ffmpeg: el servidor no transcodifica (el vídeo va del CDN de RD al navegador).

👉 **Configuración completa, API keys y despliegue gratuito (GitHub Pages + Render/Fly.io): ver [`SETUP.md`](./SETUP.md).**

## Tests

```bash
npm test    # búsqueda, idioma, caché, auth, perfiles, progreso, Real-Debrid y descargas RD (sin red)
```

## Arquitectura

```
server.js            Express: auth, API REST, proxy de búsqueda y catálogos, Real-Debrid
lib/
  auth.js            Usuarios (scrypt) + sesiones firmadas (HMAC) + middleware + login externo
  firebaseAuth.js    Verificación de ID tokens de Firebase (RS256, sin dependencias)
  userdata.js        Perfiles + favoritos + progreso (por título) + ajustes + cuenta (token RD)
  realdebrid.js      Cliente Real-Debrid (magnet -> stream HTTPS directo)
  rddownloads.js     Descargas a disco de enlaces Real-Debrid (progreso + reanudación)
  search.js          Motores Torrentio + Peerflix, idioma, helpers, dedupe, caché
  catalog.js         Catálogos de streaming (TMDB) + recomendaciones + tráiler + last episode
public/
  index.html · app.js · style.css · config.js
test/run.mjs         Tests sin dependencias externas
Dockerfile           Imagen Node lista para desplegar
android-standalone/  App Android / Android TV nativa (ExoPlayer + Chromecast)
```

El **progreso se indexa por título** (`movie:<tmdb>` / `series:<tmdb>:<temporada>:<episodio>`), el mismo esquema que usa `WatchStore.kt` de la app Android, para que "continuar viendo" se sincronice entre la web y el móvil.

## API (resumen)

| Método | Ruta | Auth | Descripción |
|--------|------|:----:|-------------|
| `POST` | `/api/auth/register` `/login` `/logout` `/firebase` | – | Registro / login / logout / login con Google |
| `GET`  | `/api/auth/me` | – | Usuario actual |
| `GET`  | `/api/config` | – | Capacidades (search, catalogs, firebase) |
| `GET`  | `/api/search?query&type&source&season&episode` | ✅ | Búsqueda de fuentes (Torrentio/Peerflix/all) |
| `GET`  | `/api/catalogs?type` · `/api/discover` · `/api/genres` · `/api/recommendations` | ✅ | Catálogos TMDB |
| `GET`  | `/api/title/:type/:id` · `/api/title/series/:id/season/:n` | ✅ | Ficha y episodios |
| `GET`  | `/api/rd/status` · `PUT`/`DELETE` `/api/rd/token` | ✅ | Estado y token de Real-Debrid |
| `POST` | `/api/rd/stream` | ✅ | magnet → URL HTTPS directa reproducible |
| `POST` | `/api/rd/download` · `GET /api/rd/downloads` | ✅ | Descargar con RD a disco / listar |
| `GET`  | `/rd-file/:id` | ✅ | Reproducir un archivo ya descargado (Range) |
| `GET`/`PUT` | `/api/settings/server` | ✅ | Carpeta de descargas |
| `GET`  | `/api/me/state` · perfiles · favoritos · progreso · ajustes | ✅ | Estado por usuario y perfil |

> Uso legítimo: accede solo a contenido para el que tengas derechos.
