package org.continuouspath.justtype.view

import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import org.xmlpull.v1.XmlPullParser

/**
 * Keyboard key tile: colored fill with a black border whose corner radius and stroke
 * width scale with the key's size, replacing the rounded_square_transparent PNG
 * layer-lists (fixed-dp strokes looked thin on large keys; the fixed-16dp underlay
 * radius bled white outside the PNG's proportional corners). All key states share
 * this geometry, so a highlighted key no longer renders larger than its neighbors.
 *
 * Referenced from XML as `<drawable class="…KeyTileDrawable" android:color="…"/>`
 * (custom drawable inflation, API 24+); the color attribute sets the fill.
 */
class KeyTileDrawable() : Drawable() {

	constructor(fillColor: Int) : this() {
		fillPaint.color = fillColor
	}

	private val fillPaint =
		Paint(Paint.ANTI_ALIAS_FLAG).apply {
			style = Paint.Style.FILL
			color = Color.WHITE
		}

	private val borderPaint =
		Paint(Paint.ANTI_ALIAS_FLAG).apply {
			style = Paint.Style.STROKE
			color = Color.BLACK
		}

	private val rect = RectF()

	// Every key highlight (press flash, error flash, scan/two-switch tints) copies the
	// key's background via constantState and recolors it with setTint — both are inert
	// on a base Drawable, so this class must supply them or highlights silently vanish.
	private var explicitColorFilter: ColorFilter? = null
	private var tintFilter: PorterDuffColorFilter? = null

	override fun inflate(
		r: Resources,
		parser: XmlPullParser,
		attrs: AttributeSet,
		theme: Resources.Theme?,
	) {
		super.inflate(r, parser, attrs, theme)
		val ta = r.obtainAttributes(attrs, intArrayOf(android.R.attr.color))
		fillPaint.color = ta.getColor(0, Color.WHITE)
		ta.recycle()
	}

	override fun draw(canvas: Canvas) {
		if (bounds.isEmpty) return
		val filter = explicitColorFilter ?: tintFilter
		fillPaint.colorFilter = filter
		borderPaint.colorFilter = filter
		val density = Resources.getSystem().displayMetrics.density
		rect.set(bounds)
		// The tile inset doubles as inter-key spacing (grid cells are edge-to-edge).
		val tileInset = TILE_INSET_DP * density
		rect.inset(tileInset, tileInset)
		if (rect.isEmpty) return

		val side = minOf(rect.width(), rect.height())
		val radius = side * CORNER_RADIUS_FRACTION
		val stroke = (side * BORDER_STROKE_FRACTION).coerceAtLeast(density)
		canvas.drawRoundRect(rect, radius, radius, fillPaint)

		borderPaint.strokeWidth = stroke
		val strokeInset = stroke / 2f
		rect.inset(strokeInset, strokeInset)
		canvas.drawRoundRect(rect, radius - strokeInset, radius - strokeInset, borderPaint)
	}

	override fun setAlpha(alpha: Int) {
		fillPaint.alpha = alpha
		borderPaint.alpha = alpha
		invalidateSelf()
	}

	override fun setColorFilter(colorFilter: ColorFilter?) {
		explicitColorFilter = colorFilter
		invalidateSelf()
	}

	// SRC_IN over the whole tile (fill + border), matching how the retired PNG
	// layer-lists rendered a tint: one flat highlight color.
	override fun setTintList(tint: ColorStateList?) {
		tintFilter = tint?.let { PorterDuffColorFilter(it.defaultColor, PorterDuff.Mode.SRC_IN) }
		invalidateSelf()
	}

	override fun getConstantState(): ConstantState = TileState(fillPaint.color)

	private class TileState(private val fillColor: Int) : ConstantState() {
		override fun newDrawable(): Drawable = KeyTileDrawable(fillColor)

		override fun getChangingConfigurations(): Int = 0
	}

	@Deprecated("Deprecated in Java")
	override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

	companion object {
		// Proportions measured from the retired tile PNG (radius arc 116/818 of the
		// side, border band ~2.9%); KeyHistoryView draws its keys with the same values.
		const val CORNER_RADIUS_FRACTION = 0.142f
		const val BORDER_STROKE_FRACTION = 0.029f
		const val TILE_INSET_DP = 3f
	}
}
