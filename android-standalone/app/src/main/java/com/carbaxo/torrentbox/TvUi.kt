package com.carbaxo.torrentbox

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Todo lo que hace que la app se pueda manejar con el MANDO de una tele.
 *
 * El problema en una Smart TV no es el diseño, es que **no se ve dónde estás**:
 * sin ratón ni dedo, lo único que te sitúa es el foco, y Material3 lo señala de
 * una forma pensada para el móvil (casi invisible a tres metros). Aquí se
 * resuelve con un anillo blanco grueso + un pequeño zoom, al estilo de Stremio
 * para TV, y con una barra de secciones a la izquierda en vez de abajo.
 */
object Tv {

    /** Se ejecuta en una tele (Android TV, Google TV, Fire TV…). */
    var isTv: Boolean = false
        private set

    fun init(ctx: Context) {
        val pm = ctx.packageManager
        val leanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("android.hardware.type.television")
        val uiMode = runCatching {
            (ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType ==
                Configuration.UI_MODE_TYPE_TELEVISION
        }.getOrDefault(false)
        isTv = leanback || uiMode
    }

    // El anillo de foco: blanco puro, que es lo que mejor se ve en cualquier
    // tele y con cualquier carátula debajo.
    val Ring = Color.White
    val RingSoft = Color(0x33FFFFFF)

    /** Margen de seguridad: las teles recortan los bordes de la imagen. */
    val overscan = 20.dp
}

/**
 * Anillo de foco SIN hacer nada más. Para lo que ya es enfocable de por sí
 * (Button, FilterChip, TextButton…): solo le añade la señal visual.
 */
@Composable
fun Modifier.tvFocusRing(
    shape: Shape = RoundedCornerShape(10.dp),
    scale: Float = 1.06f,
    focusRequester: FocusRequester? = null
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (focused) scale else 1f, tween(120), label = "tvRing")
    var m: Modifier = this
    if (focusRequester != null) m = m.focusRequester(focusRequester)
    return m
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer { scaleX = s; scaleY = s }
        .border(BorderStroke(if (focused) 3.dp else 0.dp, if (focused) Tv.Ring else Color.Transparent), shape)
}

/**
 * Hace enfocable y pulsable un elemento que no lo era (una tarjeta, una
 * carátula, una fila), con su anillo de foco. Sustituye a `Modifier.clickable`.
 *
 * En el móvil se comporta exactamente igual que antes: como nunca hay foco, el
 * anillo no llega a dibujarse.
 */
@Composable
fun Modifier.tvClickable(
    shape: Shape = RoundedCornerShape(10.dp),
    scale: Float = 1.06f,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
): Modifier = this
    .tvFocusRing(shape, scale, focusRequester)
    .clickable(onClick = onClick)

/**
 * Igual que [tvFocusRing] pero además tiñe el fondo al enfocar. Para las filas
 * de una lista, donde el anillo solo se pierde entre el resto de líneas.
 */
@Composable
fun Modifier.tvRow(
    shape: Shape = RoundedCornerShape(10.dp),
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
): Modifier {
    var focused by remember { mutableStateOf(false) }
    var m: Modifier = this
    if (focusRequester != null) m = m.focusRequester(focusRequester)
    return m
        .onFocusChanged { focused = it.isFocused }
        .clip(shape)
        .background(if (focused) Tv.RingSoft else Color.Transparent)
        .border(BorderStroke(if (focused) 3.dp else 0.dp, if (focused) Tv.Ring else Color.Transparent), shape)
        .clickable(onClick = onClick)
}

/** Enfocable sin pulsar (contenedores que solo deben poder recibir el foco). */
@Composable
fun Modifier.tvFocusable(): Modifier = this.focusable()

/** Pide el foco al entrar en la pantalla; en el móvil no hace nada. */
@Composable
fun rememberTvFocus(): FocusRequester = remember { FocusRequester() }
