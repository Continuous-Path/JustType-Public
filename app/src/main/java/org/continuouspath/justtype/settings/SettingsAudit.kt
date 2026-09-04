package org.continuouspath.justtype.settings

/**
 * Diagnostic comparison of stored settings against their registry defaults.
 * Used by StartupManager on the first start after a package update to make
 * unexpected settings drift visible (filter logcat with: adb logcat -s SettingsAudit).
 */
object SettingsAudit {

	data class Drift(val key: String, val stored: Boolean, val default: Boolean)

	data class Change(val key: String, val before: Any?, val after: Any?)

	/**
	 * Settings that differ between the update-time snapshot and the current
	 * store. This is the alarm signal: the user's pre-update changes exist on
	 * both sides of the boundary and can never appear here — only values
	 * mutated during install/initialization (or keys that vanished) do.
	 * Keys absent from the snapshot but present now are NOT reported: those
	 * are new settings receiving their first defaults, which is expected.
	 */
	fun updateBoundaryChanges(
		snapshot: Map<String, Any?>,
		current: Map<String, Any?>,
		ignoredKeys: Set<String> = emptySet(),
	): List<Change> = snapshot.keys
		.filterNot { it in ignoredKeys }
		.mapNotNull { key ->
			val before = snapshot[key]
			val after = current[key]
			when {
				key !in current -> Change(key, before, null)
				before != after -> Change(key, before, after)
				else -> null
			}
		}

	/**
	 * Every registry [SettingsDef.Toggle] whose stored value differs from its
	 * registry default. Missing keys read back as the default and are never
	 * reported; a key stored under a non-boolean type (getBoolean throws
	 * ClassCastException — DataStore keys are name-equal only) is skipped.
	 */
	fun booleanDrift(registry: SettingsRegistry, repo: SettingsRepository): List<Drift> = registry.pages.values
		.flatten()
		.filterIsInstance<SettingsDef.Toggle>()
		.distinctBy { it.key }
		.mapNotNull { def ->
			val stored = runCatching { repo.getBoolean(def.key, def.defaultValue) }.getOrDefault(def.defaultValue)
			if (stored != def.defaultValue) Drift(def.key, stored, def.defaultValue) else null
		}
}
