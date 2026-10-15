package dev.dimka.amglass

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Liquid-glass switches for Apple Music settings. The app uses its own (obfuscated) Material3
 * Switch, so instead of swapping composables we pass marker colors through SwitchColors and
 * repaint the track and thumb wherever Modifier.background draws a marker. Layout, thumb motion
 * and press growth stay Material's; only the visuals change.
 *
 * Bindings (Apple Music 7.0.0-beta):
 *  w0.v5.a(Z, pi.l, O0.i, Z, w0.r5, z0.n, I, I)  Switch; arg 4 = SwitchColors (16 color longs)
 *  K.x  BackgroundNode: L = color, j(o1.G) draws the background, then o1.G.Z1() draws content
 *  o1.G.a -> X0.a CanvasDrawScope: c() = size, a -> DrawParams -> V0.b canvas -> a: android Canvas
 */
object SwitchGlass {
    // Opaque marker colors; the low byte encodes the role, +4 for disabled.
    private const val BASE = 0xFF0A0B00.toInt()
    private const val CHECKED_THUMB = 1
    private const val CHECKED_TRACK = 2
    private const val UNCHECKED_THUMB = 3
    private const val UNCHECKED_TRACK = 4
    private const val DISABLED = 4

    private lateinit var colorsCtor: Constructor<*>
    private lateinit var bgColor: Field
    private lateinit var scopeDraw: Field
    private lateinit var drawParams: Field
    private lateinit var composeCanvas: Field
    private lateinit var androidCanvas: Field
    private lateinit var sizeOf: Method
    private lateinit var drawContent: Method

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** @return the Switch composable and the background draw method to hook. */
    fun bind(cl: ClassLoader): Pair<Method, Method> {
        val colors = cl.loadClass("w0.r5")
        colorsCtor = colors.declaredConstructors.first { it.parameterTypes.size == 16 }.apply { isAccessible = true }
        val switch = cl.loadClass("w0.v5").declaredMethods.first {
            it.name == "a" && it.parameterTypes.size == 8 && it.parameterTypes[4] == colors
        }
        val bgNode = cl.loadClass("K.x")
        bgColor = bgNode.getDeclaredField("L").apply { isAccessible = true }
        val drawScopeClass = cl.loadClass("o1.G")
        val bgDraw = bgNode.getDeclaredMethod("j", drawScopeClass)

        scopeDraw = drawScopeClass.getDeclaredField("a").apply { isAccessible = true }
        val canvasDrawScope = cl.loadClass("X0.a")
        drawParams = canvasDrawScope.getDeclaredField("a").apply { isAccessible = true }
        sizeOf = canvasDrawScope.getMethod("c")
        composeCanvas = cl.loadClass("X0.a\$a").declaredFields
            .first { it.type.name == "V0.u" || it.type.name == "V0.b" || it.type.isInterface && it.type.name.startsWith("V0.") }
            .apply { isAccessible = true }
        androidCanvas = cl.loadClass("V0.b").getDeclaredField("a").apply { isAccessible = true }
        drawContent = drawScopeClass.getMethod("Z1")
        return switch to bgDraw
    }

    /** SwitchColors whose thumb/track are markers and whose borders/icons are transparent. */
    fun markerColors(): Any {
        fun marker(role: Int) = (BASE or role).toLong() shl 32
        val clear = 0L
        return colorsCtor.newInstance(
            marker(CHECKED_THUMB), marker(CHECKED_TRACK), clear, clear,
            marker(UNCHECKED_THUMB), marker(UNCHECKED_TRACK), clear, clear,
            marker(CHECKED_THUMB + DISABLED), marker(CHECKED_TRACK + DISABLED), clear, clear,
            marker(UNCHECKED_THUMB + DISABLED), marker(UNCHECKED_TRACK + DISABLED), clear, clear,
        )
    }

    /** @return true when [node] drew a marker and the original background must be skipped. */
    fun drawBackground(node: Any, scope: Any): Boolean {
        val argb = (bgColor.getLong(node) ushr 32).toInt()
        if (argb and 0xFFFFFF00.toInt() != BASE) return false
        var role = argb and 0xFF
        if (role !in 1..8) return false
        val disabled = role > DISABLED
        if (disabled) role -= DISABLED

        val drawScope = scopeDraw.get(scope) ?: return false
        val canvas = androidCanvas.get(composeCanvas.get(drawParams.get(drawScope)) ?: return false) as? Canvas
            ?: return false
        val size = sizeOf.invoke(drawScope) as Long
        val w = java.lang.Float.intBitsToFloat((size ushr 32).toInt())
        val h = java.lang.Float.intBitsToFloat(size.toInt())
        if (!(w > 0f && h > 0f)) return false

        val res = Resources.getSystem()
        val density = res.displayMetrics.density
        val dark = (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (disabled) canvas.saveLayerAlpha(null, 110)

        when (role) {
            CHECKED_TRACK, UNCHECKED_TRACK -> drawTrack(canvas, w, h, role == CHECKED_TRACK, dark)
            else -> drawThumb(canvas, w, h, role == CHECKED_THUMB, density)
        }

        if (disabled) canvas.restore()
        drawContent.invoke(scope)
        return true
    }

    /** iOS track: green when on, neutral fill when off. */
    private fun drawTrack(canvas: Canvas, w: Float, h: Float, on: Boolean, dark: Boolean) {
        paint.shader = null
        paint.color = when {
            on && dark -> Color.rgb(0x30, 0xD1, 0x58)
            on -> Color.rgb(0x34, 0xC7, 0x59)
            dark -> Color.argb(0x52, 0x78, 0x78, 0x80)
            else -> Color.argb(0x29, 0x78, 0x78, 0x80)
        }
        canvas.drawRoundRect(0f, 0f, w, h, h / 2f, h / 2f, paint)
    }

    /**
     * LiquidToggle-style thumb: a white pill at rest that turns into clear glass while pressed.
     * Apple Music's Material thumb rests at 24dp in both states and grows to 28dp when pressed;
     * that growth drives the glass progress, so the press animation stays Material's spring.
     */
    private fun drawThumb(canvas: Canvas, w: Float, h: Float, @Suppress("UNUSED_PARAMETER") on: Boolean, density: Float) {
        val rest = 24f * density
        val pressed = 28f * density
        val p = ((w - rest) / (pressed - rest)).coerceIn(0f, 1f)

        val cx = w / 2f
        val cy = h / 2f
        // Pill inside the 52x32dp track: 30x26dp at rest, swelling 35% when pressed.
        val scale = 1f + 0.35f * p
        val pw = 30f * density * scale
        val ph = 26f * density * scale
        val l = cx - pw / 2f
        val t = cy - ph / 2f
        val r = ph / 2f

        // Soft drop shadow.
        paint.shader = RadialGradient(
            cx, cy + 2f * density, pw * 0.6f,
            Color.argb((50 * (1f - p)).toInt(), 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(l - 3f * density, t - 2f * density, l + pw + 3f * density, t + ph + 4f * density, r, r, paint)

        // Body: opaque white at rest, fading to a faint glass tint when pressed.
        paint.shader = LinearGradient(
            0f, t, 0f, t + ph,
            intArrayOf(
                Color.argb((255 - 200 * p).toInt(), 255, 255, 255),
                Color.argb((255 - 225 * p).toInt(), 255, 255, 255),
            ),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(l, t, l + pw, t + ph, r, r, paint)
        paint.shader = null

        if (p > 0f) {
            // Glass rim: bright specular edge plus a thin prismatic fringe.
            stroke.strokeWidth = 1.2f * density
            stroke.shader = LinearGradient(
                l, t, l + pw, t + ph,
                intArrayOf(
                    Color.argb((220 * p).toInt(), 255, 255, 255),
                    Color.argb((40 * p).toInt(), 255, 255, 255),
                    Color.argb((160 * p).toInt(), 255, 255, 255),
                ),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(l, t, l + pw, t + ph, r, r, stroke)
            stroke.strokeWidth = 0.8f * density
            stroke.shader = LinearGradient(
                l, cy, l + pw, cy,
                intArrayOf(
                    Color.argb((120 * p).toInt(), 255, 80, 80),
                    Color.argb((60 * p).toInt(), 80, 255, 160),
                    Color.argb((120 * p).toInt(), 80, 140, 255),
                ),
                null, Shader.TileMode.CLAMP,
            )
            val inset = 1.6f * density
            canvas.drawRoundRect(l + inset, t + inset, l + pw - inset, t + ph - inset, r - inset, r - inset, stroke)
            stroke.shader = null
        }
    }
}
