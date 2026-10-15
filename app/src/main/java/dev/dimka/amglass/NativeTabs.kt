package dev.dimka.amglass

import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.WeakHashMap

/**
 * Bridges Apple Music's Material BottomNavigationView to the Compose LiquidBottomTabs:
 * the native view stays in place (invisible) as the source of tabs, selection and navigation,
 * while its touches are forwarded to the glass ComposeView.
 */
class NativeTabs(
    private val nav: ViewGroup,
    private val glass: View,
) {
    data class Tab(val icon: Drawable?, val label: CharSequence)

    var tabs by mutableStateOf(emptyList<Tab>()); private set
    var selectedIndex by mutableIntStateOf(0); private set

    private val navLoc = IntArray(2)
    private val glassLoc = IntArray(2)

    init {
        registry[nav] = this
        nav.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                sync()
                return true
            }
        })
        sync()
    }

    private fun menu(): ViewGroup? = nav.getChildAt(0) as? ViewGroup

    private fun sync() {
        val menu = menu() ?: return
        // Native items stay laid out (for performClick) but are never seen.
        if (menu.alpha != 0f) menu.alpha = 0f
        if (glass.alpha != nav.alpha) glass.alpha = nav.alpha

        val items = (0 until menu.childCount).map { menu.getChildAt(it) }
        if (items.size != tabs.size || items.zip(tabs).any { (v, t) -> label(v) != t.label }) {
            tabs = items.map { Tab(icon(it), label(it)) }
        }
        items.indexOfFirst { it.isSelected }.takeIf { it >= 0 }?.let { selectedIndex = it }
    }

    fun select(index: Int) {
        menu()?.getChildAt(index)?.performClick()
    }

    /** Re-dispatches a nav-view touch to the glass ComposeView. */
    fun forward(event: MotionEvent): Boolean {
        nav.getLocationInWindow(navLoc)
        glass.getLocationInWindow(glassLoc)
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation((navLoc[0] - glassLoc[0]).toFloat(), (navLoc[1] - glassLoc[1]).toFloat())
        glass.dispatchTouchEvent(copy)
        copy.recycle()
        return true
    }

    private fun label(item: View): CharSequence =
        item.contentDescription ?: firstOf<TextView>(item)?.text ?: ""

    private fun icon(item: View): Drawable? =
        firstOf<ImageView>(item)?.drawable?.constantState?.newDrawable(item.resources)?.mutate()

    private inline fun <reified T : View> firstOf(view: View): T? = firstOf(view, T::class.java)

    private fun <T : View> firstOf(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) firstOf(view.getChildAt(i), type)?.let { return it }
        return null
    }

    companion object {
        private val registry = WeakHashMap<View, NativeTabs>()
        fun forNav(view: Any): NativeTabs? = registry[view]
    }
}
