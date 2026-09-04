package org.continuouspath.justtype.logic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.data.PhraseRepository
import org.continuouspath.justtype.settings.SettingsRegistry
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.testutil.ResetSingletonsRule
import java.io.File

/**
 * Shared JTUI fixture for logic scenario tests: resets the settings
 * singletons, applies [configureRepo] on top of the Optimized-layout default,
 * and constructs a JTUI whose output hooks all record into this harness.
 * Call [tearDown] from @After.
 */
class TestJtui(
	tmpRoot: File,
	autoInit: Boolean = true,
	configureRepo: (SettingsRepository) -> Unit = {},
) {
	val committed = StringBuilder()
	var lastSnapshot: JTUISnapshot? = null
	var autospaceSuppressed = false
	var confidenceSignals = 0
	var couldHaveSavedPrompts = mutableListOf<Long>()
	var spanCollapses = 0
	val repo: SettingsRepository
	val jtui: JTUI

	init {
		val app = ApplicationProvider.getApplicationContext<Context>()
		SettingsRepository.resetInstanceForTesting()
		SettingsRegistry.resetInstanceForTesting()
		SettingsRegistry.getInstance(app)
		repo = SettingsRepository.getInstance(app)
		repo.putString(Constants.KEY_LAYOUT_MODE, Constants.MODE_OPT)
		configureRepo(repo)
		jtui = JTUI(
			onAddNewPhrase = {},
			phraseRepository = PhraseRepository(File(tmpRoot, "phrases.json")),
			sayInterruptible = {},
			sayQueued = {},
			onUiUpdate = { lastSnapshot = it },
			onImmediateOutput = { committed.append(it) },
			onSpellingOutput = {},
			onSpeakSentence = {},
			onSpeakNextSentence = {},
			onFinalizeText = {},
			onNumericOutput = {},
			onAmbiguousSequenceStart = {},
			onSpaceIfNeeded = {},
			onUndoPressed = {},
			onDeleteWord = {},
			onDeleteChar = {},
			assets = app.assets,
			filesDir = tmpRoot,
			prefs = repo,
			context = app,
			onEnterKey = {},
			onSetAutospaceSuppressed = { autospaceSuppressed = it },
			onConfidenceSignal = { confidenceSignals += 1 },
			onCouldHaveSavedPrompt = { couldHaveSavedPrompts.add(it) },
			onNgbSpanCollapse = { spanCollapses += 1 },
		)
		if (autoInit) {
			jtui.init()
			jtui.layoutMode = LayoutMode.Optimized
		}
	}

	fun tearDown() = ResetSingletonsRule.resetAll()
}
