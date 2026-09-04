import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.util.TimeZone
import java.util.zip.GZIPOutputStream

/**
 * Packages built language DBs into publishable langpack artifacts: gzips each `{Id}Db.db`, computes
 * SHA-256, and emits `manifest.json` (the static server contract the app's LangpackManifestSource
 * consumes — see docs/langpacks.md). Output lands in [outputDir]; upload every file in it with
 * `gh release upload langpacks-v1 <files> --clobber` (see docs/langpacks.md for the exact command).
 *
 * The catalog file lists which languages are published and their metadata:
 *   Espanol.version=1
 *   Espanol.endonym=Español
 *   Espanol.locale=es
 * Bump `{Id}.version` whenever a corpus changes; artifact filenames embed the version.
 */
abstract class PackageLanguageArtifactsTask : DefaultTask() {

	/** `{Id}.version/.endonym/.locale` entries; only listed languages are published. */
	@get:InputFile
	lateinit var catalogFile: File

	/** Built pristine DBs, one `{Id}Db.db` per language (from the build{Id}Db tasks). */
	@get:InputFiles
	lateinit var dbFiles: List<File>

	/** Base URL the manifest's artifact links point at (no trailing slash). */
	@get:Input
	lateinit var baseUrl: String

	@get:Input
	var minAppVersionCode: Int = 1

	@get:OutputDirectory
	lateinit var outputDir: File

	@TaskAction
	fun run() {
		val catalog = Properties().apply { catalogFile.inputStream().use { load(it.reader(Charsets.UTF_8)) } }
		val ids = catalog.stringPropertyNames().filter { it.endsWith(".version") }.map { it.removeSuffix(".version") }.sorted()
		require(ids.isNotEmpty()) { "No languages in ${catalogFile.name} (need at least one {Id}.version entry)" }

		outputDir.mkdirs()
		outputDir.listFiles { f -> f.name.endsWith(".db.gz") || f.name == "manifest.json" }?.forEach { it.delete() }

		val entries = StringBuilder()
		var published = 0
		for (id in ids) {
			val db = dbFiles.firstOrNull { it.name == "${id}Db.db" && it.exists() }
			if (db == null) {
				logger.warn("packageLanguageArtifacts: no built DB for '$id' (expected ${id}Db.db); skipping")
				continue
			}
			val version = catalog.getProperty("$id.version").toInt()
			val endonym = catalog.getProperty("$id.endonym", id)
			val locale = catalog.getProperty("$id.locale", "")
			val gz = File(outputDir, "${id}Db-v$version.db.gz")
			GZIPOutputStream(gz.outputStream()).use { out -> db.inputStream().use { it.copyTo(out) } }
			val sha = MessageDigest.getInstance("SHA-256").let { md ->
				gz.inputStream().use { ins ->
					val buf = ByteArray(64 * 1024)
					while (true) {
						val n = ins.read(buf)
						if (n < 0) break
						md.update(buf, 0, n)
					}
				}
				md.digest().joinToString("") { "%02x".format(it) }
			}
			if (entries.isNotEmpty()) entries.append(",\n")
			entries.append(
				"""    {
      "id": "${esc(id)}", "endonym": "${esc(endonym)}", "localeCode": "${esc(locale)}",
      "db": {
        "url": "${esc("$baseUrl/${gz.name}")}",
        "bytes": ${gz.length()},
        "installedBytes": ${db.length()},
        "sha256": "$sha",
        "version": $version
      }
    }""",
			)
			published++
			logger.lifecycle("langpack: $id v$version — ${gz.name} (${gz.length()} B gz, ${db.length()} B installed)")
		}

		val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
		File(outputDir, "manifest.json").writeText(
			"""{
  "formatVersion": 1,
  "minAppVersionCode": $minAppVersionCode,
  "generated": "$stamp",
  "languages": [
$entries
  ]
}
""",
		)
		logger.lifecycle("langpack: wrote manifest.json with $published language(s) to $outputDir")
	}

	private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
