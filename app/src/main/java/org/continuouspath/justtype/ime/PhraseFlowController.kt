package org.continuouspath.justtype.ime

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.activity.PhraseOverlayActivity
import org.continuouspath.justtype.data.PhraseEntry
import org.continuouspath.justtype.data.PhraseRepository
import org.continuouspath.justtype.logic.AutoCapReason
import org.continuouspath.justtype.logic.JTUISnapshot
import org.continuouspath.justtype.logic.LayoutMode

/**
 * Manages "Add New Phrase" flow lifecycle and phrase auto-commit scheduling.
 *
 * Extracted from JustTypeIME (Phase 2 Step 8). This is a permanent instance
 * (like other subsystems) that manages individual flow lifecycles internally.
 * The nullable [flowHandle] exposes a [PhraseFlowHandle] for ImeTextController
 * guard clauses when a flow is active.
 */
class PhraseFlowController(
	private val scope: CoroutineScope,
	private val context: Context,
	private val phraseRepository: PhraseRepository,
	private val callbacks: PhraseFlowCallbacks,
) {
	// ── Active flow state ──────────────────────────────────────────────

	private var activeFlow: ActiveFlow? = null

	val isActive: Boolean get() = activeFlow != null

	/** Nullable handle for ImeTextController boundary (null = no active flow). */
	val flowHandle: PhraseFlowHandle? get() = activeFlow

	// ── Auto-commit state ──────────────────────────────────────────────

	private var autoCommitJob: Job? = null
	var autoCommitDelayMs: Long = 0L
	private var pendingCandidate: String? = null

	// ── Flow lifecycle ─────────────────────────────────────────────────

	fun startFlow(initialPhrase: String = "") {
		callbacks.executeOnUiThread {
			cancelAutoCommit()
			activeFlow?.cancelFlow()
			callbacks.resetJTUI(
				capitalize = true,
				callUpdateUi = true,
				isManualShift = true,
				resetToStartPage = true,
				autoCapReason = AutoCapReason.MANUAL,
			)
			activeFlow = ActiveFlow(initialPhrase).also { it.start() }
		}
	}

	fun cancelCurrentFlow() {
		activeFlow?.cancelFlow()
	}

	/** Absorbs onCancelNewPhrase logic: cancel if active, else reset JTUI phrase mode. */
	fun cancelOrReset() {
		activeFlow?.cancelFlow() ?: run {
			callbacks.setPhraseFlowMode(false)
			callbacks.setCurrentPageToStartingPage()
		}
	}

	fun onDonePressed() {
		activeFlow?.onDonePressed()
	}

	fun onUiSnapshot(ui: JTUISnapshot) {
		activeFlow?.onUiSnapshot(ui)
	}

	fun completeFromCustomWord(word: String): Boolean = activeFlow?.completeFromCustomWord(word) ?: false

	// ── Auto-commit ────────────────────────────────────────────────────

	fun scheduleAutoCommit(candidate: String) {
		cancelAutoCommit()
		if (autoCommitDelayMs <= 0) return
		pendingCandidate = candidate
		autoCommitJob = scope.launch {
			delay(autoCommitDelayMs)
			if (pendingCandidate == candidate) {
				callbacks.autoCommitComposingText(candidate)
			}
			pendingCandidate = null
		}
	}

	fun cancelAutoCommit() {
		autoCommitJob?.cancel()
		autoCommitJob = null
		pendingCandidate = null
	}

	/**
	 * Consolidates the onUiUpdate auto-commit block from JustTypeIME.
	 * Called from the JTUI onUiUpdate callback after speech scheduling.
	 */
	fun handleUiAutoCommit(
		suspendCommit: Boolean,
		selectedType: String,
		selectedCandidate: String,
	) {
		if (isActive || suspendCommit) {
			cancelAutoCommit()
		} else if (autoCommitDelayMs > 0 && selectedType == "PH" && selectedCandidate.isNotEmpty()) {
			scheduleAutoCommit(selectedCandidate)
		} else {
			cancelAutoCommit()
		}
	}

	// ── Cleanup ────────────────────────────────────────────────────────

	fun destroy() {
		cancelAutoCommit()
		activeFlow = null
	}

	// ── Active flow (per-flow state) ───────────────────────────────────

	private inner class ActiveFlow(
		private val initialPhrase: String = "",
	) : PhraseFlowHandle {
		private val handler = Handler(Looper.getMainLooper())
		private var finished: Boolean = false

		private val resultReceiver = object : ResultReceiver(handler) {
			override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
				when (resultCode) {
					RESULT_OK -> {
						val phrase = resultData?.getString(Constants.EXTRA_PHRASE).orEmpty()
						val abbrev = resultData?.getString(Constants.EXTRA_ABBREV).orEmpty()
						if (phrase.isNotEmpty() && abbrev.isNotEmpty()) {
							val entry = phraseRepository.add(abbrev, phrase)
							callbacks.addPhraseEntry(entry)
							callbacks.scheduleBackup()
							callbacks.debugLog("[phraseFlow] Saved phrase abbrev='$abbrev' len=${phrase.length}")
						} else {
							callbacks.debugLog("[phraseFlow] Received empty phrase/abbrev, treating as cancel")
						}
						finishFlow()
					}
					else -> {
						callbacks.debugLog("[phraseFlow] Cancel result received")
						finishFlow()
					}
				}
			}
		}

		fun start() {
			callbacks.debugLog("[phraseFlow] start overlay")
			cancelAutoCommit()
			callbacks.setPhraseFlowMode(true)
			launchOverlay()
		}

		override fun consumeImmediate(@Suppress("UNUSED_PARAMETER") text: String): Boolean = false
		override fun consumeNumeric(@Suppress("UNUSED_PARAMETER") text: String): Boolean = false
		override fun consumeSpelling(@Suppress("UNUSED_PARAMETER") text: String): Boolean = false
		fun onUiSnapshot(@Suppress("UNUSED_PARAMETER") ui: JTUISnapshot) { /* no-op */ }

		override fun handleBackspace(): Boolean = false

		fun onDonePressed() {
			callbacks.executeOnUiThread {
				val useAlpha = callbacks.getLayoutMode() == LayoutMode.Alphabetical
				callbacks.startAbbreviationEntry(useAlpha, inPhraseFlow = true)
				callbacks.setCapsLock(true)
				callbacks.setPhraseAbbrevModeActive(true)
			}
			sendOverlayCommand(Constants.ACTION_IME_DONE)
		}

		fun cancelFlow() {
			callbacks.debugLog("[phraseFlow] cancel requested")
			sendOverlayCommand(Constants.ACTION_IME_CANCEL)
			finishFlow()
		}

		fun completeFromCustomWord(@Suppress("UNUSED_PARAMETER") word: String): Boolean = false

		private fun launchOverlay() {
			val intent = Intent(context, PhraseOverlayActivity::class.java).apply {
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				putExtra(Constants.EXTRA_RESULT_RECEIVER, resultReceiver)
				if (initialPhrase.isNotEmpty()) {
					putExtra(Constants.EXTRA_INITIAL_PHRASE, initialPhrase)
				}
			}
			runCatching {
				ContextCompat.startActivity(context, intent, null)
			}.onFailure {
				callbacks.debugLog("[phraseFlow] Failed to launch overlay: ${it.message}")
				finishFlow()
			}
		}

		private fun sendOverlayCommand(action: String) {
			val intent = Intent(action).setPackage(context.packageName)
			runCatching { context.sendBroadcast(intent) }
		}

		private fun finishFlow() {
			if (finished) return
			finished = true
			cancelAutoCommit()
			callbacks.executeOnUiThread {
				callbacks.setPhraseFlowMode(false)
				val useAlpha = callbacks.getLayoutMode() == LayoutMode.Alphabetical
				callbacks.startAbbreviationEntry(useAlpha, inPhraseFlow = false)
				callbacks.resetJTUI(
					capitalize = callbacks.getShiftState(),
					callUpdateUi = false,
					isManualShift = false,
					resetToStartPage = false,
					autoCapReason = callbacks.getAutoCapReason(),
				)
				callbacks.setCurrentPageToStartingPage()
			}
			activeFlow = null
		}
	}
}

/**
 * Reverse-communication interface from [PhraseFlowController] back to JustTypeIME.
 */
interface PhraseFlowCallbacks {
	// ── JTUI control ───────────────────────────────────────────────────
	fun setPhraseFlowMode(active: Boolean)
	fun startAbbreviationEntry(useAlpha: Boolean, inPhraseFlow: Boolean)
	fun setCapsLock(active: Boolean)
	fun setPhraseAbbrevModeActive(active: Boolean)
	fun resetJTUI(
		capitalize: Boolean,
		callUpdateUi: Boolean,
		isManualShift: Boolean = false,
		resetToStartPage: Boolean = false,
		autoCapReason: AutoCapReason,
	)
	fun setCurrentPageToStartingPage()
	fun getShiftState(): Boolean
	fun getAutoCapReason(): AutoCapReason
	fun getLayoutMode(): LayoutMode
	fun addPhraseEntry(entry: PhraseEntry)

	// ── Text editing (auto-commit via ImeTextController) ───────────────
	fun autoCommitComposingText(text: String)

	// ── IME services ───────────────────────────────────────────────────
	fun scheduleBackup()
	fun debugLog(message: String)
	fun executeOnUiThread(block: () -> Unit)
}
