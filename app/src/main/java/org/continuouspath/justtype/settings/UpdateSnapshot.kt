package org.continuouspath.justtype.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import java.io.File

/**
 * Snapshot of all settings taken by PackageUpdateReceiver the moment a package
 * update lands — before any other new-version code runs. StartupManager diffs
 * it against the store on the first startup after the update: any difference
 * (beyond newly added keys) means something mutated settings during install or
 * initialization, with no user involved.
 */
object UpdateSnapshot {
	private const val FILENAME = "update_prefs_snapshot.json"

	fun file(context: Context): File = File(context.filesDir, FILENAME)

	fun write(context: Context, prefs: Preferences) = PrefsSidecar.writeTo(file(context), prefs)

	fun read(context: Context): Preferences? = PrefsSidecar.readFrom(file(context))

	fun delete(context: Context) {
		file(context).delete()
	}
}
