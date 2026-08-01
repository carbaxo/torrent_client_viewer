# Secretos y configuración (repositorio público)

Este repositorio es **público**: nada secreto puede vivir dentro. Lo que hace falta
para compilar y publicar va en **Settings → Secrets and variables → Actions** del
repositorio en GitHub.

## Secretos de GitHub Actions

| Secreto | Para qué | Si falta |
|---|---|---|
| `KEYSTORE_B64` | La clave de firma del APK, en base64 | El APK se firma con la clave de depuración de Android y **no sirve para actualizar** las instalaciones que ya existen |
| `KEYSTORE_PASSWORD` | Contraseña del almacén | Igual que arriba: no se firma |
| `KEY_ALIAS` | Alias de la clave (`vizplay`) | Se usa `vizplay` por defecto |
| `KEY_PASSWORD` | Contraseña de la clave | Se usa la del almacén |
| `TMDB_KEY` | Catálogos y carátulas (TMDB v3) | La app funciona, pero **sin catálogos** |

### Por qué la clave de firma no puede estar en el repositorio

Android identifica una app por su **firma**. Quien tenga el `.keystore` y su
contraseña puede compilar un APK que el sistema instalará **encima de esta app**,
heredando sus datos guardados. Por eso está en `.gitignore` y solo existe como
secreto y como fichero local.

Y por eso tampoco se puede cambiar de clave a la ligera: un APK firmado con otra
clave **no actualiza** al ya instalado, hay que desinstalar y volver a instalar en
cada dispositivo.

### La clave vieja (`torrentbox`) está quemada

La primera clave, con alias `torrentbox` y contraseña `torrentbox`, estuvo subida
al repositorio. Reescribir el historial la quitó de la rama, **pero no del
servidor**: GitHub conserva los commits antiguos accesibles por su SHA, y los SHA
están a la vista en el historial público de Actions. Es decir, siguió siendo
descargable después de hacer público el repositorio.

Por eso se ha generado una clave nueva (alias `vizplay`, RSA 4096, PKCS12) con una
contraseña aleatoria que nunca ha pasado por el repositorio. Cambiar de clave
obliga a desinstalar y reinstalar en cada aparato: se acepta a cambio de que la
clave filtrada deje de servir para nada.

Purgar los objetos viejos del servidor hay que **pedírselo a GitHub Support**; es
lo único que corta el acceso por SHA.

### Generar `KEYSTORE_B64`

```bash
base64 -w0 vizplay.keystore          # Linux
base64 -i vizplay.keystore | tr -d '\n'   # macOS
[Convert]::ToBase64String([IO.File]::ReadAllBytes("vizplay.keystore"))  # PowerShell
```

El resultado (una sola línea larga) es el valor del secreto.

## Compilar en local

La clave de TMDB **no está** en `gradle.properties` a propósito. Ponla en tu
`~/.gradle/gradle.properties`, que está fuera del repositorio:

```properties
TMDB_KEY=tu_clave_de_tmdb
```

o pásala al compilar:

```bash
./gradlew assembleDebug -PTMDB_KEY=tu_clave
```

Sin clave de firma se compila igual (Android usa la de depuración). Para firmar
como la app publicada, deja tu `vizplay.keystore` en `android-standalone/` y
añade a tu `~/.gradle/gradle.properties`:

```properties
KEYSTORE_PASSWORD=...
KEY_ALIAS=vizplay
KEY_PASSWORD=...
```

## Lo que NO es secreto, aunque lo parezca

- **`google-services.json`** y **`public/config.js`** llevan claves de Firebase
  (`AIzaSy…`). Están **diseñadas para viajar dentro de la app** y son extraíbles de
  cualquier APK, así que publicarlas no cambia nada. Lo que protege los datos son
  las **reglas de Firestore**.
- El **client ID web de Google** es público por diseño: se le manda al cliente.

## Reglas de Firestore: esto sí es crítico

`firestore.rules` es lo único que impide que cualquiera lea el **token de
Real-Debrid** de cada cuenta. Con la base de datos en «modo de prueba», el fichero
del repositorio no sirve de nada: hay que **desplegarlo**.

```bash
firebase deploy --only firestore:rules
```

Compruébalo en la consola de Firebase → Firestore → Reglas: debe aparecer la regla
con `request.auth.uid == uid`, no un `allow read, write: if true`.
