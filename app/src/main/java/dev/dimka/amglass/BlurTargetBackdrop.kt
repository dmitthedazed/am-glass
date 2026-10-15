package dev.dimka.amglass

import android.graphics.RenderNode
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.View
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop

/**
 * [Backdrop] backed by the RenderNode that Apple Music's BlurTarget records its content into,
 * so `drawBackdrop` samples the real View hierarchy instead of a Compose layer.
 */
class BlurTargetBackdrop(
    private val target: View,
    private val node: RenderNode,
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    // No invalidation needed: the drawn RenderNode is referenced, not copied, so the
    // render thread picks up each new BlurTarget recording on its own.
    private val targetLocation = IntArray(2)

    /**
     * Apple Music pages have no background of their own; the window paints it. Without it the
     * recorded content is transparent around glyphs, so the glass turned see-through there and the
     * real, sharp text showed through it.
     */
    private val windowColor: Int by lazy {
        (target.rootView.background as? ColorDrawable)?.color
            ?: TypedValue().let { value ->
                val resolved = target.context.theme.resolveAttribute(android.R.attr.colorBackground, value, true)
                if (resolved && value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) value.data
                else android.graphics.Color.BLACK
            }
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val coordinates = coordinates ?: return
        val canvas = drawContext.canvas.nativeCanvas
        if (!canvas.isHardwareAccelerated || !node.hasDisplayList()) return
        canvas.drawColor(windowColor)

        // Screen space: popup menus sample the main window's BlurTarget from their own window.
        target.getLocationOnScreen(targetLocation)
        val position = coordinates.positionOnScreen()
        translate(targetLocation[0] - position.x, targetLocation[1] - position.y) {
            drawContext.canvas.nativeCanvas.drawRenderNode(node)
        }
    }
}
