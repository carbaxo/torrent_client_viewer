# 🎬 Torrent Client Viewer

App web multiusuario para **buscar, descargar y reproducir vídeo en streaming mientras se descarga**, con catálogos de plataformas y perfiles independientes.

## Funciones

- 🔐 **Login por usuario/contraseña** (scrypt + sesión firmada). Cada usuario tiene su **biblioteca privada**.
- 🍿 **Catálogos de streaming** (TMDB): Netflix, Prime Video, HBO Max, Disney+ con pósters; clic en un título → busca torrents.
- 🔎 **Buscador con 2 motores**: **Torrentio** (vía OMDb → IMDb) y **Peerflix** (apibay/TPB). Selector *Torrentio | Peerflix | Todos*.
  - Filtros de calidad (4K/1080p/720p/SD), selección de **temporada/episodio** para series, badges de fuente y seeders.
  - Botones **Descargar y ver**, **Copiar Magnet** y **Abrir en Peerflix** (`peerflix://`).
- ⬇️ **Descarga** por magnet, `.torrent`, o desde un resultado de búsqueda (WebTorrent, red BitTorrent real).
- ▶️ **Streaming mientras se descarga** (HTTP *Range*), con **⚙ transcodificación ffmpeg** para formatos no nativos (mkv/avi).
- 💬 **Subtítulos**: ficheros `.srt/.vtt` incluidos en el torrent (convertidos a WebVTT) y **pistas embebidas** en mkv (extraídas con ffmpeg).
- 💾 **Persistencia**: los torrents se reanudan al reiniciar (verifican el archivo en disco sin depender de peers).
- ⏸️ Pausar/reanudar, estadísticas en tiempo real.
- 📱 **Responsive** (móvil y escritorio), tema oscuro.

## Puesta en marcha rápida

```bash
npm install
export OMDB_API_KEY=...   # buscador (https://www.omdbapi.com/apikey.aspx)
export TMDB_API_KEY=...   # catálogos (https://www.themoviedb.org/settings/api)
npm start                 # http://localhost:3000
```

Instala **ffmpeg** para transcodificación/subtítulos embebidos (o usa el `Dockerfile`, que lo incluye).

👉 **Configuración completa, API keys y despliegue gratuito (GitHub Pages + Render/Fly.io): ver [`SETUP.md`](./SETUP.md).**

## Tests

```bash
npm test    # 32 asserts: motores de búsqueda, caché, persistencia, propietarios y auth (sin red)
```

## Arquitectura

```
server.js            Express: auth, API REST, streaming (Range), transcodificación, subtítulos, persistencia
lib/
  auth.js            Usuarios (scrypt) + sesiones firmadas (HMAC) + middleware
  search.js          Motores Torrentio + Peerflix, helpers, dedupe, caché
  catalog.js         Catálogos de streaming (TMDB discover + watch providers)
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
| `GET`  | `/stream/:h/:i` | ✅ | Streaming con Range |
| `GET`  | `/transcode/:h/:i` | ✅ | Transcodificar a MP4 en vivo |
| `GET`  | `/api/torrents/:h/:i/subinfo` | ✅ | Pistas de subtítulos embebidas |
| `GET`  | `/subtitle/:h/:i` · `/subtitle-embedded/:h/:i/:track` | ✅ | Subtítulos en WebVTT |

> Uso legítimo: descarga solo contenido para el que tengas derechos.
