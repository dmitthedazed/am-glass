package dev.dimka.amglass

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * Liquid Glass with the catalog's tab bar look (vibrancy + blur + lens over a 40% container color).
 * [isLight] is fixed per theme (no luminance probing): dark glass with white content in dark
 * mode, light glass with black content in light mode. [rememberAdaptiveGlass] can invert it.
 */
@Stable
class AdaptiveGlass internal constructor(val isLight: Boolean) {
    val contentColor: Color = if (isLight) Color.Black else Color.White

    /** The catalog's LiquidBottomTabs recipe: vibrancy, 8dp blur, 24dp lens. */
    fun BackdropEffectScope.effects(lensHeightScale: Float = 1f) {
        vibrancy()
        blur(8f.dp.toPx())
        lens(24f.dp.toPx() * lensHeightScale, 24f.dp.toPx() * lensHeightScale)
    }

    /** The catalog's tab bar container color. */
    val drawSurface: DrawScope.() -> Unit = {
        drawRect(
            if (isLight) Color(0xFFFAFAFA).copy(0.4f)
            else Color(0xFF121212).copy(0.4f)
        )
    }
}

@Composable
fun rememberAdaptiveGlass(inverted: Boolean = false): AdaptiveGlass {
    val isLight = isSystemInDarkTheme() == inverted
    return remember(isLight) { AdaptiveGlass(isLight) }
}
