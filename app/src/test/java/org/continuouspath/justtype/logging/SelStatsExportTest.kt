package org.continuouspath.justtype.logging

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Date

/** sel_stats TSV export formatting (the beta-tester distribution channel). */
class SelStatsExportTest {

	@Test
	fun `tsv carries header, version, and one row per counter`() {
		val out = SelStatsExport.tsv(
			listOf(
				Triple("TiengViet", "ns_m:F.p1", 12.5),
				Triple("English", "s_n:N.h", 3.25),
			),
			versionName = "1.2.3",
			now = Date(0),
		)
		val lines = out.trimEnd().split("\n")
		assertThat(lines[0]).startsWith("# JustType sel_stats export ")
		assertThat(lines[1]).isEqualTo("# app 1.2.3")
		assertThat(lines[2]).isEqualTo("lang\tbucket\tweight")
		assertThat(lines[3]).isEqualTo("TiengViet\tns_m:F.p1\t12.5000")
		assertThat(lines[4]).isEqualTo("English\ts_n:N.h\t3.2500")
	}

	@Test
	fun `missing version renders as a placeholder`() {
		val out = SelStatsExport.tsv(emptyList(), versionName = null)
		assertThat(out).contains("# app ?")
		assertThat(out.trimEnd().split("\n")).hasSize(3) // headers only
	}
}
