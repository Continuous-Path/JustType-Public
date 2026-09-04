import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Fetches a published langpack artifact and drops the verified, decompressed `{Id}Db.db` into a local
 * path (typically `src/main/assets/databases/`). This is the "download during the build" escape hatch
 * for producing one-off bundled builds once raw corpora move out of the repo; while corpora live in
 * `src/main/db/`, generating locally via build{Id}Db is preferred (no network in CI).
 *
 * Invoke via the `fetchLanguageDb` registration in app/build.gradle:
 *   ./gradlew :app:fetchLanguageDb -PfetchUrl=<artifact .db.gz url> -PfetchSha256=<hex> -PfetchLanguage=<Id>
 * (url + sha256 come from the published manifest.json.)
 */
abstract class FetchLanguageArtifactTask : DefaultTask() {

	@get:Input
	lateinit var url: String

	/** SHA-256 (hex) of the .gz exactly as served — from manifest.json. */
	@get:Input
	lateinit var sha256: String

	@get:OutputFile
	lateinit var outputFile: File

	@TaskAction
	fun run() {
		val conn = URL(url).openConnection() as HttpURLConnection
		conn.connectTimeout = 30_000
		conn.readTimeout = 120_000
		conn.instanceFollowRedirects = true
		val digest = MessageDigest.getInstance("SHA-256")
		val tmp = File(outputFile.parentFile, outputFile.name + ".part")
		try {
			check(conn.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${conn.responseCode} from $url" }
			outputFile.parentFile.mkdirs()
			conn.inputStream.use { raw ->
				GZIPInputStream(DigestInputStream(raw, digest)).use { gz ->
					tmp.outputStream().use { out -> gz.copyTo(out) }
				}
			}
		} finally {
			conn.disconnect()
		}
		val actual = digest.digest().joinToString("") { "%02x".format(it) }
		check(actual.equals(sha256, ignoreCase = true)) {
			tmp.delete()
			"sha256 mismatch for $url: expected $sha256, got $actual"
		}
		outputFile.delete()
		check(tmp.renameTo(outputFile)) { "Could not move $tmp to $outputFile" }
		logger.lifecycle("fetchLanguageDb: verified ${outputFile.name} (${outputFile.length()} B) from $url")
	}
}
