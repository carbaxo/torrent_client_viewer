# 🎬 Torrent Client Viewer

App web multiusuario para **buscar, descargar y reproducir vídeo en streaming mientras se descarga**, con catálogos de plataformas y perfiles independientes.

## Funciones

- 🔐 **Login por usuario/contraseña** (scrypt + sesión firmada) o **con Google** (Firebase). Cada usuario tiene su **biblioteca privada**.
- 👨‍👩‍👧 **Perfiles por cuenta** (hasta 5), con **modo infantil** (solo catálogos familiares, sin buscar/añadir/borrar).
- ☁️ **Sincronización** de favoritos, historial y ajustes entre dispositivos (Firestore) al entrar con Google.
- ❤️ **Favoritos** con su propia pestaña, ⏯️ **continuar viendo** (reanuda donde lo dejaste), ✓ **vistos** automáticos y 🎯 **recomendaciones** TMDB según lo que ves.
- ▶️ **Ver** (descarga a un buffer temporal que se borra al terminar de verlo) o ⬇️ **Descargar** (permanente); carpetas configurables en Ajustes.
- ⚡ **Real-Debrid opcional por cuenta**: streaming HTTPS directo y **descarga a disco** desde sus servidores con tu token privado (el token se sincroniza entre el PC, la web y el móvil).
- 🍿 **Catálogos de streaming** (TMDB): Netflix, Prime Video, HBO Max, Disney+ con pósters (idioma configurable); clic en un título → busca torrents. Ficha con **tráiler** de YouTube.
- 📺 **TV (AceStream)**: canales y eventos P2P (search-ace.stream) con detección del engine local; se abren en la app AceStream instalada.
- 🔎 **Buscador con 2 motores**: **Torrentio** (IMDb vía OMDb **o TMDB**) y **Peerflix** (apibay/TPB). Selector *Torrentio | Peerflix | Todos*.
  - Filtros de calidad (4K/1080p/720p/SD), **idioma de la fuente** (banderas) con orden por tu preferencia, selección de **temporada/episodio** para series, badges de fuente y seeders.
  - Botones **Descargar y ver**, **Copiar Magnet** y **Abrir en Peerflix** (`peerflix://`).
- ⏭️ **Siguiente episodio** automático en el reproductor y 🔔 **avisos de episodios nuevos** de tus series favoritas.
- ⬇️ **Descarga** por magnet, `.torrent`, o desde un resultado de búsqueda (WebTorrent, red BitTorrent real).
- ▶️ **Streaming mientras se descarga** (HTTP *Range*), con **⚙ transcodificación ffmpeg** para formatos no nativos (mkv/avi).
- 💬 **Subtítulos**: ficheros `.srt/.vtt` incluidos en el torrent (convertidos a WebVTT) y **pistas embebidas** en mkv (extraídas con ffmpeg).
- 💾 **Persistencia**: los torrents se reanudan al reiniciar (verifican el archivo en disco sin depender de peers).
- ⏸️ Pausar/reanudar, estadísticas en tiempo real.
- 📱 **Responsive** (móvil y escritorio), tema oscuro.

## Puesta en marcha rápida

```bash
npm install
cp .env.example .env      # y rellena OMDB_API_KEY y TMDB_API_KEY dentro
npm start                 # http://localhost:3000
```

Instala **ffmpeg** para transcodificación/subtítulos embebidos (o usa el `Dockerfile`, que lo incluye).

👉 **Configuración completa, API keys y despliegue gratuito (GitHub Pages + Render/Fly.io): ver [`SETUP.md`](./SETUP.md).**

## Tests

```bash
npm test    # 53 asserts: búsqueda, idioma, caché, persistencia, auth, Real-Debrid, descargas RD y AceStream (sin red)
```

## Arquitectura

```
server.js            Express: auth, API REST, streaming (Range), transcodificación, subtítulos, persistencia
lib/
  auth.js            Usuarios (scrypt) + sesiones firmadas (HMAC) + middleware + login externo
  firebaseAuth.js    Verificación de ID tokens de Firebase (RS256, sin dependencias)
  userdata.js        Perfiles + favoritos + progreso + ajustes + cuenta (token RD)
  realdebrid.js      Cliente Real-Debrid (magnet -> stream HTTPS directo)
  rddownloads.js     Descargas a disco de enlaces Real-Debrid (progreso + reanudación)
  acestream.js       TV P2P: playlist/búsqueda de canales + engine local (AceStream)
  search.js          Motores Torrentio + Peerflix, idioma, helpers, dedupe, caché
  catalog.js         Catálogos de streaming (TMDB) + recomendaciones + tráiler + last episode
  subtitles.js       SRT→VTT y extracción de subtítulos embebidos (ffmpeg/ffprobe)
  store.js           Persistencia de torrents + propietarios por usuario
  transcode.js       Transcodificación en vivo con ffmpeg (H.264/AAC yuv420p)
public/
  index.html · app.js · style.css · config.js
test/run.mjs         Tests sin dependencias externas
Dockerfile           Imagen con Node + ffmpeg lista para desplegar
```

## API (resumen)

| Método | Ruta | Auth | Descripción |
|--------|------|:----:|-------------|
| `POST` | `/api/auth/register` `/login` `/logout` | – | Registro / login / logout |
| `GET`  | `/api/auth/me` | – | Usuario actual |
| `GET`  | `/api/config` | – | Capacidades (ffmpeg, search, catalogs) |
| `GET`  | `/api/search?query&type&source&season&episode` | ✅ | Búsqueda (Torrentio/Peerflix/all) |
| `GET`  | `/api/catalogs?type` | ✅ | Catálogos de streaming (TMDB) |
| `POST` | `/api/torrents` · `/api/torrents/upload` | ✅ | Añadir por magnet / `.torrent` |
| `GET`  | `/api/torrents` | ✅ | Mi biblioteca |
| `POST` | `/api/torrents/:h/pause` · `/resume` | ✅ | Pausar / reanudar |
| `DELETE`| `/api/torrents/:h?files=` | ✅ | Quitar de mi biblioteca (y opcional del disco) |
| `POST` | `/api/rd/download` · `GET /api/rd/downloads` | ✅ | Descargar con RD a disco / listar |
| `GET`  | `/api/tv/channels` · `/search` · `/engine` · `/resolve` | ✅ | TV AceStream |
| `GET`  | `/stream/:h/:i` | ✅ | Streaming con Range |
| `GET`  | `/transcode/:h/:i` | ✅ | Transcodificar a MP4 en vivo |
| `GET`  | `/api/torrents/:h/:i/subinfo` | ✅ | Pistas de subtítulos embebidas |
| `GET`  | `/subtitle/:h/:i` · `/subtitle-embedded/:h/:i/:track` | ✅ | Subtítulos en WebVTT |

> Uso legítimo: descarga solo contenido para el que tengas derechos.
