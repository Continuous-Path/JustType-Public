package org.continuouspath.justtype.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class KeyTileDrawableTest {

	@Test
	fun `key state backgrounds inflate as KeyTileDrawable`() {
		val ctx = RuntimeEnvironment.getApplication()
		val backgrounds = listOf(
			R.drawable.button_background,
			R.drawable.button_background_highlight,
			R.drawable.button_background_feedback,
		)
		for (res in backgrounds) {
			assertThat(ContextCompat.getDrawable(ctx, res)).isInstanceOf(KeyTileDrawable::class.java)
		}
	}

	@Test
	fun `draws without crashing across key sizes`() {
		val canvas = Canvas(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
		val tile = KeyTileDrawable()
		for (size in listOf(0, 1, 16, 120, 900)) {
			tile.setBounds(0, 0, size, size)
			tile.draw(canvas)
		}
	}

	// Every highlight path (press flash, error flash, scan/two-switch tints) does
	// constantState.newDrawable() + DrawableCompat.setTint on the copy; both were
	// base-Drawable no-ops here, which killed all key highlighting on device.
	@Test
	fun `tinted constant-state copy renders the tint, original stays untinted`() {
		val flashGreen = 0xFF81C784.toInt()
		val original = KeyTileDrawable(Color.WHITE)
		val copy = original.constantState!!.newDrawable().mutate()
		assertThat(copy).isInstanceOf(KeyTileDrawable::class.java)
		assertThat(copy).isNotSameInstanceAs(original)
		DrawableCompat.setTint(DrawableCompat.wrap(copy), flashGreen)
		assertThat(centerPixel(copy)).isEqualTo(flashGreen)
		assertThat(centerPixel(original)).isEqualTo(Color.WHITE)
	}

	private fun centerPixel(drawable: android.graphics.drawable.Drawable): Int {
		val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
		drawable.setBounds(0, 0, 64, 64)
		drawable.draw(Canvas(bitmap))
		return bitmap.getPixel(32, 32)
	}
}
