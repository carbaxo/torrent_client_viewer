# 🎬 Torrent Client Viewer

App web para **descargar torrents** (por enlace *magnet* o archivo `.torrent`) a tu disco local y **reproducir el vídeo en streaming mientras se descarga**.

Usa [WebTorrent](https://webtorrent.io/) en un backend Node.js que se conecta a la red BitTorrent (TCP/uTP/DHT), guarda los archivos en disco y sirve el vídeo por HTTP con soporte de *range requests*, de modo que el `<video>` del navegador puede reproducir y adelantar mientras aún está bajando.

## Requisitos

- Node.js 18 o superior.

## Instalación

```bash
npm install
```

## Uso

```bash
npm start
```

Abre <http://localhost:3000> en el navegador.

- **Añadir magnet:** pega un enlace `magnet:?xt=...` y pulsa *Añadir magnet*.
- **Subir .torrent:** selecciona un archivo `.torrent` y pulsa *Subir .torrent*.
- Cada torrent muestra el progreso, velocidad, peers y ETA en tiempo real.
- Pulsa **▶ Ver** en cualquier archivo de vídeo para reproducirlo en streaming mientras se descarga.

Los archivos se guardan en la carpeta `downloads/` (configurable con la variable de entorno `DOWNLOAD_DIR`).

## Configuración

| Variable        | Por defecto        | Descripción                          |
|-----------------|--------------------|--------------------------------------|
| `PORT`          | `3000`             | Puerto del servidor web              |
| `DOWNLOAD_DIR`  | `./downloads`      | Carpeta donde se guardan los archivos|

## Notas

- El navegador reproduce de forma nativa `mp4`, `webm` y `ogg`. Formatos como `mkv` o `avi` se descargan igualmente, pero puede que no se reproduzcan directamente en el `<video>` (necesitarías transcodificar, p. ej. con ffmpeg, o abrirlos con un reproductor externo como VLC).
- Al adelantar en el reproductor, WebTorrent prioriza las piezas necesarias para esa posición.
- Descarga solo contenido para el que tengas derechos. Esta herramienta es para uso legítimo.

## Arquitectura

```
server.js         Backend Express + WebTorrent (API REST + streaming con Range)
public/
  index.html      Interfaz
  app.js          Lógica de cliente (añadir, listar, progreso, reproductor)
  style.css       Estilos
```

### API

| Método   | Ruta                              | Descripción                              |
|----------|-----------------------------------|------------------------------------------|
| `POST`   | `/api/torrents`                   | Añade un torrent por magnet (`{magnet}`) |
| `POST`   | `/api/torrents/upload`            | Añade un torrent subiendo un `.torrent`  |
| `GET`    | `/api/torrents`                   | Lista todos los torrents con progreso    |
| `GET`    | `/api/torrents/:infoHash`         | Detalle de un torrent                    |
| `DELETE` | `/api/torrents/:infoHash?files=`  | Elimina un torrent (`files=true` borra el disco) |
| `GET`    | `/stream/:infoHash/:fileIndex`    | Sirve el archivo con soporte de Range    |
