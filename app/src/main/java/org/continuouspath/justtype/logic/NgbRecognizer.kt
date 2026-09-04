package org.continuouspath.justtype.logic

/**
 * Recognition-based learning matcher (engine-spec Phase C).
 *
 * Watches the committed output stream and emits (context, target) transitions
 * on the SAME greedy-segmentation basis as the shipped table — required for
 * sane interpolation between tiers. Manual typing of a known unit therefore
 * promotes it exactly like accepting it as a prediction would.
 *
 * Lazy sealing: a segment's incoming transition is emitted only once the NEXT
 * segment begins (or on [flush]). Typing "việt" then "nam" as separate commits
 * yields exactly one transition, (prev -> "việt nam") — never the interim
 * (prev -> "việt") — matching what batch segmentation of the final text counts.
 */
class NgbRecognizer(
	private val units: Set<List<String>>,
	private val maxUnitSyls: Int = 5,
	private val windowSegments: Int = 4,
) {

	private val segments = ArrayDeque<List<String>>() // current greedy segmentation
	private val tail = mutableListOf<String>() // syllables not yet sealed into segments
	private var emitted = 0 // transitions emitted for sealed segments

	fun clear() {
		segments.clear()
		tail.clear()
		emitted = 0
	}

	/**
	 * Feed newly committed syllables; returns transitions newly SEALED by this
	 * commit, oldest first. Each is (context syllable, target segment).
	 */
	fun commit(syls: List<String>): List<Pair<String, List<String>>> {
		tail.addAll(syls)
		val out = mutableListOf<Pair<String, List<String>>>()
		// Peel complete segments greedily — but NEVER while the whole tail is
		// still a prefix of a longer known unit: "chúc mừng năm" must wait for
		// "mới" rather than sealing "chúc mừng" early (longest match must be
		// decided on final text, exactly like the batch segmentation).
		while (tail.isNotEmpty() && !tailIsOpenPrefix()) {
			val seg = longestUnitAt(tail) ?: break
			sealSegment(seg, out)
			repeat(seg.size) { tail.removeAt(0) }
		}
		return out
	}

	/** True while the ENTIRE tail could still grow into one longer unit. */
	private fun tailIsOpenPrefix(): Boolean = tail.size < maxUnitSyls && units.any { it.size > tail.size && it.subList(0, tail.size) == tail }

	/** Seals everything (end of run: punctuation, reset, field change). */
	fun flush(): List<Pair<String, List<String>>> {
		val out = mutableListOf<Pair<String, List<String>>>()
		while (tail.isNotEmpty()) {
			val seg = longestUnitAt(tail) ?: break
			sealSegment(seg, out)
			repeat(seg.size) { tail.removeAt(0) }
		}
		// The final segment's transition seals now.
		if (segments.size >= 2 && emitted < segments.size - 1) {
			out.add(segments[segments.size - 2].last() to segments.last())
		}
		clear()
		return out
	}

	private fun sealSegment(seg: List<String>, out: MutableList<Pair<String, List<String>>>) {
		segments.addLast(seg)
		// The PREVIOUS segment's transition into this one is now stable.
		if (segments.size >= 2) {
			out.add(segments[segments.size - 2].last() to seg)
			emitted++
		}
		while (segments.size > windowSegments) {
			segments.removeFirst()
			if (emitted > 0) emitted--
		}
	}

	/**
	 * C2 delete-context reconstruction (plan.md round-1 item 9): derive the
	 * NGB (context, gateOpen) from a trailing window of committed syllables,
	 * on the SAME greedy-segmentation basis as learning. The final segment
	 * decides: a multi-syllable unit ends a complete word (gate CLOSED — its
	 * last syllable cannot also start the next word); a bare syllable leaves
	 * the gate OPEN. Pure query — the learning stream is untouched.
	 * Null when [syls] is empty.
	 */
	fun deriveContext(syls: List<String>): Pair<String, Boolean>? {
		if (syls.isEmpty()) return null
		var i = 0
		var lastSeg: List<String> = listOf(syls[0])
		while (i < syls.size) {
			lastSeg = longestUnitAt(syls.subList(i, syls.size)) ?: break
			i += lastSeg.size
		}
		return lastSeg.last() to (lastSeg.size == 1)
	}

	/** Longest unit (or single syllable) at the START of [syls]. */
	private fun longestUnitAt(syls: List<String>): List<String>? {
		if (syls.isEmpty()) return null
		for (width in minOf(maxUnitSyls, syls.size) downTo 2) {
			val cand = syls.subList(0, width)
			if (cand in units) return cand.toList()
		}
		return listOf(syls[0])
	}
}
