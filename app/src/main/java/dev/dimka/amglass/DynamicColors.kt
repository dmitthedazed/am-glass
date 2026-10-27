package dev.dimka.amglass

import android.app.Application
import android.app.Instrumentation
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.util.TypedValue
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * Material You accent instead of Apple Music's blue key color.
 *
 * Every accent in the app (theme colorPrimary, ~30 text styles, `color_primary*`, Compose
 * `colorResource`) is a reference chain ending in `key_color_light` (#0088FF) or
 * `key_color_dark` (#0091FF). The framework reports the last reference of a resolved chain as
 * `TypedValue.resourceId` / the TypedArray resource id, so matching those two ids at the
 * points where colors leave the resource system catches all of them.
 */
object DynamicColors {

    @Volatile private var lightId = 0
    @Volatile private var darkId = 0
    /** App resources: unlike Resources.getSystem() in an app process, they carry the Monet overlays. */
    @Volatile private var appRes: Resources? = null

    fun hook() {
        // Resolve ids from the app's own table once it exists (stable names, ids may shift).
        XposedBridge.hookMethod(
            Instrumentation::class.java.getMethod("callApplicationOnCreate", Application::class.java),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val res = (param.args[0] as Application).resources
                    appRes = res
                    lightId = res.getIdentifier("key_color_light", "color", Entry.PACKAGE)
                    darkId = res.getIdentifier("key_color_dark", "color", Entry.PACKAGE)
                    Entry.log("dynamic colors: light=0x${lightId.toString(16)} dark=0x${darkId.toString(16)}")
                }
            },
        )

        // Resources.getColor / getColorStateList / Compose colorResource.
        val impl = Class.forName("android.content.res.ResourcesImpl")
        XposedBridge.hookMethod(
            impl.getDeclaredMethod("getValue", Int::class.javaPrimitiveType, TypedValue::class.java, Boolean::class.javaPrimitiveType),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val value = param.args[1] as TypedValue
                    if (value.isColorType()) accentFor(value.resourceId)?.let { value.data = it }
                }
            },
        )

        // Styles, themes and XML attributes (including items of color selector files).
        val resourceId: (TypedArray, Int) -> Int = { a, i -> a.getResourceId(i, 0) }
        XposedBridge.hookMethod(
            TypedArray::class.java.getMethod("getColor", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    accentFor(resourceId(param.thisObject as TypedArray, param.args[0] as Int))?.let { param.result = it }
                }
            },
        )
        XposedBridge.hookMethod(
            TypedArray::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val a = param.thisObject as TypedArray
                    val index = param.args[0] as Int
                    if (a.getType(index) !in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return
                    accentFor(resourceId(a, index))?.let { param.result = ColorStateList.valueOf(it) }
                }
            },
        )
    }

    /** Accent for a key color resource id, or null if [id] is something else. */
    private fun accentFor(id: Int): Int? = when {
        id == 0 -> null
        id == lightId -> accent(dark = false)
        id == darkId -> accent(dark = true)
        else -> null
    }

    /** Material 3 primary tones: light tone 40, dark a bit brighter than tone 80's pastel. */
    fun accent(dark: Boolean): Int = (appRes ?: Resources.getSystem()).getColor(
        if (dark) android.R.color.system_accent1_300 else android.R.color.system_accent1_600,
        null,
    )

    val isDark: Boolean
        get() = ((appRes ?: Resources.getSystem()).configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Material You role from the system palette, e.g. `role("surface_container")` (API 34 names). */
    fun role(name: String, dark: Boolean = isDark): Int {
        val res = appRes ?: Resources.getSystem()
        val id = res.getIdentifier("system_${name}_${if (dark) "dark" else "light"}", "color", "android")
        return if (id != 0) res.getColor(id, null) else accent(dark)
    }

    private fun TypedValue.isColorType() = type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
}
