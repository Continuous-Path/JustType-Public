package org.continuouspath.justtype.ime

/**
 * Production implementation of [ImeTextCallbacksImplDeps], extracted from
 * the inline anonymous object in JustTypeIME.
 *
 * The inline form mixed real conditional logic ([phraseFlowHandle]'s
 * isInitialized guard) and stateful property setters with simple forwarders.
 * Splitting the body into a named class with explicit accessor lambdas
 * makes the conditional logic and setter state directly testable, while
 * keeping the IME-host wiring as a thin block in JustTypeIME.
 *
 * Each constructor parameter forwards a single IME-host accessor; setters
 * for [isEditMode] / [isNewInputSession] use a getter+setter lambda pair so
 * the underlying IME-host fields remain the single source of truth.
 */
@Suppress("LongParameterList") // 1:1 mirror of the inline Deps body the class replaces; grouping is a follow-up.
class ImeTextCallbacksImplDepsImpl(
	private val isInputViewShownProvider: () -> Boolean,
	private val isAlphaCharProvider: (Char) -> Boolean,
	private val isEditModeAccess: PropertyAccess<Boolean>,
	private val isNewInputSessionAccess: PropertyAccess<Boolean>,
	private val phraseFlowHandleProvider: () -> PhraseFlowHandle?,
	private val sendDpadEventFn: (Int, Int) -> Unit,
	private val speakQueuedFn: (String) -> Unit,
	private val reuseOrCreatePendingSelectionFn: (String, String) -> TtsController.PendingSelection,
	private val speakIfEnabledFn: (TtsController.PendingSelection?) -> Boolean,
	private val cancelScheduledSpeakFn: (Boolean) -> Unit,
	private val rememberLastSpokenFn: (String, String) -> Unit,
	private val getPendingSelectionFn: () -> TtsController.PendingSelection?,
	private val setPendingSelectionFn: (TtsController.PendingSelection?) -> Unit,
	private val recordSpellNumericFn: (String) -> Unit,
	private val flushSpellNumericIfNeededFn: (String) -> Unit,
	private val getAutoRestoreFn: () -> Boolean,
	private val errorNotificationFn: () -> Unit,
	private val errorBeepFn: (Boolean) -> Unit,
	private val debugLogFn: (String) -> Unit,
) : ImeTextCallbacksImplDeps {

	override val isInputViewShown: Boolean get() = isInputViewShownProvider()

	override fun isAlphaChar(c: Char): Boolean = isAlphaCharProvider(c)

	override var isEditMode: Boolean
		get() = isEditModeAccess.get()
		set(value) {
			isEditModeAccess.set(value)
		}

	override var isNewInputSession: Boolean
		get() = isNewInputSessionAccess.get()
		set(value) {
			isNewInputSessionAccess.set(value)
		}

	override val phraseFlowHandle: PhraseFlowHandle? get() = phraseFlowHandleProvider()

	override fun sendDpadEvent(direction: Int, movementMode: Int) = sendDpadEventFn(direction, movementMode)

	override fun speakQueued(text: String) = speakQueuedFn(text)

	override fun reuseOrCreatePendingSelection(text: String, type: String) = reuseOrCreatePendingSelectionFn(text, type)

	override fun speakIfEnabled(pending: TtsController.PendingSelection?): Boolean = speakIfEnabledFn(pending)

	override fun cancelScheduledSpeak(clearPending: Boolean) = cancelScheduledSpeakFn(clearPending)

	override fun rememberLastSpoken(text: String, type: String) = rememberLastSpokenFn(text, type)

	override fun getPendingSelection(): TtsController.PendingSelection? = getPendingSelectionFn()

	override fun setPendingSelection(pending: TtsController.PendingSelection?) = setPendingSelectionFn(pending)

	override fun recordSpellNumeric(text: String) = recordSpellNumericFn(text)

	override fun flushSpellNumericIfNeeded(reason: String) = flushSpellNumericIfNeededFn(reason)

	override fun getAutoRestore(): Boolean = getAutoRestoreFn()

	override fun errorNotification() = errorNotificationFn()

	override fun errorBeep(force: Boolean) = errorBeepFn(force)

	override fun debugLog(message: String) = debugLogFn(message)
}

/**
 * A getter/setter pair around a backing field. Used by
 * [ImeTextCallbacksImplDepsImpl] to wrap mutable IME-host properties so the
 * Deps impl reflects writes back to the IME host (instead of holding a
 * private copy that drifts out of sync).
 */
class PropertyAccess<T>(
	val get: () -> T,
	val set: (T) -> Unit,
)
