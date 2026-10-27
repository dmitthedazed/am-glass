package dev.dimka.amglass

import android.graphics.RenderNode
import android.view.View
import android.widget.FrameLayout
import android.content.res.AssetManager
import android.content.res.Resources
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Proxy

/**
 * Apple Music 7.x draws its floating bars with Dimezis BlurView v3: a [BlurTarget] records the
 * page content into a public RenderNode and each BlurView redraws that node with a blur.
 * We keep the target, drop BlurView's own blur controller and put a ComposeView with the
 * backdrop library's `drawBackdrop` inside the BlurView, sampling the same RenderNode.
 */
class Entry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    private lateinit var modulePath: String

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PACKAGE) return
        val cl = lpparam.classLoader
        hookModuleResources()
        runCatching { DynamicColors.hook() }.onFailure { log("dynamic colors hook failed: $it") }
        runCatching { SystemFont.hook() }.onFailure { log("system font hook failed: $it") }
        val blurViewClass = cl.loadClass("eightbitlab.com.blurview.BlurView")
        val blurTargetClass = cl.loadClass("eightbitlab.com.blurview.BlurTarget")

        // Field and method names are obfuscated, so match them by type.
        val nodeField = blurTargetClass.declaredFields.first { it.type == RenderNode::class.java }
            .apply { isAccessible = true }
        val controllerField = blurViewClass.declaredFields.first { it.type.isInterface }
            .apply { isAccessible = true }
        val controllerType = controllerField.type
        val setupTarget = blurViewClass.declaredMethods.first {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == blurTargetClass
        }

        XposedBridge.hookMethod(setupTarget, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val blurView = param.thisObject as FrameLayout
                val target = param.args[0] as View
                val name = idName(blurView)
                val style = GlassStyle.forId(name)
                log("BlurView '$name' -> ${if (style != null) "glass" else "untouched"}")
                if (style == null) return

                val node = nodeField.get(target) as? RenderNode ?: return
                param.result?.let { disableController(it, controllerType) }
                val noop = noopController(cl, controllerType)
                controllerField.set(blurView, noop)
                param.result = noop

                runCatching { GlassInstaller.install(blurView, target, node, style) }
                    .onFailure { log("install failed for $name: ${it.stackTraceToString()}") }
            }
        })


        // The native nav view sits above the glass and would eat its touches; hand them over.
        val dispatchTouch = android.view.ViewGroup::class.java
            .getDeclaredMethod("dispatchTouchEvent", android.view.MotionEvent::class.java)
        XposedBridge.hookMethod(dispatchTouch, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val tabs = NativeTabs.forNav(param.thisObject) ?: return
                param.result = tabs.forward(param.args[0] as android.view.MotionEvent)
            }
        })

        // Settings switches: feed marker colors to Material's Switch, repaint markers as glass.
        runCatching {
            val (switch, bgDraw) = SwitchGlass.bind(cl)
            XposedBridge.hookMethod(switch, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[4] = SettingsM3.switchColors()
                }
            })
            XposedBridge.hookMethod(bgDraw, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching { if (SwitchGlass.drawBackground(param.thisObject, param.args[0])) param.result = null }
                        .onFailure { log("switch glass: ${it.stackTraceToString().take(600)}") }
                }
            })
        }.onFailure { log("switch hook failed: $it") }

        // Settings screens: Material 3 segmented lists and headers.
        runCatching { SettingsM3.hook(cl) }.onFailure { log("settings m3 hook failed: $it") }

        // Material 3 shared-axis transitions for pushes inside a tab.
        runCatching { Transitions.hook(cl) }.onFailure { log("transitions hook failed: $it") }

        // Popup menus blur with Haze inside the app's own Compose; swap that effect for glass.
        runCatching {
            XposedBridge.hookMethod(HazeGlass.bind(cl), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching { if (HazeGlass.draw(param.thisObject, param.args[0])) param.result = null }
                        .onFailure { log("haze glass: ${it.stackTraceToString().take(600)}") }
                }
            })
        }.onFailure { log("haze hook failed: $it") }

        log("hooked ${blurViewClass.name}.${setupTarget.name}, controller=${controllerType.name}")
    }

    /**
     * Compose reads its own strings (accessibility labels) through the host's Resources, which
     * don't contain them. Module resources are built with package id 0x6e, so route exactly
     * those ids to the module APK.
     */
    private fun hookModuleResources() {
        fun load(): Resources {
            val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
            AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(assets, modulePath)
            val system = Resources.getSystem()
            @Suppress("DEPRECATION") return Resources(assets, system.displayMetrics, system.configuration)
        }
        var moduleRes: Resources? = null
        XposedBridge.hookMethod(
            Resources::class.java.getMethod("getText", Int::class.javaPrimitiveType),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val id = param.args[0] as Int
                    if (id ushr 24 != MODULE_PACKAGE_ID || param.thisObject === moduleRes) return
                    // A missing label must never crash the host (seen once right after a module
                    // reinstall): reload the module resources once, then fall back to "".
                    param.result = runCatching { (moduleRes ?: load().also { moduleRes = it }).getText(id) }
                        .recoverCatching { load().also { moduleRes = it }.getText(id) }
                        .getOrElse {
                            log("module string 0x${id.toString(16)} unavailable: $it")
                            ""
                        }
                }
            },
        )
    }

    /** Detach the original controller's pre-draw listener and free its RenderNode. */
    private fun disableController(controller: Any, type: Class<*>) {
        runCatching {
            type.methods.firstOrNull { it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) }
                ?.invoke(controller, false)
            type.getMethod("destroy").invoke(controller)
        }.onFailure { log("disableController: $it") }
    }

    /** Controller whose draw call returns true, so BlurView just draws its children (our glass). */
    private fun noopController(cl: ClassLoader, type: Class<*>): Any {
        lateinit var proxy: Any
        proxy = Proxy.newProxyInstance(cl, arrayOf(type)) { _, method, _ ->
            when {
                method.returnType == Boolean::class.javaPrimitiveType -> true
                type.isAssignableFrom(method.returnType) -> proxy
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "toString" -> "AmGlassNoopController"
                else -> null
            }
        }
        return proxy
    }

    private fun idName(view: View): String? =
        if (view.id == View.NO_ID) null
        else runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()

    companion object {
        const val PACKAGE = "com.apple.android.music"
        private const val MODULE_PACKAGE_ID = 0x6e
        fun log(msg: String) = XposedBridge.log("[AmGlass] $msg")
    }
}
