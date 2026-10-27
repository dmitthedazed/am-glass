package dev.dimka.amglass

import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Paint
import android.graphics.fonts.Font
import android.graphics.text.TextRunShaper
import android.util.TypedValue
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * System font instead of Apple Music's SF Pro.
 *
 * Every Latin family in the app (`font/regular`, `bold`, `black`, `*_rounded`, ...) is an XML
 * family pointing at one variable font, `sf_pro_android_ui.ttf` or `sf_pro_rounded_android_ui.ttf`,
 * with the weight given as `'wght'` variation. Both the platform (XML `fontFamily`) and the app's
 * AndroidX/Compose copy load those files through `Font.Builder(AssetManager, String, boolean, int)`
 * or `Font.Builder(Resources, int)`. Swapping the builder's buffer for the system sans-serif one
 * keeps the requested weights (the system font is variable too) while every typeface the app
 * builds renders in the system font.
 */
object SystemFont {

    private val SF_FILES = listOf("sf_pro_android_ui.ttf", "sf_pro_rounded_android_ui.ttf")

    /** The font the default Paint actually shapes Latin text with (includes OEM font choices). */
    private val systemFont: Font? by lazy {
        runCatching {
            TextRunShaper.shapeTextRun("a", 0, 1, 0, 1, 0f, 0f, false, Paint()).getFont(0)
        }.onFailure { Entry.log("system font lookup failed: $it") }.getOrNull()
            ?.also { Entry.log("system font: ${it.file} axes=${it.axes?.joinToString()}") }
    }

    fun hook() {
        val builder = Font.Builder::class.java

        fun replace(builder: Any, path: String?) {
            if (path == null || SF_FILES.none { path.endsWith(it) }) return
            val font = systemFont ?: return
            XposedHelpers.setObjectField(builder, "mBuffer", font.buffer.duplicate())
            XposedHelpers.setObjectField(builder, "mFile", font.file)
            XposedHelpers.setObjectField(builder, "mException", null)
        }

        // Patch the builders after construction (the private createBuffer() may be inlined).
        XposedBridge.hookMethod(
            builder.getDeclaredConstructor(
                AssetManager::class.java, String::class.java,
                Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) =
                    replace(param.thisObject, param.args[1] as? String)
            },
        )
        XposedBridge.hookMethod(
            builder.getDeclaredConstructor(Resources::class.java, Int::class.javaPrimitiveType),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val path = runCatching {
                        TypedValue().also { (param.args[0] as Resources).getValue(param.args[1] as Int, it, true) }
                            .string?.toString()
                    }.getOrNull()
                    replace(param.thisObject, path)
                }
            },
        )
    }
}
