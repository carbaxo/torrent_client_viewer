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
- ➕ **Añadir un magnet o un enlace a mano** (pestaña Descargas): se pega el
  magnet y Real-Debrid lo baja a sus servidores; debajo se ve **la lista de la
  cuenta de RD con su estado real** (leyendo el magnet, en cola, descargando
  con su %, listo, sin semillas…) y botones Ver / Descargar / quitar. Es la
  salida a los dos fallos que no dependen de la app: que RD **aún no tenga el
  torrent cacheado** o que **el archivo se haya borrado** de su caché. También
  se puede pulsar un magnet **en el navegador** o **compartirlo** con TorrentBox,
  y cuando un enlace de la ficha falla, el propio aviso ofrece
  **«Añadirlo a Real-Debrid»**. Un enlace de hoster (1fichier, Mega…) se
  desbloquea y se descarga igual que en el "Descargador" de la web de RD.
- Reproductor **ExoPlayer (Media3)**: subtítulos, pistas de audio, velocidad,
  gestos de volumen/brillo, siguiente episodio y continuar viendo.
- 🎬 **Reproductor externo opcional** (Ajustes → Reproducción): el de la app,
  **siempre VLC** (va directo, sin preguntar), *preguntar cada vez* u **otra
  app** con el diálogo "abrir con…". VLC y MX Player manejan mejor los MKV con
  audio DTS/TrueHD. Vale igual para el streaming de RD y para un fichero ya
  descargado. A cambio se pierden "continuar viendo" y el siguiente episodio
  automático, que son del reproductor propio.
- 📺 **Emitir a la TV con VLC** (Ajustes → Reproducción, opcional): con una TV
  elegida arriba, "Ver" abre el vídeo en VLC para que sea **VLC quien emita**.
  VLC transcodifica en el móvil, así que se traga cualquier MKV con Dolby o DTS
  que el Chromecast rechaza. Dos peajes: el vídeo pasa por el teléfono (que
  tiene que quedarse encendido y en la misma WiFi) y **el último paso lo da el
  usuario** pulsando el icono de emitir dentro de VLC — Android no permite
  elegirle el dispositivo desde fuera. Al usarlo, la app cierra su propia sesión
  de Chromecast para no pelearse con VLC por la TV.
- 📺 **Chromecast**: se elige la TV en la barra superior (antes de abrir nada) y
  luego cada título va a esa TV; envía la versión con **audio AAC** convertida
  por Real-Debrid para que suene (el Chromecast no decodifica Dolby/DTS).
- Buscador con **dos motores**, los mismos addons que usa Stremio, con un chip
  por motor en cada ficha y los enlaces **ordenados Peerflix → Torrentio**:
  - **Peerflix** (Dontorrent, MejorTorrent, Wolfmax4k, Popcorntime, Bitsearch):
    es donde están las versiones en español. Se puede apuntar a tu propia URL de
    `config.peerflix.mov` desde **Ajustes → Buscadores**.
  - **Torrentio** con todos los proveedores (incluidos MejorTorrent, Wolfmax4k y
    Cinecalidad), no solo los de por defecto.
  Los dos buscan **por IMDb id**, así que en series hay que elegir episodio (no
  hay búsqueda de "temporada completa": los packs salen entre los resultados del
  episodio). Los enlaces se cargan solos al abrir la ficha.
- **Filtros de calidad** (4K/1080p/720p/480p/SD) con el número de enlaces de cada
  una, y un chip **"Otras"** para los que no se puede identificar: así ninguno
  queda escondido. La detección entiende también las formas de las webs españolas
  (`[MicroHD][1080 px]`, `1920x1080`), que antes caían en "desconocida".
- **Tamaño de cada enlace** tomado del dato exacto del addon
  (`behaviorHints.videoSize`) y, si no lo trae, del texto (`💾 4.38 GB`). Si el
  addon no lo da, simplemente no se muestra.
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

## Emitir a Chromecast (al estilo de HBO)

El botón de emitir está en la **barra superior**, en todas las pestañas: eliges
la TV **antes** de abrir nada y desde ese momento cada título que pulsas se
manda a esa TV, pudiendo **cambiar de película sin reconectar**. Hay una barra
"emitiendo" y un **mando** propio (play/pausa, ±10 s, barra de progreso, parar,
desconectar), y el "continuar viendo" se guarda igual que en el móvil.

**El sonido**: el receptor de Google Cast solo decodifica AAC/MP3/Opus/Vorbis/
FLAC, y casi todas las releases traen **Dolby AC3/EAC3 o DTS** → se ve la imagen
pero no se oye nada. Por eso la app no envía el archivo original si puede
evitarlo: pide a Real-Debrid su versión **transcodificada a H.264 + AAC** y va
probando en cadena, pasando al siguiente candidato si la TV falla:

1. **HLS de Real-Debrid** (m3u8, H.264 + AAC) — se comprueba por HTTP antes de enviarlo
2. **MP4 convertido por RD** (audio AAC)
3. **WebM convertido por RD** (audio AAC)
4. Archivo original anunciado como `video/mp4` (el receptor detecta el formato)
5. Archivo original con su tipo exacto

Si RD no ofrece versión convertida, se avisa en pantalla de que puede quedarse
sin sonido y de que conviene elegir otra fuente. El vídeo lo descarga la TV
directamente de RD: no pasa por el móvil.

> Detalle de implementación: se usa el **Default Media Receiver** `CC1AD845`
> (ver `CastOptionsProvider.kt`). El receptor con DRM que trae media3 por defecto
> rechaza los archivos cuyo tipo no reconoce y la TV se queda en negro.

## En una Smart TV (mando, sin pantalla táctil)

El **mismo APK** sirve para móvil y tele: al arrancar detecta si está en una
Android TV / Google TV y cambia la interfaz. En una tele lo único que te sitúa
es el foco, así que:

- **Anillo blanco grueso + zoom** en lo que tienes seleccionado, en todo lo
  pulsable (carátulas, episodios, chips, botones de cada enlace, descargas…).
- **Barra de secciones a la izquierda** al estilo de Stremio para TV. La sección
  **activa** va en morado; la **enfocada**, con el anillo: una dice dónde estás y
  la otra qué vas a pulsar.
- **El foco arranca colocado**: en la sección activa, en «Volver» al abrir una
  ficha y en play/pausa en el mando de Chromecast.
- **Botón ATRÁS del mando**: sale de la ficha, de la rejilla y del mando, y de
  cualquier pestaña vuelve a Descubrir (antes cerraba la app).
- Carátulas más grandes (165 dp) y margen de **overscan** para los bordes que
  recortan las teles.

## Configuración

1. **Ajustes → Real-Debrid** → pega tu token de <https://real-debrid.com/apitoken>.
   Con la sesión iniciada, el token se sincroniza con tus otros dispositivos.
2. **Ajustes → Cuenta** (opcional) para perfiles, favoritos e historial
   compartidos. Dos formas de entrar, las dos válidas:
   - **Email y contraseña**: crear cuenta o entrar directamente, sin Google.
     Requiere activar el método en **Firebase Console → Authentication →
     Sign-in method → Email/Password**; si no está activado, la app lo dice.
   - **Entrar con Google**: necesita además el `GOOGLE_WEB_CLIENT_ID` y la SHA-1
     registrada (ver más abajo).

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
  códecs del dispositivo. Al emitir, el Chromecast no admite MKV ni audio
  Dolby/DTS: la app lo resuelve enviando la versión convertida de Real-Debrid
  (ver "Emitir a Chromecast"). Si aun así falla, reproducir en la propia
  Android TV siempre funciona.
- Si RD aún no tiene el torrent cacheado, lo descarga primero en sus servidores:
  la app avisa del progreso y basta con volver a pulsar en un momento.
- Uso legítimo: descarga solo contenido para el que tengas derechos.
