package org.continuouspath.justtype

import android.app.Activity
import android.os.Bundle
import android.widget.EditText

// Bare editor host so the smoke suite has a real focused InputConnection target.
class SmokeTestActivity : Activity() {

	lateinit var editor: EditText
		private set

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		editor = EditText(this)
		setContentView(editor)
		editor.requestFocus()
	}
}
