package org.continuouspath.justtype.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AtomicFileTest {

	@get:Rule val tmpDir = TemporaryFolder()

	@Test
	fun `write creates file with body content`() {
		val target = File(tmpDir.root, "out.txt")

		AtomicFile.write(target) { it.write("hello world") }

		assertThat(target.readText()).isEqualTo("hello world")
		assertThat(tmpDir.root.listFiles()!!.map { it.name }).containsExactly("out.txt")
	}

	@Test
	fun `write replaces existing file content when append is false`() {
		val target = tmpDir.newFile("out.txt").also { it.writeText("old") }

		AtomicFile.write(target, append = false) { it.write("new") }

		assertThat(target.readText()).isEqualTo("new")
	}

	@Test
	fun `append preserves existing content and adds new`() {
		val target = tmpDir.newFile("out.txt").also { it.writeText("first\n") }

		AtomicFile.write(target, append = true) { it.write("second\n") }

		assertThat(target.readText()).isEqualTo("first\nsecond\n")
	}

	@Test
	fun `append on missing file just writes body`() {
		val target = File(tmpDir.root, "missing.txt")

		AtomicFile.write(target, append = true) { it.write("only line") }

		assertThat(target.readText()).isEqualTo("only line")
	}

	@Test
	fun `body throwing leaves target untouched and removes tmp`() {
		val target = tmpDir.newFile("out.txt").also { it.writeText("preserved") }

		val thrown = runCatching {
			AtomicFile.write(target) { error("boom") }
		}.exceptionOrNull()

		assertThat(thrown).hasMessageThat().isEqualTo("boom")
		assertThat(target.readText()).isEqualTo("preserved")
		assertThat(File(tmpDir.root, "out.txt.tmp").exists()).isFalse()
	}

	@Test
	fun `body throwing in append mode leaves target untouched`() {
		val target = tmpDir.newFile("out.txt").also { it.writeText("preserved") }

		runCatching {
			AtomicFile.write(target, append = true) {
				it.write("partial")
				error("boom")
			}
		}

		assertThat(target.readText()).isEqualTo("preserved")
		assertThat(File(tmpDir.root, "out.txt.tmp").exists()).isFalse()
	}

	@Test
	fun `creates parent directory if missing`() {
		val target = File(tmpDir.root, "nested/sub/out.txt")

		AtomicFile.write(target) { it.write("ok") }

		assertThat(target.readText()).isEqualTo("ok")
	}

	@Test
	fun `stale tmp file from prior aborted run is cleaned up`() {
		val target = File(tmpDir.root, "out.txt")
		val stale = File(tmpDir.root, "out.txt.tmp").also { it.writeText("stale data") }
		assertThat(stale.exists()).isTrue()

		AtomicFile.write(target) { it.write("fresh") }

		assertThat(target.readText()).isEqualTo("fresh")
		assertThat(stale.exists()).isFalse()
	}

	@Test(expected = IOException::class)
	fun `throws when target parent has no directory`() {
		val noParent = File("/")
		AtomicFile.write(noParent) { it.write("x") }
	}

	@Test
	fun `writeBytes writes raw bytes without UTF-8 wrapping`() {
		val target = File(tmpDir.root, "blob.bin")
		val payload = byteArrayOf(0x00, 0x01, 0x7F.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0xDE.toByte())

		AtomicFile.writeBytes(target) { it.write(payload) }

		assertThat(target.readBytes()).isEqualTo(payload)
	}

	@Test
	fun `writeBytes replaces existing binary content`() {
		val target = tmpDir.newFile("blob.bin").also { it.writeBytes(byteArrayOf(1, 2, 3)) }

		AtomicFile.writeBytes(target) { it.write(byteArrayOf(9, 9, 9)) }

		assertThat(target.readBytes()).isEqualTo(byteArrayOf(9, 9, 9))
	}

	@Test
	fun `writeBytes rolls back on body throw`() {
		val target = tmpDir.newFile("blob.bin").also { it.writeBytes(byteArrayOf(1, 2, 3)) }

		runCatching {
			AtomicFile.writeBytes(target) {
				it.write(byteArrayOf(7, 7))
				error("boom")
			}
		}

		assertThat(target.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
		assertThat(File(tmpDir.root, "blob.bin.tmp").exists()).isFalse()
	}

	@Test
	fun `writeBytes creates parent directory if missing`() {
		val target = File(tmpDir.root, "nested/blob.bin")

		AtomicFile.writeBytes(target) { it.write(byteArrayOf(42)) }

		assertThat(target.readBytes()).isEqualTo(byteArrayOf(42))
	}
}
