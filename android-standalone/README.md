# 📱 VizPlay — app Android (modo Real-Debrid)

App Android **nativa e independiente** para buscar películas y series y verlas
por **streaming directo desde Real-Debrid**, sin depender de ningún PC ni
servidor propio. Funciona en **móvil y en Android TV**.

> ⚡ **Requiere una cuenta de Real-Debrid.** La app **no lleva motor BitTorrent**:
> no descarga por torrent ni se conecta a ningún peer. Lo único que hace con un
> magnet es entregárselo a Real-Debrid, que lo resuelve en sus servidores y
> devuelve una URL HTTPS normal. Sin token de RD configurado no se puede ver ni
> descargar nada.

## Aspecto

Interfaz al estilo de la app de **Netflix**: fondo casi negro (`#141414`), texto
blanco con gris claro (`#B3B3B3`) para lo secundario, **rojo `#E50914` reservado**
para lo que debe llamar la atención, **botones rectos** (4 dp) con el principal
**blanco y texto negro**, títulos **gruesos y con la letra apretada**, carátulas
casi rectas y la sección activa marcada en **blanco**, no en color. El logotipo es
**VIZ** en blanco + **PLAY** en granate (`#C41E3A`).

> La tipografía de Netflix ("Netflix Sans") es **privativa** y no se puede
> licenciar, así que va **Inter** empaquetada en el APK (+2,4 MB): mismo carácter
> geométrico y mejor legibilidad a tamaño pequeño. En la tele toda la escala
> tipográfica crece un 15 %, porque lo que se lee a 30 cm no se lee a 3 m.

## Funciones

- **Interfaz al estilo de la web**: pestañas Descubrir / Buscar / Descargas /
  Ajustes, catálogos con carátulas (Netflix, Prime, HBO Max, Disney+ vía TMDB) y
  ficha de detalle con sinopsis, tráiler y fuentes.
- El selector **Películas / Series** de Descubrir manda en TODA la pantalla:
  «Continuar viendo», «Mi lista» y «Recomendado para ti» muestran solo lo del
  tipo elegido. Las recomendaciones se recalculan al cambiar, sembrando solo con
  títulos de ese tipo.
- ▶️ **Ver**: streaming directo desde los servidores de Real-Debrid. El vídeo va
  del CDN de RD al reproductor; no se descarga nada en el móvil.
- ⬇️ **Descargar** con **pausa y continuación**: gestor propio (WorkManager en
  primer plano) que sigue **aunque cierres la app** y sobrevive a reiniciar el
  móvil. Continuar no reempieza: se pide el fichero **desde el byte que ya hay en
  disco** (HTTP `Range`). Y si el enlace de Real-Debrid ha caducado a mitad —van
  atados a tu IP y expiran—, **se pide otro con el magnet guardado** y sigue
  desde donde iba. Eso es lo que antes se veía como "se para a mitad": lo hacía
  el DownloadManager de Android, que no sabe pausar ni renovar un enlace muerto.
- 🈯 **Los errores de Real-Debrid, en castellano y con la salida** (`errorEs`): la
  API los manda con nombre de variable (`infringing_file`) y así no dicen ni qué
  pasa ni qué hacer. El que más sale es ese: Real-Debrid tiene una lista de
  torrents bloqueados por avisos de copyright, y el bloqueo va **por torrent
  concreto y no por título**, así que la salida es probar otra versión del mismo
  capítulo — y el mensaje ahora lo dice. Se traducen también los demás códigos que
  salen de verdad (tráfico agotado, demasiadas descargas, IP no permitida, torrent
  demasiado grande…), y los que no estén en la lista siguen saliendo tal cual en
  vez de perderse.
- 📊 **Estado de tu cuenta de Real-Debrid** (Ajustes → Real-Debrid): días de
  premium que quedan y fecha de caducidad, puntos de fidelidad y **huecos de
  torrent usados/total**, leídos de la propia API (`/user` y
  `/torrents/activeCount`). Los huecos salen también en Descargas, que es donde
  se liberan: al llenarse, Real-Debrid rechaza los magnets nuevos, y ese es el
  fallo más desconcertante de todos porque no parece tener motivo.
- 📂 **Carpeta de descargas elegible** (Ajustes → Descargas): por defecto van a la
  carpeta privada de la app, que no ve la galería y **se borra al desinstalar**.
  Con «Elegir carpeta…» se puede apuntar a Descargas, Películas o la **tarjeta
  SD**, y ahí quedan visibles para otras apps y sobreviven a desinstalar. Se usa
  el selector del sistema (SAF) con permiso permanente, porque desde Android 10
  una app no puede escribir por ruta fuera de lo suyo. Si la carpeta deja de
  estar disponible (tarjeta fuera, permiso revocado), la descarga **cae a la
  carpeta de la app en vez de fallar**.
- ➕ **Añadir un magnet a mano** (pestaña Descargas): se pega el magnet y
  Real-Debrid lo baja a sus servidores; debajo se ve **la lista de la cuenta de RD
  con su estado real** (leyendo el magnet, en cola, descargando con su %, listo,
  sin semillas…) y botones Ver / Descargar / quitar. Es la salida a los dos fallos
  que no dependen de la app: que RD **aún no tenga el torrent cacheado** o que **el
  archivo se haya borrado** de su caché. Y lo que se añade aquí **aparece luego en
  la ficha del título** como un enlace más (ver «lo que ya está en tu Real-Debrid»),
  que es la vía
  para lo que no está en ningún buscador.
  - También se puede pulsar un magnet **en el navegador**, **compartirlo** con
    VizPlay, y cuando un enlace de la ficha falla el propio aviso ofrece
    **«Añadirlo a Real-Debrid»**. Un enlace de hoster (1fichier, Mega…) se
    desbloquea y se descarga igual que en el «Descargador» de la web de RD.
  - `Links.kt` limpia lo que llega: quita el `#:~:text=…` que añade Chrome al
    «copiar enlace al texto resaltado», pone el `https://` si falta, y **detecta el
    enlace truncado** para poder avisar de que está a medias en vez de un «eso no
    es un enlace» que no ayuda. Del texto compartido el enlace se **busca dentro**
    con expresión regular: los navegadores comparten «Título \n enlace», así que
    exigir que el texto empiece por `http` descartaba justo lo que se acababa de
    compartir. Compartir es más fiable que copiar y pegar, donde en el móvil es
    facilísimo llevarse solo un trozo.
  - **Se intentó y se quitó**: subir un `.torrent` (con el selector o abriéndolo
    con VizPlay) y leer la página de una ficha para extraerlo. Funcionaba a medias
    y daba más problemas que soluciones — Real-Debrid rechaza muchos torrents con
    `infringing_file`, y de las páginas no siempre se puede sacar el enlace. Queda
    el magnet, que es lo que sí funciona.
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
- Buscador con **dos motores fijos y uno opcional**, con un chip por motor en cada
  ficha, más lo que ya tengas en tu Real-Debrid (que **no lleva chip**, ver abajo).
  Los enlaces salen **ordenados: tu Real-Debrid → Extra → Peerflix → Torrentio**:
  - **Peerflix**: es donde están las versiones en español. Sus proveedores son
    **DonTorrent, MejorTorrent, Wolfmax4K, Popcorntime y Bitsearch** — o sea que
    las webs españolas ya están cubiertas aquí; un motor aparte dedicado a
    DonTorrent no añadiría ni un enlace (se intentó y se descartó por eso, y
    porque el addon público de DonTorrent, `streamingaddons.xyz`, tiene el dominio
    muerto). Se puede apuntar a tu propia URL de `config.peerflix.mov`.
  - **Torrentio** con todos los proveedores (incluidos MejorTorrent, Wolfmax4k y
    Cinecalidad), no solo los de por defecto.
  - **Addon extra** (`ExtraAddon.kt`), vacío por defecto: se pega la URL de
    **cualquier** addon de Stremio que dé torrents y pasa a ser un tercer motor.
    Está pensado para MediaFusion, Comet o Jackettio, que se configuran con
    indexadores españoles y dan una URL con esa configuración dentro. Se puso una
    ranura genérica en vez de un addon concreto porque estos addons **cambian de
    dominio y mueren**; así no hace falta una versión nueva de la app cada vez.
    Lleva botón **«Probar»** para distinguir «no hay enlaces» de «la URL no vale».
  - **Lo que ya está en tu Real-Debrid** (`RdEngine.kt`): se busca **por nombre** en
    tu propia cuenta y sus torrents salen como enlaces más, los primeros de la lista
    y marcados con «✅ en tu Real-Debrid». **No tiene chip propio a propósito**: no es
    una fuente distinta, es el mismo torrent que ya tienes, así que aparece con
    cualquier motor seleccionado en vez de esconderse en una pestaña aparte. Esto es lo que
    cierra el círculo con lo de los dibujos en castellano: cuando un título no
    aparece en ningún addon, se añade el torrent **a mano una vez** en Descargas y a
    partir de ahí sale solo en la ficha, con reproducción, descarga y selector de
    capítulos si es un pack (varios ficheros = pack, aunque el nombre no lo diga).
    Es el motor más fiable: API oficial, y lo que sale ya está en tu cuenta, así que
    no depende de semillas ni de la caché de nadie. Funciona **sin IMDb id**, así que
    salva los títulos que TMDB no sabe mapear.
    - Se compara exigiendo que **todas** las palabras del título estén en el nombre
      del torrent, no que se parezcan: los nombres traen mucha morralla (grupo,
      códec, año) y comparar el conjunto entero no casaría nunca.
    - Antes hubo aquí un motor que **rastreaba la web** de DonTorrent buscando por
      texto. Se quitó: adivinar el formato de búsqueda de una web que no se puede
      abrir desde el entorno de compilación era un pozo sin fondo. Resolver **un**
      enlace que el usuario ya encontró es un problema pequeño y cerrado, y hace lo
      mismo con muchísimo menos que romperse.
  **Filtro por IDIOMA** en la ficha, que no existía: el orden de idiomas de Ajustes
  solo *ordenaba*, así que con «español» configurado los ingleses seguían saliendo
  detrás y parecía que el ajuste se ignoraba. Arranca en «Mis idiomas» (los de
  Ajustes) y los de idioma **desconocido no se ocultan nunca**, porque la detección
  se hace por el nombre del torrent y falla a menudo — ocultarlos se llevaría por
  delante enlaces buenos, que es el error que ya se cometió con el filtro de
  calidad.
  La **detección de idioma** se arregló en dos puntos: el castellano se comprueba
  **antes** que multi/dual (un «Oliver y Benji Dual Castellano Japonés» se marcaba
  como «multi» y se hundía en la lista), y si entre las banderas está la de España
  gana el castellano. Además el nombre se **normaliza** antes de buscar palabras
  —puntos, guiones y corchetes pasan a espacios—, así `[CAST]`, `.Cast.` y `-CAST-`
  casan todos sin que «podcast» o «Castle» den falsos positivos.
  Los enlaces se **pintan conforme llega cada motor** en vez de esperar a todos: con
  seis peticiones se esperaba a la más lenta, así que un addon atascado dejaba la
  ficha en blanco veinte segundos aunque los demás hubieran contestado ya.
  Los chips de motor se muestran **siempre, incluso con (0)**. Antes se escondía
  el motor sin resultados, y eso hacía imposible saber si un addon no había
  traído nada o si el motor no existía en la app.
  El trozo común del protocolo de addon está en `Addon.kt` (una sola copia de la
  lectura de `title`/`description`, del `behaviorHints` y del cálculo de tamaño):
  así un arreglo vale para todos los motores a la vez.
  Al pasar de episodio automáticamente se **repregunta al mismo motor**, por el
  idioma: si el episodio que se está viendo vino de Peerflix o del addon extra
  está en castellano, y seguir con Torrentio lo dejaría en inglés a mitad de serie.
  Todos buscan **por IMDb id**, así que en series hay que elegir episodio (no
  hay búsqueda de "temporada completa": los packs salen entre los resultados del
  episodio). En **películas** los enlaces se cargan solos al abrir la ficha; en
  **series** la temporada se ve entera y plegada, y los enlaces salen al tocar un
  episodio (volver a tocarlo lo cierra). Antes se desplegaba solo el primer
  episodio sin ver y sus enlaces empujaban el resto de la temporada hacia abajo.
- 📺 **TV en directo (listas M3U)** — pestaña «En directo», **solo con perfil
  infantil**. Nació del problema de los dibujos: Peppa Pig y Bluey en castellano
  no salían en los índices de torrents (se probó también por packs y no aparecía
  nada), pero **Clan de RTVE emite dibujos en castellano 24 h**, gratis y en
  abierto, sin depender de semillas ni de la caché de RD.
  - De serie va una lista **integrada solo con canales de RTVE** (Clan, La 1, La 2,
    Teledeporte, 24h), que son de la televisión pública. Se ven **solo desde
    España**: RTVE bloquea por país.
  - **Atajos a las plataformas FAST** en Ajustes → Canales (Pluto TV España,
    Samsung TV Plus España): televisión gratuita y legal, y es donde está
    **Dragon Ball en castellano y sin censura 24 h**, más Dragon Ball Z y Saint
    Seiya. Esas URL son listas mantenidas por terceros y **no se han podido
    verificar** al programarlo, así que se puede volver a RTVE de un toque.
  - Una lista propia **se suma** a los canales de RTVE, no los sustituye (sin
    repetidos, por nombre). Si los sustituyera, elegir una plataforma FAST dejaría
    al perfil infantil sin Clan, que es el canal que más se usa.
  - **Importar una lista que no está en una URL**: se puede **pegar el texto** de la
    M3U o traerla **de un fichero**. Hacía falta porque una lista no siempre se
    puede «suscribir»: puede venir en un documento, en un mensaje o en un fichero
    ya descargado, y con el texto en la mano antes no había forma de usarlo. Se
    guarda en un fichero de la app y no en las preferencias, porque una M3U puede
    pesar megas y SharedPreferences se carga entera en memoria. Se **suma** a las
    otras dos fuentes: se pueden tener RTVE, una URL y una importada a la vez.
  - Se puede poner **cualquier lista M3U propia** en Ajustes → Canales. Ahí se
    avisa de que las listas públicas de Internet mezclan emisiones oficiales de
    televisiones públicas con **retransmisiones no autorizadas de canales de
    pago**; por eso la integrada no las trae.
  - Con **perfil infantil** solo se muestran los canales de dibujos, **salvo los que
    hayas importado tú**: esos se muestran siempre. Esconderle a alguien sus propios
    canales porque el nombre no cuadra con un patrón es decidir por él — y como esta
    pestaña solo existe en el perfil infantil, esconderlos era hacerlos desaparecer
    del todo, que es lo que pasaba. Además se dice **cuántos se ocultan**: si
    desaparecen en silencio parece que la lista no cargó, no que hay un filtro. El filtro
    lleva los nombres de las series concretas (Dragon Ball, Saint Seiya, los
    clásicos de Pluto TV) y no un «anime» a secas, que arrastraría canales de
    anime para adultos. Si el filtro se queda corto, un canal válido desaparece
    **del todo**, porque esta pestaña ya solo existe en el perfil infantil.
  - Hace falta `media3-exoplayer-hls`: HLS va en su propio artefacto y sin él un
    `.m3u8` no se reproduce.
  - **No** hay accesos a YouTube: se probó y se quitó. Sacaba de la app para
    llevarte a la de YouTube, y eso no es lo que se quiere aquí. (De paso quedó
    comprobado que un canal M3U 24/7 de una sola serie **en castellano no
    existe**: contra los 40.853 canales de la base de datos de iptv-org, de los
    dedicados a una sola serie infantil el único con emisión real es Caillou, en
    francés.)
- 📦 **Packs de temporada** para lo que no existe por capítulos. Las series
  infantiles en castellano (Peppa Pig, Bluey…) casi nunca se publican episodio a
  episodio: van en packs cuyos ficheros internos se llaman `04x12.avi`, que el
  addon no sabe asociar a un episodio — así que buscando por episodio **no sale
  nada aunque el torrent exista**. Ahora, en series, además del episodio se
  consulta por la **serie y la temporada**, y esos resultados salen marcados con
  📦 y un botón **«Elegir capítulo»**: el pack entra una vez en Real-Debrid y a
  partir de ahí cualquier capítulo se ve al instante. La lista va ordenada como la
  vería una persona (`04x02` antes de `04x10`, no 1‑10‑11‑2).

  > El protocolo de Stremio define los streams de serie como `id:temporada:episodio`,
  > así que **no está garantizado** que un addon conteste al preguntar por la serie
  > entera. Se prueban varias formas del id y lo que falle se ignora: en el peor
  > caso no hay packs y nada empeora.
- ⚡ **Los enlaces que ya están en tu Real-Debrid salen arriba**, marcados con
  «Ya en tu Real-Debrid · se reproduce al instante», y la cabecera dice cuántos
  hay. Se cruza el `infoHash` de cada enlace con los torrents listos de tu cuenta:
  fiable al 100% y una sola llamada.

  > Saber si algo está en la **caché global** de RD (la de otras cuentas) **ya no
  > es posible**: era `/torrents/instantAvailability` y Real-Debrid lo desactivó
  > (`disabled_endpoint`, error 37). El único truco que queda —añadir el magnet y
  > ver si sale como `downloaded`— cuesta tres llamadas y un hueco de torrent por
  > enlace, así que no vale para marcar una lista entera. Para el resto, la mejor
  > pista sigue siendo las semillas.
- **Filtros de calidad** (4K/1080p/720p/480p/SD) con el número de enlaces de cada
  una, y un chip **"Otras"** para los que no se puede identificar: así ninguno
  queda escondido. La detección entiende también las formas de las webs españolas
  (`[MicroHD][1080 px]`, `1920x1080`), que antes caían en "desconocida".
- **Tamaño de cada enlace** tomado del dato exacto del addon
  (`behaviorHints.videoSize`) y, si no lo trae, del texto (`💾 4.38 GB`). Si el
  addon no lo da, simplemente no se muestra.
- **Toda la información que dé el addon**: se leen `title` **y** `description`
  (el SDK de Stremio renombró el campo, y los addons modernos usan el segundo),
  más `behaviorHints.filename`, `videoSize` y `bingeGroup`, y las semillas tanto
  del campo numérico como del texto. Leyendo solo `title`, los enlaces de
  Peerflix salían **sin nombre de fichero, sin tamaño y con 0 seeders**. Lo que
  el addon diga de más (fuente, grupo, códec) sale en una línea aparte, y
  **"0 seeders" ya no se muestra**: significaba "el addon no da el dato", pero
  se leía como "enlace muerto". Al unir un torrent que devuelven los dos
  motores, de cada campo se queda el que informa.
- 👤 **Perfiles** (crear/editar/borrar) con **modo infantil** (catálogos filtrados
  a géneros familiares, y la pestaña «En directo» solo aquí), sincronizados con tu
  cuenta. El modo infantil **sí tiene buscador**: antes «Buscar» llevaba al
  catálogo filtrado y así no había forma de pedir una serie por su nombre, que es
  justo lo que se hace con los niños. **Cambiar de perfil está a un toque**:
  el avatar del perfil activo va en la barra superior de todas las pestañas y abre
  un «¿Quién está viendo?»; en la **tele** aparece además al fondo de la barra de
  secciones, con el foco puesto ya en el perfil activo. Antes había que entrar en
  Ajustes → Cuenta y buscarlo entre los chips.
- 🔔 Avisos de episodios nuevos de tus series favoritas y **auto-actualización**:
  compara el número de build de la Release (`Build N` en su cuerpo) con el de la app
  (`BuildConfig.CI_BUILD`). Antes comparaba **además** la fecha del APK subido con
  la hora de compilación, y eso estaba mal de raíz: la hora se graba cuando
  ARRANCA el build y el APK se sube cuando TERMINA, ~7 min después, con un margen
  de solo 2 min. Así que el propio APK recién instalado siempre parecía más nuevo
  que sí mismo y el aviso de «nueva versión» **no desaparecía nunca**. El número de
  build no depende de relojes ni de márgenes. Un fallo al comprobar nunca se
  traduce en «estás al día»: eso sería afirmar lo que no se ha podido comprobar.

## Nada en segundo plano (salvo una descarga en curso)

Al no haber motor de torrents, la app **no tiene `WAKE_LOCK` ni notificación
permanente**. En reposo no queda nada corriendo: cero CPU y cero batería.

La única excepción es una **descarga en curso**, que sí necesita un servicio en
primer plano —es justo lo que impide que el sistema la mate al cerrar la app— con
su notificación de progreso. Desaparece en cuanto la descarga acaba o se pausa.

Permisos que pide: `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`
(progreso de las descargas), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`
(la descarga en curso) y `REQUEST_INSTALL_PACKAGES` (auto-actualización).

## Android TV

El manifiesto ya declara `LEANBACK_LAUNCHER`, banner y `leanback`/`touchscreen`
como no obligatorios, así que **el mismo APK se instala en una Android TV o
Google TV y aparece en su launcher** con su banner propio. Reproduciendo en la propia TV no hace
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
- **Ficha en dos columnas** al estilo de Stremio para TV: la **carátula entera**
  (sin recortar) a la izquierda con el tráiler y «Añadir a Mi lista» debajo, y a
  la derecha la sinopsis y **los enlaces**. Antes el banner ocupaba media
  pantalla y había que bajar mucho para ver un solo enlace.

## Configuración

1. **Ajustes → Real-Debrid** → pega tu token de <https://real-debrid.com/apitoken>.

   **El token va con la CUENTA, no con el aparato.** Con la sesión iniciada se
   guarda en `users/{uid}.account.rdToken` y todos tus dispositivos con esa
   cuenta usan el mismo Real-Debrid:
   - Si la cuenta ya tiene token, **ese manda** y sustituye al del móvil.
   - Si la cuenta no tiene y el móvil sí (token pegado antes de crear la cuenta),
     se sube para vincularlo.
   - Al **cerrar sesión** se borra del dispositivo: el token es de la cuenta, y
     si no, la siguiente cuenta que entrara en ese móvil heredaría el
     Real-Debrid de la anterior.
   - **Desconectar** lo quita también de la cuenta, para que no vuelva a bajarse
     en el siguiente arranque.
   - Sin iniciar sesión, el token se queda solo en ese móvil (cifrado).

   Cada persona necesita **su propia cuenta de Real-Debrid**: RD detecta el uso
   desde muchas IPs y bloquea temporalmente las cuentas compartidas.
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
   - la **Release** `android-latest` (`VizPlay.apk`), o
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
- Las descargas van, por defecto, a la carpeta privada de la app
  (`Android/data/com.carbaxo.torrentbox/files/Movies`); se puede cambiar en
  **Ajustes → Descargas → Carpeta de descargas**.
- Formatos: ExoPlayer reproduce mp4/webm y la mayoría de mkv/avi según los
  códecs del dispositivo. Al emitir, el Chromecast no admite MKV ni audio
  Dolby/DTS: la app lo resuelve enviando la versión convertida de Real-Debrid
  (ver "Emitir a Chromecast"). Si aun así falla, reproducir en la propia
  Android TV siempre funciona.
- Si RD aún no tiene el torrent cacheado, lo descarga primero en sus servidores:
  la app avisa del progreso y basta con volver a pulsar en un momento.
- Uso legítimo: descarga solo contenido para el que tengas derechos.
