package org.continuouspath.justtype.logic

/**
 * v2 on-disk encoding of a static `ngb_ctx` row (docs/.plans/sls.md, varint
 * round): version byte 0x01, then per target in eff-descending rank order
 * `uvarint(ngb_words id)` + `uvarint(delta)`, where delta = eff for the first
 * pair and prevEff - eff after. Ids index the NGB-private `ngb_words`
 * dictionary — NOT words.wordID, which migration reassigns.
 *
 * The encoder lives here for tests and fixtures; the production encoder is
 * BuildWordDbTask.encodeCtxBlob (buildSrc cannot share sources with the app —
 * EnglishNgbTest/VietnameseNgbTest running against the real built assets are
 * the cross-check that the two stay in sync).
 */
object NgbCodec {

	const val FORMAT_VERSION = 1

	/** Decodes a v2 blob to (id, eff) pairs in stored rank order; null for an
	 *  unknown format version or truncated payload. */
	fun decode(blob: ByteArray): List<Pair<Int, Long>>? {
		if (blob.isEmpty() || blob[0].toInt() != FORMAT_VERSION) return null
		val out = ArrayList<Pair<Int, Long>>()
		var pos = 1
		var prev = 0L

		fun uvarint(): Long? {
			var value = 0L
			var shift = 0
			while (pos < blob.size) {
				val b = blob[pos].toInt() and 0xff
				pos++
				value = value or ((b and 0x7f).toLong() shl shift)
				if (b < 0x80) return value
				shift += 7
				if (shift > 63) return null
			}
			return null
		}

		while (pos < blob.size) {
			val id = uvarint() ?: return null
			val delta = uvarint() ?: return null
			val eff = if (out.isEmpty()) delta else prev - delta
			if (id <= 0 || eff < 0) return null
			out.add(id.toInt() to eff)
			prev = eff
		}
		return out
	}

	/** Test/fixture encoder — mirror of BuildWordDbTask.encodeCtxBlob. */
	fun encode(pairs: List<Pair<Int, Long>>): ByteArray {
		val out = java.io.ByteArrayOutputStream(pairs.size * 4 + 1)
		out.write(FORMAT_VERSION)
		fun uvarint(value: Long) {
			var v = value
			while (v >= 0x80) {
				out.write(((v and 0x7f) or 0x80).toInt())
				v = v ushr 7
			}
			out.write(v.toInt())
		}
		var prev = 0L
		for ((i, pair) in pairs.withIndex()) {
			uvarint(pair.first.toLong())
			uvarint(if (i == 0) pair.second else prev - pair.second)
			prev = pair.second
		}
		return out.toByteArray()
	}
}
