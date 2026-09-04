package org.continuouspath.justtype.navigation.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SwitchRolesTest {

	private val undefined = -1

	private fun roles(
		red: Int = undefined,
		green: Int = undefined,
		scan: Int = undefined,
		twoSwitch: Boolean = false,
		scanActive: Boolean = false,
	) = SwitchRoles(red, green, scan, twoSwitch, scanActive, undefined)

	@Test
	fun `configured codes map to their roles`() {
		val r = roles(red = 96, green = 97, twoSwitch = true)
		assertThat(r.roleForKeyCode(96)).isEqualTo(SwitchRoles.ROLE_RED)
		assertThat(r.roleForKeyCode(97)).isEqualTo(SwitchRoles.ROLE_GREEN)
		assertThat(r.roleForKeyCode(98)).isNull()
	}

	@Test
	fun `two-switch recognition needs a bound role`() {
		val r = roles(red = 96, twoSwitch = true, scan = 42, scanActive = true)
		assertThat(r.recognizes(96)).isTrue()
		// Two-switch takes precedence: the scan code is not a role.
		assertThat(r.recognizes(42)).isFalse()
	}

	@Test
	fun `scan recognizes its explicit code`() {
		val r = roles(scan = 42, scanActive = true)
		assertThat(r.recognizes(42)).isTrue()
		assertThat(r.recognizes(8)).isFalse() // candidates only apply while unconfigured
	}

	@Test
	fun `unconfigured scan recognizes the candidate codes`() {
		val r = roles(scanActive = true)
		// KEYCODE_1..3 and NUMPAD_1..3
		for (code in listOf(8, 9, 10, 145, 146, 147)) {
			assertThat(r.recognizes(code)).isTrue()
		}
		assertThat(r.recognizes(7)).isFalse() // KEYCODE_0
		assertThat(r.recognizes(148)).isFalse() // NUMPAD_4
	}

	@Test
	fun `nothing is recognized with no active subsystem`() {
		assertThat(roles(red = 96, scan = 42).recognizes(96)).isFalse()
		assertThat(roles(red = 96, scan = 42).recognizes(42)).isFalse()
	}

	@Test
	fun `hat presses never actuate on the undefined code`() {
		assertThat(roles(twoSwitch = true).actuates(undefined)).isFalse()
		assertThat(roles(scanActive = true).actuates(undefined)).isFalse()
	}

	@Test
	fun `hat presses need an explicitly configured scan code`() {
		// Unlike key events, HAT actuation never falls back to the candidate set.
		val unconfigured = roles(scanActive = true)
		assertThat(unconfigured.actuates(8)).isFalse()
		val configured = roles(scan = 42, scanActive = true)
		assertThat(configured.actuates(42)).isTrue()
		assertThat(configured.actuates(8)).isFalse()
	}

	@Test
	fun `hat actuation on two-switch follows the roles`() {
		val r = roles(red = 96, green = 97, twoSwitch = true)
		assertThat(r.actuates(96)).isTrue()
		assertThat(r.actuates(97)).isTrue()
		assertThat(r.actuates(98)).isFalse()
	}
}
