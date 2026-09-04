package org.continuouspath.justtype

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.continuouspath.justtype.Constants.INPUT_METHOD_DIRECT_SELECTION
import org.continuouspath.justtype.Constants.INPUT_METHOD_NONE
import org.continuouspath.justtype.Constants.INPUT_METHOD_SINGLE_SWITCH
import org.continuouspath.justtype.Constants.KEY_APP_LANGUAGE
import org.continuouspath.justtype.Constants.KEY_AUTO_RESTORE_SELECTION
import org.continuouspath.justtype.Constants.KEY_DEBUG_LOG_CATEGORIES
import org.continuouspath.justtype.Constants.KEY_DIRECTIONAL_SELECTION_DEBOUNCE_MS
import org.continuouspath.justtype.Constants.KEY_DIRECTIONAL_SELECTION_SWIPE_PERCENT
import org.continuouspath.justtype.Constants.KEY_ENABLE_DEBUG_LOG
import org.continuouspath.justtype.Constants.KEY_EXTERNAL_SWITCH_STUCK_TIMEOUT_SEC
import org.continuouspath.justtype.Constants.KEY_GREEN_SWITCH_CODE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_ACTIVEZONE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_AUTO_FALLBACK_ACTIVE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_CORNER_BIAS
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_DEADZONE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_EXITZONE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_EXIT_DELAY_MS
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_FALLBACK_PRIOR_DIRECT_SEL
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_KEY_ACT_THRESHOLD
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_PRIMARY
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED
import org.continuouspath.justtype.Constants.KEY_KEYBOARD_SIZE_RATIO
import org.continuouspath.justtype.Constants.KEY_KEY_HISTORY_HEIGHT_PERCENT
import org.continuouspath.justtype.Constants.KEY_KEY_HISTORY_SHRINK_TO_FIT
import org.continuouspath.justtype.Constants.KEY_LAST_CRASH_MESSAGE
import org.continuouspath.justtype.Constants.KEY_LAST_CRASH_THREAD
import org.continuouspath.justtype.Constants.KEY_LAST_CRASH_TIME
import org.continuouspath.justtype.Constants.KEY_LAST_SESSION_CRASHED
import org.continuouspath.justtype.Constants.KEY_LAYOUT_MODE
import org.continuouspath.justtype.Constants.KEY_NEXT_LETTER_HINTS
import org.continuouspath.justtype.Constants.KEY_OPTIMIZED_LAYOUT_SOURCE
import org.continuouspath.justtype.Constants.KEY_RED_SWITCH_CODE
import org.continuouspath.justtype.Constants.KEY_SCAN_LAYOUT_SIZE
import org.continuouspath.justtype.Constants.KEY_SCAN_SWITCH_CODE
import org.continuouspath.justtype.Constants.KEY_SHOW_BUTTONS_PRESSED
import org.continuouspath.justtype.Constants.KEY_SWITCH_DEBOUNCE_MS
import org.continuouspath.justtype.Constants.KEY_TOUCH_OVERLAY_TIMEOUT_SEC
import org.continuouspath.justtype.Constants.KEY_TOUCH_SCREEN_SWITCH_DEBOUNCE_MS
import org.continuouspath.justtype.Constants.KEY_TSS_BUTTON_HEIGHT_PERCENT
import org.continuouspath.justtype.Constants.KEY_TSS_OVERLAY_MODE
import org.continuouspath.justtype.Constants.KEY_TSS_OVERLAY_OPACITY
import org.continuouspath.justtype.Constants.KEY_TYPING_LANGUAGE
import org.continuouspath.justtype.Constants.KEY_VOCAB_ACTIVE_MASK
import org.continuouspath.justtype.Constants.MODE_ALPHA
import org.continuouspath.justtype.Constants.MODE_OPT
import org.continuouspath.justtype.Constants.SCAN_LAYOUT_SIZE_SMALL
import org.continuouspath.justtype.Constants.SWITCH_CODE_UNDEFINED
import org.continuouspath.justtype.activity.BackupRestoreActivity
import org.continuouspath.justtype.activity.SettingsActivity
import org.continuouspath.justtype.data.PhraseRepository
import org.continuouspath.justtype.ime.BroadcastBridge
import org.continuouspath.justtype.ime.BroadcastBridgeCallbacksImpl
import org.continuouspath.justtype.ime.BroadcastBridgeCallbacksImplDeps
import org.continuouspath.justtype.ime.ExternalSwitchCallbacksImpl
import org.continuouspath.justtype.ime.ExternalSwitchCallbacksImplDeps
import org.continuouspath.justtype.ime.ExternalSwitchHandler
import org.continuouspath.justtype.ime.GamepadParams
import org.continuouspath.justtype.ime.HeadTrackingCallbacksImpl
import org.continuouspath.justtype.ime.HeadTrackingCallbacksImplDeps
import org.continuouspath.justtype.ime.HeadTrackingSubsystem
import org.continuouspath.justtype.ime.IMEState
import org.continuouspath.justtype.ime.ImeTextCallbacksImpl
import org.continuouspath.justtype.ime.ImeTextCallbacksImplDepsImpl
import org.continuouspath.justtype.ime.ImeTextController
import org.continuouspath.justtype.ime.ImeWindowState
import org.continuouspath.justtype.ime.JoystickCallbacks
import org.continuouspath.justtype.ime.JoystickSubsystem
import org.continuouspath.justtype.ime.KeyFeedbackCallbacks
import org.continuouspath.justtype.ime.KeyFeedbackController
import org.continuouspath.justtype.ime.MouseJoystickCallbacks
import org.continuouspath.justtype.ime.MouseJoystickSubsystem
import org.continuouspath.justtype.ime.OverlayConfig
import org.continuouspath.justtype.ime.OverlayCoordinator
import org.continuouspath.justtype.ime.OverlayCoordinatorCallbacks
import org.continuouspath.justtype.ime.PhraseFlowCallbacksImpl
import org.continuouspath.justtype.ime.PhraseFlowCallbacksImplDeps
import org.continuouspath.justtype.ime.PhraseFlowController
import org.continuouspath.justtype.ime.PreferenceCoordinator
import org.continuouspath.justtype.ime.PreferenceCoordinatorCallbacksImpl
import org.continuouspath.justtype.ime.PreferenceCoordinatorCallbacksImplDeps
import org.continuouspath.justtype.ime.PreferenceState
import org.continuouspath.justtype.ime.PropertyAccess
import org.continuouspath.justtype.ime.ScanCallbacksImpl
import org.continuouspath.justtype.ime.ScanCallbacksImplDeps
import org.continuouspath.justtype.ime.ScanSubsystem
import org.continuouspath.justtype.ime.SettingsOverlayCallbacksImpl
import org.continuouspath.justtype.ime.SettingsOverlayCallbacksImplDeps
import org.continuouspath.justtype.ime.SettingsOverlayController
import org.continuouspath.justtype.ime.StartupManager
import org.continuouspath.justtype.ime.SwitchCodeConfig
import org.continuouspath.justtype.ime.TextUtils
import org.continuouspath.justtype.ime.TtsController
import org.continuouspath.justtype.ime.TwoSwitchCallbacksImpl
import org.continuouspath.justtype.ime.TwoSwitchCallbacksImplDeps
import org.continuouspath.justtype.ime.TwoSwitchSubsystem
import org.continuouspath.justtype.ime.UiUpdateCallbacksImpl
import org.continuouspath.justtype.ime.UiUpdateCallbacksImplDeps
import org.continuouspath.justtype.ime.UiUpdateHandler
import org.continuouspath.justtype.ime.ViewBridgeCallbacks
import org.continuouspath.justtype.ime.ViewBridgeCoordinator
import org.continuouspath.justtype.ime.VocabularyOperations
import org.continuouspath.justtype.ime.jtui
import org.continuouspath.justtype.ime.readyJtui
import org.continuouspath.justtype.input.HeadBoardSwitchBus
import org.continuouspath.justtype.input.InputCaptureGate
import org.continuouspath.justtype.layout.LayoutManager
import org.continuouspath.justtype.logging.DebugCategory
import org.continuouspath.justtype.logging.DebugLogger
import org.continuouspath.justtype.logging.ExceptionReporter
import org.continuouspath.justtype.logic.AutoCapReason
import org.continuouspath.justtype.logic.JTUI
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.applySafeKeyboardDefaults
import org.continuouspath.justtype.settings.effectiveInputMethod
import org.continuouspath.justtype.settings.forceDirectSelectionFallback
import org.continuouspath.justtype.settings.getBoolean
import org.continuouspath.justtype.settings.getFloat
import org.continuouspath.justtype.settings.getInt
import org.continuouspath.justtype.settings.getString
import java.io.File
import java.util.Locale

class JustTypeIME : InputMethodService() {

	// ── Service-scoped coroutine scope ─────────────────────────────────────
	// All subsystem coroutines are children of this scope.
	// Cancelled in onDestroy() — replaces HandlerThreads and Executors over time.
	private val serviceJob = SupervisorJob()
	val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)

	// In-flight background JTUI init (blocking body — cancellation can't interrupt it);
	// test teardown joins it so it can't outlive the test's environment.
	@androidx.annotation.VisibleForTesting
	internal var jtuiInitJob: Job? = null

	// ── Extracted subsystems ──────────────────────────────────────────────
	private lateinit var ttsController: TtsController
	private lateinit var scanSubsystem: ScanSubsystem
	private lateinit var twoSwitchSubsystem: TwoSwitchSubsystem
	private lateinit var joystickSubsystem: JoystickSubsystem
	private lateinit var mouseJoystickSubsystem: MouseJoystickSubsystem
	private lateinit var headTrackingSubsystem: HeadTrackingSubsystem
	private lateinit var imeTextController: ImeTextController
	private lateinit var phraseFlowController: PhraseFlowController
	private lateinit var externalSwitchHandler: ExternalSwitchHandler
	private var hoverRootRef: View? = null
	private val speechIndicatorOverlay by lazy {
		SpeechIndicatorOverlay(this) { hoverRootRef as? FrameLayout }
	}

	// Keyboard height (screen minus the top inset), tracked for the mouse-joystick barrier strip.
	private var currentKeyboardHeightPx: Int = 0
	private lateinit var overlayCoordinator: OverlayCoordinator
	private lateinit var settingsOverlayController: SettingsOverlayController
	private lateinit var preferenceCoordinator: PreferenceCoordinator
	private lateinit var keyFeedbackController: KeyFeedbackController
	private lateinit var uiUpdateHandler: UiUpdateHandler

	private lateinit var imeTextCallbacksImpl: ImeTextCallbacksImpl

	// True after onCreateInputView has finished binding the view tree and view-bound
	// coordinators (LayoutManager, OverlayCoordinator, UiUpdateHandler, SettingsOverlayController).
	// Use this in preference listeners and IMS callbacks that may fire before the view exists.
	private var inputViewInflated: Boolean = false

	// Guards the one-shot safe-defaults retry when onCreateInputView throws (anti-lockout).
	// Set true before recovery so a listener-driven re-entrant inflate can't loop; cleared on success.
	private var inputViewRecoveryAttempted: Boolean = false
	private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

	// ── View bridge coordinator (implements HighlightBridge, KeyActivationSink,
	//    TwoSwitchViewBridge, JoystickViewBridge, HeadTrackingViewBridge) ───
	private lateinit var viewBridgeCoordinator: ViewBridgeCoordinator

	override fun attachBaseContext(newBase: Context) {
		super.attachBaseContext(LocaleHelper.wrap(newBase))
	}

	companion object {
		// Minimum readable word-list width, in multiples of the list text size —
		// floors both the per-column width and the whole list at max Keyboard Size.
		private const val MIN_LIST_WIDTH_EM = 6

		// Joystick binding/tuning keys: a change rebuilds the gamepad detector (its device
		// filter + zone params are captured at construction).
		private val JOYSTICK_SETTINGS_KEYS = setOf(
			Constants.KEY_JOYSTICK_DEVICE_DESCRIPTOR,
			Constants.KEY_JOYSTICK_ACCEPT_ANY,
			Constants.KEY_JOYSTICK_DEADZONE,
			Constants.KEY_JOYSTICK_ACTIVEZONE,
			Constants.KEY_JOYSTICK_CORNER_BIAS,
		)

		// Settle delay before re-inflating after a failed input view, so safe-defaults writes and
		// any listener-driven work flush before the retry.
		private const val INPUT_VIEW_RETRY_DELAY_MS = 120L

		// Fraction of screen height kept for app content above the keyboard when the key grid would
		// otherwise overflow (landscape / wide tablets). Portrait phones never hit the cap.
		private const val KEYBOARD_MAX_HEIGHT_HEADROOM = 0.15f

		// Landscape screens are short — leave a larger slice for the app.
		private const val KEYBOARD_MAX_HEIGHT_HEADROOM_LANDSCAPE = 0.30f

		// KeyboardLayoutContainer style pads 8dp each side; the grid's width share is
		// taken from the container's inner width.
		private const val KEYBOARD_CONTAINER_PADDING_DP = 16f

		// Same-process observation seam for the instrumented smoke suite.
		@Volatile
		@androidx.annotation.VisibleForTesting
		internal var activeInstance: JustTypeIME? = null
			private set
	}

	private lateinit var jtui: JTUI
	private lateinit var settingsRepo: SettingsRepository

	// Accessible-prompt overlay (shared across the IME service lifetime).
	// JTUI intercepts UnDo when this prompt is showing.
	private val accessiblePrompt by lazy { org.continuouspath.justtype.ui.AccessiblePrompt(this) }

	// Type-checked readiness state for [jtui]. Loading → Constructed → Ready.
	// Use [imeState.jtui] to access JTUI safely without lateinit reflection.
	@Volatile
	private var imeState: IMEState = IMEState.Loading

	// Layout management
	private lateinit var layoutManager: LayoutManager

	// View references (delegated to LayoutManager when initialized)
	private val keyHistoryView: org.continuouspath.justtype.view.KeyHistoryView?
		get() = if (inputViewInflated) layoutManager.keyHistoryView else null
	private val keyHistoryScrollView: View?
		get() = if (inputViewInflated) layoutManager.keyHistoryScrollView else null
	private val keyGridActiveView: View?
		get() = if (inputViewInflated) layoutManager.keyGridView else null
	private val centerLabelView: TextView?
		get() = if (inputViewInflated) layoutManager.centerLabel else null
	private val selectionListView: TextView?
		get() = if (inputViewInflated) layoutManager.selectionListView as? TextView else null

	// Legacy view references (still needed for some specific logic)
	private lateinit var keyHistoryViewJT: org.continuouspath.justtype.view.KeyHistoryView
	private lateinit var keyHistoryViewScan: org.continuouspath.justtype.view.KeyHistoryView
	private lateinit var keyHistoryScrollViewJT: View
	private lateinit var keyHistoryScrollViewScan: View
	private lateinit var jtLayoutContainer: View
	private lateinit var scanLayoutContainer: View
	private lateinit var scanTopRow: androidx.constraintlayout.widget.ConstraintLayout
	private lateinit var scanKeysContainer: View
	private lateinit var scanLargeRowTop: android.widget.LinearLayout
	private lateinit var scanLargeRowBottom: android.widget.LinearLayout
	private lateinit var scanSmallRow: android.widget.LinearLayout
	private lateinit var scanButtonPool: android.widget.LinearLayout
	private lateinit var broadcastBridge: BroadcastBridge
	private lateinit var selectionListViewJT: TextView
	private lateinit var selectionListScanContainer: android.widget.LinearLayout
	private lateinit var keyGridViewJT: View
	private lateinit var centerLabelJT: TextView
	private lateinit var centerLabelScan: TextView
	private var scanTopRowHeightTarget: Int = -1

	// Shared tone generators and feedback settings
	private var keyToneGenerator: ToneGenerator? = null
	private var switchToneGenerator: ToneGenerator? = null
	private var flashKeyFeedbackEnabled: Boolean = true
	private var beepKeyFeedbackEnabled: Boolean = true

	// Head-tracking Correct/Backtrack gesture feedback toggles. Cached
	// here so the HT callbacks impl can read them via deps without
	// hitting the settings repo on every frame.
	private var correctionBeepEnabled: Boolean = true
	private var correctionFlashRedEnabled: Boolean = true
	private lateinit var buttons: List<Button>
	private lateinit var buttonsJT: List<Button>
	private lateinit var buttonsScan: List<Button>
	private var useScanLayout: Boolean = false

	private var centerLabelOriginalBackground: android.graphics.drawable.Drawable? = null

	private val flashRestores = mutableMapOf<Button, Runnable>()
	private val buttonOriginalBackgrounds = mutableMapOf<Button, android.graphics.drawable.Drawable>()

	// Standing (non-flash) background per key — what a flash settles back to (see ViewBridgeCoordinator).
	private val standingBackgrounds = mutableMapOf<Button, android.graphics.drawable.Drawable>()
	private lateinit var highlightDrawable: android.graphics.drawable.Drawable
	private lateinit var phraseRepository: PhraseRepository
	private var phraseOverlay: View? = null
	private var phraseOverlayTitle: TextView? = null
	private var phraseOverlayPrompt: TextView? = null
	private var phraseOverlayContent: TextView? = null
	private var phraseOverlayAbbrev: TextView? = null
	private var phraseOverlayDone: Button? = null
	private var phraseOverlayCancel: Button? = null

	private var isNewInputSession: Boolean = false

	// Ambiguous key sequence that was mid-entry when the field paused (sleep/app
	// switch). Restored on same-field resume iff it prefixes the word at the cursor.
	private var resumeSequenceKeys: List<Int>? = null
	private var resumeFieldId: Int = 0

	// Wake detection: `restarting` is NOT reliable (Notepad restarts input with
	// restarting=false after a screen-off), so the screen-off broadcast is the
	// ground truth. Consumed by the first onStartInput after screen-on.
	private var currentFieldId: Int = 0
	private var wokeFromScreenOff: Boolean = false
	private val screenOffReceiver = object : android.content.BroadcastReceiver() {
		override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
			wokeFromScreenOff = true
		}
	}
	private var isEditMode: Boolean = false

	private var enableDebugLog: Boolean = true
	private var errorBeepEnabled: Boolean = false

	// Listen for settings changes so we can react without restarting the IME
	private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

	// Coalesces critical-setting recreates. Rapid input-method-change checkbox taps in
	// InputMethodsActivity can otherwise stack many recreateInputView() coroutines on
	// the Main looper before any GC catches up — each recreate inflates ~30 SquareButtons
	// (each loading a Bitmap), exhausting the heap. We cancel any pending recreate when
	// a new change arrives, so only the last setting in a burst triggers a single recreate.
	private var pendingCriticalChangeJob: Job? = null
	private val criticalChangeDebounceMs: Long = 250L

	private var errorToneGenerator: ToneGenerator? = null
	private var directSelectionDebounceMs: Int = 0
	private var lastDirectSelectionActivationTime: Long = 0L
	private var twoSwitchEnabled: Boolean = false
	private var directionalSelectionEnabled: Boolean = false
	private var directSelectionEnabled: Boolean = false
	private var touchScreenSwitchEnabled: Boolean = false
	private var touchScreenSwitchFlashEnabled: Boolean = false
	private var touchScreenSwitchBeepEnabled: Boolean = false
	private var scanLayoutSizeLarge: Boolean = true
	private var showButtonsPressedPref: Boolean = false
	private var keyHistoryShrinkToFitPref: Boolean = false
	private var singleSwitchEnabled: Boolean = false

	// Try expanding the range by one character in each direction
	private fun setIgnoreCursorRange(start: Int, end: Int) = imeTextController.setIgnoreCursorRange(start, end)

	private fun autoCommitSelectedPhrase(text: String) {
		val ic = currentInputConnection ?: return
		val saveIgnoreSelectionUpdate = imeTextController.ignoreSelectionUpdate
		try {
			imeTextController.ignoreSelectionUpdate = true
			ic.setComposingText(text, 1)
			imeTextController.haveComposing = true
			imeTextController.lastComposingSent = text
			imeTextController.lastImeEditMs = SystemClock.uptimeMillis()
		} finally {
			imeTextController.ignoreSelectionUpdate = saveIgnoreSelectionUpdate
		}
		phraseFlowController.cancelAutoCommit()
	}

	private fun setActiveLayout(useScan: Boolean) {
		useScanLayout = useScan
		if (!inputViewInflated) return
		serviceScope.launch(Dispatchers.Main.immediate) {
			debugLog("[layout] setActiveLayout useScan=$useScanLayout")

			// Delegate layout switching to LayoutManager
			layoutManager.setActiveLayout(useScanLayout)

			// Update buttons reference
			buttons = if (useScanLayout) buttonsScan else buttonsJT

			applyKeyHistoryVisibility()
			if (useScanLayout) {
				configureScanButtons()
				applyScanTopRowSize()
				// Update selection list dimensions and items per column after layout
				layoutManager.postOnSelectionListLayout {
					updateSelectionListDimensions()
					updateItemsPerColumn()
				}
			} else {
				scanSubsystem.stopScan()
				// Portrait: single scrolling column. Landscape: columns from real geometry.
				imeState.jtui?.let { updateJtItemsPerColumn(it) }
				layoutManager.postOnSelectionListLayout {
					updateSelectionListDimensions()
					updateItemsPerColumn()
				}
			}
		}
	}

	private fun applyKeyHistoryVisibility() {
		if (!inputViewInflated) return
		// Don't show key history while in settings mode (it's hidden to make room for settings overlay)
		if (imeState.jtui?.isInSettingsMode == true) return
		layoutManager.setKeyHistoryVisible(showButtonsPressedPref)
	}

	private fun applyKeyHistoryShrinkToFit() {
		if (!inputViewInflated) return
		layoutManager.setKeyHistoryShrinkToFit(keyHistoryShrinkToFitPref)
	}

	private fun configureScanButtons() {
		if (!inputViewInflated) return
		val repo = SettingsRepository.get()
		val layoutPref = repo.getString(KEY_LAYOUT_MODE)
		val isOptimized = layoutPref == MODE_OPT

		// Delegate to LayoutManager
		layoutManager.scanController.heightBudgetPx = imeHeightBudgetPx()
		layoutManager.scanController.isOptimizedLayout = isOptimized
		layoutManager.scanController.isLargeLayout = scanLayoutSizeLarge
		layoutManager.configureScanButtons()
	}

	private fun applyScanTopRowSize() {
		if (!inputViewInflated) return
		layoutManager.scanController.heightBudgetPx = imeHeightBudgetPx()
		layoutManager.applyScanTopRowSize()
	}

	private fun addScanTopRowLayoutWatchers() {
		if (!::centerLabelScan.isInitialized || !::selectionListScanContainer.isInitialized || !::scanTopRow.isInitialized) return
		val listener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
			val name = when (v) {
				centerLabelScan -> "center"
				selectionListScanContainer -> "selectionContainer"
				scanTopRow -> "toprow"
				else -> v?.javaClass?.simpleName ?: "unknown"
			}
			debugLog("[scanSize][layout] $name h=${v?.height} w=${v?.width} target=$scanTopRowHeightTarget")
		}
		centerLabelScan.addOnLayoutChangeListener(listener)
		selectionListScanContainer.addOnLayoutChangeListener(listener)
		scanTopRow.addOnLayoutChangeListener(listener)
	}

	private fun updateSelectionListDimensions() {
		val jtuiRef = imeState.jtui ?: return
		if (!inputViewInflated) return
		val dimensions = layoutManager.getSelectionListDimensions() ?: return
		val (width, height) = dimensions
		if (width > 0 && height > 0) {
			jtuiRef.selectionListWidth = width
			jtuiRef.selectionListHeight = height
			selectionListView?.textSize?.let { jtuiRef.selectionListTextSizePx = it }
			debugLog("[selectionListDimensions] width=$width height=$height useScan=$useScanLayout")
		}
	}

	private fun updateItemsPerColumn() {
		val jtuiRef = imeState.jtui ?: return
		if (!inputViewInflated) return
		if (!useScanLayout) {
			updateJtItemsPerColumn(jtuiRef)
			return
		}
		val itemsPerColumn = layoutManager.calculateItemsPerColumn()
		val maxColumns = layoutManager.calculateMaxColumns()
		jtuiRef.itemsPerColumn = itemsPerColumn
		jtuiRef.maxColumns = maxColumns
		jtuiRef.selectionLineHeightPx = (layoutManager.scanController.selectionTextSizePx * 1.4f).toInt()
		debugLog("[updateItemsPerColumn] itemsPerColumn=$itemsPerColumn maxColumns=$maxColumns")
	}

	/**
	 * JT landscape: split the wide, short list into columns sized from its real
	 * geometry. Portrait (no columns container) keeps the single scrolling column.
	 */
	private fun updateJtItemsPerColumn(jtuiRef: JTUI) {
		val container = layoutManager.jtController.columnsContainer
		if (container == null) {
			jtuiRef.itemsPerColumn = 0
			jtuiRef.maxColumns = 1
			jtuiRef.selectionLineHeightPx = 0
			return
		}
		val list = layoutManager.jtController.selectionListTextView
		val lineHeight = (list.textSize * 1.4f).toInt().coerceAtLeast(1)
		val availableHeight = list.height - list.paddingTop - list.paddingBottom
		val itemsPerColumn = (availableHeight / lineHeight).coerceAtLeast(1)
		val minColumnWidth = (list.textSize * MIN_LIST_WIDTH_EM).toInt().coerceAtLeast(1)
		val maxColumns = (container.width / minColumnWidth).coerceIn(1, 3)
		jtuiRef.itemsPerColumn = if (maxColumns > 1) itemsPerColumn else 0
		jtuiRef.maxColumns = maxColumns
		jtuiRef.selectionLineHeightPx = lineHeight
		debugLog("[updateJtItemsPerColumn] itemsPerColumn=${jtuiRef.itemsPerColumn} maxColumns=$maxColumns")
	}

	/**
	 * Update the scan layout selection list columns with the provided buffers.
	 * Delegates to LayoutManager's scan controller.
	 */
	private fun updateScanColumnViews(buffers: List<CharSequence>) {
		if (!inputViewInflated) return
		debugLog("[updateScanColumnViews] buffers=${buffers.size}")
		layoutManager.scanController.updateColumnViews(buffers)
	}

	override fun onCreate() {
		android.os.Trace.beginSection("ime.onCreate")
		val perfStart = org.continuouspath.justtype.utils.PerfTrace.now()
		try {
			super.onCreate()
			activeInstance = this
			// Initialize the rolling debug log. Files live under {filesDir}/logs/
			// as DebugLog_YYYY-MM-DD.log; older files are pruned on each setLogDirectory
			// call. Retention is read from prefs after SettingsRepository is up
			// below — we pass DEFAULT_RETENTION_DAYS here for the bootstrap window.
			DebugLogger.setLogDirectory(File(filesDir, "logs"))
			// ACTION_SCREEN_OFF is a protected system broadcast (dynamic-only).
			registerReceiver(screenOffReceiver, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF))
			// ExceptionLogWriter writes silent-exception breadcrumbs +
			// uncaught-crash reports under the same logs/ directory. It needs
			// an application Context to resolve filesDir but doesn't take one
			// at each call site (would be awkward to thread through every IME
			// catch block). Wire it once here.
			// Crash handler, ExceptionLogWriter wiring, and legacy-log cleanup now run
			// process-wide in JustTypeApplication.onCreate so they cover the Nav service too.

			org.continuouspath.justtype.settings.SettingsRegistry.getInstance(applicationContext)
			settingsRepo = SettingsRepository.getInstance(applicationContext)
			// HeadBoard-driven switches feed the external-switch pipeline like real keys.
			HeadBoardSwitchBus.addConsumer(applicationContext, HeadBoardSwitchBus.Priority.IME, headBoardSwitchConsumer)
			org.continuouspath.justtype.settings.SettingsDefaults.ensureAll(settingsRepo, applicationContext)
			ClassMetadataStore.ensureDefaults(settingsRepo)

			// Handle startup checks for reinit, crash recovery, and version migration
			StartupManager(this).runStartupChecks(settingsRepo)
			val hasRunBefore = settingsRepo.getBoolean(Constants.KEY_HAS_RUN_BEFORE, false)
			if (!hasRunBefore) {
				val treeUri = BackupManager.getBackupTreeUri(settingsRepo)
				val prompted = settingsRepo.getBoolean(Constants.KEY_BACKUP_PROMPTED, false)
				if (!prompted && treeUri != null && BackupManager.hasCompatibleBackup(this, treeUri)) {
					settingsRepo.putBoolean(Constants.KEY_BACKUP_PROMPTED, true)
					val intent = Intent(this, BackupRestoreActivity::class.java).apply {
						addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
						putExtra(BackupRestoreActivity.EXTRA_PROMPT_RESTORE, true)
					}
					startActivity(intent)
				}
				settingsRepo.putBoolean(Constants.KEY_HAS_RUN_BEFORE, true)
			}

			// On first run, open SettingsActivity so the user is prompted for
			// overlay permission and can configure input methods before typing.
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
				!android.provider.Settings.canDrawOverlays(this) &&
				!settingsRepo.getBoolean(Constants.KEY_OVERLAY_PERMISSION_REQUESTED, false)
			) {
				val intent = Intent(this, SettingsActivity::class.java).apply {
					addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				}
				startActivity(intent)
			}
			DebugLogger.setEnabledCategories(
				DebugCategory.fromPrefValues(
					settingsRepo.getStringSet(KEY_DEBUG_LOG_CATEGORIES, null)?.toSet(),
				),
			)
			DebugLogger.setRetentionDays(
				settingsRepo.getInt(Constants.KEY_DEBUG_LOG_RETENTION_DAYS),
			)
			ttsController = TtsController(
				context = this,
				scope = serviceScope,
				getSpeakState = { imeState.readyJtui?.getSpeakState() == true },
				getInputConnection = { currentInputConnection },
				onSpeakingChanged = { speaking -> mainHandler.post { updateSpeechIndicator(speaking) } },
			)
			ttsController.init()
			imeTextCallbacksImpl = ImeTextCallbacksImpl(
				getJtui = { imeState.jtui },
				isJtuiReady = { imeState is IMEState.Ready },
				deps = ImeTextCallbacksImplDepsImpl(
					isInputViewShownProvider = { isInputViewShown },
					isAlphaCharProvider = { c -> isAlphaChar(c) },
					isEditModeAccess = PropertyAccess(
						get = { isEditMode },
						set = { isEditMode = it },
					),
					isNewInputSessionAccess = PropertyAccess(
						get = { isNewInputSession },
						set = { isNewInputSession = it },
					),
					phraseFlowHandleProvider = {
						if (::phraseFlowController.isInitialized) phraseFlowController.flowHandle else null
					},
					sendDpadEventFn = { direction, movementMode -> imeTextController.sendDpadEvent(direction, movementMode) },
					speakQueuedFn = { text -> speakQueued(text) },
					reuseOrCreatePendingSelectionFn = { text, type -> reuseOrCreatePendingSelection(text, type) },
					speakIfEnabledFn = { pending -> speakIfEnabled(pending) },
					cancelScheduledSpeakFn = { clearPending -> cancelScheduledSpeak(clearPending) },
					rememberLastSpokenFn = { text, type -> rememberLastSpoken(text, type) },
					getPendingSelectionFn = { ttsController.pendingSelection },
					setPendingSelectionFn = { pending -> ttsController.setPendingSelection(pending) },
					recordSpellNumericFn = { text -> recordSpellNumeric(text) },
					flushSpellNumericIfNeededFn = { reason -> flushSpellNumericIfNeeded(reason) },
					getAutoRestoreFn = { settingsRepo.getBoolean(KEY_AUTO_RESTORE_SELECTION) },
					errorNotificationFn = { imeTextController.errorNotification() },
					errorBeepFn = { force -> keyFeedbackController.errorFeedback(force) },
					debugLogFn = { message -> debugLog(message) },
				),
			)
			imeTextController = ImeTextController(
				scope = serviceScope,
				getInputConnection = { currentInputConnection },
				getInputEditorInfo = { currentInputEditorInfo },
				callbacks = imeTextCallbacksImpl,
				ttsController = ttsController,
			)
			highlightDrawable = ContextCompat.getDrawable(this, R.drawable.button_background_highlight)!!
			viewBridgeCoordinator = ViewBridgeCoordinator(
				context = this,
				scope = serviceScope,
				getButtons = { if (::buttons.isInitialized) buttons else emptyList() },
				buttonOriginalBackgrounds = buttonOriginalBackgrounds,
				flashRestores = flashRestores,
				standingBackgrounds = standingBackgrounds,
				callbacks = object : ViewBridgeCallbacks {
					override val centerLabelView: TextView? get() = this@JustTypeIME.centerLabelView
					override val selectionListView: TextView? get() = this@JustTypeIME.selectionListView
					override var centerLabelOriginalBackground: android.graphics.drawable.Drawable?
						get() = this@JustTypeIME.centerLabelOriginalBackground
						set(value) {
							this@JustTypeIME.centerLabelOriginalBackground = value
						}
					override fun getButtonsJT(): List<Button> = if (::buttonsJT.isInitialized) buttonsJT else emptyList()
					override fun triggerKeyActivation(
						index: Int,
						suppressBeep: Boolean,
					) = keyFeedbackController.triggerKeyActivation(
						index,
						suppressBeep,
					)
					override fun triggerSelectActivation() = keyFeedbackController.triggerSelectActivation()
					override val isJtuiInitialized: Boolean get() = imeState is IMEState.Ready
					override fun buttonPressedOnUiThread(index: Int) {
						// Record the target so an error triggered by this (silent, e.g. two-switch) activation
						// flashes THIS key, not a stale one.
						keyFeedbackController.noteActivatedKey(index)
						imeState.jtui?.buttonPressed(index)
					}
				},
			)
			errorToneGenerator = try {
				ToneGenerator(AudioManager.STREAM_SYSTEM, 80)
			} catch (_: Exception) {
				null
			}
			phraseRepository = PhraseRepository(File(filesDir, "phrases.json"))
			phraseFlowController = PhraseFlowController(
				scope = serviceScope,
				context = this,
				phraseRepository = phraseRepository,
				callbacks = PhraseFlowCallbacksImpl(
					deps = object : PhraseFlowCallbacksImplDeps {
						override fun getJtui(): JTUI? = imeState.jtui
						override fun autoCommitSelectedPhrase(text: String) = this@JustTypeIME.autoCommitSelectedPhrase(text)
						override fun scheduleBackup() = BackupManager.scheduleBackup(this@JustTypeIME)
						override fun debugLog(message: String) = this@JustTypeIME.debugLog(message)
						override fun executeOnUiThread(block: () -> Unit) {
							serviceScope.launch(Dispatchers.Main.immediate) { block() }
						}
					},
				),
			)
			keyToneGenerator = try {
				ToneGenerator(AudioManager.STREAM_MUSIC, 70)
			} catch (_: Exception) {
				null
			}
			switchToneGenerator = try {
				ToneGenerator(AudioManager.STREAM_SYSTEM, 70)
			} catch (_: Exception) {
				null
			}

			headTrackingSubsystem = HeadTrackingSubsystem(
				scope = serviceScope,
				context = this,
				viewBridge = viewBridgeCoordinator,
				callbacks = HeadTrackingCallbacksImpl(
					deps = object : HeadTrackingCallbacksImplDeps {
						override val imeState get() = this@JustTypeIME.imeState
						override val isInputViewShown: Boolean get() = this@JustTypeIME.isInputViewShown
						override val isBeepEnabled: Boolean get() = beepKeyFeedbackEnabled
						override val isCorrectionBeepEnabled: Boolean get() = correctionBeepEnabled
						override val isCorrectionFlashRedEnabled: Boolean get() = correctionFlashRedEnabled
						override fun getJtuiOrNull(): JTUI? = this@JustTypeIME.imeState.readyJtui
						override fun playActivationBeep() = keyFeedbackController.playKeyActivationTone()
						override fun playCorrectTone() = keyFeedbackController.playCorrectTone()
						override fun playCancelTone() = keyFeedbackController.playCancelTone()
						override fun setHighlight(buttonIndex: Int) = keyFeedbackController.setHighlight(buttonIndex)
						override fun suppressPullInForExit() = imeTextController.suppressPullInForExit()
						override fun onKeyboardReEntry() = imeTextController.onKeyboardReEntry()
						override fun onHeadTrackingUnavailable() = this@JustTypeIME.handleHeadTrackingUnavailable()
						override fun onHeadTrackingRecovered() = this@JustTypeIME.handleHeadTrackingRecovered()
						override fun debugLog(message: String) = this@JustTypeIME.debugLog(message)
					},
				),
				settingsRepo = settingsRepo,
			)
			headTrackingSubsystem.startAndRegisterReceivers()

			broadcastBridge = BroadcastBridge(this)
			broadcastBridge.registerAll(
				BroadcastBridgeCallbacksImpl(
					deps = object : BroadcastBridgeCallbacksImplDeps {
						override val imeState get() = this@JustTypeIME.imeState
						override fun getVocabularyOperations(): VocabularyOperations? = this@JustTypeIME.imeState.readyJtui?.let { jtui ->
							object : VocabularyOperations {
								override fun mergeVocabularyMasks(sourceMask: Long, targetMask: Long) = jtui.mergeVocabularyMasks(sourceMask, targetMask)
								override fun clearVocabularyMasks(deleteMask: Long) = jtui.clearVocabularyMasks(deleteMask)
								override fun reloadVocabularyFromDb() = jtui.reloadVocabularyFromDb()
								override fun forceUpdateUi() = jtui.forceUpdateUi()
								override fun init() = jtui.init()
							}
						}
						override fun getActiveVocabMask(): Long = SettingsRepository.get().getLong(KEY_VOCAB_ACTIVE_MASK, 0L)
						override fun reloadPhrases() = phraseRepository.reload()
						override fun reloadAllPreferences() = preferenceCoordinator.loadAndApplyAll()
						override fun clearAllHighlights() = keyFeedbackController.clearAllHighlights()
						override fun executeOnMain(block: () -> Unit) {
							serviceScope.launch(Dispatchers.Main.immediate) { block() }
						}
					},
				),
			)

			// Cancel old subsystems first: they live on serviceScope, so a stale scan
			// cycle keeps stepping (yellow flashes + stale routing) after a live method switch.
			if (::scanSubsystem.isInitialized) scanSubsystem.destroy()
			if (::twoSwitchSubsystem.isInitialized) twoSwitchSubsystem.destroy()

			scanSubsystem = ScanSubsystem(
				scope = serviceScope,
				highlightBridge = viewBridgeCoordinator,
				keySink = viewBridgeCoordinator,
				callbacks = ScanCallbacksImpl(
					deps = object : ScanCallbacksImplDeps {
						override val isInputViewInflated get() = inputViewInflated
						override val isTouchScreenSwitchBeepEnabled get() = touchScreenSwitchBeepEnabled
						override fun flashSwitchBar(green: Boolean, red: Boolean) = overlayCoordinator.flashSwitchBar(green, red)
						override fun beepSwitchActivation() = keyFeedbackController.beepSwitchActivation()
						override fun persistAutoLearnedSwitchCode(keyCode: Int) {
							SettingsRepository.get().putInt(KEY_SCAN_SWITCH_CODE, keyCode)
						}
					},
				),
			)

			twoSwitchSubsystem = TwoSwitchSubsystem(
				scope = serviceScope,
				viewBridge = viewBridgeCoordinator,
				keySink = viewBridgeCoordinator,
				callbacks = TwoSwitchCallbacksImpl(
					deps = object : TwoSwitchCallbacksImplDeps {
						override val isInputViewInflated get() = inputViewInflated
						override val isTouchScreenSwitchBeepEnabled get() = touchScreenSwitchBeepEnabled
						override fun flashSwitchBar(flashGreen: Boolean, flashRed: Boolean) = overlayCoordinator.flashSwitchBar(flashGreen, flashRed)
						override fun beepSwitchActivation() = keyFeedbackController.beepSwitchActivation()
						override fun beepSwitchKeyCombined() = keyFeedbackController.playSwitchKeyCombinedTone()
						override fun stepFeedback(beep: Boolean) = keyFeedbackController.stepFeedback(beep)
						override fun finalActivationFeedback(index: Int) = keyFeedbackController.keyActivationFeedback(index)
						override fun debugLog(message: String) = this@JustTypeIME.debugLog(message)
					},
				),
			)

			joystickSubsystem = JoystickSubsystem(
				scope = serviceScope,
				viewBridge = viewBridgeCoordinator,
				keySink = viewBridgeCoordinator,
				callbacks = object : JoystickCallbacks {
					override fun playActivationBeep() {
						keyFeedbackController.playKeyActivationTone()
					}
					override fun debugLog(message: String) {
						this@JustTypeIME.debugLog(message)
					}
				},
			)

			mouseJoystickSubsystem = MouseJoystickSubsystem(
				context = this,
				scope = serviceScope,
				viewBridge = viewBridgeCoordinator,
				keySink = viewBridgeCoordinator,
				callbacks = object : MouseJoystickCallbacks {
					override fun playActivationBeep() {
						keyFeedbackController.playKeyActivationTone()
					}
					override fun debugLog(message: String) {
						this@JustTypeIME.debugLog(message)
					}
					override fun playCaptureAcquiredTone() {
						keyFeedbackController.playCaptureAcquiredTone()
					}
					override fun playCaptureReleasedTone() {
						keyFeedbackController.playCaptureReleasedTone()
					}
					override fun playExitCountdownTick() {
						keyFeedbackController.playExitCountdownTick()
					}
					override fun verifyInputConnectionLive() {
						// Spike probe: commit a char, read it back, delete it. PASS = the editor's
						// InputConnection survived our capture overlay taking input focus.
						val ic = currentInputConnection
						if (ic == null) {
							Log.w("MJ_CAP", "INPUTCONN FAIL: currentInputConnection is null")
							return
						}
						ic.beginBatchEdit()
						ic.commitText("x", 1)
						val readBack = ic.getTextBeforeCursor(1, 0)?.toString()
						if (readBack == "x") ic.deleteSurroundingText(1, 0)
						ic.endBatchEdit()
						Log.w("MJ_CAP", if (readBack == "x") "INPUTCONN PASS: commitText landed and was read back" else "INPUTCONN FAIL: readBack=$readBack")
					}
				},
			)

			keyFeedbackController = KeyFeedbackController(
				getButtons = { if (::buttons.isInitialized) buttons else emptyList() },
				buttonOriginalBackgrounds = buttonOriginalBackgrounds,
				flashRestores = flashRestores,
				standingBackgrounds = standingBackgrounds,
				highlightDrawable = highlightDrawable,
				scanSubsystem = scanSubsystem,
				headTrackingSubsystem = headTrackingSubsystem,
				joystickSubsystem = joystickSubsystem,
				callbacks = object : KeyFeedbackCallbacks {
					override val isJtuiInitialized: Boolean get() = imeState is IMEState.Ready
					override val flashKeyFeedbackEnabled: Boolean get() = this@JustTypeIME.flashKeyFeedbackEnabled
					override val errorBeepEnabled: Boolean get() = this@JustTypeIME.errorBeepEnabled
					override val directSelectionEnabled: Boolean get() = this@JustTypeIME.directSelectionEnabled
					override val directSelectionDebounceMs: Int get() = this@JustTypeIME.directSelectionDebounceMs
					override val useScanLayout: Boolean get() = this@JustTypeIME.useScanLayout
					override var lastDirectSelectionActivationTime: Long
						get() = this@JustTypeIME.lastDirectSelectionActivationTime
						set(value) {
							this@JustTypeIME.lastDirectSelectionActivationTime = value
						}
					override fun buttonPressedOnUi(index: Int) {
						serviceScope.launch(Dispatchers.Main.immediate) { jtui.buttonPressed(index) }
					}
					override fun debugLog(message: String) = this@JustTypeIME.debugLog(message)
				},
			)
			keyFeedbackController.keyToneGenerator = keyToneGenerator
			keyFeedbackController.switchToneGenerator = switchToneGenerator
			keyFeedbackController.errorToneGenerator = errorToneGenerator
			keyFeedbackController.vibrator =
				if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
					getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
				} else {
					@Suppress("DEPRECATION")
					getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
				}

			// Preference coordinator (centralises preference loading + critical-setting handling)
			preferenceCoordinator = PreferenceCoordinator(
				context = this,
				scanSubsystem = scanSubsystem,
				twoSwitchSubsystem = twoSwitchSubsystem,
				joystickSubsystem = joystickSubsystem,
				mouseJoystickSubsystem = mouseJoystickSubsystem,
				headTrackingSubsystem = headTrackingSubsystem,
				phraseFlowController = phraseFlowController,
				ttsController = ttsController,
				imeTextController = imeTextController,
				callbacks = PreferenceCoordinatorCallbacksImpl(
					deps = object : PreferenceCoordinatorCallbacksImplDeps {
						override val imeState get() = this@JustTypeIME.imeState
						override val isJtuiAvailable get() = this@JustTypeIME.imeState.jtui != null
						override fun getJtuiOrNull(): JTUI? = this@JustTypeIME.imeState.readyJtui
						override fun isInSettingsMode(): Boolean = jtui.isInSettingsMode

						override fun getExternalSwitchHandler(): ExternalSwitchHandler? = if (::externalSwitchHandler.isInitialized) externalSwitchHandler else null
						override fun getOverlayCoordinator(): OverlayCoordinator? = if (inputViewInflated) overlayCoordinator else null

						override fun applyPreferenceState(state: PreferenceState) {
							directionalSelectionEnabled = state.directionalSelectionEnabled
							touchScreenSwitchEnabled = state.touchScreenSwitchEnabled
							directSelectionEnabled = state.directSelectionEnabled
							twoSwitchEnabled = state.twoSwitchEnabled
							singleSwitchEnabled = state.singleSwitchEnabled
							scanLayoutSizeLarge = state.scanLayoutSizeLarge
							flashKeyFeedbackEnabled = state.flashKeyFeedbackEnabled
							beepKeyFeedbackEnabled = state.beepKeyFeedbackEnabled
							touchScreenSwitchFlashEnabled = state.touchScreenSwitchFlashEnabled
							touchScreenSwitchBeepEnabled = state.touchScreenSwitchBeepEnabled
							directSelectionDebounceMs = state.directSelectionDebounceMs
							showButtonsPressedPref = state.showButtonsPressedPref
							keyHistoryShrinkToFitPref = state.keyHistoryShrinkToFitPref
							errorBeepEnabled = state.errorBeepEnabled
							correctionBeepEnabled = state.correctionBeepEnabled
							correctionFlashRedEnabled = state.correctionFlashRedEnabled
							enableDebugLog = state.enableDebugLog
						}

						override fun applyKeyHistoryVisibility() = this@JustTypeIME.applyKeyHistoryVisibility()
						override fun applyKeyHistoryHeight(repo: SettingsRepository) = this@JustTypeIME.applyKeyHistoryHeight(repo)
						override fun applyKeyHistoryShrinkToFit() = this@JustTypeIME.applyKeyHistoryShrinkToFit()

						override fun isButtonsReady(): Boolean = ::buttonsJT.isInitialized && ::buttonsScan.isInitialized
						override fun updateButtonClickListeners() = keyFeedbackController.updateButtonClickListeners(buttonsJT, buttonsScan)

						override fun isLayoutContainersReady(): Boolean = ::jtLayoutContainer.isInitialized && ::scanLayoutContainer.isInitialized
						override fun setActiveLayout(useScan: Boolean) = this@JustTypeIME.setActiveLayout(useScan)
						override fun useScanLayout(): Boolean = this@JustTypeIME.useScanLayout

						override fun isLayoutManagerReady(): Boolean = inputViewInflated
						override fun recreateInputView() = setInputView(onCreateInputView())
						override fun applyKeyboardSize() = this@JustTypeIME.applyKeyboardSize()

						override fun reinitJtuiInBackground() {
							serviceScope.launch(Dispatchers.IO) {
								jtui.init()
								// The typing language may have changed — follow it with the spoken voice.
								ttsController.applyForActiveLanguage()
								serviceScope.launch(Dispatchers.Main.immediate) {
									if (jtui.isInSettingsMode) {
										// Reinit ran under Settings Mode (Apply on a reinit-class
										// setting): re-emit the settings display instead of painting
										// the typing keys — forceUpdateUi would overwrite the key
										// legend with the empty "Settings" page placeholders.
										jtui.refreshSettingsDisplay()
									} else {
										jtui.forceUpdateUi()
									}
								}
							}
						}

						override fun applyTtsForActiveLanguage() {
							serviceScope.launch(Dispatchers.IO) { ttsController.applyForActiveLanguage() }
						}

						override fun applyUiVoice() {
							ttsController.applyUiVoice()
						}

						override fun debugLog(message: String) = this@JustTypeIME.debugLog(message)
						override fun executeOnUiThread(block: () -> Unit) {
							serviceScope.launch(Dispatchers.Main.immediate) { block() }
						}
					},
				),
			)

			// Listen for preference changes
			val settingsRepo = org.continuouspath.justtype.settings.SettingsRepository.get()
			prefsListener = preferenceCoordinator.createOnCreateListener()
			settingsRepo.addChangeListener(prefsListener!!)
			preferenceCoordinator.loadAndApplyAll()
		} finally {
			org.continuouspath.justtype.utils.PerfTrace.log("ime.onCreate (main-thread cold start)", org.continuouspath.justtype.utils.PerfTrace.now() - perfStart)
			android.os.Trace.endSection()
		}
	}

	override fun onStartInput(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
		android.os.Trace.beginSection("ime.onStartInput")
		try {
			super.onStartInput(attribute, restarting)
			debugLog("[onStartInput 1]  ENTRY:  isNewInputSession=$isNewInputSession     attribute=$attribute    restarting=$restarting  wokeFromScreenOff=$wokeFromScreenOff  fieldId=${attribute?.fieldId}")
			Log.d("MJ_CAP", "onStartInput restarting=$restarting fieldId=${attribute?.fieldId}")
			currentFieldId = attribute?.fieldId ?: 0
			// Cancel any deferred "head tracking disabled" broadcast — Android
			// fires onFinishInput then onStartInput in quick succession for
			// transient focus shuffles (e.g. HeadBoard adding its cursor SAW
			// during a pop-out), and HeadBoard must not disarm for those.
			// scheduleHeadBoardDisable was called in onFinishInput.
			headTrackingSubsystem.cancelDeferredHeadBoardDisable()
			// Safety net: dismiss stale settings overlay if it's visible but settings mode is not active
			if (imeState.jtui?.isInSettingsMode == false &&
				::settingsOverlayController.isInitialized &&
				settingsOverlayController.isOverlayVisible
			) {
				debugLog("[onStartInput] Dismissing stale settings overlay")
				settingsOverlayController.hide()
			}
			clearPageMoveState()
			preferenceCoordinator.loadAndApplyAll()
			// Update overlay after preferences are loaded
			if (inputViewInflated) overlayCoordinator.updateDirectionalSelection()
			// 	currentInputConnection?.finishComposingText()
			imeTextController.allowComposing = !isPasswordEditor(attribute)
			imeTextController.suppressAutospaceForField = isNoAutospaceField(attribute)
			// NGB learning hygiene: never learn from password/sensitive fields.
			imeState.jtui?.ngbLearningSuppressed = isPasswordEditor(attribute)
			debugLog("[onStartInput] imeTextController.suppressAutospaceForField=${imeTextController.suppressAutospaceForField} inputType=0x${String.format("%08X", attribute?.inputType ?: 0)}")

			// If we're mid phrase-flow, don't reset JTUI or change pages; keep spelling/phrase context intact.
			if (phraseFlowController.isActive) {
				debugLog("[onStartInput] phraseFlow active; skipping JTUI reset/pull-in to preserve spelling page")
				imeTextController.resetJTUI = false
				isNewInputSession = false
				imeTextController.attemptPullInOnFirstUpdate = false
				if (shouldShowKeyboard(attribute) && !isInputViewShown) {
					requestShowKeyboard()
				}
				return
			}

			// IMPORTANT: Reset JTUI state FIRST if flag is set (from previous field)
			// This ensures we don't carry over old word state into the new field
			if (imeState is IMEState.Ready && imeTextController.resetJTUI) {
				debugLog("[onStartInput 1a]  imeTextController.resetJTUI flag is set, clearing JTUI state before pull-in attempt.  callUpdate = false")
				val shouldShift = try {
					computeAutoShift()
				} catch (e: Exception) {
					ExceptionReporter.reportSilent("JustTypeIME:computeAutoShift", e)
					false
				}
				jtui.resetJTUI(shouldShift, callUpdateUi = false, autoCapReason = imeTextController.lastAutoCapReason) // Don't update UI yet
				imeTextController.resetJTUI = false
			}

			// Same-field resume (wake from sleep, app switch back): never auto-pull-in.
			// `restarting` alone is unreliable — editors restart input with
			// restarting=false after a screen-off — so the screen-off broadcast is
			// the authoritative wake signal (consumed here).
			val resumingAfterScreenOff = wokeFromScreenOff
			wokeFromScreenOff = false
			if ((restarting || resumingAfterScreenOff) && handleSameFieldResume(attribute)) return

			// Attempt immediate pull-in at current cursor (no delay) in case we're touching a word
			// Only if JTUI has been initialized; at startup, auto-select even the first candidate
			if (imeState is IMEState.Ready) {
				debugLog("[onStartInput 2]  Calling tryImmediatePullInAtCurrentCursor()")
				val pullInResult = tryImmediatePullInAtCurrentCursor(selectFirstOnMatch = true)
				if (pullInResult > 0) {
					isNewInputSession = false
					// Determine Shift state based on cursor context at session start
					updateShiftFromCursor(false)
					debugLog("[onStartInput 3]  tryImmediatePullInAtCurrentCursor() SUCCESS - word pulled in and selected")
					imeTextController.resetJTUI = false
					imeTextController.haveComposing = true
					// Ensure keyboard is shown after successful pull-in
					if (shouldShowKeyboard(attribute) && !isInputViewShown) {
						debugLog("[onStartInput 3a]  Requesting keyboard to show")
						requestShowKeyboard()
					}
				} else if (pullInResult == -1) {
					// Early failure (InputConnection not ready yet) - will retry on first onUpdateSelection
					debugLog("[onStartInput 4b]  tryImmediatePullInAtCurrentCursor() blocked (connection not ready), will retry on first cursor update")
					isNewInputSession = false
					imeTextController.resetJTUI = false
					imeTextController.haveComposing = false
					imeTextController.attemptPullInOnFirstUpdate = true // Set flag to retry on first update
					// Best-effort context now — a static field may never send the
					// cursor update the retry waits for (fail-soft when no IC).
					imeTextController.reconstructNgbContextAtCursor()
					// Ensure keyboard is shown
					if (shouldShowKeyboard(attribute) && !isInputViewShown) {
						debugLog("[onStartInput 4c]  Requesting keyboard to show (will retry pull-in on first update)")
						requestShowKeyboard()
					}
				} else {
					// No word at cursor (result = 0), reset JTUI state
					debugLog("[onStartInput 4]  tryImmediatePullInAtCurrentCursor() FAILED (no word at cursor), resetting JTUI state, callUpdate = true")
					val shouldShift = try {
						computeAutoShift()
					} catch (e: Exception) {
						ExceptionReporter.reportSilent("JustTypeIME:computeAutoShift", e)
						false
					}
					jtui.resetJTUI(shouldShift, true, autoCapReason = imeTextController.lastAutoCapReason)
					// No word to pull in, but the PRECEDING text still carries the
					// prediction context — empty field / sentence-final = the BOS
					// row, mid-text cursor = the previous word (field-entry funnel).
					imeTextController.reconstructNgbContextAtCursor()
					isNewInputSession = false
					// Flag that reset has already been performed
					imeTextController.resetJTUI = false
					imeTextController.haveComposing = false
					// Ensure keyboard is shown even if no pull-in
					if (shouldShowKeyboard(attribute) && !isInputViewShown) {
						debugLog("[onStartInput 4a]  Requesting keyboard to show (no word to pull in)")
						requestShowKeyboard()
					}
				}
			} else {
				debugLog("[onStartInput 5]  ENTRY:  jtui NOT initialized; setting imeTextController.resetJTUI=true")
				// Mark new editor session; don't inject prior buffer into a new field
				isNewInputSession = true
				// Flag that reset may still need to be performed
				imeTextController.resetJTUI = true
				imeTextController.haveComposing = false
				// Ensure keyboard is shown
				if (shouldShowKeyboard(attribute) && !isInputViewShown) {
					debugLog("[onStartInput 5a]  Requesting keyboard to show (JTUI not initialized)")
					requestShowKeyboard()
				}
			}

			// Notify HeadBoard of head tracking state
			headTrackingSubsystem.notifyHeadBoardOfTrackingState()
		} finally {
			android.os.Trace.endSection()
		}
	}

	// Same-field resume (wake from sleep, app switch back): a word that was MID-ENTRY
	// when the field paused is kept exactly as the user left it; otherwise reset clean.
	// Never auto-pull-in here — synthesizing a key sequence the user did not type reads
	// as phantom typing, and a stale InputConnection snapshot can even resurrect text
	// from elsewhere. Returns false when JTUI is not ready (caller falls through).
	private fun handleSameFieldResume(attribute: android.view.inputmethod.EditorInfo?): Boolean {
		if (imeState !is IMEState.Ready) return false
		val activeSeqLen = imeState.jtui?.getAmbiguousSequenceLength() ?: 0
		debugLog("[onStartInput 2r]  restarting=true activeSeqLen=$activeSeqLen — resume path, skipping auto pull-in")
		if (activeSeqLen == 0) {
			val saved = resumeSequenceKeys?.takeIf { resumeFieldId == currentFieldId }
			resumeSequenceKeys = null
			val restored = saved != null &&
				saved.isNotEmpty() &&
				imeTextController.tryImmediatePullInAtCurrentCursor(resumeKeys = saved) == 1
			debugLog("[onStartInput 2r]  saved=${saved?.size ?: 0} keys, restored=$restored")
			if (!restored) {
				val shouldShift = try {
					computeAutoShift()
				} catch (e: Exception) {
					ExceptionReporter.reportSilent("JustTypeIME:computeAutoShift", e)
					false
				}
				jtui.resetJTUI(shouldShift, true, autoCapReason = imeTextController.lastAutoCapReason)
				imeTextController.haveComposing = false
				// The resume path is the COMMON field entry on a real device
				// (screen wake, app switch, editors restarting input): without
				// this the NGB context stayed null here — the BOS dead zone
				// Cliff kept hitting however the funnel branches were fixed.
				imeTextController.reconstructNgbContextAtCursor()
			}
		}
		isNewInputSession = false
		imeTextController.resetJTUI = false
		imeTextController.attemptPullInOnFirstUpdate = false
		if (shouldShowKeyboard(attribute) && !isInputViewShown) {
			requestShowKeyboard()
		}
		headTrackingSubsystem.notifyHeadBoardOfTrackingState()
		return true
	}

	// Re-showing the keyboard (e.g. switching back from another IME) can leave the key views
	// blank until the next interaction. Force a UI rebuild — posted to the key grid so it runs
	// after the view is laid out — so the current page renders immediately (BUG #0). Both hooks
	// are covered because Android may re-show the window via either path.
	private fun refreshKeyboardOnShow() {
		val ready = imeState as? IMEState.Ready ?: return
		val grid = keyGridActiveView ?: return
		grid.post { runCatching { ready.jtui.forceUpdateUi() } }
	}

	override fun onStartInputView(editorInfo: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
		super.onStartInputView(editorInfo, restarting)
		refreshKeyboardOnShow()
	}

	override fun onFinishInput() {
		super.onFinishInput()
		debugLog("[onFinishInput 1]  ENTRY")
		Log.d("MJ_CAP", "onFinishInput")
		// Snapshot a mid-entry ambiguous sequence (no Select activations) so a
		// same-field resume can restore it. Sequences the user has stepped with
		// Select are never restored (their intent is ambiguous after a pause).
		resumeSequenceKeys = imeState.jtui
			?.takeIf { it.getAmbiguousSequenceLength() > 0 && !it.hasActiveSelection() }
			?.getAmbiguousKeyNumbers()
		resumeFieldId = currentFieldId
		// Clear composing and local tracking at the end of a session
		currentInputConnection?.finishComposingText()
		isNewInputSession = false
		cancelPullIn()
		scanSubsystem.stopScan()
		imeTextController.attemptPullInOnFirstUpdate = false // Clear deferred pull-in flag
		// Since no word pulled in, so trigger reset of JTUI state
		debugLog("[onFinishInput 2]  EXITING, reset JTUI state")
		imeTextController.resetJTUI = true
		imeTextController.autoSpaceDecision = false
		imeTextController.autoSpaceInserted = false
		imeTextController.pendingTrailingSpace = false
		setIgnoreCursorRange(-1, -1)

		// Cancel exit zone timer, reset HT pause state, clear borders
		headTrackingSubsystem.onInputFinished()

		// Notify HeadBoard that head tracking is disabled (keyboard closing).
		// Deferred — Android also fires onFinishInput for transient focus
		// shuffles (e.g. HeadBoard adds its cursor SAW during a pop-out),
		// and we don't want to disarm HeadBoard for those. If onStartInput
		// fires within HEADBOARD_DISABLE_DEFER_MS, the scheduled broadcast
		// is cancelled by cancelDeferredHeadBoardDisable() above.
		headTrackingSubsystem.scheduleHeadBoardDisable()

		// Hide overlay and switch bar when keyboard closes
		if (inputViewInflated) overlayCoordinator.onKeyboardHidden()

		// Cancel any stuck switch timeouts
		if (::externalSwitchHandler.isInitialized) externalSwitchHandler.cancelAllStuckTimeouts()
		joystickSubsystem.cancelAndClear()
		if (::mouseJoystickSubsystem.isInitialized) mouseJoystickSubsystem.cancelAndClear()
		// Tear down the two-switch cycle too (was omitted): its timeout/auto-repeat/activation-repeat
		// jobs otherwise keep running after the keyboard closes and fire against the closed session,
		// and its red/green tints would persist into the next open.
		if (::twoSwitchSubsystem.isInitialized) twoSwitchSubsystem.cancelAndClear()
		// Drop per-field remembered speech so speak-last-selection can't read a prior field's text.
		if (::ttsController.isInitialized) ttsController.clearSessionState()
	}

	override fun onWindowHidden() {
		super.onWindowHidden()
		ImeWindowState.shown = false
		// CRITICAL: When keyboard window is hidden, immediately deactivate overlay
		debugLog("[onWindowHidden] Keyboard hidden - deactivating overlay")
		// Relinquish the composition + word buffer: stale composing/JTUI state re-inserts
		// the current word on reopen (duplication); the reopen pull-in re-absorbs it.
		imeTextController.relinquishComposingOnHide()
		imeState.jtui?.let { jtuiRef ->
			val shouldShift = runCatching { computeAutoShift() }.getOrDefault(false)
			runCatching { jtuiRef.resetJTUI(shouldShift, callUpdateUi = false, autoCapReason = imeTextController.lastAutoCapReason) }
		}
		joystickSubsystem.cancelAndClear()
		if (::mouseJoystickSubsystem.isInitialized) mouseJoystickSubsystem.cancelAndClear()
		if (::twoSwitchSubsystem.isInitialized) twoSwitchSubsystem.cancelAndClear()
		if (inputViewInflated) overlayCoordinator.onKeyboardHidden()
		// Exit settings mode cleanly when keyboard is hidden (e.g., user presses Home)
		// so the overlay doesn't remain stale when the keyboard reappears
		imeState.jtui?.let { jtuiRef ->
			if (jtuiRef.isInSettingsMode) {
				debugLog("[onWindowHidden] Exiting settings mode (keyboard hidden while in settings)")
				jtuiRef.exitSettingsMode()
			}
		}
	}

	override fun onWindowShown() {
		super.onWindowShown()
		ImeWindowState.shown = true
		// When keyboard window is shown, update overlay state based on enabled features
		debugLog("[onWindowShown] Keyboard shown - updating overlay")
		if (inputViewInflated) overlayCoordinator.onKeyboardShown()
		headTrackingSubsystem.onWindowShown()
		refreshKeyboardOnShow() // BUG #0: re-render keys when the window is (re)shown
		if (::mouseJoystickSubsystem.isInitialized && currentKeyboardHeightPx > 0) {
			mouseJoystickSubsystem.updateKeyboardHeight(currentKeyboardHeightPx)
		}
		// onWindowHidden's reset cleared the NGB context (e.g. Enter in a
		// single-line field hides the window); a re-show without a full input
		// restart never re-derived it — the last orphan reset of the BOS
		// dead-zone saga. Text-derived, idempotent, fail-soft.
		if (imeState is IMEState.Ready && jtui.getAmbiguousSequenceLength() == 0) {
			imeTextController.reconstructNgbContextAtCursor()
		}
	}

	// Never enter fullscreen extract mode (the framework's landscape default): it replaces the
	// editor with a text box filling the display, hiding the app and breaking the grid layout.
	override fun onEvaluateFullscreenMode(): Boolean = false

	// Keyboard height feeds the mouse-joystick barrier geometry. Use the input view's real height:
	// visibleTopInsets is 0 here, so screen-minus-top-inset returned the full screen and placed the
	// barrier off the bottom edge (it never caught a hover).
	override fun onComputeInsets(outInsets: Insets) {
		super.onComputeInsets(outInsets)
		val newHeight = hoverRootRef?.height ?: 0
		// This callback fires on every layout pass (tens of times/sec) — only log on
		// actual height changes, not every call, to avoid main-thread log spam on weak devices.
		if (newHeight != currentKeyboardHeightPx && newHeight > 0) {
			Log.d("MJ_ESC", "INSETS visibleTop=${outInsets.visibleTopInsets} hoverRootH=$newHeight -> kbdHeight=$newHeight")
			currentKeyboardHeightPx = newHeight
			if (::mouseJoystickSubsystem.isInitialized) {
				mouseJoystickSubsystem.updateKeyboardHeight(newHeight)
			}
		}
	}

	override fun onTrimMemory(level: Int) {
		super.onTrimMemory(level)
		if (inputViewInflated) overlayCoordinator.onTrimMemory(level)
	}

	// A render failure must degrade to a fallback view, never crash the IME (anti-lockout).
	@Suppress("TooGenericExceptionCaught")
	override fun onCreateInputView(): View {
		android.os.Trace.beginSection("ime.onCreateInputView")
		try {
			// Ensure AppCompat/Material theming for AppCompat widgets in IME context
			val themed = ContextThemeWrapper(this, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar)
			val inflater = LayoutInflater.from(themed)
			val root = inflater.inflate(R.layout.ime_input, null)
			// Determine if scan layout is needed based on effective input method
			val startupPrimary = settingsRepo.getString(KEY_INPUT_METHOD_PRIMARY)
			val startupEffective = if (startupPrimary != INPUT_METHOD_NONE) {
				startupPrimary
			} else {
				settingsRepo.getString(KEY_INPUT_METHOD, INPUT_METHOD_DIRECT_SELECTION) ?: INPUT_METHOD_DIRECT_SELECTION
			}
			useScanLayout = startupEffective == INPUT_METHOD_SINGLE_SWITCH
			scanLayoutSizeLarge = settingsRepo.getString(KEY_SCAN_LAYOUT_SIZE) != SCAN_LAYOUT_SIZE_SMALL

			// Initialize LayoutManager to handle layout switching and view management
			layoutManager = LayoutManager(root, this, settingsRepo)
			layoutManager.initialize()

			// Legacy view references (still needed for some specific logic)
			jtLayoutContainer = root.findViewById(R.id.jtLayoutContainer)
			scanLayoutContainer = root.findViewById(R.id.scanLayoutContainer)
			scanTopRow = root.findViewById(R.id.scanTopRow)
			scanKeysContainer = root.findViewById(R.id.scanKeysContainer)
			scanLargeRowTop = root.findViewById(R.id.scanLargeRowTop)
			scanLargeRowBottom = root.findViewById(R.id.scanLargeRowBottom)
			scanSmallRow = root.findViewById(R.id.scanSmallRow)
			scanButtonPool = root.findViewById(R.id.scanButtonPool)
			addScanTopRowLayoutWatchers()

			keyHistoryScrollViewJT = root.findViewById(R.id.keyHistoryScrollView)
			keyHistoryViewJT = root.findViewById(R.id.keyHistoryView)
			keyHistoryScrollViewScan = root.findViewById(R.id.keyHistoryScrollViewScan)
			keyHistoryViewScan = root.findViewById(R.id.keyHistoryViewScan)

			selectionListViewJT = root.findViewById(R.id.selectionList)
			selectionListScanContainer = root.findViewById(R.id.selectionListScanContainer)
			centerLabelJT = root.findViewById(R.id.centerLabel)
			centerLabelScan = root.findViewById(R.id.centerLabelScan)
			keyGridViewJT = root.findViewById(R.id.keyGrid)

			phraseOverlay = root.findViewById(R.id.phraseOverlay)
			phraseOverlayTitle = root.findViewById(R.id.phraseOverlayTitle)
			phraseOverlayPrompt = root.findViewById(R.id.phraseOverlayPrompt)
			phraseOverlayContent = root.findViewById(R.id.phraseOverlayContent)
			phraseOverlayAbbrev = root.findViewById(R.id.phraseOverlayAbbrev)
			phraseOverlayDone = root.findViewById(R.id.phraseOverlayDone)
			phraseOverlayCancel = root.findViewById(R.id.phraseOverlayCancel)
			phraseOverlayDone?.setOnClickListener { phraseFlowController.onDonePressed() }
			phraseOverlayCancel?.setOnClickListener { phraseFlowController.cancelCurrentFlow() }

			// Initialize overlay coordinator (touch detection overlay + switch bar).
			// Input-view rebuilds (rotation, critical-setting recreation) replace this
			// coordinator — tear down the old instance first or its system windows
			// (TSS bar + touch overlay) orphan and survive IME close.
			if (::overlayCoordinator.isInitialized) overlayCoordinator.destroy()
			overlayCoordinator = OverlayCoordinator(
				context = this,
				scope = serviceScope,
				callbacks = object : OverlayCoordinatorCallbacks {
					override val isInputViewShown get() = this@JustTypeIME.isInputViewShown
					override val isJtuiInitialized get() = imeState is IMEState.Ready
					override val isDirectionalSelectionEnabled get() = directionalSelectionEnabled
					override val isTwoSwitchEnabled get() = twoSwitchEnabled
					override val isSingleSwitchEnabled get() = singleSwitchEnabled
					override val isTouchScreenSwitchEnabled get() = touchScreenSwitchEnabled
					override val isFlashEnabled get() = touchScreenSwitchFlashEnabled
					override val inputSurface get() = viewBridgeCoordinator
					override fun requestHideSelf() = requestHideSelf(0)
					override fun scanSwitchDown(keyCode: Int) = scanSubsystem.handleSwitchDown(keyCode)
					override fun scanSwitchUp() = scanSubsystem.handleSwitchUp()
					override fun twoSwitchTouchDown(role: String) = twoSwitchSubsystem.handleTouchDown(role)
					override fun twoSwitchTouchUp() = twoSwitchSubsystem.handleTouchUp()
					override fun debugLog(message: String) = this@JustTypeIME.debugLog(message)
				},
			)

			// Switch bar initialization delegated to OverlayCoordinator
			overlayCoordinator.initSwitchBar(root.findViewById(R.id.switchBarContainer))

			// Apply keyboard size based on preference
			applyKeyboardSize()
			applySelectionTextSize()

			// Get button references from LayoutManager
			buttonsJT = layoutManager.jtController.buttons
			buttonsScan = layoutManager.scanController.buttons

			// Store original backgrounds from LayoutManager (filter out nulls)
			standingBackgrounds.clear()
			layoutManager.allButtonOriginalBackgrounds.forEach { (btn, drawable) ->
				drawable?.let { buttonOriginalBackgrounds[btn] = it }
			}

			// Set active layout based on input method selection
			setActiveLayout(useScanLayout)

			// Initialize two-switch highlighting immediately if enabled
			twoSwitchSubsystem.onViewsReady()
			if (twoSwitchEnabled) {
				twoSwitchSubsystem.startCycle(restartOnly = false)
			} else {
				twoSwitchSubsystem.clearColors()
			}

			uiUpdateHandler = UiUpdateHandler(
				context = this,
				getKeyHistoryView = { keyHistoryView },
				getKeyHistoryScrollView = { keyHistoryScrollView },
				getSelectionListView = { selectionListView },
				getCenterLabelView = { centerLabelView },
				getKeyGridActiveView = { keyGridActiveView },
				// Fetch the live button views from the layout manager rather than a cached snapshot:
				// the cache can be captured before the grid's child views exist, leaving renders with
				// zero buttons and a blank keyboard (BUG #0).
				getButtons = { if (::layoutManager.isInitialized) runCatching { layoutManager.buttons }.getOrDefault(emptyList()) else emptyList() },
				getSelectionListScanContainer = { if (::selectionListScanContainer.isInitialized) selectionListScanContainer else null },
				useScanLayout = { useScanLayout },
				phraseFlowController = phraseFlowController,
				scanSubsystem = scanSubsystem,
				ttsController = ttsController,
				imeTextController = imeTextController,
				callbacks = UiUpdateCallbacksImpl(
					deps = object : UiUpdateCallbacksImplDeps {
						override fun getJtui(): JTUI? = imeState.jtui
						override fun updateScanColumnViews(buffers: List<CharSequence>) = this@JustTypeIME.updateScanColumnViews(buffers)
						override fun updateJtColumnViews(buffers: List<CharSequence>) {
							if (inputViewInflated) layoutManager.jtController.updateColumnViews(buffers)
						}
						override fun updateSelectionListDimensions() = this@JustTypeIME.updateSelectionListDimensions()
						override fun applyScanTopRowSize() = this@JustTypeIME.applyScanTopRowSize()
						override fun updateItemsPerColumn() = this@JustTypeIME.updateItemsPerColumn()
						override fun updateShiftFromCursor(suppressUpdateUI: Boolean) = this@JustTypeIME.updateShiftFromCursor(suppressUpdateUI)
						override fun getCursorOffset(): Int = this@JustTypeIME.getCursorOffset()
						override fun setIgnoreCursorRange(start: Int, end: Int) = this@JustTypeIME.setIgnoreCursorRange(start, end)
						override fun applyEditorUpdate(preview: String) = this@JustTypeIME.applyEditorUpdate(preview)
						override fun commitImmediateText(text: String) = this@JustTypeIME.commitImmediateText(text)
						override fun getInputConnection(): InputConnection? = currentInputConnection
						override fun debugLog(message: String) = this@JustTypeIME.debugLog(message)
						override fun debugLog(category: DebugCategory, message: String) = this@JustTypeIME.debugLog(category, message)
						override fun getHeadTrackingCenterOverride(): String? = if (::headTrackingSubsystem.isInitialized) headTrackingSubsystem.getCenterLabelOverride() else null
					},
				),
			)

			jtui = JTUI(
				onAddNewPhrase = { phraseText -> phraseFlowController.startFlow(phraseText) },
				onCancelNewPhrase = { phraseFlowController.cancelOrReset() },
				onPhraseDone = { phraseFlowController.onDonePressed() },
				phraseRepository = phraseRepository,
				sayInterruptible = { text -> speakInterruptible(text) },
				sayQueued = { text -> speakQueued(text) },
				sayUiInterruptible = { text -> ttsController.speakUiInterruptible(text) },
				sayUiQueued = { text -> ttsController.speakUiQueued(text) },
				onSpeakLastSelection = { speakLastSelection() },
				onUiUpdate = { ui ->
					serviceScope.launch(Dispatchers.Main.immediate) { uiUpdateHandler.handleUiSnapshot(ui) }
				},

				onNumericOutput = { text -> imeTextController.onNumericOutput(text) },

				onImmediateOutput = { text -> imeTextController.onImmediateOutput(text) },

				onSpellingOutput = { textParam -> imeTextController.onSpellingOutput(textParam) },

				onSpeakSentence = { checkPrev -> imeTextController.onSpeakSentence(checkPrev) },

				onSpeakNextSentence = { imeTextController.onSpeakNextSentence() },
				onFinalizeText = { text -> imeTextController.onFinalizeText(text) },
				onAmbiguousSequenceStart = { imeTextController.onAmbiguousSequenceStart() },
				onSpaceIfNeeded = { afterPunct -> imeTextController.onSpaceIfNeeded(afterPunct) },

				onUndoPressed = { context -> imeTextController.onUndoPressed(context) },

				onDeleteWord = {
					val saveEditMode = isEditMode
					isEditMode = true
					handleDeleteWord()
					isEditMode = saveEditMode
				},
				onDeleteChar = {
					val saveEditMode = isEditMode
					isEditMode = true
					handleDeleteChar()
					isEditMode = saveEditMode
				},

				assets = assets,
				filesDir = filesDir,
				prefs = org.continuouspath.justtype.settings.SettingsRepository.get(),
				context = this,
				onEnterKey = { imeTextController.handleEnterAction() },
				onErrorBeep = { force -> keyFeedbackController.errorFeedback(force) },
				onConfidenceSignal = { keyFeedbackController.confidenceSignal() },
				onCouldHaveSavedPrompt = { saved -> showCouldHaveSavedPrompt(saved) },
				onNgbSpanCollapse = { imeTextController.handleNgbSpanCollapse() },
				onSetAutospaceSuppressed = { suppressed -> imeTextController.suppressAutospaceMode = suppressed },
				onCustomWordIntercept = { word ->
					phraseFlowController.completeFromCustomWord(word)
				},
				onDataMutation = {
					BackupManager.scheduleBackup(this)
				},
				onCursorMove = { direction, hadAmbig, mode, selecting -> handleCursorMove(direction, hadAmbig, mode, selecting) },
				onScroll = { direction -> handleScroll(direction) },
				onBookmark = { action, isSelecting -> handleBookmark(action, isSelecting) },
				onManualPullIn = {
					if (!imeTextController.handleManualPullIn()) keyFeedbackController.errorFeedback(false)
				},
				onEditModeExit = { imeTextController.handleEditModeExit() },
				onClipboardAction = { keyCode -> imeTextController.handleClipboardAction(keyCode) },
				onSpeakSelectionOrSentence = { imeTextController.handleSpeakSelectionOrSentence() },
				onCaseChange = { caseType, caseMode -> handleCaseChange(caseType, caseMode) },
				onEditingDelete = { handleEditingDelete() },
				onSettingsEnter = { settingsOverlayController.show() },
				onSettingsExit = { settingsOverlayController.hide() },
				onSettingsDisplayUpdate = { displayState -> settingsOverlayController.updateUI(displayState) },
				settingsLangpackServices = org.continuouspath.justtype.langpack.ImeLangpackServices(
					applicationContext,
					serviceScope,
					settingsRepo,
				),
				onSettingsAction = { actionId ->
					// Action rows that open an outside surface over the current app. shareLogs
					// already NEW_TASKs its chooser; LanguagesActivity needs the flag from a service.
					when (actionId) {
						SettingsActivity.ACTION_GET_MORE_LANGUAGES -> startActivity(
							android.content.Intent(this, org.continuouspath.justtype.activity.LanguagesActivity::class.java)
								.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
						)
						SettingsActivity.ACTION_SUBMIT_FEEDBACK ->
							org.continuouspath.justtype.logging.DebugLogShareHelper.shareLogs(this)
					}
				},
				onSettingsApply = { key, _ ->
					// Settings Mode applied a preference — reload and reconfigure immediately.
					// This is the direct notification path; the SharedPreferences listener
					// provides a secondary backup.
					preferenceCoordinator.loadAndApplyAll()
					// Notify HeadBoard when input method changes from Settings Mode
					if (key == KEY_INPUT_METHOD || key == KEY_INPUT_METHOD_PRIMARY) {
						headTrackingSubsystem.notifyHeadBoardOfTrackingState()
					}
				},
				onOpenNavigationKeyboard = { launchNavigationKeyboard() },
				wldDispatcher = headTrackingSubsystem.wldCoroutineDispatcher,
				wldScope = serviceScope,
				log = { category, msg -> debugLog(category, msg) },
				isAccessiblePromptShowing = { accessiblePrompt.isShowing },
				dismissAccessiblePrompt = { accessiblePrompt.dismiss() },
			)

			// Initialize dynamic column settings for scan layout
			// For JT layout, use single column (itemsPerColumn = 0)
			// For scan layout, calculate after container is measured
			if (!useScanLayout) {
				jtui.itemsPerColumn = 0
				jtui.maxColumns = 1
			}

			// Set up button click listeners immediately (they'll check imeState before processing)
			keyFeedbackController.updateButtonClickListeners(buttonsJT, buttonsScan)

			// Move heavy JTUI initialization to background thread to reduce keyboard startup lag.
			// Snapshot the JTUI reference so a re-entry (new onCreateInputView reassigning the
			// `jtui` field) cannot make this coroutine flip imeState to Ready against a
			// half-initialized successor instance.
			val initializingJtui = jtui
			imeState = IMEState.Constructed(initializingJtui)
			jtuiInitJob = serviceScope.launch(Dispatchers.IO) {
				try {
					// Heavy operations: opens database, loads word lists from assets, processes vocabulary
					org.continuouspath.justtype.utils.PerfTrace.measure("jtui.init (DB open + dictionary load)") {
						initializingJtui.init()
					}
				} catch (e: Exception) {
					// Blocking init can't be interrupted by cancellation; if the service died
					// while it ran, the failure is teardown noise (half-released DBs), not a
					// fault to crash on. In tests it poisoned the next runTest in the worker.
					if (isActive) throw e
					debugLog("[onCreateInputView] init failed after service destroy: ${e.message}")
					return@launch
				}
				// Cache the Select key index for abort-on-next-key logic
				headTrackingSubsystem.selectKeyIndex = initializingJtui.getSelectKeyIndex()

				// After initialization completes, update UI on main thread
				serviceScope.launch(Dispatchers.Main.immediate) {
					// If the IME was re-entered while we were initializing, the field has
					// been reassigned to a successor JTUI; let that newer init flip the state.
					if (jtui !== initializingJtui) return@launch
					imeState = IMEState.Ready(initializingJtui)
					debugLog("[onCreateInputView] JTUI initialization completed on background thread")

					// Apply initial shift state from cursor context and refresh UI once
					try {
						updateShiftFromCursor(true)
						initializingJtui.forceUpdateUi(false)
					} catch (e: Exception) {
						ExceptionReporter.reportSilent("JustTypeIME:postInitUiRefresh", e)
					}

					// Deferred field attach (startup race): when the editor attached
					// BEFORE initialization finished, onStartInput took its
					// not-ready branch and never re-fires while the user stays in
					// the field — the NGB context stayed null for the whole
					// session (Cliff's BOS dead zone, round 3). Derive it from the
					// field text now that the engine is up.
					if (currentInputStarted) {
						imeTextController.reconstructNgbContextAtCursor()
					}

					// Preferences-dependent UI
					preferenceCoordinator.loadAndApplyAll()
				}
			}

			// React to preference changes (e.g., layout mode) without restarting the IME
			try {
				prefsListener?.let { settingsRepo.removeChangeListener(it) }
				prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
					if (key == KEY_LAYOUT_MODE) {
						// Apply layout mode change immediately while suppressing editor commits
						val layoutPref = settingsRepo.getString(KEY_LAYOUT_MODE)
						val newMode = if (layoutPref == MODE_ALPHA) {
							org.continuouspath.justtype.logic.LayoutMode.Alphabetical
						} else {
							org.continuouspath.justtype.logic.LayoutMode.Optimized
						}
						serviceScope.launch(Dispatchers.Main.immediate) {
							val prevSuspend = imeTextController.suspendCommit
							imeTextController.suspendCommit = true
							try {
								jtui.layoutMode = newMode
								if (useScanLayout) {
									configureScanButtons()
									applyScanTopRowSize()
								}
							} finally {
								imeTextController.suspendCommit = prevSuspend
							}
						}
					} else if (key == KEY_KEYBOARD_SIZE_RATIO) {
						serviceScope.launch(Dispatchers.Main.immediate) {
							applyKeyboardSize()
							applyKeyHistoryHeight(settingsRepo) // Key history height is relative to key size
						}
					} else if (key == KEY_SHOW_BUTTONS_PRESSED) {
						serviceScope.launch(Dispatchers.Main.immediate) {
							showButtonsPressedPref = settingsRepo.getBoolean(KEY_SHOW_BUTTONS_PRESSED)
							applyKeyHistoryVisibility()
						}
					} else if (key == KEY_KEY_HISTORY_HEIGHT_PERCENT) {
						serviceScope.launch(Dispatchers.Main.immediate) { applyKeyHistoryHeight(settingsRepo) }
					} else if (key == KEY_KEY_HISTORY_SHRINK_TO_FIT) {
						serviceScope.launch(Dispatchers.Main.immediate) {
							keyHistoryShrinkToFitPref = settingsRepo.getBoolean(KEY_KEY_HISTORY_SHRINK_TO_FIT)
							applyKeyHistoryShrinkToFit()
						}
					} else if (key == Constants.KEY_SELECTION_TEXT_SIZE_SP) {
						serviceScope.launch(Dispatchers.Main.immediate) { applySelectionTextSize() }
					} else if (key == Constants.KEY_KEY_HISTORY_MARK_LATEST) {
						serviceScope.launch(Dispatchers.Main.immediate) {
							layoutManager.setKeyHistoryMarkLatest(
								settingsRepo.getBoolean(Constants.KEY_KEY_HISTORY_MARK_LATEST, true),
							)
						}
					} else if (key == Constants.KEY_KEY_HISTORY_HIGHLIGHT) {
						// Re-emit the snapshot so the highlight applies/clears live; JTUI reads
						// the pref per update. Skip in settings mode (would overwrite key labels).
						if (imeState is IMEState.Ready && imeState.jtui?.isInSettingsMode != true) {
							serviceScope.launch(Dispatchers.Main.immediate) { jtui.forceUpdateUi() }
						}
					} else if (key == KEY_NEXT_LETTER_HINTS) {
						serviceScope.launch(Dispatchers.Main.immediate) {
							val enabled = settingsRepo.getBoolean(KEY_NEXT_LETTER_HINTS)
							jtui.showNextLetterHints = enabled
						}
					} else if (key == KEY_ENABLE_DEBUG_LOG) {
						enableDebugLog = settingsRepo.getBoolean(KEY_ENABLE_DEBUG_LOG)
					} else if (key == KEY_DEBUG_LOG_CATEGORIES) {
						val values = settingsRepo.getStringSet(KEY_DEBUG_LOG_CATEGORIES, null)?.toSet()
						DebugLogger.setEnabledCategories(DebugCategory.fromPrefValues(values))
					} else if (key == Constants.KEY_DEBUG_LOG_RETENTION_DAYS) {
						DebugLogger.setRetentionDays(
							settingsRepo.getInt(Constants.KEY_DEBUG_LOG_RETENTION_DAYS),
						)
					} else if (key == KEY_SWITCH_DEBOUNCE_MS || key == KEY_EXTERNAL_SWITCH_STUCK_TIMEOUT_SEC) {
						externalSwitchHandler.updateSettings(
							debounceMs = settingsRepo.getInt(KEY_SWITCH_DEBOUNCE_MS).toLong().coerceIn(0L, 1000L),
							stuckTimeoutMs = settingsRepo.getInt(KEY_EXTERNAL_SWITCH_STUCK_TIMEOUT_SEC).coerceIn(2, 60) * 1000L,
						)
					} else if (key == KEY_HEADTRACKING_DEADZONE ||
						key == KEY_HEADTRACKING_ACTIVEZONE ||
						key == KEY_HEADTRACKING_EXITZONE ||
						key == KEY_HEADTRACKING_KEY_ACT_THRESHOLD ||
						key == KEY_HEADTRACKING_EXIT_DELAY_MS ||
						key == KEY_HEADTRACKING_CORNER_BIAS
					) {
						serviceScope.launch(Dispatchers.Main.immediate) { headTrackingSubsystem.updateZoneParams(settingsRepo) }
					} else if (key == KEY_TOUCH_OVERLAY_TIMEOUT_SEC ||
						key == KEY_DIRECTIONAL_SELECTION_SWIPE_PERCENT ||
						key == KEY_TOUCH_SCREEN_SWITCH_DEBOUNCE_MS ||
						key == KEY_DIRECTIONAL_SELECTION_DEBOUNCE_MS ||
						key == KEY_TSS_BUTTON_HEIGHT_PERCENT ||
						key == KEY_TSS_OVERLAY_OPACITY ||
						key == KEY_TSS_OVERLAY_MODE
					) {
						val repo = settingsRepo
						overlayCoordinator.updateConfig(
							OverlayConfig(
								timeoutSec = repo.getInt(KEY_TOUCH_OVERLAY_TIMEOUT_SEC).coerceIn(2, 10),
								swipePercent = repo.getInt(KEY_DIRECTIONAL_SELECTION_SWIPE_PERCENT).coerceIn(2, 20),
								touchSwitchDebounceMs = repo.getInt(KEY_TOUCH_SCREEN_SWITCH_DEBOUNCE_MS).coerceIn(0, 500),
								directionalDebounceMs = repo.getInt(KEY_DIRECTIONAL_SELECTION_DEBOUNCE_MS).coerceIn(0, 500),
								overlayModeEnabled = repo.getBoolean(KEY_TSS_OVERLAY_MODE),
								buttonHeightPercent = repo.getInt(KEY_TSS_BUTTON_HEIGHT_PERCENT).coerceIn(5, 100),
								overlayOpacityPercent = repo.getInt(KEY_TSS_OVERLAY_OPACITY).coerceIn(10, 100),
							),
						)
						overlayCoordinator.updateTouchScreenSwitch()
					} else if (key == KEY_INPUT_METHOD ||
						key == KEY_INPUT_METHOD_PRIMARY ||
						key == KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED ||
						key == KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED ||
						key == KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED
					) {
						// Input method changed — delegate to full critical setting handler
						// for layout switching, overlay updates, and HeadBoard notification.
						// Skip while in Settings Mode: the settings controller already
						// handles the apply directly via onApplySettingImmediate, and
						// triggering layout recreation/switch here would disrupt the
						// settings navigation (e.g. back out to wrong page).
						val inSettingsMode = imeState.jtui?.isInSettingsMode == true
						if (!preferenceCoordinator.isRecreatingInputView && !inSettingsMode) {
							// Debounce: cancel any in-flight recreate and schedule a fresh one.
							// Rapid checkbox toggles in InputMethodsActivity coalesce to a single
							// recreate; the last key in the burst is what we actually apply.
							pendingCriticalChangeJob?.cancel()
							pendingCriticalChangeJob = serviceScope.launch(Dispatchers.Main.immediate) {
								delay(criticalChangeDebounceMs)
								preferenceCoordinator.handleCriticalSettingChange(key)
							}
						}
					} else if (key == KEY_APP_LANGUAGE) {
						// UI language changed — rebuild SettingsRegistry and JTUI labels
						val newContext = LocaleHelper.wrap(applicationContext)
						org.continuouspath.justtype.settings.SettingsRegistry.reinitialize(newContext)
						if (imeState is IMEState.Ready) {
							serviceScope.launch(Dispatchers.Main.immediate) {
								jtui.updateLocaleContext(newContext)
								// Only call forceUpdateUi when NOT in settings mode.
								// In settings mode, updateLocaleContext() already refreshes
								// the display via settingsController.refreshDisplay().
								// Calling forceUpdateUi() would overwrite the key labels
								// with the empty "Settings" page placeholders.
								if (!jtui.isInSettingsMode) {
									jtui.forceUpdateUi()
								}
							}
						}
					} else if (key == KEY_TYPING_LANGUAGE ||
						key == KEY_OPTIMIZED_LAYOUT_SOURCE ||
						key == Constants.KEY_TONE_LABEL_STYLE
					) {
						// Typing language, pinned layout source, or tone-label style changed —
						// reinitialize JTUI so the word database, resolved layout, and page
						// grids all take effect live.
						if (imeState is IMEState.Ready) {
							preferenceCoordinator.reinitJtuiInBackground()
						}
					} else if (key == Constants.PREFS_KEY_LANGUAGE_TTS_VOICE) {
						// A per-language voice was chosen in the voice picker — rebind the live
						// TTS engine so the change is audible immediately (not just persisted).
						if (imeState is IMEState.Ready) {
							preferenceCoordinator.applyTtsForActiveLanguage()
						}
					} else if (key in JOYSTICK_SETTINGS_KEYS) {
						// Rebuild so device binding + zone changes apply live.
						if (::externalSwitchHandler.isInitialized) {
							externalSwitchHandler.initGamepadDetector(
								GamepadParams.fromSettings(settingsRepo),
								GamepadParams.deviceFilterFromSettings(settingsRepo),
							)
						}
					} else if (key != null && preferenceCoordinator.isCriticalSettingChange(key)) {
						// Critical keys without a dedicated branch above (show/require accented
						// keys, scan layout size, future additions). Without this fallthrough,
						// writes from SettingsActivity only take effect after a process restart:
						// this listener replaces the coordinator's onCreate listener, so its
						// critical-setting path never fires once the input view exists.
						// Debounced like the input-method branch so paired writes (e.g. Show
						// accented OFF dragging Require OFF) coalesce into one rebuild.
						if (!preferenceCoordinator.isRecreatingInputView) {
							pendingCriticalChangeJob?.cancel()
							pendingCriticalChangeJob = serviceScope.launch(Dispatchers.Main.immediate) {
								delay(criticalChangeDebounceMs)
								preferenceCoordinator.handleCriticalSettingChange(key)
							}
						}
					}
				}
				settingsRepo.addChangeListener(prefsListener!!)
			} catch (e: Exception) {
				// A throw here kills all settings reactivity for the session — must be visible.
				ExceptionReporter.reportSilent("JustTypeIME:prefsListenerRegistration", e)
			}

			// Initialize external switch handler (Bluetooth switches, gamepad, analog stick)
			externalSwitchHandler = ExternalSwitchHandler(
				scope = serviceScope,
				callbacks = ExternalSwitchCallbacksImpl(
					deps = object : ExternalSwitchCallbacksImplDeps {
						override val imeState get() = this@JustTypeIME.imeState
						override val isInputViewShown get() = this@JustTypeIME.isInputViewShown
						override val isSingleSwitchEnabled get() = singleSwitchEnabled
						override val isTwoSwitchEnabled get() = twoSwitchEnabled
						override val isJoystickMethodActive
							get() = settingsRepo.effectiveInputMethod() == Constants.INPUT_METHOD_JOYSTICK
						override val isSwitchInputLoggingEnabled
							get() = settingsRepo.getBoolean(Constants.KEY_DEV_SWITCH_INPUT_LOGS, false)
						override fun getJtuiOrNull(): JTUI? = this@JustTypeIME.imeState.readyJtui
						override fun launchOnMain(block: () -> Unit) {
							serviceScope.launch(Dispatchers.Main.immediate) { block() }
						}
						override fun scanSwitchDown(keyCode: Int) = scanSubsystem.handleSwitchDown(keyCode)
						override fun scanSwitchUp() = scanSubsystem.handleSwitchUp()
						override fun twoSwitchDown(role: String) {
							twoSwitchSubsystem.handleSwitchDown(role)
						}
						override fun twoSwitchUp() = twoSwitchSubsystem.handleSwitchUp()
						override fun setTwoSwitchHeld(held: Boolean) {
							twoSwitchSubsystem.switchHeld = held
						}
						override fun joystickInput(x: Float, y: Float) = joystickSubsystem.handleInput(x, y)
						override fun getSwitchCodes() = SwitchCodeConfig(
							scanCode = settingsRepo.getInt(KEY_SCAN_SWITCH_CODE, SWITCH_CODE_UNDEFINED),
							redCode = settingsRepo.getInt(KEY_RED_SWITCH_CODE, SWITCH_CODE_UNDEFINED),
							greenCode = settingsRepo.getInt(KEY_GREEN_SWITCH_CODE, SWITCH_CODE_UNDEFINED),
						)
						override fun debugLog(message: String) = this@JustTypeIME.debugLog(message)
					},
				),
			)

			// Initialize gamepad detector (reacts to whichever stick is pushed further)
			externalSwitchHandler.initGamepadDetector(
				GamepadParams.fromSettings(settingsRepo),
				GamepadParams.deviceFilterFromSettings(settingsRepo),
			)

			// Initialize settings overlay controller
			settingsOverlayController = SettingsOverlayController(
				context = this,
				callbacks = SettingsOverlayCallbacksImpl(
					deps = object : SettingsOverlayCallbacksImplDeps {
						override val isInputViewInflated get() = inputViewInflated
						override fun executeOnUiThread(block: () -> Unit) {
							serviceScope.launch(Dispatchers.Main.immediate) { block() }
						}
						override fun setKeyHistoryVisible(visible: Boolean) = layoutManager.setKeyHistoryVisible(visible)
						override fun loadAndApplyAllPreferences() = preferenceCoordinator.loadAndApplyAll()
						override fun updateDirectionalSelection() = overlayCoordinator.updateDirectionalSelection()
						override fun updateTouchScreenSwitch() = overlayCoordinator.updateTouchScreenSwitch()
						override fun applyKeyHistoryVisibility() = this@JustTypeIME.applyKeyHistoryVisibility()
						override fun setIsRecreatingInputView(value: Boolean) {
							preferenceCoordinator.isRecreatingInputView = value
						}
						override fun recreateInputView() = setInputView(onCreateInputView())
						override fun updateSettingsKeyLabels(labels: List<String>) = this@JustTypeIME.updateSettingsKeyLabels(labels)
						override fun updateSettingsCenterText(text: String) = this@JustTypeIME.updateSettingsCenterText(text)
						override fun debugLog(message: String) = this@JustTypeIME.debugLog(message)
					},
				),
			)
			settingsOverlayController.initViews(root)

			// Initialize head tracking state and start processor
			headTrackingSubsystem.loadSettings(settingsRepo)
			headTrackingSubsystem.startProcessor()

			// Deactivate overlays when the IME window loses focus (e.g. user opens app switcher
			// via gesture/navbar while the keyboard is active). InputMethodService has no
			// onWindowFocusChanged, so we hook ViewTreeObserver on the root view instead.
			root.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
				if (!hasFocus) {
					debugLog("[ViewTreeObserver] IME window lost focus - deactivating overlays")
					overlayCoordinator.onWindowFocusLost()
				}
			}

			inputViewInflated = true

			val hoverRoot = object : FrameLayout(themed) {
				// Forward SOURCE_MOUSE hover to the mouse-joystick subsystem, which reads
				// absolute rawX/rawY. No pointer capture: an IME window is non-focusable,
				// and Android grants capture only to the focused window.
				override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
					val isMouse = (ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
					if (isMouse &&
						::mouseJoystickSubsystem.isInitialized &&
						mouseJoystickSubsystem.isEnabled &&
						mouseJoystickSubsystem.handleMouseHoverEvent(ev)
					) {
						return true
					}
					return super.dispatchGenericMotionEvent(ev)
				}

				// A mouse click while MJ holds the pointer must not also land as a keystroke on the key
				// under the (hidden) cursor — the button-press already released capture. Swallow the tap.
				override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
					val isMouse = (ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
					if (isMouse &&
						::mouseJoystickSubsystem.isInitialized &&
						mouseJoystickSubsystem.isEnabled &&
						mouseJoystickSubsystem.handleMouseTouchEvent()
					) {
						return true
					}
					return super.dispatchTouchEvent(ev)
				}
			}
			hoverRoot.addView(root)
			hoverRootRef = hoverRoot
			// New input view (e.g. rotation): force the next onComputeInsets to re-push
			// the height even if numerically unchanged — the MJ barrier sizes itself from
			// current display metrics on that push, so this is what re-fits it to the new
			// orientation.
			currentKeyboardHeightPx = 0
			inputViewRecoveryAttempted = false
			return hoverRoot
		} catch (t: Throwable) {
			return recoverFromInputViewFailure(t)
		} finally {
			android.os.Trace.endSection()
		}
	}

	/**
	 * Salvage a failed input-view inflate so a render bug can't black-screen the user.
	 * Records the failure for the next startup's crash-loop counter, then once forces a safe
	 * Direct-Selection config and re-tries a clean inflate; meanwhile returns a plain fallback
	 * view that points the user at Emergency Reset.
	 */
	@Suppress("TooGenericExceptionCaught") // recovery must swallow anything; the fallback view is the floor.
	private fun recoverFromInputViewFailure(t: Throwable): View {
		debugLog("[onCreateInputView] inflate failed (${t.javaClass.simpleName}: ${t.message}) — showing fallback view")
		settingsRepo.edit()
			.putBoolean(KEY_LAST_SESSION_CRASHED, true)
			.putLong(KEY_LAST_CRASH_TIME, System.currentTimeMillis())
			.putString(KEY_LAST_CRASH_MESSAGE, "onCreateInputView: ${t.message ?: t.javaClass.simpleName}")
			.putString(KEY_LAST_CRASH_THREAD, Thread.currentThread().name)
			.commit()

		if (!inputViewRecoveryAttempted) {
			inputViewRecoveryAttempted = true
			runCatching {
				settingsRepo.forceDirectSelectionFallback()
				settingsRepo.applySafeKeyboardDefaults()
			}
			mainHandler.postDelayed({
				runCatching { setInputView(onCreateInputView()) }
			}, INPUT_VIEW_RETRY_DELAY_MS)
		}
		return buildFallbackInputView()
	}

	private fun buildFallbackInputView(): View {
		val message = TextView(this).apply {
			text = getString(R.string.ime_fallback_message)
			gravity = android.view.Gravity.CENTER
			setPadding(48, 48, 48, 48)
			textSize = 16f
		}
		return FrameLayout(this).apply {
			layoutParams = android.view.ViewGroup.LayoutParams(
				android.view.ViewGroup.LayoutParams.MATCH_PARENT,
				android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
			)
			addView(message)
		}
	}

	/**
	 * Head tracking stopped delivering frames mid-session — recover to Direct Selection so the
	 * user can keep typing, and announce the switch via the accessible prompt + TTS.
	 */
	private fun handleHeadTrackingUnavailable() {
		if (settingsRepo.getBoolean(KEY_HEADTRACKING_AUTO_FALLBACK_ACTIVE, false)) return // already fallen back
		debugLog("[handleHeadTrackingUnavailable] head tracking unresponsive — enabling temporary touch fallback")
		// Session-scoped: keep head tracking as the primary (so HeadBoard keeps streaming and we can
		// detect recovery) and turn on Direct Selection as a temporary touch fallback. Stash the
		// user's real Direct-Selection setting so recovery restores it exactly.
		val priorDirectSel = settingsRepo.getBoolean(KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED)
		settingsRepo.edit()
			.putBoolean(KEY_HEADTRACKING_FALLBACK_PRIOR_DIRECT_SEL, priorDirectSel)
			.putBoolean(KEY_HEADTRACKING_AUTO_FALLBACK_ACTIVE, true)
			.putBoolean(KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED, true)
			.apply()
		val message = getString(R.string.ht_unavailable_message)
		accessiblePrompt.show(message)
		speakInterruptible(message)
	}

	/**
	 * NGB-D could-have-saved prompt: while the Confidence Signal is off, JTUI
	 * keeps counting the keystrokes it could have saved; at intervals it
	 * invites the user to try the feature (Cliff's design — the invitation is
	 * periodic and rate-limited, never nagging; see plan.md "NGB-D").
	 */
	private fun showCouldHaveSavedPrompt(savedKeystrokes: Long) {
		val message = getString(R.string.ngb_conf_saved_prompt, savedKeystrokes)
		accessiblePrompt.show(message)
		speakInterruptible(message)
	}

	private fun handleHeadTrackingRecovered() {
		if (!settingsRepo.getBoolean(KEY_HEADTRACKING_AUTO_FALLBACK_ACTIVE, false)) return
		debugLog("[handleHeadTrackingRecovered] HeadBoard frames resumed — restoring head tracking")
		val priorDirectSel = settingsRepo.getBoolean(KEY_HEADTRACKING_FALLBACK_PRIOR_DIRECT_SEL, false)
		settingsRepo.edit()
			.putBoolean(KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED, priorDirectSel)
			.putBoolean(KEY_HEADTRACKING_AUTO_FALLBACK_ACTIVE, false)
			.remove(KEY_HEADTRACKING_FALLBACK_PRIOR_DIRECT_SEL)
			.apply()
		val message = getString(R.string.ht_recovered_message)
		accessiblePrompt.show(message)
		speakInterruptible(message)
	}

	override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
		super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
		if (!isInputViewShown) {
			debugLog("[onUpdateSelection 0a] Skip:  isInputViewShown=FALSE     selection $newSelStart..$newSelEnd    ignore-range ${imeTextController.ignoreCursorStart}..${imeTextController.ignoreCursorEnd}; skipping")
			// CRITICAL: Ensure overlay is deactivated when keyboard is not visible
			if (inputViewInflated) overlayCoordinator.deactivateOverlay()
			if (shouldShowKeyboard(currentInputEditorInfo)) {
				try {
					requestShowKeyboard()
				} catch (_: Exception) {}
			}
			return
		}
		imeTextController.handleSelectionUpdate(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
	}

	private fun handleCursorMove(direction: Int, hadAmbig: Boolean, movementMode: Int = JTUI.MOVEMENT_CHARACTER_LINE, isSelecting: Boolean = false) = imeTextController.handleCursorMove(direction, hadAmbig, movementMode, isSelecting)

	private fun clearPageMoveState() = imeTextController.clearPageMoveState()

	private fun handleScroll(direction: Int) = imeTextController.handleScroll(direction)

	private fun handleBookmark(action: Int, isSelecting: Boolean = false) = imeTextController.handleBookmark(action, isSelecting)

	private fun handleEditingDelete() = imeTextController.handleEditingDelete()

	private fun handleCaseChange(caseType: Int, caseMode: Int) = imeTextController.handleCaseChange(caseType, caseMode)

	private fun isPasswordEditor(attribute: android.view.inputmethod.EditorInfo?): Boolean = TextUtils.isPasswordEditor(attribute)

	private fun isNoAutospaceField(attribute: android.view.inputmethod.EditorInfo?): Boolean = TextUtils.isNoAutospaceField(attribute)

	/**
	 * Determines if the keyboard should be shown based on the editor's input type.
	 * Returns false for TYPE_NULL (inputType == 0) to prevent keyboard from showing
	 * on activities without text input fields.
	 */
	private fun shouldShowKeyboard(attribute: android.view.inputmethod.EditorInfo?): Boolean = TextUtils.shouldShowKeyboard(attribute)

	// requestShowSelf is API 28+; on 26-27 show our own window directly.
	private fun requestShowKeyboard() {
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
			requestShowSelf(0)
		} else {
			showWindow(true)
		}
	}

	/** Selection-list text size from the pref (sp); pushes JT view, scan columns, and JTUI metrics. */
	private fun applySelectionTextSize() {
		if (!inputViewInflated) return
		val sp = settingsRepo.getInt(Constants.KEY_SELECTION_TEXT_SIZE_SP).coerceIn(12, 32).toFloat()
		selectionListViewJT.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp)
		val sizePx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
		layoutManager.scanController.selectionTextSizePx = sizePx
		layoutManager.jtController.applyColumnTextSize(sizePx)
		updateSelectionListDimensions()
		updateItemsPerColumn()
	}

	private fun applyKeyboardSize() {
		if (useScanLayout) return
		try {
			val prefs = settingsRepo
			val ratio = prefs.getFloat(KEY_KEYBOARD_SIZE_RATIO).coerceIn(0.50f, 0.95f)
			val applyRatio: (View, View) -> Unit = { gridView, listView ->
				val parent = gridView.parent as? LinearLayout
				if (parent != null) {
					// Floor the list side so the slider max can't crush it to a sliver;
					// past the floor the extra weight is wasted anyway (the grid is
					// square-capped). Pre-layout (extent 0) applies the raw ratio and
					// re-runs once the parent has a size.
					val parentExtent = if (parent.orientation == LinearLayout.HORIZONTAL) parent.width else parent.height
					val minListPx = selectionListViewJT.textSize * MIN_LIST_WIDTH_EM
					val effectiveRatio = if (parentExtent > 0) {
						minOf(ratio, 1f - minListPx / parentExtent).coerceAtLeast(0.50f)
					} else {
						parent.post { if (parent.width > 0 || parent.height > 0) applyKeyboardSize() }
						ratio
					}
					val gridLp = gridView.layoutParams as LinearLayout.LayoutParams
					val listLp = listView.layoutParams as LinearLayout.LayoutParams
					gridLp.width = 0
					listLp.width = 0
					gridLp.weight = effectiveRatio
					listLp.weight = (1f - effectiveRatio).coerceAtLeast(0.05f)
					gridView.layoutParams = gridLp
					listView.layoutParams = listLp
					parent.requestLayout()
				}
			}
			if (::keyGridViewJT.isInitialized && ::selectionListViewJT.isInitialized) {
				// Landscape: the ratio weight belongs to the columns container, not the
				// list inside it (which keeps weight 1 among its sibling columns).
				val listSide: View = layoutManager.jtController.columnsContainer ?: selectionListViewJT
				applyRatio(keyGridViewJT, listSide)
				// The grid is a full-width square, so in landscape/tablets its width-driven height can
				// exceed the screen. Cap the square against the height left for it so the IME fits.
				(keyGridViewJT as? org.continuouspath.justtype.view.SquareGridLayout)?.maxSquarePx = gridMaxSquarePx()
				// Refresh cached ratio on each key so they redraw immediately with new size
				val keyGrid = keyGridViewJT
				if (keyGrid is android.view.ViewGroup) {
					for (i in 0 until keyGrid.childCount) {
						val child = keyGrid.getChildAt(i)
						if (child is org.continuouspath.justtype.view.SquareButton) {
							child.updateKeyboardSizeRatio(ratio)
						}
					}
				}
			}
			debugLog("[kb-size] ratio=" + String.format(java.util.Locale.getDefault(), "%.2f", ratio))
		} catch (e: Exception) {
			ExceptionReporter.reportSilent("JustTypeIME:applyKeyboardSizeRatio", e)
		}
	}

	/**
	 * Upper bound (px) on the square key-grid side. Two constraints combine:
	 * - Height budget: when the key-history bar stacks above the grid (portrait, or
	 *   landscape with the vertical-column pref off) history key height is percent *
	 *   (side / 3), so the joint fit is side * (1 + percent / 3) <= budget. With the
	 *   landscape side column the grid may use the full budget.
	 * - Portrait parity: in landscape the width-driven side would grow into the long
	 *   screen edge, so it is capped at the portrait size — the ratio share of the
	 *   short edge inside the container padding. Keys stay the same size as portrait.
	 */
	private fun gridMaxSquarePx(): Int {
		val m = resources.displayMetrics
		val landscape = m.widthPixels > m.heightPixels
		val sideColumn = landscape &&
			settingsRepo.getBoolean(Constants.KEY_KEY_HISTORY_VERTICAL_LANDSCAPE, true)
		var cap = if (sideColumn) {
			imeHeightBudgetPx()
		} else {
			val percent = settingsRepo.getFloat(KEY_KEY_HISTORY_HEIGHT_PERCENT).coerceIn(0.25f, 1.0f)
			(imeHeightBudgetPx() / (1f + percent / 3f)).toInt()
		}
		if (landscape) {
			val ratio = settingsRepo.getFloat(KEY_KEYBOARD_SIZE_RATIO).coerceIn(0.50f, 0.95f)
			val innerShortEdge = minOf(m.widthPixels, m.heightPixels) - (KEYBOARD_CONTAINER_PADDING_DP * m.density).toInt()
			cap = minOf(cap, (ratio * innerShortEdge).toInt())
		}
		return cap.coerceAtLeast(1)
	}

	/** Height (px) the whole IME may occupy; the rest stays visible app content. */
	private fun imeHeightBudgetPx(): Int {
		val m = resources.displayMetrics
		val headroom = if (m.widthPixels > m.heightPixels) KEYBOARD_MAX_HEIGHT_HEADROOM_LANDSCAPE else KEYBOARD_MAX_HEIGHT_HEADROOM
		return (m.heightPixels * (1f - headroom)).toInt()
	}

	/**
	 * Key history height tracks the key size the grid actually renders — width-driven,
	 * capped by [gridMaxSquarePx] — so history keys and grid keys stay equal in both
	 * orientations.
	 */
	private fun computeKeyHistoryHeightDp(repo: SettingsRepository): Float {
		val m = resources.displayMetrics
		val percent = repo.getFloat(KEY_KEY_HISTORY_HEIGHT_PERCENT).coerceIn(0.25f, 1.0f)
		val ratio = repo.getFloat(KEY_KEYBOARD_SIZE_RATIO).coerceIn(0.50f, 0.95f)
		// Width-driven estimate uses the short edge — key size is portrait-parity in landscape.
		val sidePx = minOf(ratio * minOf(m.widthPixels, m.heightPixels), gridMaxSquarePx().toFloat())
		val oneKeyHeightDp = sidePx / 3f / m.density
		return (percent * oneKeyHeightDp).coerceAtLeast(12f)
	}

	private fun applyKeyHistoryHeight(repo: SettingsRepository) {
		val heightDp = computeKeyHistoryHeightDp(repo)
		keyHistoryView?.setKeyHistoryHeight(heightDp)
		if (this::keyHistoryViewScan.isInitialized) {
			keyHistoryViewScan.setKeyHistoryHeight(heightDp)
		}
	}

	// --- Auto-Shift detection based on editor context if not already manually shifted ---
	private fun updateShiftFromCursor(suppressUpdateUI: Boolean = false) = imeTextController.updateShiftFromCursor(suppressUpdateUI)

	private fun computeAutoShift(): Boolean = imeTextController.computeAutoShift()

	private fun handleDeleteWord() = imeTextController.handleDeleteWord()

	private fun handleDeleteChar() = imeTextController.handleDeleteChar()

	/**
	 * Checks if a word can be pulled in at the current cursor position.
	 * Returns: -1 (error/blocked), 0 (no word found), 1 (word found and producible)
	 * If successful, sets imeTextController.detectedWord, imeTextController.detectedStart, imeTextController.detectedEnd member variables.
	 *
	 * @param checkRightContext If false, only checks for words that don't extend right of cursor
	 */
	private fun tryImmediatePullInAtCurrentCursor(selectFirstOnMatch: Boolean = false): Int = imeTextController.tryImmediatePullInAtCurrentCursor(selectFirstOnMatch)

	private fun cancelPullIn() = imeTextController.cancelPullIn()

	private fun runPullInFlow(word: String, startAbs: Int, endAbs: Int, selectFirstOnMatch: Boolean = false, suppressUIUpdate: Boolean = false): Boolean = imeTextController.runPullInFlow(word, startAbs, endAbs, selectFirstOnMatch, suppressUIUpdate)

	// ── TTS delegation (all speech state lives in TtsController) ────────

	private fun speakQueued(text: String) = ttsController.speakQueued(text)
	private fun speakInterruptible(text: String) = ttsController.speakInterruptible(text)
	private fun speakLastSelection() = ttsController.speakLastSelection()
	private fun scheduleSpeakSelected(text: String?, type: String?) = ttsController.scheduleSpeakSelected(text, type)
	private fun cancelScheduledSpeak(clearPending: Boolean = false) = ttsController.cancelScheduledSpeak(clearPending)
	private fun flushSpellNumericIfNeeded(trigger: String) = ttsController.flushSpellNumericIfNeeded(trigger)
	private fun recordSpellNumeric(text: String) = ttsController.recordSpellNumeric(text)

	private fun speakIfEnabled(pending: TtsController.PendingSelection?): Boolean = ttsController.speakIfEnabled(pending) {
		jtui.setShiftState(true, isManual = false, skipUpdate = false, autoReason = org.continuouspath.justtype.logic.AutoCapReason.SENTENCE_START)
	}

	private fun rememberLastSpoken(text: String, type: String, markPending: Boolean = true) = ttsController.rememberLastSpoken(text, type, markPending)

	/** Main thread only. Shows/hides the small speaking icon over the keyboard. */
	private fun updateSpeechIndicator(speaking: Boolean) {
		if (speaking) {
			speechIndicatorOverlay.show(SpeechIndicatorOverlay.iconResFor(settingsRepo))
		} else {
			speechIndicatorOverlay.hide()
		}
	}

	private fun reuseOrCreatePendingSelection(text: String, defaultType: String): TtsController.PendingSelection = ttsController.reuseOrCreatePendingSelection(text, defaultType)

	private fun commitImmediateText(text: String) = imeTextController.commitImmediateText(text)

	private fun applyEditorUpdate(topCandidate: String?) = imeTextController.applyEditorUpdate(topCandidate)

	private fun getCursorOffset(): Int = imeTextController.getCursorOffset()

	private fun launchNavigationKeyboard() {
		if (!org.continuouspath.justtype.navigation.NavigationModeService.isRunning) {
			Toast.makeText(this, R.string.navigation_mode_service_not_enabled_toast, Toast.LENGTH_LONG).show()
			return
		}
		if (!settingsRepo.getBoolean(Constants.KEY_NAVIGATION_MODE_ENABLED, false)) {
			Toast.makeText(this, R.string.navigation_mode_disabled_toast, Toast.LENGTH_LONG).show()
			return
		}
		settingsRepo.putBoolean(Constants.KEY_NAVIGATION_OVERLAY_REQUESTED, true)
		requestHideSelf(0)
	}

	private fun debugLog(message: String) {
		debugLogWithCategory(null, message)
	}

	private fun debugLog(category: DebugCategory, message: String) {
		debugLogWithCategory(category, message)
	}

	private fun debugLogWithCategory(categoryOverride: DebugCategory?, message: String) {
		if (!BuildConfig.DEBUG_EDITING) return
		if (!enableDebugLog) return
		try {
			val methodName = Thread.currentThread().stackTrace.getOrNull(4)?.methodName ?: "unknown"
			val resolvedCategory = categoryOverride ?: inferCategoryForMessage(message)
			val logged = DebugLogger.log(resolvedCategory) { "[$methodName] $message" }
			if (logged && imeTextController.showEditOps) {
				android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
			}
		} catch (_: Exception) {}
	}

	private fun inferCategoryForMessage(message: String): DebugCategory {
		val normalized = message.lowercase(Locale.getDefault())
		return when {
			"undo" in normalized -> DebugCategory.UndoFlow
			normalized.contains("pull-in") || normalized.contains("pull in") || normalized.contains("pullin") -> DebugCategory.PullInFlow
			normalized.contains("ambig") || normalized.contains("compos") || normalized.contains("preview") -> DebugCategory.AmbigBuffer
			normalized.contains("selection") || normalized.contains("cursor") || normalized.contains("setignore") -> DebugCategory.SelectionSync
			normalized.contains("commit") || normalized.contains("delete") || normalized.contains("inputconnection") -> DebugCategory.InputConnection
			normalized.contains("shift") ||
				normalized.contains("auto-cap") ||
				normalized.contains("auto-shift") ||
				normalized.contains("auto shift") ||
				normalized.contains("caps") -> DebugCategory.ShiftState
			normalized.contains("worddb") || normalized.contains("casecount") -> DebugCategory.WordDb
			else -> DebugCategory.Lifecycle
		}
	}

	private fun isAlphaChar(c: Char): Boolean = c.isLetter()

	/**
	 * Scroll the JT selection list TextView so the selected item (by line index) is fully in view.
	 * No-op if view is null, layout not ready, or selectionIndex is null/negative.
	 * For lines that contain an ImageSpan (e.g. keyboard layout preview), uses the drawable height
	 * so the full image is visible; layout line bounds alone are often too small for image-only lines.
	 */
	// While a settings setup screen is capturing input (joystick device / switch key),
	// don't consume it here — let the setup screen bind it without double-handling.
	private fun setupCaptureActive(): Boolean = ::settingsRepo.isInitialized && InputCaptureGate.isActive(settingsRepo)

	// HeadBoard-driven switches: same gating as the onKeyDown/onKeyUp overrides below.
	// Registered in onCreate, removed in onDestroy.
	private val headBoardSwitchConsumer = HeadBoardSwitchBus.Consumer { event ->
		when {
			setupCaptureActive() || !::externalSwitchHandler.isInitialized -> false
			event.action == KeyEvent.ACTION_DOWN -> externalSwitchHandler.handleKeyDown(event.keyCode, event)
			event.action == KeyEvent.ACTION_UP -> externalSwitchHandler.handleKeyUp(event.keyCode, event)
			else -> false
		}
	}

	override fun onGenericMotionEvent(event: MotionEvent): Boolean = (
		!setupCaptureActive() &&
			::externalSwitchHandler.isInitialized &&
			externalSwitchHandler.handleGenericMotionEvent(event)
		) ||
		super.onGenericMotionEvent(event)

	override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = (
		!setupCaptureActive() &&
			::externalSwitchHandler.isInitialized &&
			externalSwitchHandler.handleKeyDown(keyCode, event)
		) ||
		super.onKeyDown(keyCode, event)

	override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = (
		!setupCaptureActive() &&
			::externalSwitchHandler.isInitialized &&
			externalSwitchHandler.handleKeyUp(keyCode, event)
		) ||
		super.onKeyUp(keyCode, event)

	override fun onDestroy() {
		android.os.Trace.beginSection("ime.onDestroy")
		try {
			if (activeInstance === this) activeInstance = null
			runCatching { unregisterReceiver(screenOffReceiver) }
			HeadBoardSwitchBus.removeConsumer(headBoardSwitchConsumer)
			ImeWindowState.shown = false
			// Dismiss any in-flight AccessiblePrompt overlay so its window
			// doesn't outlive the IME service.
			accessiblePrompt.dismiss()
			// Destroy head tracking subsystem BEFORE cancelling coroutine scope
			// (defensive for Step 6b migration to coroutines)
			if (::headTrackingSubsystem.isInitialized) headTrackingSubsystem.destroy()
			if (::phraseFlowController.isInitialized) phraseFlowController.destroy()
			if (::externalSwitchHandler.isInitialized) externalSwitchHandler.destroy()
			if (inputViewInflated) overlayCoordinator.destroy()
			if (::settingsOverlayController.isInitialized) settingsOverlayController.destroy()
			if (::imeTextController.isInitialized) imeTextController.destroy()
			// Cancel all coroutine children
			serviceJob.cancel()
			try {
				prefsListener?.let {
					org.continuouspath.justtype.settings.SettingsRepository.get().removeChangeListener(it)
				}
			} catch (_: Exception) {}
			if (::joystickSubsystem.isInitialized) joystickSubsystem.destroy()
			if (::mouseJoystickSubsystem.isInitialized) mouseJoystickSubsystem.destroy()
			// Drop the inflated hover-root reference so we don't retain it past service teardown.
			hoverRootRef = null
			prefsListener = null
			keyToneGenerator?.release()
			keyToneGenerator = null
			errorToneGenerator?.release()
			errorToneGenerator = null
			switchToneGenerator?.release()
			switchToneGenerator = null
			if (::ttsController.isInitialized) ttsController.shutdown()
			if (::scanSubsystem.isInitialized) scanSubsystem.destroy()
			if (::twoSwitchSubsystem.isInitialized) twoSwitchSubsystem.destroy()
			if (::broadcastBridge.isInitialized) broadcastBridge.unregisterAll()

			super.onDestroy()
		} finally {
			android.os.Trace.endSection()
		}
	}

	private fun updateSettingsKeyLabels(labels: List<String>) {
		if (labels.size < 8) return
		if (useScanLayout) {
			// Update scan layout buttons
			if (::buttonsScan.isInitialized) {
				for (i in 0 until minOf(8, buttonsScan.size)) {
					val btn = buttonsScan[i]
					if (btn is org.continuouspath.justtype.view.SquareButton) {
						btn.setCenteredLabel(labels[i])
					}
				}
			}
		} else {
			// Update JT grid layout buttons
			if (!::keyGridViewJT.isInitialized) return
			val keyGrid = keyGridViewJT as? android.view.ViewGroup ?: return
			// SquareGridLayout has 9 children: 0-2 top row, 3=left, 4=center, 5=right, 6-8 bottom row
			// Key indices 0-7 correspond to children 0,1,2,3,5,6,7,8 (child 4 is the center label)
			val childIndices = intArrayOf(0, 1, 2, 3, 5, 6, 7, 8)
			for (i in 0..7) {
				val child = keyGrid.getChildAt(childIndices[i])
				if (child is org.continuouspath.justtype.view.SquareButton) {
					child.setCenteredLabel(labels[i])
				}
			}
		}
	}

	private fun updateSettingsCenterText(text: String) {
		if (useScanLayout) {
			if (::centerLabelScan.isInitialized) centerLabelScan.text = text
		} else {
			centerLabelJT.text = text
		}
	}
}
