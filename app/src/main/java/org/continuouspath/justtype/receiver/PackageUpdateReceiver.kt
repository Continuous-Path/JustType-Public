package org.continuouspath.justtype.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.continuouspath.justtype.Constants.KEY_LAST_UPDATE_TIME
import org.continuouspath.justtype.Constants.KEY_NEEDS_FULL_REINIT
import org.continuouspath.justtype.logging.DebugCategory
import org.continuouspath.justtype.logging.DebugLogger

/**
 * Static BroadcastReceiver that handles package update/reinstall events.
 *
 * This receiver is registered in the manifest and survives process death,
 * ensuring we can detect when the app has been updated or reinstalled.
 * It sets a flag that the IME checks on next startup to perform a full
 * reinitialization if needed.
 */
class PackageUpdateReceiver : BroadcastReceiver() {

	override fun onReceive(
		context: Context,
		intent: Intent,
	) {
		if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
			DebugLogger.log(DebugCategory.Lifecycle) {
				"[PackageUpdateReceiver] Package updated/reinstalled, setting reinit flag"
			}

			val repo = org.continuouspath.justtype.settings.SettingsRepository.getInstance(context)
			// Snapshot the store exactly as the update found it — before the flag
			// writes below and before any other new-version code runs. StartupManager
			// diffs against it on first startup to catch settings mutated across the
			// update boundary without user involvement.
			org.continuouspath.justtype.settings.UpdateSnapshot.write(context, repo.snapshotPreferences())
			// Blocking commit: the receiver process may be killed as soon as
			// onReceive returns, which would drop an async write of these flags.
			repo.edit()
				.putBoolean(KEY_NEEDS_FULL_REINIT, true)
				.putLong(KEY_LAST_UPDATE_TIME, System.currentTimeMillis())
				.commit()
		}
	}
}
