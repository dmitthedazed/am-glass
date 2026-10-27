package dev.dimka.amglass

import android.content.res.ColorStateList
import android.graphics.RenderNode
import android.widget.ImageView
import android.widget.TextView
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlin.math.abs
import dev.dimka.amglass.catalog.LiquidBottomTab
import dev.dimka.amglass.catalog.LiquidBottomTabs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.WeakHashMap

object GlassInstaller {

    private const val TAG = "am-glass"
    private val backdrops = WeakHashMap<View, BlurTargetBackdrop>()

    /**
     * Apple Music's own lifecycle/recomposer tags belong to its (obfuscated) copy of AndroidX,
     * which our classloader can't see, so the glass views get a private owner and recomposer.
     */
    private val owner by lazy { GlassOwner() }
    private val recomposer by lazy {
        Recomposer(AndroidUiDispatcher.Main).also { r ->
            CoroutineScope(AndroidUiDispatcher.Main).launch { r.runRecomposeAndApplyChanges() }
        }
    }

    fun install(blurView: FrameLayout, target: View, node: RenderNode, style: GlassStyle) {
        if (blurView.findViewWithTag<View>(TAG) != null) return
        val backdrop = backdrops.getOrPut(target) { BlurTargetBackdrop(target, node) }

        // The pressed tab bar and its indicator grow past these bounds, so nothing may clip them.
        blurView.clipChildren = false
        // Apple Music re-enables outline clipping on the BlurView later; drop the outline itself
        // and keep the flag off, the glass draws its own capsule.
        blurView.outlineProvider = null
        blurView.clipToOutline = false
        blurView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (blurView.clipToOutline) blurView.clipToOutline = false
                return true
            }
        })
        // A parent clips each child to the child's own bounds, so the container must allow it too.
        (blurView.parent as? ViewGroup)?.clipChildren = false
        blurView.background = null

        val glass = ComposeView(blurView.context).apply {
            tag = TAG
            clipChildren = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setParentCompositionContext(recomposer)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        }
        blurView.addView(
            glass, 0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        // Sibling views are inflated alongside the BlurView but may not be attached yet.
        blurView.post {
            val material = style.materialViewId.takeIf { it.isNotEmpty() }?.let { findById(blurView, it) }
            material?.let(::keepBackgroundCleared)
            when (style) {
                GlassStyle.Tabs -> {
                    val nav = material as? ViewGroup ?: return@post
                    // LiquidBottomTabs is 64dp tall; the native bar is 62dp.
                    nav.layoutParams = nav.layoutParams.apply { height = dp(nav, 64f) }
                    (glass.getChildAt(0) as? ViewGroup)?.clipChildren = false
                    val tabs = NativeTabs(nav, glass)
                    glass.setContent { AppleMusicTabs(backdrop, tabs) }
                }
                GlassStyle.Option -> glass.setContent { GlassOption(backdrop) }
                GlassStyle.Capsule -> {
                    val content = material
                    glass.setContent { GlassCapsule(backdrop) { color -> content?.let { tintMiniPlayer(it, color) } } }
                }
            }
            (glass.getChildAt(0) as? ViewGroup)?.clipChildren = false
        }
    }

    /** Recolors the mini player's title, artist and buttons; the artwork stays untouched. */
    private fun tintMiniPlayer(content: View, color: Int) {
        fun find(name: String): View? =
            content.resources.getIdentifier(name, "id", Entry.PACKAGE).takeIf { it != 0 }?.let(content::findViewById)
        (find("mini_player_title") as? TextView)?.setTextColor(color)
        (find("mini_player_artist") as? TextView)?.setTextColor((color and 0x00FFFFFF) or (0x99 shl 24))
        for (button in listOf("mini_player_play_btn", "mini_player_next_btn")) {
            (find(button) as? ImageView)?.imageTintList = ColorStateList.valueOf(color)
        }
    }

    private fun dp(view: View, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, view.resources.displayMetrics).toInt()

    private fun findById(anchor: View, name: String): View? {
        val id = anchor.resources.getIdentifier(name, "id", Entry.PACKAGE)
        if (id == 0) return null
        return (anchor.parent as? View)?.findViewById(id) ?: anchor.rootView.findViewById(id)
    }

    /** Apple Music paints an opaque "apple material" pill over the bars; keep it off. */
    private fun keepBackgroundCleared(view: View) {
        view.background = null
        view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (view.background != null) view.background = null
                return true
            }
        })
    }
}

/** Catalog BottomTabsContent, with Apple Music's tabs as content. */
@Composable
private fun AppleMusicTabs(backdrop: Backdrop, tabs: NativeTabs) {
    val glass = rememberAdaptiveGlass()
    val items = tabs.tabs
    if (items.isEmpty()) return

    val selectedTabIndex = remember(tabs) { { tabs.selectedIndex } }
    LiquidBottomTabs(
        selectedTabIndex = selectedTabIndex,
        onTabSelected = { if (it != tabs.selectedIndex) tabs.select(it) },
        onTabReselected = { tabs.select(it) },
        backdrop = backdrop,
        tabsCount = items.size,
        glass = glass,
        modifier = Modifier.fillMaxSize(),
    ) {
        items.forEachIndexed { index, tab ->
            LiquidBottomTab({ tabs.select(index) }) {
                val painter = remember(tab.icon) { tab.icon?.let(::DrawablePainter) }
                Box(
                    Modifier
                        .size(28f.dp)
                        .then(
                            if (painter != null) Modifier.paint(painter, colorFilter = ColorFilter.tint(glass.contentColor))
                            else Modifier
                        )
                )
                BasicText(
                    tab.label.toString(),
                    style = TextStyle(Color.Unspecified, 12f.sp),
                    color = { glass.contentColor }
                )
            }
        }
    }
}

/**
 * Mini player: the same iOS 26 adaptive glass as the tab bar, with the lens scaled from the bar's
 * 64dp height. Its native text and buttons follow the glass content color.
 */
@Composable
private fun GlassCapsule(backdrop: Backdrop, onContentColor: (Int) -> Unit) {
    val glass = rememberAdaptiveGlass()
    LaunchedEffect(glass) { onContentColor(glass.contentColor.toArgb()) }
    Box(
        Modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = { with(glass) { effects(size.height / 64f.dp.toPx()) } },
                highlight = { Highlight.Default },
                shadow = { Shadow() },
                onDrawSurface = glass.drawSurface,
            )
            .fillMaxSize()
    )
}

/**
 * Header buttons (square → capsule/circle) and the overflow menu (tall → rounded rectangle).
 * Apple Music still clips the BlurView to its own shape; this only shapes the lens.
 */
@Composable
private fun GlassOption(backdrop: Backdrop) {
    val glass = rememberAdaptiveGlass()
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Buttons are small squares; a short menu can be square too, so size decides, not just aspect.
    val isButton = abs((maxWidth - maxHeight).value) < 4f && maxHeight <= 64f.dp
    val shape = if (isButton) Capsule() else RoundedRectangle(24f.dp)
    Box(
        Modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = { with(glass) { effects(size.minDimension / 64f.dp.toPx()) } },
                highlight = { Highlight.Default },
                onDrawSurface = glass.drawSurface,
            )
            .fillMaxSize()
    )
    }
}

private class DrawablePainter(private val drawable: Drawable) : Painter() {
    override val intrinsicSize: Size
        get() = Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())

    override fun DrawScope.onDraw() {
        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }
}

private class GlassOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    init {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
}
