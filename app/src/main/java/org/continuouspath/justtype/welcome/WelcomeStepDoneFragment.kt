package org.continuouspath.justtype.welcome

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.continuouspath.justtype.R

class WelcomeStepDoneFragment : Fragment() {
	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = inflater.inflate(R.layout.fragment_welcome_step_done, container, false)

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		val card = view.findViewById<View>(R.id.chooseMethodCard)
		card.findViewById<TextView>(R.id.rowLabel).setText(R.string.welcome_done_choose_method)
		card.setOnClickListener { InputMethodChooser.show(requireContext()) }
	}
}
