# 📱 TorrentBox — app Android (modo Real-Debrid)

App Android **nativa e independiente** para buscar películas y series y verlas
por **streaming directo desde Real-Debrid**, sin depender de ningún PC ni
servidor propio. Funciona en **móvil y en Android TV**.

> ⚡ **Requiere una cuenta de Real-Debrid.** La app **no lleva motor BitTorrent**:
> no descarga por torrent ni se conecta a ningún peer. Lo único que hace con un
> magnet es entregárselo a Real-Debrid, que lo resuelve en sus servidores y
> devuelve una URL HTTPS normal. Sin token de RD configurado no se puede ver ni
> descargar nada.

## Funciones

- **Interfaz al estilo de la web**: pestañas Descubrir / Buscar / Descargas /
  Ajustes, catálogos con carátulas (Netflix, Prime, HBO Max, Disney+ vía TMDB) y
  ficha de detalle con sinopsis, tráiler y fuentes.
- ▶️ **Ver**: streaming directo desde los servidores de Real-Debrid. El vídeo va
  del CDN de RD al reproductor; no se descarga nada en el móvil.
- ⬇️ **Descargar**: la encola en el **DownloadManager del sistema**, así que
  continúa **aunque cierres la app** y queda disponible sin conexión.
- Reproductor **ExoPlayer (Media3)**: subtítulos, pistas de audio, velocidad,
  gestos de volumen/brillo, siguiente episodio, Chromecast y continuar viendo.
- Buscador con **dos motores**: Torrentio (banderas de idioma) y apibay/TPB, con
  **filtros de calidad** (4K/1080p/720p/SD) y orden por idioma preferido.
- **Perfiles** (crear/editar/borrar) con **modo infantil** (solo catálogos
  familiares), sincronizados con tu cuenta de Google.
- 🔔 Avisos de episodios nuevos de tus series favoritas y auto-actualización.

## Nada en segundo plano

Al no haber motor de torrents, la app **no tiene servicio en primer plano, ni
`WAKE_LOCK`, ni notificación permanente**. Cuando la cierras, se cierra: cero
CPU y cero batería. Las descargas siguen porque las lleva el sistema, no la app.

Permisos que pide: `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`
(progreso de las descargas) y `REQUEST_INSTALL_PACKAGES` (auto-actualización).

## Android TV

El manifiesto ya declara `LEANBACK_LAUNCHER`, banner y `leanback`/`touchscreen`
como no obligatorios, así que **el mismo APK se instala en una Android TV o
Google TV y aparece en su launcher**. Reproduciendo en la propia TV no hace
falta castear nada, y ExoPlayer se encarga de los MKV con DTS/AC3 que un
Chromecast no acepta.

Desde el móvil también puedes **castear** con el botón de Chromecast del
reproductor: como la URL de RD es HTTPS pública, la TV la descarga ella misma
directamente de RD (el vídeo no pasa por el móvil).

## Configuración

1. **Ajustes → Real-Debrid** → pega tu token de <https://real-debrid.com/apitoken>.
   Si entras con Google, el token se sincroniza con tus otros dispositivos.
2. **Ajustes → Cuenta → Entrar con Google** (opcional) para perfiles, favoritos
   e historial compartidos.

## Sincronización con tu cuenta de Google

La app sincroniza perfiles, favoritos, historial y el token de Real-Debrid vía
Firebase (Auth + Firestore). Google exige registrar la **huella SHA-1** de la
app en tu proyecto de Firebase:

1. Firebase Console → tu proyecto → **Configuración → Tus apps → Añadir app →
   Android**. Nombre de paquete: `com.carbaxo.torrentbox`.
2. En **SHA-1** pega:
   `8C:82:4C:DD:BA:A8:DE:9D:96:71:AE:00:49:70:C6:33:F1:F1:26:49`
3. Descarga el `google-services.json` y el **ID de cliente web** (OAuth 2.0),
   y pégalo en `gradle.properties` → `GOOGLE_WEB_CLIENT_ID`.

La app se firma con `torrentbox.keystore` (incluido) para que la SHA-1 sea
siempre la misma.

## Cómo conseguir el APK

No necesitas compilar nada: **GitHub Actions lo compila y lo publica**.

1. En el repo, pestaña **Actions → "Build Android APK" → Run workflow** (o se
   lanza solo al hacer push a `android-standalone/`).
2. Cuando termine (verde), descarga el APK desde:
   - la **Release** `android-latest` (`TorrentBox.apk`), o
   - los **Artifacts** de la ejecución del workflow.
3. En el móvil o la TV: abre el APK, permite **"Instalar apps desconocidas"** e
   instálalo.

## Compilar en local (opcional, requiere Android Studio o el SDK)

```bash
cd android-standalone
./gradlew assembleDebug
# APK en app/build/outputs/apk/debug/app-debug.apk
```

## Notas

- Requiere Android 7.0 (API 24) o superior.
- Las descargas van a la carpeta privada de la app
  (`Android/data/com.carbaxo.torrentbox/files/Movies`).
- Formatos: ExoPlayer reproduce mp4/webm y la mayoría de mkv/avi según los
  códecs del dispositivo. Al castear, la limitación es el Chromecast: MKV y DTS
  no están soportados por Google Cast, así que para ese contenido conviene
  reproducir en la propia Android TV.
- Si RD aún no tiene el torrent cacheado, lo descarga primero en sus servidores:
  la app avisa del progreso y basta con volver a pulsar en un momento.
- Uso legítimo: descarga solo contenido para el que tengas derechos.
