package org.continuouspath.justtype.navigation

import android.content.Context
import android.graphics.Rect
import android.view.ViewConfiguration
import org.continuouspath.justtype.R
import org.continuouspath.justtype.navigation.engine.DragStep
import org.continuouspath.justtype.navigation.engine.GesturePaths
import org.continuouspath.justtype.navigation.engine.NavBounds
import org.continuouspath.justtype.navigation.engine.NavDirection
import org.continuouspath.justtype.navigation.engine.ScrollReach
import kotlin.math.abs
import kotlin.math.min

/**
 * Owns the transient length arrow shown beside the Nav grid: overlay lifecycle, span math,
 * auto-hide, and fade-out. Serves both step-size axes — scroll reach and drag-cursor step —
 * since both express "how far one press travels". A collaborator so the service stays at its
 * function budget; the span comes from the same travel math the injected gesture uses.
 */
class NavLengthArrowController(
	private val context: Context,
	private val scheduler: NavScheduler,
	private val gridBounds: () -> Rect?,
) {
	private var overlay: ScrollLengthArrowOverlay? = null

	// Lazy: the service constructs this before its Context is attached.
	private val tapThresholdPx by lazy { ViewConfiguration.get(context).scaledTouchSlop }

	/** Show (or refresh) the arrow for a scroll [reach]; every call extends the auto-hide timeout. */
	fun show(reach: ScrollReach, atFloor: Boolean) {
		val screen = screenRect()
		// Canonical horizontal axis — the arrow shows how far, not which way.
		val segment = GesturePaths.scrollSwipe(
			NavBounds(0, 0, screen.right, screen.bottom),
			NavDirection.RIGHT,
			reach.percent,
			avoid = null,
		)
		val spanPx = abs(segment.endX - segment.startX)
		val label = if (atFloor || ScrollReach.isBelowTapThreshold(spanPx, tapThresholdPx)) {
			context.getString(R.string.nav_scroll_min)
		} else {
			null
		}
		showSpan(spanPx, screen, label)
	}

	/** Show (or refresh) the arrow for a drag-cursor [step]; every call extends the auto-hide timeout. */
	fun show(step: DragStep, atFloor: Boolean) {
		val screen = screenRect()
		val spanPx = step.stepPx(min(screen.width(), screen.height()))
		val label = if (atFloor) context.getString(R.string.nav_scroll_min) else null
		showSpan(spanPx, screen, label)
	}

	private fun showSpan(spanPx: Int, screen: Rect, label: String?) {
		val o = overlay ?: ScrollLengthArrowOverlay(context).also {
			overlay = it
			it.show()
		}
		o.setArrow(spanPx, gridBounds(), screen, label)
		// Hold at full opacity, then fade — every show() re-arms both timers from now.
		scheduler.post(TOKEN_LENGTH_ARROW, LENGTH_ARROW_TIMEOUT_MS) { fade() }
	}

	private fun screenRect(): Rect {
		val m = context.resources.displayMetrics
		return Rect(0, 0, m.widthPixels, m.heightPixels)
	}

	private fun fade() {
		val o = overlay ?: return
		o.fadeOut { if (overlay === o) overlay = null }
	}

	fun hide() {
		scheduler.cancel(TOKEN_LENGTH_ARROW)
		overlay?.hide()
		overlay = null
	}

	private companion object {
		const val LENGTH_ARROW_TIMEOUT_MS = 1500L
		const val TOKEN_LENGTH_ARROW = "length_arrow_hide"
	}
}
