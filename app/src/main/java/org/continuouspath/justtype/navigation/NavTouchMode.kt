package org.continuouspath.justtype.navigation

/**
 * Active mode for [NavTouchOverlay]. INACTIVE never consumes touch events.
 * (The IME's DIRECTIONAL_TWO_SWITCH mode is intentionally omitted for v1.)
 */
enum class NavTouchMode {
	INACTIVE,
	TOUCH_SCREEN_SWITCH,
	DIRECTIONAL_SELECTION,
}
