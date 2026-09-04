package org.continuouspath.justtype.ime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MjCalibrationTest {
	private val density = 2.6f // Pixel 8

	/** Feed [n] push samples of the given per-event pixel delta at a 10ms gap (so speed = delta*100 px/s). */
	private fun MjCalibration.pushAt(deltaPx: Float, n: Int = 20) {
		repeat(n) { addPushSample(deltaPx, 0f, 10L) }
	}

	private fun MjCalibration.restAt(deltaPx: Float, n: Int = 30) {
		repeat(n) { addRestingSample(deltaPx, 0f, 10L) }
	}

	@Test fun `a quiet stick with a strong push derives a small dead zone and usable sensitivity`() {
		val c = MjCalibration()
		c.restAt(0.1f) // tiny idle jitter → ~10 px/sec
		c.pushAt(25f) // strong push → 2500 px/sec
		val r = c.derive(density)
		assertThat(r.usable).isTrue()
		assertThat(r.deadZone).isEqualTo(0.05f) // floor: near-zero noise → clamps to min
		assertThat(r.sensitivityDpPerSec).isEqualTo((2500 / density).toInt())
	}

	@Test fun `a jittery stick derives a wider dead zone than a quiet one`() {
		val quiet = MjCalibration().apply {
			restAt(0.2f)
			pushAt(20f)
		}
		val jittery = MjCalibration().apply {
			restAt(3f)
			pushAt(20f)
		}
		assertThat(jittery.derive(density).deadZone).isGreaterThan(quiet.derive(density).deadZone)
	}

	@Test fun `dead zone is clamped to its ceiling for a very noisy stick`() {
		val c = MjCalibration()
		c.restAt(8f) // very noisy idle → 800 px/sec
		c.pushAt(20f) // 2000 px/sec push
		// noise/peak * margin = 800/2000 * 3 = 1.2 → clamps to the 0.40 ceiling.
		assertThat(c.derive(density).deadZone).isEqualTo(0.40f)
	}

	@Test fun `no push yields an unusable result`() {
		val c = MjCalibration()
		c.restAt(0.2f) // only resting, user never pushed
		assertThat(c.derive(density).usable).isFalse()
	}

	@Test fun `a push barely above idle noise is unusable`() {
		val c = MjCalibration()
		c.restAt(5f) // 500 px/sec idle
		c.pushAt(6f) // 600 px/sec — under 2x the noise, can't distinguish
		assertThat(c.derive(density).usable).isFalse()
	}

	@Test fun `sensitivity is clamped into range`() {
		val tiny = MjCalibration().apply {
			restAt(0.1f)
			pushAt(300f)
		} // 30000 px/sec → over max
		assertThat(tiny.derive(density).sensitivityDpPerSec).isEqualTo(MjCalibration.SENSITIVITY_MAX)
	}

	@Test fun `zero and backwards time gaps are ignored`() {
		val c = MjCalibration()
		c.addPushSample(100f, 0f, 0L) // dt=0 → ignored
		c.addPushSample(100f, 0f, -5L) // negative → ignored
		c.restAt(0.2f)
		assertThat(c.pushPeakPxPerSec()).isEqualTo(0f)
		assertThat(c.derive(density).usable).isFalse() // no valid push samples
	}

	@Test fun `reset clears all samples`() {
		val c = MjCalibration()
		c.restAt(1f)
		c.pushAt(20f)
		c.reset()
		assertThat(c.restingNoisePxPerSec()).isEqualTo(0f)
		assertThat(c.pushPeakPxPerSec()).isEqualTo(0f)
	}

	@Test fun `percentile interpolates and handles edges`() {
		val v = listOf(1f, 2f, 3f, 4f, 5f)
		assertThat(MjCalibration.percentile(v, 0f)).isEqualTo(1f)
		assertThat(MjCalibration.percentile(v, 1f)).isEqualTo(5f)
		assertThat(MjCalibration.percentile(v, 0.5f)).isEqualTo(3f)
		assertThat(MjCalibration.percentile(emptyList(), 0.95f)).isEqualTo(0f)
		assertThat(MjCalibration.percentile(listOf(7f), 0.95f)).isEqualTo(7f) // single element
	}
}
