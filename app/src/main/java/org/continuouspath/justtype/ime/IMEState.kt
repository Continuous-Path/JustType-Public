package org.continuouspath.justtype.ime

import org.continuouspath.justtype.logic.JTUI

/**
 * Type-checked readiness state for the IME's `JTUI` instance.
 *
 * Lifecycle:
 * - [Loading]: JTUI not yet constructed. Touching JTUI is a crash.
 * - [Constructed]: JTUI assigned, but the heavy [JTUI.init] (opens databases, loads
 *   vocabulary) hasn't finished. Plain field reads/writes (e.g. `isInSettingsMode`,
 *   `itemsPerColumn`) are safe; anything that touches `wordDb`/`wld`/`customDb` is not.
 * - [Ready]: JTUI fully initialised. All operations are safe.
 *
 * Callers that need a fully-ready JTUI should match against [Ready] or use [isReady].
 * Callers that only need a constructed JTUI (plain field access) can use [jtui], which
 * returns the instance whenever the state is [Constructed] or [Ready].
 */
sealed class IMEState {
	data object Loading : IMEState()
	data class Constructed(val jtui: JTUI) : IMEState()
	data class Ready(val jtui: JTUI) : IMEState()
}

/** The JTUI instance if assigned (Constructed or Ready), else null. */
val IMEState.jtui: JTUI?
	get() = when (this) {
		is IMEState.Constructed -> jtui
		is IMEState.Ready -> jtui
		IMEState.Loading -> null
	}

/** True when JTUI's heavy init() has completed. */
val IMEState.isReady: Boolean get() = this is IMEState.Ready

/** The fully-initialized JTUI if state is Ready, else null. */
val IMEState.readyJtui: JTUI? get() = (this as? IMEState.Ready)?.jtui
