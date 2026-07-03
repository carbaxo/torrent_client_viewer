# 🎬 Torrent Client Viewer

App web para **buscar, descargar y reproducir vídeo en streaming mientras se descarga**.

- 🔎 **Buscador** de películas y series por nombre (OMDb → IMDb ID → [Torrentio](https://torrentio.strem.fun/)).
- ⬇️ **Descarga** por enlace *magnet*, por archivo `.torrent`, o directamente desde un resultado de búsqueda.
- ▶️ **Reproducción en streaming** mientras se descarga (HTTP *Range requests*).
- ⚙️ **Transcodificación en vivo con ffmpeg** para formatos no nativos del navegador (mkv, avi…).
- 💾 **Persistencia**: los torrents se reanudan automáticamente al reiniciar el servidor.
- ⏸️ **Pausar/reanudar** descargas y estadísticas en tiempo real.
- 📱 **Diseño responsive** (móvil y escritorio) con tema oscuro.

Usa [WebTorrent](https://webtorrent.io/) en un backend Node.js que se conecta a la red BitTorrent (TCP/uTP/DHT) y guarda los archivos en disco.

## Requisitos

- **Node.js 18+**.
- **ffmpeg** (opcional, solo para transcodificar formatos no nativos). Si no está instalado, todo lo demás funciona igual.
- Una **API key gratuita de OMDb** para el buscador: <https://www.omdbapi.com/apikey.aspx>.

## Instalación

```bash
npm install          # instala express, cors, multer y webtorrent
```

> En Node 18+ no hace falta `node-fetch`: se usa el `fetch` nativo. `cors` se incluye para permitir consumir la API desde otros orígenes.

Instalar ffmpeg (para transcodificación):

```bash
# Debian/Ubuntu
sudo apt-get install ffmpeg
# macOS
brew install ffmpeg
# Windows: https://ffmpeg.org/download.html
```

## Uso

```bash
export OMDB_API_KEY=tu_api_key_de_omdb   # necesaria para el buscador
npm start
```

Abre <http://localhost:3000>.

- **Buscar:** escribe un título, elige *Película/Serie* y pulsa *Buscar*. En cada resultado puedes **Descargar aquí** (lo añade a la app) o **Abrir Magnet**.
- **Añadir manual:** pega un magnet o sube un `.torrent`.
- **Reproducir:** pulsa **▶ Ver** en un archivo de vídeo. Si el formato no es nativo del navegador y hay ffmpeg, aparece **⚙ Convertir** para transcodificar en vivo.

## Configuración (variables de entorno)

| Variable        | Por defecto     | Descripción                                            |
|-----------------|-----------------|--------------------------------------------------------|
| `PORT`          | `3000`          | Puerto del servidor web                                |
| `OMDB_API_KEY`  | *(vacío)*       | API key de OMDb; sin ella el buscador queda desactivado |
| `DOWNLOAD_DIR`  | `./downloads`   | Carpeta donde se guardan los archivos                  |
| `DATA_DIR`      | `./data`        | Estado de persistencia (torrents guardados)            |
| `FFMPEG_PATH`   | `ffmpeg`        | Ruta al binario de ffmpeg                              |

Las API keys **solo viven en el servidor** (variables de entorno); el frontend nunca las ve.

## Tests

```bash
npm test    # tests de los helpers de búsqueda, caché y persistencia (sin red)
```

## Notas

- **Códecs del navegador:** `mp4/webm/ogg` se reproducen de forma nativa. Para `mkv`/`avi` usa **⚙ Convertir** (ffmpeg → H.264/AAC en `yuv420p`, compatible con todos los navegadores). La transcodificación en vivo no permite adelantar.
- **Rate limiting:** el buscador limita a 10 peticiones/minuto por IP.
- **Caché:** los resultados de búsqueda se cachean en memoria 1 hora.
- Descarga solo contenido para el que tengas derechos. Esta herramienta es para uso legítimo.

## Arquitectura

```
server.js            Backend Express: API REST, streaming (Range), transcodificación y persistencia
lib/
  search.js          Nombre -> IMDb (OMDb) -> streams (Torrentio); helpers + caché
  store.js           Persistencia de torrents en data/torrents.json
  transcode.js       Transcodificación en vivo con ffmpeg
public/
  index.html         Interfaz (buscador + descargas + reproductor)
  app.js             Lógica de cliente
  style.css          Estilos y diseño responsive
test/
  run.mjs            Tests sin dependencias externas
```

### API

| Método   | Ruta                               | Descripción                                        |
|----------|------------------------------------|----------------------------------------------------|
| `GET`    | `/api/config`                      | Capacidades del servidor (ffmpeg, buscador)        |
| `GET`    | `/api/search?query=&type=`         | Busca torrents (`type`: `movie`\|`series`)          |
| `POST`   | `/api/torrents`                    | Añade un torrent por magnet (`{magnet}`)           |
| `POST`   | `/api/torrents/upload`             | Añade un torrent subiendo un `.torrent`            |
| `GET`    | `/api/torrents`                    | Lista todos los torrents con progreso              |
| `GET`    | `/api/torrents/:infoHash`          | Detalle de un torrent                              |
| `POST`   | `/api/torrents/:infoHash/pause`    | Pausa un torrent                                   |
| `POST`   | `/api/torrents/:infoHash/resume`   | Reanuda un torrent                                 |
| `DELETE` | `/api/torrents/:infoHash?files=`   | Elimina un torrent (`files=true` borra del disco)  |
| `GET`    | `/stream/:infoHash/:fileIndex`     | Sirve el archivo con soporte de Range              |
| `GET`    | `/transcode/:infoHash/:fileIndex`  | Transcodifica el archivo a MP4 en vivo (ffmpeg)    |

### Respuesta de `/api/search`

```json
{
  "success": true,
  "imdbId": "tt1375666",
  "title": "Inception",
  "type": "movie",
  "streams": [
    {
      "name": "Torrentio",
      "title": "1080p - 2.1 GB - 150 seeders",
      "filename": "Inception.2010.1080p.BluRay.x264",
      "url": "magnet:?xt=urn:btih:...",
      "infoHash": "...",
      "quality": "1080p",
      "size": "2.1 GB",
      "seeders": 150
    }
  ]
}
```
