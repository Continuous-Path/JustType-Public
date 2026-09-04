package org.continuouspath.justtype

/**
 * Which build is this, and what source produced it.
 *
 * Development happens on `dev`; releases are cut on `main` and published from there as a
 * fresh-history snapshot. A tester therefore may be running any of several trees, so a bug
 * report needs more than a version number — it needs the commit. [detail] carries that, and
 * the pair is shown at the foot of the settings list and attached to crash reports.
 *
 * Values come from BuildConfig, injected at build time from `git describe`. On a tagged
 * release commit that reads as a plain tag (`v1.1`); anywhere else it carries the distance
 * and short SHA (`v1.1-14-ga1b2c3d`), with `-dirty` appended for an uncommitted tree.
 */
object BuildIdentity {

	/** Human-facing version, e.g. `1.1 (build 2)`. */
	val version: String = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

	/** True once the build came from an exact release tag — no distance, no dirty marker. */
	val isTaggedRelease: Boolean =
		BuildConfig.SOURCE_REVISION.let { rev ->
			rev != UNKNOWN && !rev.contains("-dirty") && !Regex("-\\d+-g[0-9a-f]+$").containsMatchIn(rev)
		}

	/**
	 * Source line: the release tag alone once tagged, otherwise branch and revision so the
	 * exact commit can be found. Empty-safe when git was unavailable at build time.
	 */
	val detail: String = buildString {
		append(BuildConfig.BUILD_TYPE)
		if (BuildConfig.SOURCE_REVISION != UNKNOWN) {
			append(" · ")
			if (!isTaggedRelease && BuildConfig.SOURCE_BRANCH != UNKNOWN) {
				append(BuildConfig.SOURCE_BRANCH).append('@')
			}
			append(BuildConfig.SOURCE_REVISION)
		}
	}

	/** Two-line block for the settings footer. */
	fun settingsFooter(): String = "$version\n$detail\n${BuildConfig.SOURCE_REPO_URL}"

	/** Single line for crash reports and backup metadata. */
	fun oneLine(): String = "$version $detail"

	private const val UNKNOWN = "unknown"
}
