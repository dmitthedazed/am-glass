package dev.dimka.amglass

/**
 * Which Apple Music BlurViews get glass.
 * [materialViewId] names the view Apple Music paints its opaque "apple material" on;
 * its background is dropped so the glass shows through.
 */
enum class GlassStyle(val materialViewId: String) {
    /** Replaced 1:1 by the catalog's LiquidBottomTabs, driven by the native nav view. */
    Tabs("bottom_nav_view"),
    /** Plain capsule with the same glass stack as the tab bar container. */
    Capsule("mini_player_content"),
    /** Round header buttons and the overflow popup menu (same BlurView layout, no material view). */
    Option("");

    companion object {
        fun forId(id: String?): GlassStyle? = when (id) {
            "bottom_nav_blur" -> Tabs
            "mini_player_blur" -> Capsule
            "option_button_blur" -> Option
            else -> null
        }
    }
}
