package org.continuouspath.justtype.utils

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter

object AtomicFile {

	@Throws(IOException::class)
	fun write(
		target: File,
		append: Boolean = false,
		body: (BufferedWriter) -> Unit,
	) {
		runAtomic(target) { fos ->
			val writer = BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8))
			if (append) copyExistingTo(target, writer)
			body(writer)
			writer.flush()
		}
	}

	@Throws(IOException::class)
	fun writeBytes(
		target: File,
		body: (OutputStream) -> Unit,
	) {
		runAtomic(target) { fos ->
			body(fos)
			fos.flush()
		}
	}

	// body must NOT close fos — runAtomic owns the lifecycle so fsync can run on a live FD.
	@Suppress("TooGenericExceptionCaught") // body is arbitrary caller code; rollback must run for any throw.
	private fun runAtomic(target: File, body: (FileOutputStream) -> Unit) {
		ensureParent(target)
		val tmp = File(target.parentFile, "${target.name}.tmp")
		if (tmp.exists()) tmp.delete()
		val fos = FileOutputStream(tmp)
		try {
			body(fos)
			fos.fd.sync()
		} catch (e: Throwable) {
			tmp.delete()
			throw e
		} finally {
			fos.close()
		}
		commitRename(tmp, target)
	}

	@Throws(IOException::class)
	private fun ensureParent(target: File) {
		val parent = target.parentFile
			?: throw IOException("target has no parent directory: ${target.absolutePath}")
		if (!parent.exists() && !parent.mkdirs()) {
			throw IOException("could not create parent directory: ${parent.absolutePath}")
		}
	}

	private fun copyExistingTo(source: File, writer: BufferedWriter) {
		if (!source.exists()) return
		source.bufferedReader().use { reader -> reader.copyTo(writer) }
	}

	@Throws(IOException::class)
	private fun commitRename(tmp: File, target: File) {
		if (tmp.renameTo(target)) return
		// Some Android filesystems require the destination to be absent first.
		target.delete()
		if (tmp.renameTo(target)) return
		tmp.delete()
		throw IOException("atomic rename failed: ${tmp.absolutePath} -> ${target.absolutePath}")
	}
}
