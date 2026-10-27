package dev.dimka.amglass

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderNode
import android.graphics.drawable.BitmapDrawable
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.WeakHashMap

/**
 * Material 3 shared-axis X transitions for pushes and pops inside a tab (Library → Playlists,
 * Settings → Crossfade, back). Apple Music navigates with Jetpack Navigation without animations,
 * and FragmentManager's special-effects controller never starts animators we hand it for these
 * fragments, so we animate the page views ourselves.
 *
 * Pages are children of the inner `nested_nav_host_fragment` FragmentContainerView (the
 * NavHostFragment's own view, same id as its container). When a page is added there while
 * another is still attached, that is a transition: the new page slides/fades in, the old one
 * slides/fades out as a hardware-bitmap snapshot in the container overlay (FragmentManager hides
 * and removes it at once and ends any `startViewTransition` hold, so the live view can't be used).
 * Direction: forward if `FragmentNavigator.d` (navigate) ran just before, otherwise a pop (system
 * back is handled by FragmentManager itself, not by the navigator).
 */
object Transitions {

    private const val DURATION = 400L
    private const val FADE = 0.3f // share of the duration the old page takes to fade out
    private const val SLIDE_DP = 30f
    private const val NAVIGATE_WINDOW = 500L
    private val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)

    private val running = WeakHashMap<View, Boolean>()
    private var pagesId = 0
    @Volatile private var lastNavigate = 0L

    fun hook(cl: ClassLoader) {
        val navigator = cl.loadClass("androidx.navigation.fragment.FragmentNavigator")
        val navigate = navigator.declaredMethods.first {
            it.name == "d" && it.parameterTypes.size == 3 && it.parameterTypes[0] == List::class.java
        }
        XposedBridge.hookMethod(navigate, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                lastNavigate = SystemClock.uptimeMillis()
            }
        })

        val containerView = cl.loadClass("androidx.fragment.app.FragmentContainerView")
        XposedBridge.hookMethod(
            containerView.getMethod("addView", View::class.java, Int::class.javaPrimitiveType, ViewGroup.LayoutParams::class.java),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { onPageAdded(param.thisObject as ViewGroup, param.args[0] as View) }
                        .onFailure { Entry.log("transition: ${it.stackTraceToString().take(400)}") }
                }
            },
        )
    }

    private fun onPageAdded(container: ViewGroup, page: View) {
        if (pagesId == 0) pagesId = container.resources.getIdentifier("nested_nav_host_fragment", "id", Entry.PACKAGE)
        if (container.id != pagesId || !container.isShown || running[container] == true) return
        val old = (0 until container.childCount).map { container.getChildAt(it) }.filter { it !== page }
        if (old.isEmpty()) return // a tab's first page: nothing to transition from
        // Pushes go through FragmentNavigator.navigate; system back pops in FragmentManager.
        val forward = SystemClock.uptimeMillis() - lastNavigate < NAVIGATE_WINDOW
        // The old page is hidden and removed by FragmentManager right away (it also ends any view
        // transition we start), so it leaves as a snapshot in the container's overlay.
        val leaving = old.filter { it.isShown }.mapNotNull { v -> snapshot(v)?.let { BitmapDrawable(container.resources, it).apply { setBounds(v.left, v.top, v.right, v.bottom) } } }
        running[container] = true
        leaving.forEach { container.overlay.add(it) }
        animate(container, leaving, page, forward)
    }

    private fun animate(container: ViewGroup, leaving: List<BitmapDrawable>, page: View, forward: Boolean) {
        val slide = SLIDE_DP * container.resources.displayMetrics.density
        val inFrom = if (forward) slide else -slide
        val outTo = if (forward) -slide else slide
        val bounds = leaving.map { Rect(it.bounds) }
        fun frame(t: Float) {
            val e = emphasized.getInterpolation(t)
            page.translationX = inFrom * (1 - e)
            page.alpha = ((t - FADE) / (1 - FADE)).coerceIn(0f, 1f)
            leaving.forEachIndexed { i, d ->
                val dx = (outTo * e).toInt()
                d.setBounds(bounds[i].left + dx, bounds[i].top, bounds[i].right + dx, bounds[i].bottom)
                d.alpha = ((1 - (t / FADE).coerceIn(0f, 1f)) * 255).toInt()
            }
            container.invalidate()
        }
        frame(0f)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION
            interpolator = LinearInterpolator()
            addUpdateListener { frame(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    page.translationX = 0f
                    page.alpha = 1f
                    leaving.forEach {
                        container.overlay.remove(it)
                        it.bitmap.recycle()
                    }
                    running.remove(container)
                }
            })
            start()
        }
    }

    /**
     * Renders [view] into a hardware bitmap: recorded into a RenderNode (children stay
     * RenderNodes, so hardware bitmaps like artwork work) and drawn by a HardwareRenderer.
     */
    private fun snapshot(view: View): Bitmap? {
        val w = view.width
        val h = view.height
        if (w == 0 || h == 0) return null
        val node = RenderNode("am-glass-transition").apply { setPosition(0, 0, w, h) }
        view.draw(node.beginRecording(w, h))
        node.endRecording()
        val reader = ImageReader.newInstance(
            w, h, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        val renderer = HardwareRenderer()
        return try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            reader.acquireNextImage()?.use { image ->
                image.hardwareBuffer?.use { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
            }
        } finally {
            renderer.destroy()
            reader.close()
            node.discardDisplayList()
        }
    }
}
