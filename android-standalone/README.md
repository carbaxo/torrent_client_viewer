# 📱 TorrentBox — app Android autónoma

App Android **nativa e independiente**: descarga torrents **en el propio móvil**
y reproduce el vídeo **mientras se descarga**, sin depender de ningún PC ni
servidor.

- **Interfaz al estilo de la web**: pestañas Descubrir / Buscar / TV / Descargas /
  Ajustes, catálogos con carátulas (Netflix, Prime, HBO Max, Disney+ vía TMDB) y
  ficha de detalle con sinopsis, tráiler y fuentes (Ver / Descargar).
- Motor BitTorrent en el dispositivo: **libtorrent4j** (natives incluidos), con
  **persistencia**: las descargas permanentes se reanudan al reabrir la app.
- Descarga **secuencial** con priorización de piezas → reproducción mientras baja.
- Reproductor **ExoPlayer (Media3)**: subtítulos, pistas de audio, velocidad,
  siguiente episodio, Chromecast y continuar viendo.
- Buscador con **dos motores**: Torrentio (banderas de idioma) y apibay/TPB, con
  **filtros de calidad** (4K/1080p/720p/SD) y orden por idioma preferido.
- **Real-Debrid**: ver por streaming y descargar a disco.
- **TV (AceStream)**: canales/eventos P2P vía search-ace.stream.
- **Perfiles** (crear/editar/borrar) con **modo infantil** (solo catálogos
  familiares), sincronizados con la app del PC.
- Descarga en segundo plano con servicio en primer plano y **auto-actualización**.

## Sincronización con tu cuenta de Google

La app usa **la misma cuenta** que la app del PC y sincroniza perfiles,
favoritos, historial y el token de Real-Debrid vía Firebase (Auth + Firestore).
Google exige registrar la **huella SHA-1** de la app en tu proyecto de Firebase:

1. Firebase Console → tu proyecto → **Configuración → Tus apps → Añadir app →
   Android**. Nombre de paquete: `com.carbaxo.torrentbox`.
2. En **SHA-1** pega:
   `8C:82:4C:DD:BA:A8:DE:9D:96:71:AE:00:49:70:C6:33:F1:F1:26:49`
3. Descarga el `google-services.json` y el **ID de cliente web** (OAuth 2.0),
   y pásamelo (o pégalo en `gradle.properties` → `GOOGLE_WEB_CLIENT_ID`).

La app se firma con `torrentbox.keystore` (incluido) para que la SHA-1 sea
siempre la misma.

## Cómo conseguir el APK

No necesitas compilar nada: **GitHub Actions lo compila y lo publica**.

1. En el repo, pestaña **Actions → "Build Android APK" → Run workflow** (o se
   lanza solo al hacer push a `android-standalone/`).
2. Cuando termine (verde), descarga el APK desde:
   - la **Release** `android-latest` (`TorrentBox.apk`), o
   - los **Artifacts** de la ejecución del workflow.
3. En el móvil: abre el APK, permite **"Instalar apps desconocidas"** e instálalo.

## Compilar en local (opcional, requiere Android Studio o el SDK)

```bash
cd android-standalone
./gradlew assembleDebug
# APK en app/build/outputs/apk/debug/app-debug.apk
```

## Notas
- Requiere Android 7.0 (API 24) o superior.
- La descarga va a la carpeta privada de la app (`Android/data/.../torrents`).
- Formatos: mp4/webm se reproducen nativamente; mkv/avi dependen de los códecs
  del dispositivo (ExoPlayer soporta muchos).
- Uso legítimo: descarga solo contenido para el que tengas derechos.
