package org.continuouspath.justtype.ime

import android.content.Context
import org.continuouspath.justtype.Constants.KEY_DIRECTIONAL_SELECTION_SWIPE_DISTANCE_DP
import org.continuouspath.justtype.Constants.KEY_DIRECTIONAL_SELECTION_SWIPE_PERCENT
import org.continuouspath.justtype.Constants.KEY_KEYBOARD_SIZE_RATIO
import org.continuouspath.justtype.Constants.KEY_KEY_HISTORY_HEIGHT_DP
import org.continuouspath.justtype.Constants.KEY_KEY_HISTORY_HEIGHT_PERCENT
import org.continuouspath.justtype.Constants.KEY_LAST_CRASH_MESSAGE
import org.continuouspath.justtype.Constants.KEY_LAST_CRASH_THREAD
import org.continuouspath.justtype.Constants.KEY_LAST_CRASH_TIME
import org.continuouspath.justtype.Constants.KEY_LAST_RUN_VERSION
import org.continuouspath.justtype.Constants.KEY_LAST_SESSION_CRASHED
import org.continuouspath.justtype.Constants.KEY_LAST_UPDATE_TIME
import org.continuouspath.justtype.Constants.KEY_NEEDS_FULL_REINIT
import org.continuouspath.justtype.Constants.KEY_TSS_BUTTON_HEIGHT_DP
import org.continuouspath.justtype.Constants.KEY_TSS_BUTTON_HEIGHT_PERCENT
import org.continuouspath.justtype.Constants.KEY_TSS_OVERLAY_BUTTONS
import org.continuouspath.justtype.Constants.KEY_TSS_OVERLAY_MODE
import org.continuouspath.justtype.logging.DebugCategory
import org.continuouspath.justtype.logging.DebugLogger
import org.continuouspath.justtype.settings.CrashLoopRecovery
import org.continuouspath.justtype.settings.SettingsAudit
import org.continuouspath.justtype.settings.SettingsRegistry
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.UpdateSnapshot

/**
 * Handles startup checks for package updates, crash recovery, and version migrations.
 * Called early in onCreate() to ensure clean state after reinstalls, updates, or crashes.
 */
class StartupManager(
	private val context: Context,
) {

	fun runStartupChecks(repo: SettingsRepository) {
		// Check for package update/reinstall (flag set by PackageUpdateReceiver)
		if (repo.getBoolean(KEY_NEEDS_FULL_REINIT, false)) {
			val updateTime = repo.getLong(KEY_LAST_UPDATE_TIME, 0)
			DebugLogger.log(DebugCategory.Lifecycle) {
				"[handleStartupChecks] Package was updated/reinstalled at ${java.util.Date(updateTime)}, performing full reinit"
			}
			repo.edit()
				.remove(KEY_NEEDS_FULL_REINIT)
				.remove(KEY_LAST_UPDATE_TIME)
				.apply()

			performPostUpdateCleanup(repo)
			auditSettingsDrift(repo)
		}

		// Check for crash recovery (flag set by CrashHandler)
		if (repo.getBoolean(KEY_LAST_SESSION_CRASHED, false)) {
			val crashTime = repo.getLong(KEY_LAST_CRASH_TIME, 0)
			val crashMessage = repo.getString(KEY_LAST_CRASH_MESSAGE, "Unknown")
			val crashThread = repo.getString(KEY_LAST_CRASH_THREAD, "Unknown")

			DebugLogger.log(DebugCategory.Lifecycle) {
				"[handleStartupChecks] Recovering from crash at ${java.util.Date(crashTime)}: " +
					"thread='$crashThread', message='$crashMessage'"
			}

			repo.edit()
				.remove(KEY_LAST_SESSION_CRASHED)
				.remove(KEY_LAST_CRASH_TIME)
				.remove(KEY_LAST_CRASH_MESSAGE)
				.remove(KEY_LAST_CRASH_THREAD)
				.apply()

			performCrashRecovery(repo, crashTime)
		}

		// Check for version changes and perform migrations
		val currentVersion = try {
			val info = context.packageManager.getPackageInfo(context.packageName, 0)
			androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
		} catch (_: Exception) {
			0L
		}
		val storedVersion = repo.getLong(KEY_LAST_RUN_VERSION, 0)

		if (storedVersion != currentVersion) {
			DebugLogger.log(DebugCategory.Lifecycle) {
				"[handleStartupChecks] Version changed from $storedVersion to $currentVersion"
			}

			if (storedVersion < currentVersion) {
				performMigrations(storedVersion, currentVersion, repo)
			}

			repo.putLong(KEY_LAST_RUN_VERSION, currentVersion)
		}
	}

	/**
	 * Post-update diagnostics (filter with: adb logcat -s SettingsAudit).
	 *
	 * ALARM — update-boundary check: diff the store against the snapshot
	 * PackageUpdateReceiver wrote the moment the update was installed. The
	 * user's pre-update changes exist on both sides of the boundary and can
	 * never appear here; any hit means install/initialization itself mutated
	 * a setting. Newly added keys (fresh defaults) are expected and excluded.
	 *
	 * INVENTORY — every registry toggle off its registry default, logged for
	 * context only; deliberate user choices land here by design.
	 */
	private fun auditSettingsDrift(repo: SettingsRepository) {
		val snapshot = UpdateSnapshot.read(context)
		if (snapshot == null) {
			android.util.Log.i("SettingsAudit", "update-boundary check: no snapshot (receiver did not run)")
		} else {
			val changes = SettingsAudit.updateBoundaryChanges(
				snapshot.asMap().mapKeys { it.key.name },
				repo.all,
				ignoredKeys = setOf(KEY_NEEDS_FULL_REINIT, KEY_LAST_UPDATE_TIME),
			)
			if (changes.isEmpty()) {
				android.util.Log.i("SettingsAudit", "update-boundary check: no settings changed across the update")
			} else {
				android.util.Log.w(
					"SettingsAudit",
					"update-boundary ALERT: ${changes.size} setting(s) changed across the update with no user action",
				)
				for (c in changes) {
					android.util.Log.w("SettingsAudit", "  ${c.key}: ${c.before} -> ${c.after}")
				}
			}
			DebugLogger.log(DebugCategory.Lifecycle) {
				"[auditSettingsDrift] boundary changes: ${changes.size} " +
					changes.joinToString(", ") { "${it.key}: ${it.before}->${it.after}" }
			}
			UpdateSnapshot.delete(context)
		}

		val drift = runCatching {
			SettingsAudit.booleanDrift(SettingsRegistry.get(), repo)
		}.getOrNull() ?: return
		android.util.Log.i(
			"SettingsAudit",
			"inventory: ${drift.size} toggle(s) off registry default (user choices land here too)",
		)
		for (d in drift) {
			android.util.Log.i("SettingsAudit", "  ${d.key}=${d.stored} (default ${d.default})")
		}
	}

	private fun performPostUpdateCleanup(repo: SettingsRepository) {
		DebugLogger.log(DebugCategory.Lifecycle) {
			"[performPostUpdateCleanup] Refreshing static language metadata after package update"
		}
		refreshBundledLanguageMetadata(repo)
	}

	/**
	 * An app update can ship a new language build for BUNDLED languages, but each language's
	 * active DB was extracted once and holds user data — it is never re-copied. From the freshest
	 * pristine source (downloaded langpack pack if present, else the bundled asset via a temp
	 * copy) this pushes the build-produced metadata into every present language's active DB, and
	 * migrates the words table itself when the build stamp differs (see
	 * [org.continuouspath.justtype.logic.WordDb.migrateLanguageBuild] — a rebuilt corpus reaches
	 * existing installs no other way). Langpack UPDATES do the same in LangpackInstaller; this
	 * covers the app-update boundary.
	 */
	private fun refreshBundledLanguageMetadata(repo: SettingsRepository) {
		val languages = (
			org.continuouspath.justtype.LanguageRegistry.load(repo).filter { it.present }.map { it.name } +
				org.continuouspath.justtype.Constants.TYPING_LANGUAGE_ENGLISH
			).distinct()
		for (id in languages) {
			val active = java.io.File(context.filesDir, org.continuouspath.justtype.logic.WordDb.activeDbName(id))
			if (!active.exists()) continue
			val pack = java.io.File(context.filesDir, "langpacks/${id}Db.db")
			val changed = runCatching {
				if (pack.exists()) {
					migrateAndRefresh(id, active, pack)
				} else {
					val temp = java.io.File.createTempFile("${id}Db", ".db", context.cacheDir)
					try {
						context.assets.open("databases/${id}Db.db").use { input ->
							temp.outputStream().use { input.copyTo(it) }
						}
						migrateAndRefresh(id, active, temp)
					} finally {
						temp.delete()
					}
				}
			}.getOrElse { e ->
				// Missing bundled asset (langpack-only language in a release APK) lands here — fine.
				DebugLogger.log(DebugCategory.Lifecycle) { "[refreshBundledLanguageMetadata] $id: ${e.message}" }
				emptyList()
			}
			if (changed.isNotEmpty()) {
				android.util.Log.i("SettingsAudit", "post-update metadata refresh: $id ${changed.joinToString()}")
			}
		}
	}

	/**
	 * Migrate the words table when the source ships a different language build, then refresh the
	 * build-produced metadata rows. Ordered this way because the migration rewrites the words
	 * table wholesale; the metadata refresh is cheap and idempotent either way.
	 */
	private fun migrateAndRefresh(id: String, active: java.io.File, source: java.io.File): List<String> {
		val migrated = org.continuouspath.justtype.logic.WordDb.migrateLanguageBuild(active, source)
		if (migrated) {
			android.util.Log.i("SettingsAudit", "language build migrated for $id")
		}
		val changed = org.continuouspath.justtype.logic.WordDb.refreshStaticMetadata(active, source)
		return if (migrated) changed + "words" else changed
	}

	/** Crash-loop recovery for the IME startup path; the shared counter + safe mode live in [CrashLoopRecovery]. */
	private fun performCrashRecovery(repo: SettingsRepository, crashTime: Long) {
		CrashLoopRecovery.record(repo, crashTime) { CrashLoopRecovery.imeSafeMode(repo) }
	}

	private fun performMigrations(fromVersion: Long, toVersion: Long, repo: SettingsRepository) {
		DebugLogger.log(DebugCategory.Lifecycle) {
			"[performMigrations] Migrating from version $fromVersion to $toVersion"
		}

		val displayMetrics = context.resources.displayMetrics

		// Migrate key history height from dp to % of key height
		if (repo.contains(KEY_KEY_HISTORY_HEIGHT_DP) && !repo.contains(KEY_KEY_HISTORY_HEIGHT_PERCENT)) {
			val oldDp = repo.getFloat(KEY_KEY_HISTORY_HEIGHT_DP, 96f)
			val ratio = repo.getFloat(KEY_KEYBOARD_SIZE_RATIO, 0.55f).coerceIn(0.50f, 0.95f)
			val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
			val oneKeyHeightDp = (ratio * screenWidthDp) / 3f
			val percent = if (oneKeyHeightDp > 0f) (oldDp / oneKeyHeightDp).coerceIn(0.25f, 1.0f) else 1.0f
			repo.putFloat(KEY_KEY_HISTORY_HEIGHT_PERCENT, percent)
		}

		// Migrate swipe distance from dp to % of screen width
		if (repo.contains(KEY_DIRECTIONAL_SELECTION_SWIPE_DISTANCE_DP) && !repo.contains(KEY_DIRECTIONAL_SELECTION_SWIPE_PERCENT)) {
			val oldDp = repo.getInt(KEY_DIRECTIONAL_SELECTION_SWIPE_DISTANCE_DP, 100)
			val density = displayMetrics.density
			val screenWidthPx = displayMetrics.widthPixels
			val oldPx = oldDp * density
			val percent = ((oldPx / screenWidthPx) * 100).toInt().coerceIn(2, 20)
			repo.putInt(KEY_DIRECTIONAL_SELECTION_SWIPE_PERCENT, percent)
		}

		// Migrate TSS button height from dp to % of display height
		if (repo.contains(KEY_TSS_BUTTON_HEIGHT_DP) && !repo.contains(KEY_TSS_BUTTON_HEIGHT_PERCENT)) {
			val oldDp = repo.getInt(KEY_TSS_BUTTON_HEIGHT_DP, 48).coerceIn(24, 120)
			val density = displayMetrics.density
			val displayHeightPx = displayMetrics.heightPixels
			val oldPx = oldDp * density
			val percent = ((oldPx / displayHeightPx) * 100).toInt().coerceIn(5, 100)
			repo.putInt(KEY_TSS_BUTTON_HEIGHT_PERCENT, percent)
		}

		// Migrate TSS overlay buttons toggle to overlay mode
		if (repo.contains(KEY_TSS_OVERLAY_BUTTONS) && !repo.contains(KEY_TSS_OVERLAY_MODE)) {
			repo.putBoolean(KEY_TSS_OVERLAY_MODE, repo.getBoolean(KEY_TSS_OVERLAY_BUTTONS, false))
		}
	}
}
