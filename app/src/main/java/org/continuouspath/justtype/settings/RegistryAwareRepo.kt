package org.continuouspath.justtype.settings

/**
 * Single-source-of-truth reads against [SettingsRegistry]. The default value is
 * looked up from the registry entry, preventing call-site defaults from drifting
 * away from the canonical default.
 *
 * Throws [IllegalStateException] if the registry isn't initialized or if the key
 * isn't registered with the requested type. For internal-flag keys not in the
 * registry (e.g. `KEY_HAS_RUN_BEFORE`), use the two-arg `getXxx(key, default)`
 * member functions on [SettingsRepository] directly.
 */

fun SettingsRepository.getBoolean(key: String): Boolean {
	val def = lookupRegistered(key, SettingsDef.Toggle::class.java)
	return getBoolean(key, def.defaultValue)
}

fun SettingsRepository.getInt(key: String): Int {
	val def = lookupRegistered(key, SettingsDef.IntSlider::class.java)
	return getInt(key, def.defaultValue)
}

fun SettingsRepository.getFloat(key: String): Float {
	val def = lookupRegistered(key, SettingsDef.FloatSlider::class.java)
	return getFloat(key, def.defaultValue)
}

fun SettingsRepository.getString(key: String): String {
	val def = lookupRegistered(key, SettingsDef.Choice::class.java)
	return getString(key, def.defaultValue)
}

private fun <T : SettingsDef> lookupRegistered(key: String, expected: Class<T>): T {
	val def = SettingsRegistry.get().findByKey(key)
		?: error(
			"Key '$key' is not registered in SettingsRegistry. " +
				"Use the two-arg getXxx(key, default) form for internal-flag keys.",
		)
	require(expected.isInstance(def)) {
		"Key '$key' is registered as ${def::class.simpleName}, not ${expected.simpleName}"
	}
	@Suppress("UNCHECKED_CAST")
	return def as T
}
