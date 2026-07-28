package com.carbaxo.torrentbox

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Aspecto de VizPlay, al estilo de la app de Netflix.
 *
 * Lo que se copia son las **convenciones**, no la marca: fondo casi negro, texto
 * blanco con un gris claro para lo secundario, rojo reservado para lo importante,
 * botones rectos (el principal blanco con texto negro) y títulos gruesos y
 * apretados.
 *
 * La tipografía de Netflix ("Netflix Sans") es privativa y no se puede licenciar,
 * así que va **Inter**, que es la que usan hoy la mayoría de las interfaces
 * oscuras: mismo carácter geométrico y mejor legibilidad a tamaño pequeño.
 */

// ------------------------------------------------------------------ colores
/** Fondo de Netflix. Casi negro, pero no negro puro: no "sangra" en OLED. */
val Bg = Color(0xFF141414)

/** Tarjetas y hojas, un paso por encima del fondo. */
val Surface1 = Color(0xFF202020)
val Surface2 = Color(0xFF2A2A2A)

/** Texto secundario: gris claro, no gris medio, para que se lea en la tele. */
val Muted = Color(0xFFB3B3B3)

/** El rojo de acento, para lo que debe llamar la atención. */
val Accent = Color(0xFFE50914)

/** Granate más profundo: el "PLAY" del logotipo y los rellenos con texto blanco. */
val AccentDeep = Color(0xFFC41E3A)

/** Verde de "listo/conectado" y ámbar de aviso (no son de marca, son estado). */
val OkGreen = Color(0xFF34D399)
val WarnAmber = Color(0xFFFBBF24)

/**
 * Esquina de los botones y las tarjetas. Netflix usa casi recto (4dp): es la
 * diferencia mas visible frente al boton "pastilla" de Material.
 */
val NfShape = RoundedCornerShape(4.dp)

/**
 * Esquema de color.
 *
 * `primary` es BLANCO a propósito: en Material3 el botón relleno toma de ahí su
 * fondo, así que con esto **todos** los botones de la app quedan blancos con
 * texto negro —el botón de "Reproducir" de Netflix— sin tocar cada llamada. El
 * secundario queda con borde blanco translúcido sobre transparente.
 */
fun vizColorScheme(): ColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    secondary = Surface2,
    onSecondary = Color.White,
    secondaryContainer = Color(0x33FFFFFF),   // chip seleccionado
    onSecondaryContainer = Color.White,
    background = Bg,
    onBackground = Color.White,
    surface = Surface1,
    onSurface = Color.White,
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    outline = Color(0x59FFFFFF),              // borde de los botones secundarios
    outlineVariant = Color(0x26FFFFFF),
    error = Accent,
    onError = Color.White
)

// -------------------------------------------------------------- tipografía
private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

/** Variante de Inter pensada para tamaños grandes: títulos y logotipo. */
private val InterDisplay = FontFamily(
    Font(R.font.inter_display_bold, FontWeight.Bold),
    Font(R.font.inter_display_black, FontWeight.Black)
)

/** Tipo de letra del logotipo (VIZPLAY). */
val WordmarkFont = InterDisplay

/**
 * Escala tipográfica. Los títulos van **gruesos y con la letra apretada**
 * (`letterSpacing` negativo), que es lo que da el aire de Netflix; el cuerpo va
 * normal y bien espaciado para poder leerlo.
 *
 * En la tele todo crece un 15 %: lo que se lee bien a 30 cm no se lee a 3 m.
 */
fun vizTypography(tv: Boolean): Typography {
    val k = if (tv) 1.15f else 1f
    fun sp(v: Float) = (v * k).sp
    return Typography(
        headlineLarge = TextStyle(
            fontFamily = InterDisplay, fontWeight = FontWeight.Black,
            fontSize = sp(30f), lineHeight = sp(36f), letterSpacing = (-0.6).sp
        ),
        headlineMedium = TextStyle(
            fontFamily = InterDisplay, fontWeight = FontWeight.Black,
            fontSize = sp(26f), lineHeight = sp(32f), letterSpacing = (-0.5).sp
        ),
        headlineSmall = TextStyle(
            fontFamily = InterDisplay, fontWeight = FontWeight.Bold,
            fontSize = sp(23f), lineHeight = sp(29f), letterSpacing = (-0.4).sp
        ),
        titleLarge = TextStyle(
            fontFamily = InterDisplay, fontWeight = FontWeight.Bold,
            fontSize = sp(20f), lineHeight = sp(26f), letterSpacing = (-0.3).sp
        ),
        titleMedium = TextStyle(
            fontFamily = Inter, fontWeight = FontWeight.Bold,
            fontSize = sp(17f), lineHeight = sp(23f), letterSpacing = (-0.2).sp
        ),
        titleSmall = TextStyle(
            fontFamily = Inter, fontWeight = FontWeight.Bold,
            fontSize = sp(15f), lineHeight = sp(20f)
        ),
        bodyLarge = TextStyle(
            fontFamily = Inter, fontWeight = FontWeight.Normal,
            fontSize = sp(16f), lineHeight = sp(23f)
        ),
        bodyMedium = TextStyle(
            fontFamily = Inter, fontWeight = FontWeight.Normal,
            fontSize = sp(14f), lineHeight = sp(20f)
        ),
        bodySmall = TextStyle(
            fontFamily = Inter, fontWeight = FontWeight.Normal,
            fontSize = sp(13f), lineHeight = sp(19f)
        ),
        // Los botones usan labelLarge: en Netflix van en negrita
        labelLarge = TextStyle(
            fontFamily = Inter, fontWeight = FontWeight.Bold,
            fontSize = sp(14f), letterSpacing = 0.1.sp
        ),
        labelMedium = TextStyle(
            fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = sp(12f)
        ),
        labelSmall = TextStyle(
            fontFamily = Inter, fontWeight = FontWeight.Medium,
            fontSize = sp(11f), lineHeight = sp(15f)
        )
    )
}
