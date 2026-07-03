plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.carbaxo.torrentbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.carbaxo.torrentbox"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        // Clave TMDB inyectada desde un secreto de CI (-PTMDB_KEY=...)
        buildConfigField("String", "TMDB_KEY", "\"${project.findProperty("TMDB_KEY") ?: ""}\"")
        // ID de cliente web de Google (para el login con Google, milestone sync)
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${project.findProperty("GOOGLE_WEB_CLIENT_ID") ?: ""}\"")
    }

    // Firma estable (misma SHA-1 en cada build) para poder registrar la app
    // en Firebase y que funcione el login con Google.
    signingConfigs {
        create("app") {
            storeFile = file("../torrentbox.keystore")
            storePassword = "torrentbox"
            keyAlias = "torrentbox"
            keyPassword = "torrentbox"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("app")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("app")
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
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Reproductor
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // Motor BitTorrent en el dispositivo (natives incluidos por ABI)
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-31")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-31")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-31")

    // Servidor HTTP local para hacer streaming del archivo mientras baja
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Cliente HTTP para la búsqueda (apibay) y TMDB
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Carga de imágenes (carátulas TMDB)
    implementation("io.coil-kt:coil-compose:2.7.0")
}
