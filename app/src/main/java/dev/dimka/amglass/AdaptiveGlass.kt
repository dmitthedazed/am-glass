package dev.dimka.amglass

import android.graphics.Bitmap
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Liquid Glass with the catalog's tab bar look (vibrancy + blur + lens over a 40% container color).
 * Still probes how bright the content under it is, after AdaptiveLuminanceGlassContent, to flip
 * its content color between black and white.
 */
@Stable
class AdaptiveGlass internal constructor(
    val layer: GraphicsLayer,
    private val luminance: Animatable<Float, *>,
    private val content: androidx.compose.animation.core.Animatable<Color, *>,
    private val isLightTheme: Boolean,
) {
    val contentColor: Color get() = content.value

    /** The catalog's LiquidBottomTabs recipe: vibrancy, 8dp blur, 24dp lens. */
    fun BackdropEffectScope.effects(lensHeightScale: Float = 1f) {
        vibrancy()
        blur(8f.dp.toPx())
        lens(24f.dp.toPx() * lensHeightScale, 24f.dp.toPx() * lensHeightScale)
    }

    /** The catalog's tab bar container color. */
    val drawSurface: DrawScope.() -> Unit = {
        drawRect(
            if (isLightTheme) Color(0xFFFAFAFA).copy(0.4f)
            else Color(0xFF121212).copy(0.4f)
        )
    }

    /** Use as `onDrawBackdrop` so the sampled backdrop is also recorded for luminance probing. */
    val recordBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = { drawBackdrop ->
        drawBackdrop()
        layer.record { drawBackdrop() }
    }
}

@Composable
fun rememberAdaptiveGlass(): AdaptiveGlass {
    val isLightTheme = !isSystemInDarkTheme()
    val layer = rememberGraphicsLayer()
    val luminance = remember(isLightTheme) { Animatable(if (isLightTheme) 1f else 0f) }
    val content = remember(isLightTheme) { Animatable(if (isLightTheme) Color.Black else Color.White) }

    LaunchedEffect(layer) {
        val buffer = IntArray(25)
        while (isActive) {
            // The layer is empty until the glass has drawn once (or while it is off screen).
            val captured = layer.size.width > 0 && layer.size.height > 0 && runCatching {
                val bitmap = layer.toImageBitmap().asAndroidBitmap()
                Bitmap.createScaledBitmap(bitmap, 5, 5, false)
                    .copy(Bitmap.Config.ARGB_8888, false)
                    .getPixels(buffer, 0, 5, 0, 0, 5, 5)
            }.isSuccess
            if (!captured) {
                delay(250)
                continue
            }
            val average = buffer.sumOf { argb ->
                val r = (argb shr 16 and 0xFF) / 255f
                val g = (argb shr 8 and 0xFF) / 255f
                val b = (argb and 0xFF) / 255f
                0.2126 * r + 0.7152 * g + 0.0722 * b
            } / buffer.size
            launch { content.animateTo(if (average > 0.5f) Color.Black else Color.White, tween(1000)) }
            luminance.animateTo(average.toFloat(), tween(1000))
        }
    }
    return remember(layer, luminance, content, isLightTheme) { AdaptiveGlass(layer, luminance, content, isLightTheme) }
}
