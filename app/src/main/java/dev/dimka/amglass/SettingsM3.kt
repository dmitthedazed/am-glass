package dev.dimka.amglass

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Material 3 (Android 16 style) look for Apple Music's Compose settings screens.
 *
 * The screens live in the app's own obfuscated Compose, so we cannot compose our own UI there.
 * Instead, while one of the settings lambdas runs, we rewrite the arguments of the calls it makes:
 *  - category header `Rb.e2.invoke`: its Text gets the original-case title in `primary`;
 *  - category row `Rb.f2.invoke(scope, index, composer, flags)`: the row's clip shape
 *    (`lg.b.d(Modifier, Shape)`) becomes a segmented-list shape with a 2dp gap, its background
 *    (`K.v.b(Modifier, color, Shape)`) becomes `surfaceContainer`, dividers are turned off;
 *  - row icons (`w0.R1.c`, 3 = tint) use `primary`; radio buttons (`w0.D3.a`) and sliders
 *    (`w0.F4.a`) get M3 colors;
 *  - switches (`w0.v5.a`, via [switchColors]) use M3 SwitchColors from the dynamic palette.
 * Only the first clip/background call of a row is touched: rows contain switches that use the
 * same helpers.
 *
 * Bindings (Apple Music 7.0.0-beta): model `settings2.model.b$a` (a = title, c = items,
 * e = hasDividers); `a0.h.b(topStart, topEnd, bottomEnd, bottomStart, defaultsMask)` corner shape;
 * `S.H0.j(Modifier, start, top, end, bottom, defaultsMask)` padding; `w0.S5.b` Text (0 = text,
 * 2 = color); `w0.r5` SwitchColors (16 color longs).
 */
object SettingsM3 {

    private const val OUTER = 24f
    private const val INNER = 4f
    private const val GAP = 2f

    private class Row(val index: Int, val count: Int) {
        var clipped = false
        var filled = false
    }

    private val header = ThreadLocal<String?>()
    private val row = ThreadLocal<Row?>()

    private lateinit var shape: Method
    private lateinit var padding: Method
    private lateinit var colorsCtor: Constructor<*>

    fun hook(cl: ClassLoader) {
        shape = cl.loadClass("a0.h").getDeclaredMethod(
            "b", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        val modifier = cl.loadClass("O0.i")
        padding = cl.loadClass("S.H0").getDeclaredMethod(
            "j", modifier, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        colorsCtor = cl.loadClass("w0.r5").declaredConstructors.first { it.parameterTypes.size == 16 }
            .apply { isAccessible = true }

        hookInvoke(cl.loadClass("Rb.e2"), object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val category = XposedHelpers.getObjectField(param.thisObject, "a")
                header.set(XposedHelpers.getObjectField(category, "a") as String)
            }
            override fun afterHookedMethod(param: MethodHookParam) = header.remove()
        })
        hookInvoke(cl.loadClass("Rb.f2"), object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val category = XposedHelpers.getObjectField(param.thisObject, "a")
                XposedHelpers.setBooleanField(category, "e", false)
                val count = (XposedHelpers.getObjectField(category, "c") as List<*>).size
                row.set(Row(param.args[1] as Int, count))
            }
            override fun afterHookedMethod(param: MethodHookParam) = row.remove()
        })

        // Row clip: segmented shape, gap above every row but the first.
        val g0 = cl.loadClass("V0.g0")
        XposedBridge.hookMethod(cl.loadClass("lg.b").getDeclaredMethod("d", modifier, g0), object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val r = row.get() ?: return
                if (r.clipped) return
                r.clipped = true
                val top = if (r.index == 0) OUTER else INNER
                val bottom = if (r.index == r.count - 1) OUTER else INNER
                param.args[1] = shape.invoke(null, top, top, bottom, bottom, 0)
                if (r.index > 0) param.args[0] = padding.invoke(null, param.args[0], 0f, GAP, 0f, 0f, 0)
            }
        })
        // Row background: surfaceContainer.
        XposedBridge.hookMethod(
            cl.loadClass("K.v").getDeclaredMethod("b", modifier, Long::class.javaPrimitiveType, g0),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val r = row.get() ?: return
                    if (r.filled) return
                    r.filled = true
                    param.args[1] = color(containerRole())
                }
            },
        )
        // Row icons (e.g. the outward arrow of link rows): primary instead of the app's blue.
        val icon = cl.loadClass("w0.R1").declaredMethods.first {
            it.name == "c" && it.parameterTypes.size == 7 && it.parameterTypes[3] == Long::class.javaPrimitiveType
        }
        XposedBridge.hookMethod(icon, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (row.get() != null) param.args[3] = color(DynamicColors.role("primary"))
            }
        })
        // RadioButton `w0.D3.a(selected, Modifier, enabled, colors, ...)`, colors `w0.B3`
        // (selected, unselected, disabledSelected, disabledUnselected).
        val radioColors = cl.loadClass("w0.B3")
        val radioCtor = radioColors.declaredConstructors.first { it.parameterTypes.size == 4 }
        val radio = cl.loadClass("w0.D3").declaredMethods.first {
            it.name == "a" && it.parameterTypes.size == 7 && it.parameterTypes[3] == radioColors
        }
        XposedBridge.hookMethod(radio, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val disabled = color(alpha(DynamicColors.role("on_surface"), 0.38f))
                param.args[3] = radioCtor.newInstance(
                    color(DynamicColors.role("primary")), color(DynamicColors.role("on_surface_variant")), disabled, disabled,
                )
            }
        })
        // Slider `w0.F4.a(value, onChange, Modifier, enabled, ..., colors = 5, ...)`, colors `w0.s4`
        // (thumb, activeTrack, activeTick, inactiveTrack, inactiveTick, then the disabled five).
        val sliderColors = cl.loadClass("w0.s4")
        val sliderCtor = sliderColors.declaredConstructors.first { it.parameterTypes.size == 10 }
        val slider = cl.loadClass("w0.F4").declaredMethods.first {
            it.name == "a" && it.parameterTypes.size > 5 && it.parameterTypes[5] == sliderColors
        }
        XposedBridge.hookMethod(slider, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val old = param.args[5] ?: return
                val primary = color(DynamicColors.role("primary"))
                param.args[5] = sliderCtor.newInstance(
                    primary, primary, color(DynamicColors.role("on_primary")),
                    color(DynamicColors.role("secondary_container")), color(DynamicColors.role("on_secondary_container")),
                    *listOf("f", "g", "h", "i", "j").map { XposedHelpers.getLongField(old, it) }.toTypedArray(),
                )
            }
        })
        // Category header text: original case, primary color.
        val text = cl.loadClass("w0.S5").declaredMethods.first {
            it.name == "b" && it.parameterTypes.size == 21 && it.parameterTypes[0] == String::class.java
        }
        XposedBridge.hookMethod(text, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val title = header.get() ?: return
                param.args[0] = title
                param.args[2] = color(DynamicColors.role("primary"))
            }
        })
    }

    /** Android 16 settings: tonal cards on the page background. */
    private fun containerRole() =
        DynamicColors.role(if (DynamicColors.isDark) "surface_container" else "surface_container_lowest")

    /** Material 3 SwitchColors from the dynamic palette (checked / unchecked, then disabled). */
    fun switchColors(): Any {
        fun r(name: String) = DynamicColors.role(name)
        val primary = r("primary")
        val outline = r("outline")
        val highest = r("surface_container_highest")
        val onSurface = r("on_surface")
        return colorsCtor.newInstance(
            color(r("on_primary")), color(primary), color(primary), color(r("on_primary_container")),
            color(outline), color(highest), color(outline), color(highest),
            color(r("surface")), color(alpha(onSurface, 0.12f)), 0L, color(alpha(onSurface, 0.38f)),
            color(alpha(onSurface, 0.38f)), color(alpha(highest, 0.12f)), color(alpha(onSurface, 0.12f)), color(alpha(highest, 0.38f)),
        )
    }

    private fun alpha(argb: Int, a: Float) = (argb and 0xFFFFFF) or ((a * 255).toInt() shl 24)

    /** Compose packs an sRGB Color as the ARGB int in the high 32 bits. */
    private fun color(argb: Int): Long = (argb.toLong() and 0xFFFFFFFFL) shl 32

    private fun hookInvoke(type: Class<*>, hook: XC_MethodHook) =
        type.declaredMethods.filter { it.name == "invoke" }
            .let { all -> all.filter { !it.isBridge }.ifEmpty { all } }
            .forEach { XposedBridge.hookMethod(it, hook) }
}
