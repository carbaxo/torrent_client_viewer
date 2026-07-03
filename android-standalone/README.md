# 📱 TorrentBox — app Android autónoma

App Android **nativa e independiente**: descarga torrents **en el propio móvil**
y reproduce el vídeo **mientras se descarga**, sin depender de ningún PC ni
servidor.

- Motor BitTorrent en el dispositivo: **libtorrent4j** (natives incluidos).
- Descarga **secuencial** con priorización de piezas → reproducción mientras baja.
- Reproductor **ExoPlayer (Media3)** vía servidor HTTP local con soporte de Range.
- Buscador integrado (apibay / The Pirate Bay), sin API key.
- Descarga en segundo plano con servicio en primer plano.

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
