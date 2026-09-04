package org.continuouspath.justtype.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import org.continuouspath.justtype.Constants.ACTION_IME_CANCEL
import org.continuouspath.justtype.Constants.ACTION_IME_DONE
import org.continuouspath.justtype.Constants.EXTRA_ABBREV
import org.continuouspath.justtype.Constants.EXTRA_INITIAL_ABBREV
import org.continuouspath.justtype.Constants.EXTRA_INITIAL_PHRASE
import org.continuouspath.justtype.Constants.EXTRA_PHRASE
import org.continuouspath.justtype.Constants.EXTRA_RESULT_RECEIVER
import org.continuouspath.justtype.LocaleHelper
import org.continuouspath.justtype.R

class PhraseOverlayActivity : AppCompatActivity() {
	override fun attachBaseContext(newBase: Context) {
		super.attachBaseContext(LocaleHelper.wrap(newBase))
	}

	private var resultReceiver: ResultReceiver? = null
	private var resultSent: Boolean = false

	private lateinit var phraseInput: EditText
	private lateinit var abbrevInput: EditText

	private val commandReceiver =
		object : BroadcastReceiver() {
			override fun onReceive(
				context: Context?,
				intent: Intent?,
			) {
				when (intent?.action) {
					ACTION_IME_DONE -> handleImeDone()
					ACTION_IME_CANCEL -> cancelAndFinish()
				}
			}
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_phrase_overlay)

		window.setSoftInputMode(
			WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
				WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE,
		)

		resultReceiver =
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				intent.getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver::class.java)
			} else {
				intent.getParcelableExtra(EXTRA_RESULT_RECEIVER)
			}
		if (resultReceiver == null) {
			finishAndRemoveTask()
			return
		}

		phraseInput = findViewById(R.id.phraseInput)
		abbrevInput = findViewById(R.id.abbrevInput)

		val initialPhrase = intent.getStringExtra(EXTRA_INITIAL_PHRASE).orEmpty()
		val initialAbbrev = intent.getStringExtra(EXTRA_INITIAL_ABBREV).orEmpty()
		if (initialPhrase.isNotEmpty()) {
			phraseInput.setText(initialPhrase)
			phraseInput.setSelection(initialPhrase.length)
		}
		if (initialAbbrev.isNotEmpty()) {
			abbrevInput.setText(initialAbbrev)
			abbrevInput.setSelection(initialAbbrev.length)
		}

		phraseInput.setOnEditorActionListener { _, actionId, _ ->
			if (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE) {
				focusAbbreviation()
				true
			} else {
				false
			}
		}
		abbrevInput.setOnEditorActionListener { _, actionId, _ ->
			if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
				deliverIfValidOrCancel()
				true
			} else {
				false
			}
		}

		phraseInput.setOnKeyListener { _, keyCode, event ->
			if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
				focusAbbreviation()
				true
			} else {
				false
			}
		}
		abbrevInput.setOnKeyListener { _, keyCode, event ->
			if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
				deliverIfValidOrCancel()
				true
			} else {
				false
			}
		}

		onBackPressedDispatcher.addCallback(
			this,
			object : OnBackPressedCallback(true) {
				override fun handleOnBackPressed() {
					cancelAndFinish()
				}
			},
		)

		phraseInput.post {
			phraseInput.requestFocus()
			showKeyboard(phraseInput)
		}
	}

	override fun onResume() {
		super.onResume()
		val filter =
			IntentFilter().apply {
				addAction(ACTION_IME_DONE)
				addAction(ACTION_IME_CANCEL)
			}
		ContextCompat.registerReceiver(
			this,
			commandReceiver,
			filter,
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)
	}

	override fun onPause() {
		super.onPause()
		runCatching { unregisterReceiver(commandReceiver) }
	}

	override fun onStop() {
		super.onStop()
		if (!isFinishing && !resultSent) {
			cancelAndFinish()
		}
	}

	private fun handleImeDone() {
		if (phraseInput.isFocused) {
			focusAbbreviation()
		} else {
			deliverIfValidOrCancel()
		}
	}

	private fun focusAbbreviation() {
		abbrevInput.requestFocus()
		abbrevInput.setSelection(abbrevInput.text?.length ?: 0)
		showKeyboard(abbrevInput)
	}

	private fun deliverIfValidOrCancel() {
		val rawPhrase = phraseInput.text?.toString() ?: ""
		val phraseForValidation = rawPhrase.trim()
		val abbrev =
			abbrevInput.text
				?.toString()
				?.trim()
				.orEmpty()
		if (phraseForValidation.isEmpty() || abbrev.isEmpty()) {
			cancelAndFinish()
			return
		}
		sendResult(rawPhrase, abbrev)
	}

	private fun sendResult(
		phrase: String,
		abbrev: String,
	) {
		if (resultSent) return
		val bundle =
			bundleOf(
				EXTRA_PHRASE to phrase,
				EXTRA_ABBREV to abbrev,
			)
		resultReceiver?.send(RESULT_OK, bundle)
		resultSent = true
		setResult(RESULT_OK, Intent().putExtras(bundle))
		finishAndRemoveTask()
	}

	private fun cancelAndFinish() {
		if (resultSent) return
		resultReceiver?.send(RESULT_CANCELED, Bundle())
		resultSent = true
		setResult(RESULT_CANCELED)
		finishAndRemoveTask()
	}

	private fun showKeyboard(target: EditText) {
		val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
		imm?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
	}
}
