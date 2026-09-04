package org.continuouspath.justtype.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsAuditTest {
	private lateinit var context: Context
	private lateinit var registry: SettingsRegistry
	private lateinit var repo: SettingsRepository

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		registry = SettingsRegistry.getInstance(context)
		repo = SettingsRepository.getInstance(context)
		repo.clearForTesting()
	}

	@After
	fun tearDown() {
		SettingsRepository.resetInstanceForTesting()
		SettingsRegistry.resetInstanceForTesting()
	}

	private fun firstToggleWithDefault(default: Boolean): SettingsDef.Toggle = registry.pages.values
		.flatten()
		.filterIsInstance<SettingsDef.Toggle>()
		.first { it.defaultValue == default }

	@Test
	fun `no drift when all keys are absent`() {
		assertEquals(emptyList<SettingsAudit.Drift>(), SettingsAudit.booleanDrift(registry, repo))
	}

	@Test
	fun `no drift after ensureDefaults`() {
		registry.ensureDefaults(repo)
		assertEquals(emptyList<SettingsAudit.Drift>(), SettingsAudit.booleanDrift(registry, repo))
	}

	@Test
	fun `flipped toggle is reported with stored and default values`() {
		registry.ensureDefaults(repo)
		val toggle = firstToggleWithDefault(default = false)
		repo.putBoolean(toggle.key, true)

		val drift = SettingsAudit.booleanDrift(registry, repo)

		assertEquals(listOf(SettingsAudit.Drift(toggle.key, stored = true, default = false)), drift)
	}

	@Test
	fun `multiple flipped toggles are all reported`() {
		registry.ensureDefaults(repo)
		val offByDefault = firstToggleWithDefault(default = false)
		val onByDefault = firstToggleWithDefault(default = true)
		repo.putBoolean(offByDefault.key, true)
		repo.putBoolean(onByDefault.key, false)

		val drift = SettingsAudit.booleanDrift(registry, repo)

		assertEquals(2, drift.size)
		assertTrue(drift.contains(SettingsAudit.Drift(offByDefault.key, stored = true, default = false)))
		assertTrue(drift.contains(SettingsAudit.Drift(onByDefault.key, stored = false, default = true)))
	}

	// ── updateBoundaryChanges ─────────────────────────────────────────────────

	@Test
	fun `boundary - user pre-update changes never fire`() {
		val snapshot = mapOf("a" to true, "b" to false, "size" to 0.7f)
		val current = mapOf("a" to true, "b" to false, "size" to 0.7f)
		assertEquals(emptyList<SettingsAudit.Change>(), SettingsAudit.updateBoundaryChanges(snapshot, current))
	}

	@Test
	fun `boundary - value mutated across the update is reported with before and after`() {
		val snapshot = mapOf("a" to false, "b" to true)
		val current = mapOf("a" to true, "b" to true)
		assertEquals(
			listOf(SettingsAudit.Change("a", before = false, after = true)),
			SettingsAudit.updateBoundaryChanges(snapshot, current),
		)
	}

	@Test
	fun `boundary - newly added keys are not reported`() {
		val snapshot = mapOf("a" to true)
		val current = mapOf("a" to true, "new_setting" to false)
		assertEquals(emptyList<SettingsAudit.Change>(), SettingsAudit.updateBoundaryChanges(snapshot, current))
	}

	@Test
	fun `boundary - removed keys are reported`() {
		val snapshot = mapOf("a" to true, "gone" to true)
		val current = mapOf("a" to true)
		assertEquals(
			listOf(SettingsAudit.Change("gone", before = true, after = null)),
			SettingsAudit.updateBoundaryChanges(snapshot, current),
		)
	}

	@Test
	fun `boundary - ignored keys are skipped`() {
		val snapshot = mapOf("needs_full_reinit" to true, "a" to 1)
		val current = mapOf("a" to 1)
		assertEquals(
			emptyList<SettingsAudit.Change>(),
			SettingsAudit.updateBoundaryChanges(snapshot, current, ignoredKeys = setOf("needs_full_reinit")),
		)
	}

	@Test
	fun `type-mismatched key is not reported`() {
		registry.ensureDefaults(repo)
		val toggle = firstToggleWithDefault(default = false)
		repo.clearForTesting()
		// Same key stored under a non-boolean type: reads fall back to the
		// default, so the audit must not flag it.
		repo.putInt(toggle.key, 1)
		assertEquals(emptyList<SettingsAudit.Drift>(), SettingsAudit.booleanDrift(registry, repo))
	}
}
