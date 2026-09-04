package org.continuouspath.justtype

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [BuildIdentity] is what turns a tester's screenshot into a commit. These pin the shape of
 * the strings and the tagged-release detection, which decides whether a build presents as a
 * clean release or as a development build carrying its branch and SHA.
 */
class BuildIdentityTest {

	private val taggedRelease = Regex("^v?\\d+\\.\\d+(\\.\\d+)?$")
	private val developmentBuild = Regex("-\\d+-g[0-9a-f]+$")

	@Test fun `version names the release and the build number`() {
		assertThat(BuildIdentity.version).contains(BuildConfig.VERSION_NAME)
		assertThat(BuildIdentity.version).contains(BuildConfig.VERSION_CODE.toString())
	}

	@Test fun `detail always identifies the build type`() {
		assertThat(BuildIdentity.detail).contains(BuildConfig.BUILD_TYPE)
	}

	@Test fun `a development build carries the branch and revision`() {
		val rev = BuildConfig.SOURCE_REVISION
		if (rev == "unknown" || !developmentBuild.containsMatchIn(rev)) return // tagged or no git
		assertThat(BuildIdentity.isTaggedRelease).isFalse()
		assertThat(BuildIdentity.detail).contains(rev)
		assertThat(BuildIdentity.detail).contains(BuildConfig.SOURCE_BRANCH)
	}

	@Test fun `a tagged release drops the branch qualifier`() {
		if (!taggedRelease.matches(BuildConfig.SOURCE_REVISION)) return
		assertThat(BuildIdentity.isTaggedRelease).isTrue()
		assertThat(BuildIdentity.detail).doesNotContain("@")
	}

	@Test fun `a dirty tree is never mistaken for a release`() {
		if (!BuildConfig.SOURCE_REVISION.contains("-dirty")) return
		assertThat(BuildIdentity.isTaggedRelease).isFalse()
	}

	@Test fun `the settings footer carries version, source and repo on three lines`() {
		val lines = BuildIdentity.settingsFooter().lines()
		assertThat(lines).hasSize(3)
		assertThat(lines[0]).isEqualTo(BuildIdentity.version)
		assertThat(lines[1]).isEqualTo(BuildIdentity.detail)
		assertThat(lines[2]).isEqualTo(BuildConfig.SOURCE_REPO_URL)
	}

	@Test fun `the one-line form stays single-line for crash reports`() {
		assertThat(BuildIdentity.oneLine()).doesNotContain("\n")
		assertThat(BuildIdentity.oneLine()).contains(BuildIdentity.version)
	}
}
