package dev.dimka.amglass

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.TypedValue
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Replaces Apple Music's Haze blur (popup menus) with liquid glass. Haze lives in the app's own
 * obfuscated Compose, so the glass is drawn with platform RenderNode/RenderEffect using the
 * backdrop library's lens shader, sampling the same Haze source layers.
 *
 * Obfuscated bindings (Apple Music 7.0.0-beta, verified at runtime):
 *  nh.k  HazeEffectNode: X = positionOnScreen, a0 = size (packed floats), l0 = areas, L = state
 *  nh.w  HazeState: a = all areas
 *  nh.g  HazeArea: b() = positionOnScreen, a() = contentLayer (Y0.e -> a: Y0.h -> c: RenderNode)
 *  o1.G  draw scope: a -> X0.a CanvasDrawScope -> a (DrawParams) -> V0.b canvas -> a: android Canvas
 *  Ke.G.c(o1.G) draws the node's content.
 */
object HazeGlass {
    private class Bindings(cl: ClassLoader) {
        val node: Class<*> = cl.loadClass("nh.k")
        val position: Field = field(node, "X")
        val size: Field = field(node, "a0")
        val areas: Field = field(node, "l0")
        val area: Class<*> = cl.loadClass("nh.g")
        val areaPosition: Method = area.getMethod("b")
        val areaLayer: Method = area.getMethod("a")
        val layerImpl: Field = field(cl.loadClass("Y0.e"), "a")
        val implNode: Field = field(cl.loadClass("Y0.h"), "c")
        val scopeDraw: Field = field(cl.loadClass("o1.G"), "a")
        val drawParams: Field = field(cl.loadClass("X0.a"), "a")
        val composeCanvas: Field = cl.loadClass("X0.a\$a").declaredFields
            .first { it.type.name == "V0.u" || it.type.name == "V0.b" || it.type.isInterface && it.type.name.startsWith("V0.") }
            .apply { isAccessible = true }
        val androidCanvas: Field = field(cl.loadClass("V0.b"), "a")
        val drawContent: Method = cl.loadClass("Ke.G").declaredMethods
            .first { it.name == "c" && it.parameterTypes.contentEquals(arrayOf(cl.loadClass("o1.G"))) }
            .apply { isAccessible = true }

        private fun field(c: Class<*>, name: String) = c.getDeclaredField(name).apply { isAccessible = true }
    }

    private class State {
        val source = RenderNode("AmGlass source")
        val path = Path()
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val fill = Paint()
    }

    private lateinit var b: Bindings
    private val states = WeakHashMap<Any, State>()
    private val lensShader by lazy {
        Class.forName("com.kyant.backdrop.internal.ShadersKt")
            .getField("RoundedRectRefractionShaderString").get(null) as String
    }

    fun bind(cl: ClassLoader): Method {
        b = Bindings(cl)
        return b.node.declaredMethods.first { it.name == "j" && it.parameterTypes.size == 1 }
    }

    /** @return true when the glass (and the node's content) was drawn and Haze must be skipped. */
    fun draw(node: Any, scope: Any): Boolean {
        // Haze leaves areas empty for effects drawn inside their own source (the header buttons);
        // sampling that source from within itself recurses on the RenderThread, so skip those.
        val areas = (b.areas.get(node) as? List<*>).orEmpty()
        val drawScope = b.scopeDraw.get(scope) ?: return false
        val params = b.drawParams.get(drawScope) ?: return false
        val canvas = b.androidCanvas.get(b.composeCanvas.get(params) ?: return false) as? Canvas ?: return false
        if (!canvas.isHardwareAccelerated) return false
        if (areas.isEmpty()) return drawMaterialOnly(node, scope, canvas)

        val pos = b.position.getLong(node)
        val size = b.size.getLong(node)
        val x = unpackX(pos); val y = unpackY(pos)
        val w = unpackX(size); val h = unpackY(size)
        if (!(w > 0f && h > 0f) || x.isNaN() || y.isNaN()) return false

        val state = states.getOrPut(node) { State() }
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        val dark = (android.content.res.Resources.getSystem().configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        // Round header buttons are circles; menus use the app's 20dp card corners.
        val radius = if (kotlin.math.abs(w - h) < 2f) w / 2f else minOf(20f * density, minOf(w, h) / 2f)

        // Backdrop: the Haze source layers, positioned relative to this node, on the window color.
        val src = state.source
        src.setPosition(0, 0, w.toInt(), h.toInt())
        val rec = src.beginRecording()
        rec.drawColor(if (dark) Color.BLACK else Color.WHITE)
        for (area in areas) {
            area ?: continue
            val layer = b.areaLayer.invoke(area) ?: continue
            val areaNode = b.implNode.get(b.layerImpl.get(layer)) as? RenderNode ?: continue
            if (!areaNode.hasDisplayList()) continue
            val ap = b.areaPosition.invoke(area) as Long
            rec.save()
            rec.translate(unpackX(ap) - x, unpackY(ap) - y)
            rec.drawRenderNode(areaNode)
            rec.restore()
        }
        src.endRecording()

        // Same stack as the bars: color controls -> blur -> lens.
        val lens = RuntimeShader(lensShader).apply {
            setFloatUniform("size", w, h)
            setFloatUniform("offset", 0f, 0f)
            setFloatUniform("cornerRadii", floatArrayOf(radius, radius, radius, radius))
            setFloatUniform("refractionHeight", 16f * density)
            setFloatUniform("refractionAmount", -32f * density)
            setFloatUniform("depthEffect", 1f)
        }
        val color = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(colorControls(-0.05f, 1f, 1.5f)))
        val blur = RenderEffect.createBlurEffect(12f * density, 12f * density, Shader.TileMode.CLAMP)
        src.setRenderEffect(
            RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(lens, "content"),
                RenderEffect.createChainEffect(blur, color),
            )
        )

        state.path.reset()
        state.path.addRoundRect(0f, 0f, w, h, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(state.path)
        canvas.drawRenderNode(src)
        state.fill.color = if (dark) Color.argb(40, 0, 0, 0) else Color.argb(60, 255, 255, 255)
        canvas.drawPath(state.path, state.fill)
        canvas.restore()

        // Rim highlight, brightest on the top-left and bottom-right edges.
        state.stroke.strokeWidth = 1f * density
        state.stroke.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(Color.argb(140, 255, 255, 255), Color.argb(20, 255, 255, 255), Color.argb(90, 255, 255, 255)),
            null, Shader.TileMode.CLAMP,
        )
        val inset = state.stroke.strokeWidth / 2f
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, radius, radius, state.stroke)

        b.drawContent.invoke(null, scope)
        return true
    }

    /** Glass material without refraction, for effects that have no usable backdrop source. */
    private fun drawMaterialOnly(node: Any, scope: Any, canvas: Canvas): Boolean {
        val size = b.size.getLong(node)
        val w = unpackX(size); val h = unpackY(size)
        if (!(w > 0f && h > 0f)) return false
        val state = states.getOrPut(node) { State() }
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        val dark = (android.content.res.Resources.getSystem().configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val radius = if (kotlin.math.abs(w - h) < 2f) w / 2f else minOf(20f * density, minOf(w, h) / 2f)

        state.fill.shader = LinearGradient(
            0f, 0f, 0f, h,
            if (dark) intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(28, 255, 255, 255))
            else intArrayOf(Color.argb(190, 255, 255, 255), Color.argb(120, 255, 255, 255)),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, state.fill)
        state.fill.shader = null

        state.stroke.strokeWidth = 1f * density
        state.stroke.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(Color.argb(160, 255, 255, 255), Color.argb(20, 255, 255, 255), Color.argb(100, 255, 255, 255)),
            null, Shader.TileMode.CLAMP,
        )
        val inset = state.stroke.strokeWidth / 2f
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, radius - inset, radius - inset, state.stroke)

        b.drawContent.invoke(null, scope)
        return true
    }

    private fun colorControls(brightness: Float, contrast: Float, saturation: Float): ColorMatrix {
        val invSat = 1f - saturation
        val r = 0.213f * invSat; val g = 0.715f * invSat; val bl = 0.072f * invSat
        val t = (0.5f - contrast * 0.5f + brightness) * 255f
        val s = saturation; val c = contrast
        return ColorMatrix(floatArrayOf(
            c * (r + s), c * g, c * bl, 0f, t,
            c * r, c * (g + s), c * bl, 0f, t,
            c * r, c * g, c * (bl + s), 0f, t,
            0f, 0f, 0f, 1f, 0f,
        ))
    }

    private fun unpackX(v: Long) = java.lang.Float.intBitsToFloat((v ushr 32).toInt())
    private fun unpackY(v: Long) = java.lang.Float.intBitsToFloat(v.toInt())
}
