plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

// Número de build de CI (GitHub Actions) para versionar y auto-actualizar
val ciBuild: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "").toIntOrNull() ?: 0

android {
    namespace = "com.carbaxo.torrentbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.carbaxo.torrentbox"
        minSdk = 24
        targetSdk = 34
        versionCode = if (ciBuild > 0) 1000 + ciBuild else 2
        versionName = if (ciBuild > 0) "2.$ciBuild" else "2.0-dev"
        // Con qué build de CI se compiló (0 = local); la app lo compara con la Release
        buildConfigField("int", "CI_BUILD", "$ciBuild")
        // Clave TMDB inyectada desde un secreto de CI (-PTMDB_KEY=...)
        buildConfigField("String", "TMDB_KEY", "\"${project.findProperty("TMDB_KEY") ?: ""}\"")
        // ID de cliente web de Google (para el login con Google, milestone sync)
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${project.findProperty("GOOGLE_WEB_CLIENT_ID") ?: ""}\"")
    }

    // Firma estable (misma SHA-1 en cada build): hace falta para registrar la app
    // en Firebase, para que funcione el login con Google y, sobre todo, para que
    // Android acepte un APK nuevo como ACTUALIZACIÓN del ya instalado.
    //
    // La clave NO vive en el repositorio, que es público. Quien tenga la clave y su
    // contraseña puede firmar un APK que Android instalará encima de esta app, así
    // que va como secreto de CI y, en local, como fichero que no se versiona. Si no
    // hay clave, se compila igual: Android usa su clave de depuración por defecto,
    // pero el APK resultante NO servirá para actualizar los ya instalados.
    val keystoreFile = file(System.getenv("VIZPLAY_KEYSTORE") ?: "../vizplay.keystore")
    val keystorePass = System.getenv("VIZPLAY_KEYSTORE_PASSWORD")
        ?: project.findProperty("KEYSTORE_PASSWORD") as String?
    val aliasName = System.getenv("VIZPLAY_KEY_ALIAS")
        ?: project.findProperty("KEY_ALIAS") as String? ?: "vizplay"
    val aliasPass = System.getenv("VIZPLAY_KEY_PASSWORD")
        ?: project.findProperty("KEY_PASSWORD") as String? ?: keystorePass
    val canSign = keystoreFile.exists() && !keystorePass.isNullOrBlank()

    signingConfigs {
        if (canSign) {
            create("app") {
                storeFile = keystoreFile
                storePassword = keystorePass
                keyAlias = aliasName
                keyPassword = aliasPass
                // Con minSdk 24 el AGP firma SOLO con v2 (el esquema del bloque de
                // firma), porque v2 existe justo desde Android 7.0. Se fuerza v1
                // (la firma JAR de META-INF) además de v2 por dos motivos:
                // instaladores de algunos Android TV baratos verifican v2 mal y
                // rechazan el APK con "App no instalada", y sin v1 ni keytool ni
                // jarsigner pueden leer quién firmó el APK, que es justo lo que
                // hace falta para diagnosticar un fallo de instalación.
                // El certificado es el mismo, así que la identidad de firma NO
                // cambia: sigue actualizando las instalaciones existentes.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        // findByName y no getByName: sin clave la configuración no existe, y
        // getByName reventaría la compilación en vez de recurrir a la de
        // depuración, que es lo que se quiere para quien clone el repo.
        debug {
            signingConfigs.findByName("app")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("app")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // media3 marca su API de Cast/ExoPlayer como "unstable"; se acepta en todo
        // el módulo para no anotar cada función que la usa.
        freeCompilerArgs += "-opt-in=androidx.media3.common.util.UnstableApi"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    // Animación del foco en la tele (anillo + zoom al enfocar con el mando)
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Descargas propias: worker en primer plano que sobrevive a cerrar la app
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Reproductor
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    // HLS va en su PROPIO artefacto: sin esto un .m3u8 no se reproduce, y los
    // canales de TV en directo son todos HLS.
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // Chromecast (CastPlayer + botón de ruta) — el diálogo de Cast necesita AppCompat
    implementation("androidx.media3:media3-cast:1.4.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")

    // Cliente HTTP para la búsqueda (apibay) y TMDB
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Carga de imágenes (carátulas TMDB)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Cifrado del token de Real-Debrid en disco
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Firebase: login con Google + Firestore (sincroniza con la cuenta del PC)
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
}
