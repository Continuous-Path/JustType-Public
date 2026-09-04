package org.continuouspath.justtype.navigation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.continuouspath.justtype.BuildConfig
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.GamepadDirectionDetector
import org.continuouspath.justtype.R
import org.continuouspath.justtype.ime.ExternalSwitchHandler
import org.continuouspath.justtype.ime.GamepadParams
import org.continuouspath.justtype.ime.HeadTrackingSubsystem
import org.continuouspath.justtype.ime.JoystickSubsystem
import org.continuouspath.justtype.ime.ScanSubsystem
import org.continuouspath.justtype.ime.TwoSwitchSubsystem
import org.continuouspath.justtype.input.HatSwitchCodes
import org.continuouspath.justtype.input.HatSwitchEdgeDetector
import org.continuouspath.justtype.input.HeadBoardSwitchBus
import org.continuouspath.justtype.input.InputCaptureGate
import org.continuouspath.justtype.navigation.engine.CandidatePolicy
import org.continuouspath.justtype.navigation.engine.FocusGeometry
import org.continuouspath.justtype.navigation.engine.GesturePaths
import org.continuouspath.justtype.navigation.engine.NavBounds
import org.continuouspath.justtype.navigation.engine.NavDirection
import org.continuouspath.justtype.navigation.engine.NavSettingsRouter
import org.continuouspath.justtype.navigation.engine.ScrollPlanner
import org.continuouspath.justtype.navigation.engine.SelectionFingerprint
import org.continuouspath.justtype.navigation.engine.SelectionMatcher
import org.continuouspath.justtype.navigation.engine.SwitchRoles
import org.continuouspath.justtype.navigation.engine.WindowStatePolicy
import org.continuouspath.justtype.settings.CrashLoopRecovery
import org.continuouspath.justtype.settings.SettingsRegistry
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.effectiveInputMethod
import kotlin.math.abs

// Wires the overlay, every input subsystem, and the selection/scroll engine; decision logic
// lives in the engine/ + collaborator tier.
class NavigationModeService :
	AccessibilityService(),
	NavActionDispatcher {

	private var overlay: NavigationOverlayHost? = null
	private var focusRing: NavigationFocusOverlay? = null
	private var selectedNode: AccessibilityNodeInfo? = null

	// Page/availability/reach transitions live in NavPageState; these lambdas are its
	// Android-side effects. (repositionTimeout is declared below; lambdas defer the reference.)
	private val pageState: NavPageState = NavPageState(
		computeAvailability = { computeAvailability(selectedNode) },
		onRender = { rerender() },
		onDragTeardown = {
			hideDragCursor()
			hideSelectCursor()
			liveDrag.abort() // leaving a drag without a DROP lifts the held finger in place
			overlay?.setHandleTouchLocked(false)
			dragController.cancel()
		},
		onSeedSelectCursor = { seedSelectCursor() },
		onShowReachArrow = { reach ->
			lengthArrow.show(reach, atFloor = reach.atMin(SCROLL_STEP_MIN_PERCENT))
		},
		onHideArrow = { lengthArrow.hide() },
		armRepositionTimeout = { arm ->
			mainHandler.removeCallbacks(repositionTimeout)
			if (arm) mainHandler.postDelayed(repositionTimeout, REPOSITION_TIMEOUT_MS)
		},
	)
	private val nodeActions = NodeActionPerformer()
	private val snapshotter = NodeSnapshotter()
	private val gestures = GestureDispatcher(this)
	private var captureGeneration = 0L

	// An edge-scroll is animating and its reselect is pending; arrows are swallowed meanwhile.
	private var edgeScrollInFlight = false
	private var scrollSettle: ScrollSettleState? = null
	private var gestureScrollInFlight = false

	// Transient scroll reach — in-memory only, reset to the default on every ScrollMode entry.

	// Last ring rect. Survives node death and window changes so selection re-seeds near
	// where the user was, instead of resetting to the tree's first node.
	private var lastSelectionBounds: Rect? = null

	// Identity of the selection, refreshed on every (re)selection — lets a dead live node be
	// re-pointed at the same logical node in a fresh capture instead of a nearest-guess.
	private var selectionFingerprint: SelectionFingerprint? = null
	private lateinit var prefs: SettingsRepository
	private val mainHandler = Handler(Looper.getMainLooper())
	private val scheduler = NavScheduler(mainHandler)
	private val lengthArrow = NavLengthArrowController(this, scheduler) { overlay?.windowBoundsOnScreen() }
	private val dragController = DragController(DRAG_STEP_MIN_PERCENT, DRAG_STEP_MAX_PERCENT, DRAG_STEP_INCREMENT)
	private val liveDrag = LiveDragSession(gestures, scheduler)
	private var dragCursor: DragCursorOverlay? = null

	// Pick-up crosshair state lives in the controller; rendering (the shared drop-cursor
	// overlay) stays here — see showSelectCursor/hideSelectCursor.
	private val selectCursor = SelectCursorController(DRAG_STEP_MIN_PERCENT, DRAG_STEP_MAX_PERCENT, DRAG_STEP_INCREMENT)

	// Refresh the drop-cursor overlay to the controller's current cursor (a val, not a method — keeps the service off its function budget).
	private val showDragCursor: () -> Unit = {
		val bounds = dragController.cursorBounds()
		if (bounds != null) {
			val o = dragCursor ?: DragCursorOverlay(this).also {
				dragCursor = it
				it.show()
			}
			o.setCursor(bounds, dragController.pickupBounds())
		}
	}
	private val hideDragCursor: () -> Unit = {
		dragCursor?.hide()
		dragCursor = null
	}

	// Drag operations — orchestration only; the session logic lives in dragController / liveDrag.
	private val liveDragEnabled: Boolean get() = prefs.getBoolean(Constants.KEY_NAV_LIVE_DRAG, false)
	private val startDrag: () -> Boolean = {
		val start = selectCursor.cursorBounds()
		if (start == null) {
			false // no start point seeded — error cue
		} else {
			val m = resources.displayMetrics
			hideSelectCursor() // the drop crosshair replaces the pick-up crosshair
			dragController.select(start, Rect(0, 0, m.widthPixels, m.heightPixels))
			// Live mode presses the finger down at the pickup now and holds it through the move.
			// Lock the window-drag handle so an injected stroke landing on it can't move the whole
			// grid (keys stay live for arrows); unlocked when the session ends (goToPage/returnToNav).
			if (liveDragEnabled) {
				liveDrag.begin(start.centerX(), start.centerY())
				overlay?.setHandleTouchLocked(true)
			}
			pageState.goToPage(OverlayPage.DragMoveMode)
			showDragCursor()
			// Surface the current drag step beside the grid on entry (goToPage hid any prior arrow).
			lengthArrow.show(dragController.step, atFloor = dragController.atMinStep())
			true
		}
	}
	private val dragNudge: (NavDirection) -> Boolean = { dir ->
		if (pageState.page == OverlayPage.DragMode) {
			selectNudge(dir) // pick-up page: move the free start-point cursor, not a drop cursor
		} else if (!dragController.isActive) {
			false
		} else {
			dragController.nudge(dir)
			// Live mode drags the held finger to the new cursor in real time.
			if (liveDrag.isActive) dragController.cursorBounds()?.let { liveDrag.extendTo(it.centerX(), it.centerY()) }
			showDragCursor()
			true
		}
	}
	private val adjustDragStep: (Boolean) -> Boolean = { grow ->
		val before = dragController.step
		if (grow) dragController.growStep() else dragController.shrinkStep()
		lengthArrow.show(dragController.step, atFloor = dragController.atMinStep())
		dragController.step != before // false at the bound → error cue
	}
	private val commitDrop: () -> Boolean = {
		if (liveDrag.isActive) {
			// Live mode already moved the target as the finger travelled — just lift to drop.
			val ok = liveDrag.release()
			hideDragCursor()
			dragController.cancel()
			ok
		} else {
			val gesture = dragController.buildDrop()
			hideDragCursor()
			dragController.cancel()
			if (gesture == null) {
				false
			} else {
				// Let the injected drop pass through the grid to the app: if the pickup or drop path
				// sits under the floating kbd, the grid would otherwise grab the touch and move itself.
				// Restore touch after the gesture finishes playing (it plays on wall-clock post-dispatch).
				overlay?.setTouchable(false)
				scheduler.post(TOKEN_DROP_TOUCH_RESTORE, gesture.totalDurationMs + DROP_TOUCH_RESTORE_MARGIN_MS) {
					overlay?.setTouchable(true)
				}
				gestures.dragGesture(gesture) { done -> if (!done) feedback?.errorFeedback() }
			}
		}
	}

	// Drag-page action router — keeps the drag branches out of dispatch()'s complexity budget.
	// Also owns the scroll-vs-drag split for the shared LONGER/SHORTER keys.
	private val dragDispatch: (NavAction) -> Boolean = { action ->
		when (action) {
			NavAction.PickUp -> startDrag()
			NavAction.DropTarget -> commitDrop().also { pageState.returnToNav() }
			NavAction.DragMoveUp -> dragNudge(NavDirection.UP)
			NavAction.DragMoveDown -> dragNudge(NavDirection.DOWN)
			NavAction.DragMoveLeft -> dragNudge(NavDirection.LEFT)
			NavAction.DragMoveRight -> dragNudge(NavDirection.RIGHT)
			NavAction.PathLonger -> adjustStepFor(pageState.page, grow = true)
			else -> adjustStepFor(pageState.page, grow = false) // PathShorter
		}
	}

	// Route the shared LONGER/SHORTER keys to whichever step the current page owns.
	private fun adjustStepFor(page: OverlayPage, grow: Boolean): Boolean = when (page) {
		OverlayPage.DragMode -> adjustSelectStep(grow)
		OverlayPage.DragMoveMode -> adjustDragStep(grow)
		else -> pageState.adjustReach(grow, SCROLL_STEP_INCREMENT, SCROLL_STEP_MIN_PERCENT, SCROLL_STEP_MAX_PERCENT)
	}

	/** Seed the pick-up cursor near the last selection (else screen center), clamped on screen. */
	private fun seedSelectCursor() {
		val m = resources.displayMetrics
		selectCursor.seed(lastSelectionBounds, Rect(0, 0, m.widthPixels, m.heightPixels))
		showSelectCursor()
		lengthArrow.show(selectCursor.step, atFloor = selectCursor.atMinStep())
	}

	private fun selectNudge(dir: NavDirection): Boolean {
		val m = resources.displayMetrics
		if (!selectCursor.nudge(dir, Rect(0, 0, m.widthPixels, m.heightPixels))) return false
		showSelectCursor()
		return true
	}

	private fun adjustSelectStep(grow: Boolean): Boolean {
		val changed = selectCursor.adjustStep(grow)
		lengthArrow.show(selectCursor.step, atFloor = selectCursor.atMinStep())
		return changed // false at the bound -> error cue
	}

	// The pick-up crosshair reuses the drop cursor overlay with no pickup (crosshair only, no lead line).
	private fun showSelectCursor() {
		val c = selectCursor.cursorBounds() ?: return
		val o = dragCursor ?: DragCursorOverlay(this).also {
			dragCursor = it
			it.show()
		}
		o.setCursor(c, null)
	}

	private fun hideSelectCursor() {
		selectCursor.clear()
		dragCursor?.hide()
		dragCursor = null
	}

	private val serviceJob = SupervisorJob()
	private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
	private var inputSurface: NavInputSurface? = null

	private var scanSubsystem: ScanSubsystem? = null
	private var twoSwitchSubsystem: TwoSwitchSubsystem? = null
	private var headTrackingSubsystem: HeadTrackingSubsystem? = null
	private var joystickSubsystem: JoystickSubsystem? = null
	private var gamepadDetector: GamepadDirectionDetector? = null

	// Turns the d-pad HAT stream into paired switch down/up events (see HatSwitchEdgeDetector).
	private val hatSwitchEdges = HatSwitchEdgeDetector()
	private var feedback: NavSubsystemFeedback? = null
	private val navVibrator: android.os.Vibrator? by lazy {
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
			getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
		} else {
			@Suppress("DEPRECATION")
			getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
		}
	}
	private var twoSwitchUnconfiguredToastShown = false

	// Edge-scroll diagnostics, defaulting to BuildConfig.DEBUG (Developer Settings can override).
	private var scrollLogsEnabled = BuildConfig.DEBUG
	private var navTouchOverlay: NavTouchOverlay? = null
	private val volumeCombo = VolumeComboDetector()
	private var inputMethodListenerToken: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

	// Minimized mode: the grid is hidden behind a small floating button. Head tracking
	// stops listening; HeadBoard returns to normal cursor control until a re-open signal.
	private var minimizedOverlay: NavMinimizedOverlay? = null
	private var minimized = false
	private var navResumeReceiverRegistered = false

	private val overlayRequestPoll = object : Runnable {
		override fun run() {
			reconcileOverlayVisibility()
			// Self-heal the motion claim: if a setup screen released it then died before
			// clearing the capture gate (no write to re-fire the listener), the gate ages out
			// and this reclaims the sources on the next tick.
			reconcileMotionListening()
			mainHandler.postDelayed(this, POLL_INTERVAL_MS)
		}
	}

	private var lastReconcileState = ""

	private fun reconcileOverlayVisibility() {
		val enabled = prefs.getBoolean(Constants.KEY_NAVIGATION_MODE_ENABLED, false) &&
			prefs.getBoolean(Constants.KEY_NAVIGATION_OVERLAY_REQUESTED, false)
		val imeVisible = isImeWindowVisible()
		// Nav only opens when JustType is enabled as a system on-screen keyboard,
		// regardless of which IME is currently active (per product requirement).
		val wantOverlay = enabled && !imeVisible && isJustTypeImeEnabledInSystem()
		// Minimized counts as "present" — don't re-create the grid behind the button.
		val present = overlay != null || minimized
		val state = "reconcileOverlay enabled=$enabled imeVisible=$imeVisible want=$wantOverlay " +
			"present=$present minimized=$minimized windows=${windows?.size}"
		if (state != lastReconcileState) {
			lastReconcileState = state
			scrollLog { state }
		}
		if (wantOverlay && !present) {
			showOverlay()
		} else if (!wantOverlay && present) {
			hideOverlay()
		}
	}

	private fun isImeWindowVisible(): Boolean {
		// The window list alone misses an open keyboard: a full-screen touch-capture overlay
		// (ours or the IME's own) occludes the IME window, and the system drops fully-occluded
		// windows from getWindows(). Same process as the IME, so trust its own state first.
		if (org.continuouspath.justtype.ime.ImeWindowState.shown) return true
		val ws = windows ?: return false
		for (w in ws) {
			if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true
		}
		return false
	}

	/** True when JustType is enabled in the system's on-screen keyboard list. */
	private fun isJustTypeImeEnabledInSystem(): Boolean = runCatching {
		val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
			?: return false
		imm.enabledInputMethodList.any { it.packageName == packageName }
	}.getOrDefault(false)

	override fun onServiceConnected() {
		super.onServiceConnected()
		// The XML event mask re-applies only on a service re-toggle; assert it at runtime so
		// an app update picks up mask changes without the user re-enabling the service.
		serviceInfo?.let { info ->
			info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
				AccessibilityEvent.TYPE_WINDOWS_CHANGED or
				AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
				AccessibilityEvent.TYPE_VIEW_SCROLLED
			serviceInfo = info
		}
		// Init the registry too: registry-aware getFloat (GamepadParams.fromSettings) throws if
		// it's uninitialized, and the Nav service can restart before the IME inits it.
		SettingsRegistry.getInstance(applicationContext)
		prefs = SettingsRepository.getInstance(applicationContext)
		maybeRecoverFromCrashLoop()
		// No setup screen is capturing at a fresh service start; clear any stale gate so a
		// reboot during the elapsedRealtime() window can't spuriously suppress input.
		InputCaptureGate.end(prefs)
		scrollLogsEnabled = prefs.getBoolean(Constants.KEY_DEV_SCROLL_LOGS, BuildConfig.DEBUG)
		inputMethodListenerToken = prefs.addMainThreadListener(inputMethodChangeListener)
		isRunning = true
		mainHandler.post(overlayRequestPoll)
		// HeadBoard-driven switches enter the same onKeyEvent path as physical keys.
		HeadBoardSwitchBus.addConsumer(applicationContext, HeadBoardSwitchBus.Priority.NAV, headBoardSwitchConsumer)
	}

	private val headBoardSwitchConsumer = HeadBoardSwitchBus.Consumer { event -> onKeyEvent(event) }

	/**
	 * If the previous session crashed, feed it to the shared crash-loop counter; on a loop, Nav
	 * safe mode quiets the overlay + forces Direct Selection. Whichever surface (IME or Nav) starts
	 * first after a crash handles it, then clears the recovery flag. Leaves KEY_CRASH_REPORT_PENDING
	 * alone — Settings still offers the report.
	 */
	private fun maybeRecoverFromCrashLoop() {
		if (!prefs.getBoolean(Constants.KEY_LAST_SESSION_CRASHED, false)) return
		val crashTime = prefs.getLong(Constants.KEY_LAST_CRASH_TIME, 0L)
		CrashLoopRecovery.record(prefs, crashTime) { CrashLoopRecovery.navSafeMode(prefs) }
		prefs.edit()
			.remove(Constants.KEY_LAST_SESSION_CRASHED)
			.remove(Constants.KEY_LAST_CRASH_TIME)
			.remove(Constants.KEY_LAST_CRASH_MESSAGE)
			.remove(Constants.KEY_LAST_CRASH_THREAD)
			.apply()
	}

	// Gamepad axes (API 34+) arrive on the main looper, so feeding the subsystems is main-thread safe.
	override fun onMotionEvent(event: MotionEvent) {
		if (overlay == null) return
		if (InputCaptureGate.isActive(prefs)) return // a setup screen is capturing a device
		gamepadDetector?.handleMotionEvent(event)
		// The d-pad arrives as HAT-axis motion (not KeyEvents), so it's routed here; face buttons
		// and keyboard keys come through onKeyEvent. Edge-detect the HAT into a switch actuation.
		routeHatSwitch(event)
	}

	/** Map the d-pad HAT axis to a switch actuation, edge-detected (one down on press, one up on release). */
	private fun routeHatSwitch(event: MotionEvent) {
		if (scanSubsystem == null && twoSwitchSubsystem == null) return
		hatSwitchEdges.onHatCode(
			code = HatSwitchCodes.hatToDpadKeyCode(event),
			actuates = { switchRoles().actuates(it) },
			onDown = ::hatSwitchDown,
			onUp = { hatSwitchUp() },
		)
	}

	private fun hatSwitchDown(code: Int) {
		twoSwitchSubsystem?.let { tss ->
			val role = switchRoles().roleForKeyCode(code) ?: return
			tss.switchHeld = true
			tss.handleSwitchDown(role)
			return
		}
		scanSubsystem?.handleSwitchDown(code)
	}

	private fun hatSwitchUp() {
		twoSwitchSubsystem?.let {
			it.switchHeld = false
			it.handleSwitchUp()
			return
		}
		scanSubsystem?.handleSwitchUp()
	}

	/**
	 * True when joystick-on-Nav must use the pre-34 path (no motion-source observation):
	 * either the device is below API 34, or the dev "force pre-34" toggle is on.
	 */
	private fun usePre34JoystickPath(): Boolean = Build.VERSION.SDK_INT < 34 ||
		prefs.getBoolean(Constants.KEY_DEV_FORCE_PRE34_JOYSTICK, false)

	/** Start/stop listening for gamepad/joystick motion events (analog sticks + d-pad HAT; API 34+). */
	private fun setMotionListening(enabled: Boolean) {
		// Hard API gate: the motion-source APIs are API 34+ and would crash below it (nothing
		// to release there either). The dev "force pre-34" flag only suppresses claiming — the
		// release must still run so toggling the flag on doesn't leak a system-wide claim.
		if (Build.VERSION.SDK_INT < 34) return
		val claim = enabled && !usePre34JoystickPath()
		runCatching {
			val info = serviceInfo ?: AccessibilityServiceInfo()
			if (claim) {
				info.flags = info.flags or AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS
				// Stick motion arrives as SOURCE_JOYSTICK (gamepad sticks carry the
				// joystick bit); SOURCE_GAMEPAD is a button source and not a valid
				// motion-event source here.
				info.setMotionEventSources(InputDevice.SOURCE_JOYSTICK)
			} else {
				info.flags = info.flags and AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS.inv()
				info.setMotionEventSources(0)
			}
			serviceInfo = info
		}
	}

	override fun onUnbind(intent: android.content.Intent?): Boolean {
		isRunning = false
		mainHandler.removeCallbacks(overlayRequestPoll)
		hideOverlay()
		return super.onUnbind(intent)
	}

	override fun onDestroy() {
		isRunning = false
		HeadBoardSwitchBus.removeConsumer(headBoardSwitchConsumer)
		mainHandler.removeCallbacks(overlayRequestPoll)
		inputMethodListenerToken?.let { prefs.removeChangeListener(it) }
		inputMethodListenerToken = null
		hideOverlay()
		serviceScope.cancel()
		super.onDestroy()
	}

	override fun onAccessibilityEvent(event: AccessibilityEvent?) {
		val type = event?.eventType ?: return
		when (type) {
			AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowStateChanged(event.windowId)
			AccessibilityEvent.TYPE_WINDOWS_CHANGED -> reconcileOverlayVisibility()
			AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED ->
				if (edgeScrollInFlight) {
					// An awaited edge-scroll settles ~debounce after its LAST movement event —
					// content-changed counts too, for hosts that scroll without VIEW_SCROLLED frames.
					if (scrollSettle != null) {
						scrollSettle?.sawScrollEvent = true
						scheduler.post(TOKEN_EDGE_RESELECT, SCROLL_SETTLE_DEBOUNCE_MS) { finishScrollSettle() }
					}
				} else if (selectedNode != null || pageState.page == OverlayPage.ScrollMode) {
					// Content rebound or scrolled under us (app-initiated included) — debounced ring
					// re-sync (same-token posts replace pending), so event storms collapse to one
					// re-sync per quiet window. ScrollMode also re-greys its keys from settled state.
					scheduler.post(TOKEN_RING_RESYNC, CONTENT_RESYNC_DEBOUNCE_MS) {
						resyncRing()
						pageState.refreshSelectionAvailability()
					}
				}
		}
	}

	/** Passive ring re-sync: follow the selection if it moved; re-point via fingerprint if the
	 *  live node died; clear the ring (anchor + fingerprint kept) if it's gone — selection only
	 *  changes to the same logical node, never to a guess; guesses wait for the next press. */
	private fun resyncRing() {
		val held = selectedNode ?: return
		if (held.refresh()) {
			val r = Rect().also { held.getBoundsInScreen(it) }
			if (r.width() > 0 && r.height() > 0) {
				if (r != lastSelectionBounds) scrollLog { "resync: ring follows selection to $r" }
				selectionFingerprint = NodeSnapshotter.fingerprintOf(held)
				lastSelectionBounds = Rect(r)
				focusRing?.setBounds(r)
				return
			}
		}
		withCapture { capture ->
			val candidates = candidatesIn(capture)
			val fresh = candidates.map { NodeSnapshotter.fingerprintOf(it) }
			val match = selectionFingerprint?.let { SelectionMatcher.match(it, fresh) }
			if (match != null) {
				scrollLog { "resync: fingerprint re-pointed (candidates=${candidates.size})" }
				setSelection(candidates[match])
			} else {
				scrollLog { "resync: selection gone — ring cleared, anchor kept" }
				selectedNode = null
				focusRing?.setBounds(null)
			}
		}
	}

	/**
	 * Soft window-state handling: state changes that leave the selection alive (our own
	 * overlay/menu windows, the IME popping, benign in-app transitions) keep selection,
	 * pending callbacks, and the current page. Only a change the selection doesn't survive
	 * soft-clears — anchor (lastSelectionBounds) and fingerprint kept for re-seeding.
	 */
	private fun onWindowStateChanged(eventWindowId: Int) {
		val held = selectedNode
		val survives = held != null &&
			held.refresh() &&
			run {
				val r = Rect().also { held.getBoundsInScreen(it) }
				val allWindows = windows ?: emptyList()
				WindowStatePolicy.selectionSurvives(
					// A different APP window announcing itself (dialog, new activity) is a real
					// change even when the covered selection still refreshes — the capture overlay
					// blinds the visibility check, so this catches what it can't.
					otherAppWindowAnnounced = eventWindowId != held.windowId &&
						allWindows.any { it.id == eventWindowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION },
					bounds = r.toNavBounds(),
					overlayUp = navTouchOverlay != null,
					visibleToUser = held.isVisibleToUser,
					heldWindowPresent = allWindows.any { it.id == held.windowId },
				)
			}
		if (survives) {
			scrollLog { "windowState: selection survives — kept" }
			return
		}
		cancelPendingNavCallbacks()
		liveDrag.abort() // lift any held finger before the drag is discarded (window changed under us)
		overlay?.setHandleTouchLocked(false)
		dragController.cancel()
		selectedNode = null
		focusRing?.setBounds(null)
		if (pageState.page != OverlayPage.Nav) {
			mainHandler.removeCallbacks(repositionTimeout)
			pageState.resetToNav()
			rerender()
		}
	}

	override fun onInterrupt() { /* no-op */ }

	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)
		// A rotation/resize invalidates the drag's cached screen box and any held-drag coordinates
		// (both live in the old coordinate space), so end an in-progress drag rather than clamp to
		// stale geometry. Also covers the pick-up cursor, whose point is in the old space too.
		// No-op when nothing drag-related is active.
		if (dragController.isActive || liveDrag.isActive || selectCursor.isActive) pageState.returnToNav()
		// A system dark/light toggle writes no pref, so the appearance-keys listener never fires —
		// re-apply the Nav appearance live here (matches the theme-pref-change path).
		overlay?.applyAppearance()
		rerender()
	}

	override fun onKeyEvent(event: KeyEvent?): Boolean {
		val e = event ?: return false

		// Failsafe quick-exit: volume-down ×3 minimizes the Nav kbd, regardless of input method.
		// Works even when a touch-capture overlay (directional / touch-screen-switch) is consuming
		// every touch — those have no other way out. Non-consuming, so the user keeps volume control.
		if (e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN &&
			e.action == KeyEvent.ACTION_DOWN &&
			e.repeatCount == 0
		) {
			volumeCombo.record(e.eventTime)
			if (volumeCombo.tripped(VOLUME_COMBO_WINDOW_MS)) {
				volumeCombo.clear()
				// minimizeOverlay() tears down the input layer (incl. the touch overlay) too.
				if (!minimized) mainHandler.post { minimizeOverlay() }
			}
			return false
		}

		if (overlay == null) return false // Nav overlay hidden → pass to IME / app
		// Consume (but don't re-fire) auto-repeat of a held switch code so it doesn't leak into the
		// focused app; sustained-hold behavior is driven by the subsystem's own auto-repeat.
		if (e.repeatCount != 0) return !InputCaptureGate.isActive(prefs) && switchRoles().recognizes(e.keyCode)
		if (prefs.getBoolean(Constants.KEY_DEV_SWITCH_INPUT_LOGS, false)) {
			android.util.Log.d(
				"SwitchProbe",
				"NAV onKeyEvent code=${e.keyCode} action=${e.action} gateActive=${InputCaptureGate.isActive(prefs)} " +
					"tss=${twoSwitchSubsystem != null} scan=${scanSubsystem != null} method=${prefs.effectiveInputMethod()}",
			)
		}
		if (InputCaptureGate.isActive(prefs)) return false // a setup screen is capturing a switch

		twoSwitchSubsystem?.let { tss ->
			val role = switchRoles().roleForKeyCode(e.keyCode) ?: return@let
			if (e.action == KeyEvent.ACTION_DOWN) {
				tss.switchHeld = true
				tss.handleSwitchDown(role)
			} else if (e.action == KeyEvent.ACTION_UP) {
				tss.switchHeld = false
				tss.handleSwitchUp()
			}
			return true
		}

		scanSubsystem?.let { ss ->
			if (switchRoles().scanMatches(e.keyCode)) {
				if (e.action == KeyEvent.ACTION_DOWN) {
					ss.handleSwitchDown(e.keyCode)
				} else if (e.action == KeyEvent.ACTION_UP) {
					ss.handleSwitchUp()
				}
				return true
			}
		}
		return false
	}

	/** Classification of the configured switch codes, captured fresh so settings changes apply live. */
	private fun switchRoles(): SwitchRoles = SwitchRoles(
		redCode = prefs.getInt(Constants.KEY_RED_SWITCH_CODE, Constants.SWITCH_CODE_UNDEFINED),
		greenCode = prefs.getInt(Constants.KEY_GREEN_SWITCH_CODE, Constants.SWITCH_CODE_UNDEFINED),
		scanCode = prefs.getInt(Constants.KEY_SCAN_SWITCH_CODE, Constants.SWITCH_CODE_UNDEFINED),
		twoSwitchActive = twoSwitchSubsystem != null,
		scanActive = scanSubsystem != null,
		undefinedCode = Constants.SWITCH_CODE_UNDEFINED,
	)

	override fun dispatch(action: NavAction): Boolean {
		// No availability gate: the map only drives key rendering. Attempting the action and
		// reporting the real result stays honest even when the render-time map went stale.
		return when (action) {
			NavAction.Up -> {
				move(View.FOCUS_UP)
				true
			}
			NavAction.Down -> {
				move(View.FOCUS_DOWN)
				true
			}
			NavAction.Left -> {
				move(View.FOCUS_LEFT)
				true
			}
			NavAction.Right -> {
				move(View.FOCUS_RIGHT)
				true
			}
			NavAction.Tap -> performTap()
			NavAction.Back -> {
				performGlobalAction(GLOBAL_ACTION_BACK)
				true
			}
			NavAction.OpenMenu -> {
				pageState.openMenu()
				true
			}
			NavAction.BackToNav -> {
				pageState.returnToNav()
				true
			}
			NavAction.NextMenuPage, NavAction.PrevMenuPage, NavAction.OpenDrag, NavAction.EnterReposition -> {
				pageState.goToPage(pageState.pageForNav(action))
				true
			}
			NavAction.OpenScroll -> pageState.openScrollMode()
			NavAction.LongPress -> performLongPress().also { if (it) pageState.returnToNav() }
			NavAction.DoubleTap -> performDoubleTap().also { if (it) pageState.returnToNav() }
			NavAction.ScrollUp, NavAction.ScrollDown, NavAction.ScrollLeft, NavAction.ScrollRight ->
				// Stay on ScrollMode for repeated scrolling (its re-grey rides the settled-event
				// path — no second pre-animation capture here); only a menu-page scroll returns.
				performScroll(navDirectionFor(action)).also { if (it && pageState.page != OverlayPage.ScrollMode) pageState.returnToNav() }
			NavAction.Home, NavAction.Recents -> {
				performGlobalAction(if (action == NavAction.Home) GLOBAL_ACTION_HOME else GLOBAL_ACTION_RECENTS)
				pageState.returnToNav()
				true
			}
			is NavAction.SnapTo -> {
				overlay?.snapToRegion(action.region)
				pageState.returnToNav()
				true
			}
			NavAction.PathLonger, NavAction.PathShorter,
			NavAction.PickUp, NavAction.DropTarget,
			NavAction.DragMoveUp, NavAction.DragMoveDown, NavAction.DragMoveLeft, NavAction.DragMoveRight,
			-> dragDispatch(action)
			NavAction.Empty, NavAction.SpeedAdjustTbd -> false
			// Layer-switch keys: layout mapping API TBD — see [[justtype_systemnav_layouts]].
			NavAction.AltLayout1, NavAction.AltLayout2 -> false // no-op until SystemNav layer switching is wired
			is NavAction.Stub -> {
				showToast(getString(R.string.navigation_mode_stub_pressed, action.slotIndex))
				true
			}
		}
	}

	private fun navDirectionFor(action: NavAction): NavDirection = when (action) {
		NavAction.ScrollUp -> NavDirection.UP
		NavAction.ScrollDown -> NavDirection.DOWN
		NavAction.ScrollLeft -> NavDirection.LEFT
		else -> NavDirection.RIGHT
	}

	private fun showOverlay() {
		if (overlay != null) return // already showing — never stack a second grid
		// Post so we don't tear down the overlay window from inside its own touch dispatch.
		val host = NavigationOverlayHost(this, this, vibrator = navVibrator, onMinimizeRequested = { mainHandler.post { minimizeOverlay() } })
		host.show()
		overlay = host
		val ring = NavigationFocusOverlay(this)
		ring.show()
		focusRing = ring
		val surface = NavInputSurface(
			context = this,
			scope = serviceScope,
			dispatcher = this,
			currentPageProvider = { pageState.page },
			readyProvider = { overlay != null },
			onNoOp = ::signalNavError,
		)
		inputSurface = surface

		buildInputSubsystems(host)
	}

	/**
	 * Build the input-method subsystems for the current settings. Split out of
	 * [showOverlay] so [inputMethodChangeListener] can rebuild them in place when
	 * the user changes the input method while the overlay is up.
	 */
	private fun buildInputSubsystems(host: NavigationOverlayHost) {
		val method = prefs.effectiveInputMethod()
		// Touch-screen-switch drives a switch subsystem via screen taps, so the
		// subsystem is usable even with no hardware switch codes bound.
		val tssEnabled = prefs.getBoolean(Constants.KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED, false)
		// Directional selection is a modifier (boolean), not a primary method. It
		// conflicts with single-switch scanning, so single-switch disables it.
		val directionalEnabled = prefs.getBoolean(Constants.KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED, false) &&
			method != Constants.INPUT_METHOD_SINGLE_SWITCH
		scrollLog { "buildInputSubsystems: method='$method' tss=$tssEnabled directional=$directionalEnabled" }
		if (method == Constants.INPUT_METHOD_SINGLE_SWITCH) {
			buildScanSubsystem(host)
		} else if (method == Constants.INPUT_METHOD_TWO_SWITCH) {
			buildTwoSwitchSubsystem(host, tapDriven = tssEnabled)
		} else if (method == Constants.INPUT_METHOD_HEAD_TRACKING) {
			buildHeadTrackingSubsystem(host)
		} else if (method == Constants.INPUT_METHOD_JOYSTICK) {
			buildJoystickSubsystem(host)
		}

		// One touch overlay, one mode. Directional takes precedence (mirrors the IME's
		// OverlayCoordinator, which checks directional before touch-screen-switch).
		if (directionalEnabled) {
			startDirectionalSelection()
		} else if (tssEnabled) {
			maybeStartTouchScreenSwitch()
		}
	}

	private fun startDirectionalSelection() {
		val fullscreen = prefs.getBoolean(Constants.KEY_NAV_TOUCH_FULLSCREEN_SWIPE, false)
		val overlay = NavTouchOverlay(
			context = this,
			insetSwipeRegion = !fullscreen, // inset by default keeps system gestures usable
			onSwitch = { _, _ -> /* directional mode emits no switch events */ },
			onDirection = { dir ->
				val index = ExternalSwitchHandler.directionToIndex(dir)
				// Flash the targeted key green on a real activation; a no-op key gets the error cue.
				if (inputSurface?.onButtonPressed(index) == true) {
					overlay?.let { it.flashButton(index, activationFlashColor(it.isDarkTheme), ACTIVATION_FLASH_MS, onComplete = null) }
				}
			},
			onTearDown = { navTouchOverlay = null },
			showBorder = prefs.getBoolean(Constants.KEY_DIRECTIONAL_SHOW_REGION_BORDER, false),
		)
		overlay.show(NavTouchMode.DIRECTIONAL_SELECTION)
		navTouchOverlay = overlay
	}

	private fun activationFlashColor(dark: Boolean): Int = if (dark) ACTIVATION_FLASH_COLOR_DARK else ACTIVATION_FLASH_COLOR

	/** Red flash + error tone when a disabled/no-op key is selected via a non-direct method. */
	private fun signalNavError(index: Int) {
		overlay?.flashButton(index, ERROR_FLASH_COLOR, ACTIVATION_FLASH_MS, onComplete = null)
		feedback?.errorFeedback()
	}

	private fun buildScanSubsystem(host: NavigationOverlayHost) {
		val fb = NavSubsystemFeedback(navVibrator)
		feedback = fb
		// Flash the activated key so scan has the same activation cue as two-switch/direct-tap.
		val sink = NavKeyActivationSink(
			provider = { inputSurface },
			onActivate = { index ->
				host.flashButton(index, activationFlashColor(host.isDarkTheme), ACTIVATION_FLASH_MS, onComplete = null)
			},
		)
		val callbacks = NavScanCallbacks(fb) { code ->
			prefs.putInt(Constants.KEY_SCAN_SWITCH_CODE, code)
		}
		val ss = ScanSubsystem(serviceScope, host, sink, callbacks, scanOrderOverride = NAV_CLOCKWISE_SCAN_ORDER)
		ss.loadSettings(prefs)
		scanSubsystem = ss
	}

	/**
	 * Build the two-switch subsystem. When [tapDriven] (touch-screen-switch on),
	 * screen taps supply the switch events, so missing hardware codes are fine.
	 * Otherwise hardware codes are the only input — skip + toast if they're unset
	 * (its cycle timers would otherwise run with no possible input).
	 */
	private fun buildTwoSwitchSubsystem(host: NavigationOverlayHost, tapDriven: Boolean) {
		if (!tapDriven) {
			val redCode = prefs.getInt(Constants.KEY_RED_SWITCH_CODE, Constants.SWITCH_CODE_UNDEFINED)
			val greenCode = prefs.getInt(Constants.KEY_GREEN_SWITCH_CODE, Constants.SWITCH_CODE_UNDEFINED)
			if (redCode == Constants.SWITCH_CODE_UNDEFINED || greenCode == Constants.SWITCH_CODE_UNDEFINED) {
				if (!twoSwitchUnconfiguredToastShown) {
					showToast(getString(R.string.navigation_mode_two_switch_codes_unconfigured))
					twoSwitchUnconfiguredToastShown = true
				}
				return
			}
		}
		val fb = NavSubsystemFeedback(navVibrator)
		feedback = fb
		// Success-only feedback: flash + beep + haptic fire from onActivate (a real activation),
		// so a no-op key gets only the error cue (signalNavError), never both at once.
		val sink = NavKeyActivationSink(
			provider = { inputSurface },
			onActivate = { index ->
				host.flashButton(index, activationFlashColor(host.isDarkTheme), ACTIVATION_FLASH_MS, onComplete = null)
				fb.activationFeedback()
			},
			autoRepeatReplaysLast = true,
		)
		val callbacks = NavTwoSwitchCallbacks(fb)
		val tss = TwoSwitchSubsystem(serviceScope, host, sink, callbacks)
		tss.loadSettings(prefs)
		tss.startCycle(restartOnly = false)
		twoSwitchSubsystem = tss
		twoSwitchUnconfiguredToastShown = false
	}

	/**
	 * Head tracking on Nav: buttons-only visuals via the overlay host. Activations
	 * fire the nav input surface through [NavHeadTrackingCallbacks.buttonPressed].
	 * Subscribes to the shared [org.continuouspath.justtype.input.HeadInputBus]; runs only
	 * when no IME is up (the existing show/hide mutex). Startup mirrors the IME order.
	 */
	private fun buildHeadTrackingSubsystem(host: NavigationOverlayHost) {
		val fb = NavSubsystemFeedback(navVibrator)
		feedback = fb
		val callbacks = NavHeadTrackingCallbacks(
			feedback = fb,
			inputSurface = { inputSurface },
			// Post so we don't tear down the subsystem from inside its own exit callback.
			onExit = { mainHandler.post { minimizeOverlay() } },
			// HeadBoard went unresponsive: minimize to the touch-operable button + toast so the
			// user isn't stuck with a frozen grid and no working input.
			onUnavailable = {
				mainHandler.post {
					showToast(getString(R.string.nav_ht_unavailable_message))
					minimizeOverlay()
				}
			},
		)
		val ht = HeadTrackingSubsystem(serviceScope, this, host, callbacks, prefs)
		ht.startAndRegisterReceivers()
		ht.loadSettings(prefs)
		ht.loadCachedPrefs(Constants.INPUT_METHOD_HEAD_TRACKING, prefs)
		ht.startProcessor()
		// Tell HeadBoard to stream pose frames to the overlay (nav-mode joystick).
		// The IME enable path gates on an open IME keyboard, which nav has none of;
		// this nav-specific signal bypasses that gate. See HeadInputBus for ingest.
		sendNavHeadTracking(enabled = true)
		headTrackingSubsystem = ht
	}

	/** Wire analog-joystick input to [JoystickSubsystem] (API 34+; pre-34 is a no-op). */
	private fun buildJoystickSubsystem(host: NavigationOverlayHost) {
		if (usePre34JoystickPath()) {
			showToast(getString(R.string.navigation_mode_joystick_unsupported))
			return
		}
		val fb = NavSubsystemFeedback(navVibrator)
		feedback = fb
		val sink = NavKeyActivationSink(provider = { inputSurface })
		val js = JoystickSubsystem(serviceScope, host, sink, NavJoystickCallbacks(fb))
		js.loadSettings(prefs)
		joystickSubsystem = js

		val params = GamepadParams.fromSettings(prefs)
		gamepadDetector = GamepadDirectionDetector(
			deadZone = params.deadZone,
			activeZone = params.activeZone,
			stickMode = GamepadDirectionDetector.StickMode.BOTH,
			acceptDevice = GamepadParams.deviceFilterFromSettings(prefs),
			cardinalWidthDeg = params.cardinalWidthDeg,
			diagonalWidthDeg = params.diagonalWidthDeg,
			onDirectionChanged = { /* unused in continuous mode */ },
			onContinuousUpdate = { x, y -> joystickSubsystem?.handleInput(x, y) },
		)
		reconcileMotionListening()
	}

	/**
	 * Claim gamepad motion sources only while a Nav subsystem consumes them: the joystick
	 * (analog sticks) or a switch subsystem (d-pad HAT → switch, since face-button KeyEvents
	 * can't reach the non-focusable overlay). Claiming is system-wide — holding it otherwise
	 * would starve the IME and the setup-screen device capture. Release when no such subsystem
	 * is active OR a setup screen is capturing a device.
	 */
	private fun reconcileMotionListening() {
		val consumer = joystickSubsystem != null || scanSubsystem != null || twoSwitchSubsystem != null
		val shouldClaim = consumer && !InputCaptureGate.isActive(prefs)
		setMotionListening(enabled = shouldClaim)
	}

	private fun sendNavHeadTracking(enabled: Boolean) {
		val action = if (enabled) {
			Constants.ACTION_NAV_HEAD_TRACKING_ENABLED
		} else {
			Constants.ACTION_NAV_HEAD_TRACKING_DISABLED
		}
		sendNavBroadcast(action)
	}

	private fun sendNavBroadcast(action: String) {
		runCatching {
			sendBroadcast(
				android.content.Intent(action)
					.putExtra(Constants.EXTRA_PACKAGE_NAME, packageName),
			)
		}
	}

	/**
	 * Minimize the nav kbd to a floating button. Stops nav head tracking (HeadBoard
	 * returns to normal cursor control) and arms HeadBoard to re-open on a down-push.
	 * The grid + focus ring come down; the small button stays at the grid's position.
	 */
	private fun minimizeOverlay() {
		if (minimized) return
		liveDrag.abort() // lift any held finger before its keep-alive is cancelled below
		cancelPendingNavCallbacks()
		val pos = overlay?.windowPosition() ?: (0 to 0)
		tearDownInputSubsystems() // sends NAV_DISABLED for HT; stops listening
		overlay?.hide()
		overlay = null
		focusRing?.hide()
		focusRing = null
		inputSurface = null

		val button = NavMinimizedOverlay(this) { restoreOverlay() }
		button.show(pos.first, pos.second, methodGlyph(), NavMinimizedOverlay.State.IDLE)
		minimizedOverlay = button
		minimized = true

		// Arm HeadBoard to watch for a down-push and broadcast a resume.
		sendNavBroadcast(Constants.ACTION_NAV_HEAD_TRACKING_ARMED)
		registerNavResumeReceiver()
	}

	/** Restore the full grid from the minimized button and resume input. */
	private fun restoreOverlay() {
		if (!minimized) return
		minimizedOverlay?.hide()
		minimizedOverlay = null
		minimized = false
		unregisterNavResumeReceiver()
		showOverlay() // re-inflates grid + ring + subsystems (re-sends NAV_ENABLED for HT)
	}

	/** Single-character indicator for the active input method on the minimized button. */
	private fun methodGlyph(): String = when (prefs.effectiveInputMethod()) {
		Constants.INPUT_METHOD_HEAD_TRACKING -> "⌖"
		Constants.INPUT_METHOD_JOYSTICK -> "🕹"
		else -> "⌨"
	}

	// HeadBoard broadcasts ACTION_HEAD_TRACKING_RESUME when the (armed) cursor is
	// pushed down far enough. Re-open only when minimized and head tracking is the method.
	private val navResumeReceiver = object : android.content.BroadcastReceiver() {
		override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
			if (minimized && prefs.effectiveInputMethod() == Constants.INPUT_METHOD_HEAD_TRACKING) {
				restoreOverlay()
			}
		}
	}

	private fun registerNavResumeReceiver() {
		if (navResumeReceiverRegistered) return
		runCatching {
			registerReceiver(
				navResumeReceiver,
				android.content.IntentFilter(Constants.ACTION_HEAD_TRACKING_RESUME),
				Constants.PERMISSION_RECEIVE_HEADBOARD_EVENT,
				null,
				android.content.Context.RECEIVER_EXPORTED,
			)
			navResumeReceiverRegistered = true
		}
	}

	private fun unregisterNavResumeReceiver() {
		if (!navResumeReceiverRegistered) return
		runCatching { unregisterReceiver(navResumeReceiver) }
		navResumeReceiverRegistered = false
	}

	/**
	 * Touch-screen-switch is a modifier layered on a switch method: it feeds taps
	 * to whichever switch subsystem is active. Construct the touch overlay only
	 * when a switch subsystem exists (an overlay with no consumer would consume
	 * touches into the void — a softlock vector). The caller gates on the flag.
	 */
	private fun maybeStartTouchScreenSwitch() {
		val tss = twoSwitchSubsystem
		val scan = scanSubsystem
		if (tss == null && scan == null) return

		val overlay = NavTouchOverlay(
			context = this,
			insetSwipeRegion = false, // full-screen for TSS taps; directional uses the inset pref
			onSwitch = { role, action -> routeTouchSwitch(role, action, tss, scan) },
			onDirection = { /* TSS mode emits no directions */ },
			onTearDown = {
				navTouchOverlay = null
				// Taps were the input path — clear the subsystem's highlight/cycle so no
				// stale band lingers. Hardware-bound switches would re-arm via onKeyEvent.
				tss?.cancelAndClear()
				scan?.cancelAndClear()
			},
			showBorder = prefs.getBoolean(Constants.KEY_TOUCH_SCREEN_SWITCH_SHOW_REGION_BORDER, false),
		)
		overlay.show(NavTouchMode.TOUCH_SCREEN_SWITCH)
		navTouchOverlay = overlay
	}

	/** Mirror OverlayCoordinator's TSS routing: two-switch maps L/R→Green/Red; single-switch taps scan. */
	private fun routeTouchSwitch(
		role: String,
		action: Int,
		tss: TwoSwitchSubsystem?,
		scan: ScanSubsystem?,
	) {
		if (tss != null) {
			val mappedRole = if (role == "LeftSwitch") "Green Switch" else "Red Switch"
			if (action == android.view.MotionEvent.ACTION_DOWN) {
				tss.switchHeld = true
				tss.handleSwitchDown(mappedRole)
			} else if (action == android.view.MotionEvent.ACTION_UP) {
				tss.switchHeld = false
				tss.handleSwitchUp()
			}
		} else if (scan != null) {
			if (action == android.view.MotionEvent.ACTION_DOWN) {
				scan.handleSwitchDown(Constants.SWITCH_CODE_UNDEFINED)
			} else if (action == android.view.MotionEvent.ACTION_UP) {
				scan.handleSwitchUp()
			}
		}
	}

	private fun hideOverlay() {
		liveDrag.abort() // lift any held finger before its keep-alive is cancelled below
		cancelPendingNavCallbacks()
		mainHandler.removeCallbacks(repositionTimeout)
		minimizedOverlay?.hide()
		minimizedOverlay = null
		minimized = false
		unregisterNavResumeReceiver()
		overlay?.hide()
		overlay = null
		focusRing?.hide()
		focusRing = null
		inputSurface = null
		selectedNode = null
		pageState.resetToNav()

		tearDownInputSubsystems()
	}

	/** Tear down only the input layer (subsystems + touch overlay), leaving the host/focus-ring. */
	private fun tearDownInputSubsystems() {
		navTouchOverlay?.forceTearDown()
		navTouchOverlay = null
		volumeCombo.clear()
		hatSwitchEdges.clear()

		scanSubsystem?.destroy()
		scanSubsystem = null
		twoSwitchSubsystem?.destroy()
		twoSwitchSubsystem = null
		headTrackingSubsystem?.let {
			// Hand the system cursor back to HeadBoard, then destroy.
			sendNavHeadTracking(enabled = false)
			it.destroy()
		}
		headTrackingSubsystem = null
		joystickSubsystem?.destroy()
		joystickSubsystem = null
		gamepadDetector = null
		reconcileMotionListening()
		// Clear per-method visuals (two-switch band strips, scan/HT highlights) so they don't
		// linger when the input layer rebuilds for a different method. The host survives teardown.
		overlay?.clearAllDecorations()
		feedback?.release()
		feedback = null
	}

	private val inputMethodChangeListener =
		android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
			when (NavSettingsRouter.classify(key)) {
				NavSettingsRouter.Effect.RECONCILE_MOTION -> reconcileMotionListening()
				NavSettingsRouter.Effect.RELOAD_SCROLL_LOGS ->
					scrollLogsEnabled = prefs.getBoolean(Constants.KEY_DEV_SCROLL_LOGS, BuildConfig.DEBUG)
				NavSettingsRouter.Effect.REBUILD_INPUT -> {
					val host = overlay ?: return@OnSharedPreferenceChangeListener
					// Rebuild only the input layer in place — host + focus-ring stay up (no flicker).
					tearDownInputSubsystems()
					twoSwitchUnconfiguredToastShown = false
					buildInputSubsystems(host)
				}
				// Behavior tweaks (delays, repeat, beeps…) re-load in place so an open overlay
				// picks them up without waiting for a rebuild (minimize/expand).
				NavSettingsRouter.Effect.RELOAD_SCAN -> scanSubsystem?.loadSettings(prefs)
				NavSettingsRouter.Effect.RELOAD_TWO_SWITCH -> twoSwitchSubsystem?.loadSettings(prefs)
				NavSettingsRouter.Effect.REAPPLY_APPEARANCE -> {
					// Live appearance update (incl. theme): re-tint key faces, then re-render
					// so the theme's glyph/icon colour (set in render) updates without a re-show.
					overlay?.applyAppearance()
					rerender()
				}
				NavSettingsRouter.Effect.NONE -> {}
			}
		}

	// ── Page state ────────────────────────────────────────────────────────

	private val repositionTimeout: Runnable = Runnable { pageState.repositionTimedOut() }

	private fun rerender() {
		overlay?.render(pageState.page, pageState.availability)
	}

	// ── Focus traversal (Nav mode) ────────────────────────────────────────

	private fun cancelPendingNavCallbacks() {
		scheduler.cancelAll()
		edgeScrollInFlight = false
		scrollSettle = null
		gestureScrollInFlight = false
		lengthArrow.hide()
		hideDragCursor()
		selectCursor.clear() // the pick-up crosshair shares dragCursor (already hidden); clear its point
		// cancelAll() just killed the pending drop-touch-restore post; re-enable touch here so a
		// window change mid-drop can't leave the grid permanently non-touchable. No-op if unchanged.
		overlay?.setTouchable(true)
	}

	private fun move(direction: Int) {
		if (edgeScrollInFlight) {
			scrollLog { "move dir=${dirName(direction)} → swallowed, edge-scroll in flight" }
			return
		}
		withCapture { capture ->
			val candidates = candidatesIn(capture)
			if (candidates.isEmpty()) {
				showToast(getString(R.string.navigation_mode_no_focus))
				return@withCapture
			}
			val current = resolveCurrent(candidates)
			if (current == null) {
				// True cold start (no anchor): select the center-nearest element and consume the
				// press, so the ring appears mid-screen and the next press moves from there.
				setSelection(spatialFirst(candidates))
				return@withCapture
			}
			val next = pickNeighbor(current, candidates, direction)
			if (next != null) {
				scrollLog { "move dir=${dirName(direction)} → neighbor found, no scroll (candidates=${candidates.size})" }
				setSelection(next)
				showClippedSelection(next)
				return@withCapture
			}
			// Boundary hit — the press's own capture feeds the edge-scroll plan.
			scrollLog { "move dir=${dirName(direction)} → no neighbor, attempting edge-scroll" }
			if (tryEdgeScroll(capture, current, direction)) return@withCapture
			// Nothing else to do; selection stays put
			scrollLog { "move dir=${dirName(direction)} → edge-scroll did not run; selection stays put" }
			setSelection(current)
		}
		// The selection may have changed; re-grey any selection-dependent keys on the current page
		// (self-gated). Without this the keys reflect the previous element until the next rerender.
		pageState.refreshSelectionAvailability()
	}

	/** Runs [block] on a fresh capture, then returns the spent nodes to the pre-33 pool (selection kept). */
	private inline fun <T> withCapture(block: (NodeSnapshotter.Capture) -> T): T {
		val capture = captureTree()
		try {
			return block(capture)
		} finally {
			snapshotter.release(capture, keep = selectedNode)
		}
	}

	private inline fun scrollLog(msg: () -> String) {
		if (scrollLogsEnabled) android.util.Log.d(SCROLL_TAG, msg())
	}

	private fun dirName(direction: Int): String = when (direction) {
		View.FOCUS_UP -> "UP"
		View.FOCUS_DOWN -> "DOWN"
		View.FOCUS_LEFT -> "LEFT"
		View.FOCUS_RIGHT -> "RIGHT"
		else -> "?"
	}

	private fun tryEdgeScroll(capture: NodeSnapshotter.Capture, current: AccessibilityNodeInfo, focusDirection: Int): Boolean {
		val dir = focusToNavDirection(focusDirection) ?: return false
		val selection = capture.live.indexOfFirst { it == current }.takeIf { it >= 0 }
		val plan = ScrollPlanner.plan(capture.tree, selection, dir, SCROLL_ACTION_IDS)
		val scrolled = executePlannedScroll(capture, plan, selection, dir)
		scrollLog { "edge-scroll ${dirName(focusDirection)}: plan=$plan → $scrolled" }
		if (!scrolled) return false

		// Settle on movement events instead of a fixed delay: the reselect fires shortly after the
		// LAST scroll/content event; a short timeout with no events at all means nothing moved
		// (hard limit or inert surface) — selection stays and arrows unfreeze quickly.
		scrollSettle = ScrollSettleState(focusDirection, Rect().also { current.getBoundsInScreen(it) })
		edgeScrollInFlight = true
		scheduler.post(TOKEN_EDGE_RESELECT, SCROLL_SETTLE_NO_EVENT_TIMEOUT_MS) { finishScrollSettle() }
		return true
	}

	private class ScrollSettleState(val focusDirection: Int, val boundsBefore: Rect) {
		var sawScrollEvent = false
	}

	private fun finishScrollSettle() {
		val settle = scrollSettle ?: return
		scrollSettle = null
		edgeScrollInFlight = false
		if (!settle.sawScrollEvent) {
			scrollLog { "edge-scroll ${dirName(settle.focusDirection)}: no scroll events — hard limit, selection stays" }
			return
		}
		withCapture { capture ->
			val updated = candidatesIn(capture)
			if (updated.isEmpty()) return@withCapture
			val kept = updated.firstOrNull { it.overlapsCloselyBy(settle.boundsBefore) }
			if (kept != null) {
				setSelection(kept)
				return@withCapture
			}
			val dir = focusToNavDirection(settle.focusDirection) ?: return@withCapture
			val indexed = updated.mapIndexed { i, n ->
				FocusGeometry.IndexedBounds(i, Rect().also { n.getBoundsInScreen(it) }.toNavBounds())
			}
			FocusGeometry.pickAfterScroll(settle.boundsBefore.toNavBounds(), indexed, dir)?.let { setSelection(updated[it]) }
		}
	}

	/**
	 * "Same node as [anchorBefore]" — tolerant comparison by center proximity, so a node
	 * that scrolled by a few pixels but is still on screen is preserved as the selection.
	 */
	private fun AccessibilityNodeInfo.overlapsCloselyBy(anchorBefore: Rect): Boolean {
		val r = Rect().also { this.getBoundsInScreen(it) }
		val dx = abs(r.centerX() - anchorBefore.centerX())
		val dy = abs(r.centerY() - anchorBefore.centerY())
		return dx <= EDGE_SCROLL_OVERLAP_TOLERANCE_PX && dy <= EDGE_SCROLL_OVERLAP_TOLERANCE_PX
	}

	private fun performTap(): Boolean {
		val held = selectedNode?.takeIf { it.refresh() }
		if (held == null) {
			return withCapture { capture ->
				val candidates = candidatesIn(capture)
				if (candidates.isEmpty()) {
					showToast(getString(R.string.navigation_mode_no_focus))
					return@withCapture false
				}
				// No live selection: select only, never click sight-unseen — the ring appears
				// and a second Tap activates it.
				if (resolveCurrent(candidates) == null) setSelection(spatialFirst(candidates))
				true
			}
		}
		val clickable = findClickableAncestor(held) ?: held
		if (nodeActions.perform(clickable, AccessibilityNodeInfo.ACTION_CLICK)) return true
		// Gesture-tap where ACTION_CLICK is refused (custom canvases, WebView regions) — unless the
		// TAP POINT itself falls on our grid, where the injected tap would press a Nav key.
		val r = Rect().also { clickable.getBoundsInScreen(it) }
		val onGrid = overlay?.windowBoundsOnScreen()?.toNavBounds()?.contains(r.centerX(), r.centerY()) == true
		if (r.isEmpty || onGrid) return false
		scrollLog { "tap: ACTION_CLICK refused, gesture-tap at (${r.centerX()}, ${r.centerY()})" }
		return gestures.tap(r.centerX(), r.centerY()) { }
	}

	/** A neighbor picked while partially offscreen is scrolled fully into view; the ring follows via re-sync. */
	private fun showClippedSelection(node: AccessibilityNodeInfo) {
		val m = resources.displayMetrics
		val r = Rect().also { node.getBoundsInScreen(it) }
		val clipped = r.top < 0 || r.left < 0 || r.bottom > m.heightPixels || r.right > m.widthPixels
		if (clipped) nodeActions.perform(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
	}

	private fun setSelection(node: AccessibilityNodeInfo) {
		selectedNode = node
		selectionFingerprint = NodeSnapshotter.fingerprintOf(node)
		val bounds = Rect()
		node.getBoundsInScreen(bounds)
		lastSelectionBounds = Rect(bounds)
		focusRing?.setBounds(bounds)
	}

	/**
	 * The node the next move starts from — validity cascade:
	 * 1. held selection still refreshes with sane bounds → keep it (ring + fingerprint re-synced);
	 * 2. dead live node → fingerprint match re-points at the same logical node in the fresh capture;
	 * 3. gone → candidate nearest the last ring rect, and the pressed direction applies same-press.
	 * Null only on a true cold start.
	 */
	private fun resolveCurrent(candidates: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
		val held = selectedNode
		if (held != null && held.refresh()) {
			val r = Rect().also { held.getBoundsInScreen(it) }
			if (r.width() > 0 && r.height() > 0) {
				selectionFingerprint = NodeSnapshotter.fingerprintOf(held)
				lastSelectionBounds = Rect(r)
				focusRing?.setBounds(r)
				return held
			}
		}
		val fresh = candidates.map { NodeSnapshotter.fingerprintOf(it) }
		selectionFingerprint?.let { fp ->
			SelectionMatcher.match(fp, fresh)?.let { i ->
				scrollLog { "resolveCurrent: fingerprint re-matched (candidates=${candidates.size})" }
				return candidates[i].also(::setSelection)
			}
		}
		val anchor = lastSelectionBounds ?: return null
		val nearest = SelectionMatcher.nearestToAnchor(
			NavBounds(anchor.left, anchor.top, anchor.right, anchor.bottom),
			fresh.map { it.bounds },
		) ?: return null
		scrollLog { "resolveCurrent: selection re-anchored (candidates=${candidates.size})" }
		return candidates[nearest].also(::setSelection)
	}

	/** Cold-start pick: the candidate nearest screen center, so navigation begins mid-screen. */
	private fun spatialFirst(candidates: List<AccessibilityNodeInfo>): AccessibilityNodeInfo {
		val m = resources.displayMetrics
		val cx = m.widthPixels / 2
		val cy = m.heightPixels / 2
		return candidates.minBy { c ->
			val r = Rect().also { c.getBoundsInScreen(it) }
			val dx = (r.centerX() - cx).toLong()
			val dy = (r.centerY() - cy).toLong()
			dx * dx + dy * dy
		}
	}

	/** Live candidate nodes of [capture], in CandidatePolicy order. */
	private fun candidatesIn(capture: NodeSnapshotter.Capture): List<AccessibilityNodeInfo> {
		val candidates = CandidatePolicy.candidates(capture.tree)
		scrollLog {
			"candidates overlayUp=${navTouchOverlay != null} nodes=${capture.tree.nodes.size} focusable=${candidates.size}"
		}
		return candidates.map { capture.live[it] }
	}

	private fun captureTree(): NodeSnapshotter.Capture {
		val m = resources.displayMetrics
		val window = pinnedAppWindow()
		return snapshotter.capture(
			roots = listOfNotNull(window?.root),
			screen = NavBounds(0, 0, m.widthPixels, m.heightPixels),
			overlayUp = navTouchOverlay != null,
			layoutRtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL,
			generation = ++captureGeneration,
			pinnedWindowId = window?.id ?: -1,
		)
	}

	/**
	 * App-content roots, topmost first. Only TYPE_APPLICATION windows: excluding our capture/grid
	 * overlays by window type (they report as TYPE_SYSTEM), NOT by package — JT's own activities
	 * (Settings etc.) are app windows and must stay navigable. Prefers the active window's root.
	 */
	/**
	 * Exactly one window feeds each capture — merging roots lets occluded background windows
	 * into the candidate field (multi-window soup). Preference: the selection's window while it
	 * exists, then the active, focused, and top-layer app window. Only TYPE_APPLICATION windows:
	 * our overlays report other types, while JT's own activities must stay navigable. With the
	 * capture overlay up, the bounds-intersect visibility fallback thus applies only within the
	 * pinned window; same-window invisible nodes remain an accepted residual.
	 */
	private fun pinnedAppWindow(): AccessibilityWindowInfo? {
		val appWindows = (windows ?: emptyList()).filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
		val heldWindowId = selectedNode?.windowId
		return appWindows.firstOrNull { it.id == heldWindowId }
			?: appWindows.firstOrNull { it.isActive }
			?: appWindows.filter { it.isFocused }.maxByOrNull { it.layer }
			?: appWindows.maxByOrNull { it.layer }
	}

	private fun pickNeighbor(
		current: AccessibilityNodeInfo,
		candidates: List<AccessibilityNodeInfo>,
		direction: Int,
	): AccessibilityNodeInfo? {
		val dir = focusToNavDirection(direction) ?: return null
		val curBounds = Rect().also { current.getBoundsInScreen(it) }.toNavBounds()
		val indexed = candidates.mapIndexed { i, cand ->
			FocusGeometry.IndexedBounds(i, Rect().also { cand.getBoundsInScreen(it) }.toNavBounds())
		}
		return FocusGeometry.pickNeighbor(curBounds, indexed, dir)?.let { candidates[it] }
	}

	// ── Menu-mode action execution ────────────────────────────────────────

	private fun performLongPress(): Boolean {
		val node = selectedNode ?: return false
		val target = findClickableAncestor(node) ?: node
		return nodeActions.perform(target, AccessibilityNodeInfo.ACTION_LONG_CLICK)
	}

	private fun performDoubleTap(): Boolean {
		val node = selectedNode ?: return false
		val target = findClickableAncestor(node) ?: node
		val first = nodeActions.perform(target, AccessibilityNodeInfo.ACTION_CLICK)
		if (!first) return false
		scheduler.post(TOKEN_SECOND_CLICK, DOUBLE_TAP_GAP_MS) {
			// perform's refresh() aborts silently if the first click invalidated the node.
			nodeActions.perform(target, AccessibilityNodeInfo.ACTION_CLICK)
		}
		return true
	}

	private fun performScroll(direction: NavDirection): Boolean = withCapture { capture ->
		val selection = selectionIndexIn(capture)
		val plan = ScrollPlanner.plan(capture.tree, selection, direction, SCROLL_ACTION_IDS)
		executePlannedScroll(capture, plan, selection, direction).also { scrolled ->
			// Re-grey ScrollMode's direction keys after the content settles. The gesture path re-greys
			// from its completion callback, but the accessibility-action path (the 100%-reach default)
			// is synchronous with no callback, so post the same settle re-grey here for both. Self-gated
			// to ScrollMode; same token as the event path, so concurrent posts coalesce.
			if (scrolled) {
				scheduler.post(TOKEN_RING_RESYNC, CONTENT_RESYNC_DEBOUNCE_MS) {
					resyncRing()
					pageState.refreshSelectionAvailability()
				}
			}
		}
	}

	/**
	 * Node plan and injected swipe, ordered by the Longer/Shorter Path preference: a shortened
	 * path prefers the distance-controlled swipe over page-sized scroll actions. With no plan in
	 * ANY direction the surface is action-less (map, canvas, stubborn WebView) and the swipe is
	 * the only probe; a direction merely exhausted or axis-blocked stays an honest no.
	 */
	private fun executePlannedScroll(
		capture: NodeSnapshotter.Capture,
		plan: ScrollPlanner.ScrollPlan?,
		selection: Int?,
		direction: NavDirection,
	): Boolean {
		val step = pageState.scrollReach.percent
		val screen = capture.tree.screen
		val container = plan?.let { capture.tree.nodes[it.primary.nodeIndex].bounds }
		if (step < SCROLL_STEP_MAX_PERCENT && gestures.available) {
			if (gestureScroll(direction, container, step, screen)) return true
			return plan != null && executeScroll(capture, plan)
		}
		if (plan != null && executeScroll(capture, plan)) return true
		if (plan != null) return gestureScroll(direction, container, step, screen)
		// Swipe the container whose axis is unknown (unrecognized carousels), else probe a fully
		// action-less surface; a merely-exhausted direction on a served container stays an honest no.
		val blocked = ScrollPlanner.axisBlockedTarget(capture.tree, selection, direction, SCROLL_ACTION_IDS)
		if (blocked != null) return gestureScroll(direction, capture.tree.nodes[blocked].bounds, step, screen)
		return ScrollPlanner.availableDirections(capture.tree, selection, SCROLL_ACTION_IDS).isEmpty() &&
			gestureScroll(direction, null, step, screen)
	}

	/** Injected swipe across [container] (or the screen), avoiding the grid window. */
	private fun gestureScroll(direction: NavDirection, container: NavBounds?, distancePercent: Int, screen: NavBounds): Boolean {
		if (!gestures.available) return false
		// Coalesce auto-repeat ticks: dispatching over a running swipe would cancel it mid-flight.
		if (gestureScrollInFlight) return true
		// Clamp the container to the visible screen: a scrolled list reports bounds past the top/bottom
		// edges, and swiping there puts the finger off-screen or in the status/nav-bar gesture zones.
		val area = (container ?: screen).intersectClamped(screen)
		val segment = GesturePaths.scrollSwipe(area, direction, distancePercent, overlay?.windowBoundsOnScreen()?.toNavBounds())
		scrollLog { "gesture-scroll $direction ($distancePercent%): $segment" }
		val queued = gestures.swipe(segment) { done ->
			gestureScrollInFlight = false
			scrollLog { "gesture-scroll $direction completed → $done" }
			// Belated honesty: the system cancelled the injected swipe after we reported success.
			if (!done) feedback?.errorFeedback()
			// Re-sync the ring and re-grey ScrollMode's direction keys once the swipe settles
			// (self-gated to that page), so exhausted directions grey out even when the app fires
			// no content-changed event. Same token as the event path, so the two coalesce.
			if (done) {
				scheduler.post(TOKEN_RING_RESYNC, CONTENT_RESYNC_DEBOUNCE_MS) {
					resyncRing()
					pageState.refreshSelectionAvailability()
				}
			}
		}
		gestureScrollInFlight = queued
		return queued
	}

	private fun executeScroll(capture: NodeSnapshotter.Capture, plan: ScrollPlanner.ScrollPlan): Boolean {
		if (nodeActions.perform(capture.live[plan.primary.nodeIndex], plan.primary.actionId)) return true
		val fallback = plan.fallback ?: return false
		return nodeActions.perform(capture.live[fallback.nodeIndex], fallback.actionId)
	}

	/** The selection's index in [capture], matched by node identity (windowId + sourceId). */
	private fun selectionIndexIn(capture: NodeSnapshotter.Capture): Int? {
		val held = selectedNode ?: return null
		return capture.live.indexOfFirst { it == held }.takeIf { it >= 0 }
	}

	// ── Availability ──────────────────────────────────────────────────────

	/**
	 * Scroll entries are plan-derived and re-greyed on ScrollMode entry and after every scroll
	 * (see dispatch), so per-direction greying is honest again.
	 */
	private fun computeAvailability(node: AccessibilityNodeInfo?): Map<NavAction, Boolean> {
		val clickable = node?.let { findClickableAncestor(it) }
		val dirs = withCapture { capture ->
			ScrollPlanner.availableDirections(capture.tree, selectionIndexIn(capture), SCROLL_ACTION_IDS)
		}
		return AvailabilityPolicy.map(
			AvailabilityPolicy.SelectionProbe(
				clickableActionIds = clickable?.actionList?.map { it.id }?.toSet().orEmpty(),
				isClickable = clickable?.isClickable == true,
				isLongClickable = clickable?.isLongClickable == true,
				scrollDirections = dirs,
				gesturesAvailable = gestures.available,
			),
		)
	}
	// ── Tree walks ────────────────────────────────────────────────────────

	private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
		var current: AccessibilityNodeInfo? = node
		while (current != null) {
			if (current.isClickable) return current
			current = current.parent
		}
		return null
	}

	private fun showToast(message: String) {
		Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
	}

	companion object {
		@Volatile
		var isRunning: Boolean = false
			private set

		private const val POLL_INTERVAL_MS = 500L
		private const val VOLUME_COMBO_WINDOW_MS = 1500L

		// Reposition mode auto-returns to Nav after this long with no region pick (its only
		// escape — generous, since the target users may take time to choose).
		private const val REPOSITION_TIMEOUT_MS = 8000L
		private const val SCROLL_TAG = "NavScroll"

		// Clockwise scan around the empty center, starting top-left. Grid indices:
		// 0=top-left 1=top 2=top-right 3=left 4=right 5=bottom-left 6=bottom 7=bottom-right.
		private val NAV_CLOCKWISE_SCAN_ORDER = listOf(0, 1, 2, 4, 7, 6, 5, 3)

		// Green flash on the selected key when a two-switch activation completes. The dark variant is
		// brighter so it stays distinct from the darker dark-mode two-switch key backgrounds.
		private val ACTIVATION_FLASH_COLOR = android.graphics.Color.parseColor("#4CAF50")
		private val ACTIVATION_FLASH_COLOR_DARK = android.graphics.Color.parseColor("#69F0AE")

		// Red flash for a no-op/disabled key selection (matches the IME's error flash).
		private val ERROR_FLASH_COLOR = android.graphics.Color.parseColor("#E53935")
		private const val ACTIVATION_FLASH_MS = 250L

		private const val TOKEN_EDGE_RESELECT = "edge_scroll_reselect"
		private const val TOKEN_SECOND_CLICK = "double_tap_second_click"
		private const val TOKEN_RING_RESYNC = "ring_resync"
		private const val TOKEN_DROP_TOUCH_RESTORE = "drop_touch_restore"

		// Margin past the drop gesture's own length before the grid takes touches again — covers
		// dispatch/IPC seam so a real finger can't sneak onto the grid while the injection still plays.
		private const val DROP_TOUCH_RESTORE_MARGIN_MS = 150L

		// Resource-based scroll action ids, resolved once; page ids are API 29+ (0 = absent).
		private val SCROLL_ACTION_IDS = ScrollPlanner.ScrollActionIds(
			up = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
			down = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
			left = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
			right = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
			pageLeft = if (Build.VERSION.SDK_INT >= 29) AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id else 0,
			pageRight = if (Build.VERSION.SDK_INT >= 29) AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id else 0,
		)
		private const val DOUBLE_TAP_GAP_MS = 100L

		// Edge-scroll settle: reselect this long after the LAST movement event; bail fast (hard
		// limit / inert surface) when no event at all arrives — arrows are swallowed until then.
		private const val SCROLL_SETTLE_DEBOUNCE_MS = 120L
		private const val SCROLL_SETTLE_NO_EVENT_TIMEOUT_MS = 300L

		// Longer/Shorter Path scroll-step bounds (percent of the container's usable span).
		private const val SCROLL_STEP_MIN_PERCENT = 25
		private const val SCROLL_STEP_MAX_PERCENT = 100
		private const val SCROLL_STEP_INCREMENT = 25

		// Drag-cursor nudge-step bounds (percent of the shorter screen dimension; default 10%).
		private const val DRAG_STEP_MIN_PERCENT = 5
		private const val DRAG_STEP_MAX_PERCENT = 25
		private const val DRAG_STEP_INCREMENT = 5
		private const val CONTENT_RESYNC_DEBOUNCE_MS = 200L
		private const val EDGE_SCROLL_OVERLAP_TOLERANCE_PX = 32
	}
}

private fun focusToNavDirection(direction: Int): NavDirection? = when (direction) {
	View.FOCUS_UP -> NavDirection.UP
	View.FOCUS_DOWN -> NavDirection.DOWN
	View.FOCUS_LEFT -> NavDirection.LEFT
	View.FOCUS_RIGHT -> NavDirection.RIGHT
	else -> null
}

private fun Rect.toNavBounds(): NavBounds = NavBounds(left, top, right, bottom)
