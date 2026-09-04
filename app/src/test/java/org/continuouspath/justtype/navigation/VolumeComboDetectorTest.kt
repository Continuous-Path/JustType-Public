package org.continuouspath.justtype.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeComboDetectorTest {
	@Test
	fun `not tripped before three presses`() {
		val d = VolumeComboDetector()
		d.record(0)
		assertFalse(d.tripped(1500))
		d.record(100)
		assertFalse(d.tripped(1500))
	}

	@Test
	fun `trips on three presses within window`() {
		val d = VolumeComboDetector()
		d.record(0)
		d.record(500)
		d.record(1000)
		assertTrue(d.tripped(1500))
	}

	@Test
	fun `does not trip when third press exceeds window`() {
		val d = VolumeComboDetector()
		d.record(0)
		d.record(500)
		d.record(2000) // span 2000 > 1500
		assertFalse(d.tripped(1500))
	}

	@Test
	fun `sliding window trips on a late burst`() {
		val d = VolumeComboDetector()
		d.record(0) // stale
		d.record(10_000)
		d.record(10_400)
		d.record(10_800) // last three span 800 <= 1500
		assertTrue(d.tripped(1500))
	}

	@Test
	fun `clear resets state`() {
		val d = VolumeComboDetector()
		d.record(0)
		d.record(500)
		d.record(1000)
		assertTrue(d.tripped(1500))
		d.clear()
		assertFalse(d.tripped(1500))
		d.record(0)
		d.record(100)
		assertFalse(d.tripped(1500))
	}

	@Test
	fun `boundary span equal to window trips`() {
		val d = VolumeComboDetector()
		d.record(0)
		d.record(750)
		d.record(1500) // span exactly 1500 <= 1500
		assertTrue(d.tripped(1500))
	}
}
