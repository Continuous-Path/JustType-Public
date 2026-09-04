package org.continuouspath.justtype.langpack

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.LanguageRegistry
import org.continuouspath.justtype.logic.WordDb
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
class LangpackInstallerTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var repo: SettingsRepository
	private lateinit var filesDir: File

	@Before fun setUp() {
		SettingsRepository.resetInstanceForTesting()
		repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		filesDir = tmpDir.newFolder("files")
	}

	@After fun tearDown() {
		SettingsRepository.resetInstanceForTesting()
	}

	/** Builds a tiny real word DB (words + metadata tables), gzips it, and returns (gz, sha256-of-gz). */
	private fun makeArtifact(
		languageId: String,
		diacritics: String = "áé",
		layoutJson: String? = null,
	): Pair<File, String> {
		val dbFile = File(tmpDir.newFolder(), "${languageId}Db.db")
		WordDb.openStandalone(dbFile).use { db -> db.ensureCustomWord("hola") }
		android.database.sqlite.SQLiteDatabase.openDatabase(
			dbFile.path,
			null,
			android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
		).use { raw ->
			raw.execSQL(
				"INSERT OR REPLACE INTO metadata (`key`, value) VALUES ('diacriticSet', ?)",
				arrayOf(diacritics),
			)
			if (layoutJson != null) {
				raw.execSQL(
					"INSERT OR REPLACE INTO metadata (`key`, value) VALUES ('layoutJson', ?)",
					arrayOf(layoutJson),
				)
			}
		}
		val gz = File(tmpDir.newFolder(), "${languageId}Db.db.gz")
		GZIPOutputStream(gz.outputStream()).use { out -> dbFile.inputStream().use { it.copyTo(out) } }
		val sha = MessageDigest.getInstance("SHA-256").digest(gz.readBytes())
			.joinToString("") { "%02x".format(it) }
		return gz to sha
	}

	@Test fun `install verifies, promotes, activates, and registers`() {
		val (gz, sha) = makeArtifact("Espanol")
		val result = LangpackInstaller.install(filesDir, gz, "Espanol", sha, version = 3, repo = repo)

		assertThat(result).isInstanceOf(InstallResult.Installed::class.java)
		assertThat(LangpackStore.installedPackFile(filesDir, "Espanol").exists()).isTrue()
		assertThat(File(filesDir, "EspanolDbActive.db").exists()).isTrue()
		assertThat(LangpackStore.partFile(filesDir, "Espanol").exists()).isFalse()
		assertThat(gz.exists()).isFalse() // downloaded artifact cleaned up

		val entry = LanguageRegistry.load(repo).first { it.name == "Espanol" }
		assertThat(entry.present).isTrue()
		assertThat(entry.dbVersion).isEqualTo(3)
		assertThat(entry.diacriticSet).isEqualTo("áé")
		assertThat(entry.localeCode).isEqualTo("es")
	}

	@Test fun `re-install over an existing active DB preserves learned data`() {
		val (gz1, sha1) = makeArtifact("Espanol")
		LangpackInstaller.install(filesDir, gz1, "Espanol", sha1, version = 3, repo = repo)

		// Simulate learned state: add a custom word to the ACTIVE db (not the pristine pack).
		val active = File(filesDir, "EspanolDbActive.db")
		WordDb.openStandalone(active).use { db -> db.ensureCustomWord("aprendido") }

		// Re-download / update the same language: the pristine pack refreshes, the active db must not.
		val (gz2, sha2) = makeArtifact("Espanol")
		val result = LangpackInstaller.install(filesDir, gz2, "Espanol", sha2, version = 4, repo = repo)

		assertThat(result).isInstanceOf(InstallResult.Installed::class.java)
		WordDb.openStandalone(active).use { db ->
			assertThat(db.getWordIDByWord("aprendido")).isNotNull() // learned word survived the update
		}
		// The registry still records the new version.
		assertThat(LanguageRegistry.load(repo).first { it.name == "Espanol" }.dbVersion).isEqualTo(4)
	}

	@Test fun `update refreshes build metadata in the active DB without touching learned data`() {
		val (gz1, sha1) = makeArtifact("Espanol", layoutJson = """{"v":1}""")
		LangpackInstaller.install(filesDir, gz1, "Espanol", sha1, version = 4, repo = repo)
		val active = File(filesDir, "EspanolDbActive.db")
		WordDb.openStandalone(active).use { db ->
			db.ensureCustomWord("aprendido")
			assertThat(db.getMetadata("layoutJson")).isEqualTo("""{"v":1}""")
		}

		val (gz2, sha2) = makeArtifact("Espanol", diacritics = "áéñ", layoutJson = """{"v":2}""")
		LangpackInstaller.install(filesDir, gz2, "Espanol", sha2, version = 5, repo = repo)

		WordDb.openStandalone(active).use { db ->
			// Build-produced metadata followed the new pack; learned data survived.
			assertThat(db.getMetadata("layoutJson")).isEqualTo("""{"v":2}""")
			assertThat(db.getMetadata("diacriticSet")).isEqualTo("áéñ")
			assertThat(db.getWordIDByWord("aprendido")).isNotNull()
		}
	}

	@Test fun `checksum mismatch cleans up and installs nothing`() {
		val (gz, _) = makeArtifact("Espanol")
		val result = LangpackInstaller.install(filesDir, gz, "Espanol", "deadbeef", version = 3, repo = repo)

		assertThat(result).isInstanceOf(InstallResult.VerifyFailed::class.java)
		assertThat(LangpackStore.installedPackFile(filesDir, "Espanol").exists()).isFalse()
		assertThat(File(filesDir, "EspanolDbActive.db").exists()).isFalse()
		assertThat(LanguageRegistry.load(repo).none { it.name == "Espanol" && it.present }).isTrue()
	}

	@Test fun `truncated artifact fails without leaving partial files`() {
		val (gz, sha) = makeArtifact("Espanol")
		val bytes = gz.readBytes()
		gz.writeBytes(bytes.copyOf(bytes.size / 2))
		val result = LangpackInstaller.install(filesDir, gz, "Espanol", sha, version = 3, repo = repo)

		// Truncated gzip fails during decompression (IoError) or hashing mismatch (VerifyFailed).
		assertThat(result).isNotInstanceOf(InstallResult.Installed::class.java)
		assertThat(LangpackStore.installedPackFile(filesDir, "Espanol").exists()).isFalse()
		assertThat(LangpackStore.partFile(filesDir, "Espanol").exists()).isFalse()
	}

	@Test fun `artifact that is not a database fails the sanity check`() {
		val junk = File(tmpDir.newFolder(), "junk.gz")
		GZIPOutputStream(junk.outputStream()).use { it.write("this is not sqlite".toByteArray()) }
		val sha = MessageDigest.getInstance("SHA-256").digest(junk.readBytes())
			.joinToString("") { "%02x".format(it) }
		val result = LangpackInstaller.install(filesDir, junk, "Espanol", sha, version = 1, repo = repo)

		assertThat(result).isInstanceOf(InstallResult.CorruptDb::class.java)
		assertThat(LangpackStore.installedPackFile(filesDir, "Espanol").exists()).isFalse()
	}

	@Test fun `remove deletes files and marks not present but refuses English`() {
		val (gz, sha) = makeArtifact("Espanol")
		LangpackInstaller.install(filesDir, gz, "Espanol", sha, version = 3, repo = repo)

		assertThat(LangpackInstaller.remove(filesDir, "Espanol", repo)).isTrue()
		assertThat(File(filesDir, "EspanolDbActive.db").exists()).isFalse()
		assertThat(LangpackStore.installedPackFile(filesDir, "Espanol").exists()).isFalse()
		val entry = LanguageRegistry.load(repo).first { it.name == "Espanol" }
		assertThat(entry.present).isFalse()
		assertThat(entry.dbVersion).isEqualTo(0)

		assertThat(LangpackInstaller.remove(filesDir, Constants.TYPING_LANGUAGE_ENGLISH, repo)).isFalse()
	}

	@Test fun `remove re-points typing language and layout-source pin at the removed language`() {
		val (gz, sha) = makeArtifact("Espanol")
		LangpackInstaller.install(filesDir, gz, "Espanol", sha, version = 3, repo = repo)
		repo.putString(Constants.KEY_TYPING_LANGUAGE, "Espanol")
		repo.putString(Constants.KEY_OPTIMIZED_LAYOUT_SOURCE, "Espanol")

		LangpackInstaller.remove(filesDir, "Espanol", repo)

		assertThat(repo.getString(Constants.KEY_TYPING_LANGUAGE, ""))
			.isEqualTo(Constants.TYPING_LANGUAGE_ENGLISH)
		assertThat(repo.getString(Constants.KEY_OPTIMIZED_LAYOUT_SOURCE, ""))
			.isEqualTo(Constants.LAYOUT_SOURCE_MATCH)
	}

	@Test fun `remove leaves unrelated typing language and layout-source pin untouched`() {
		val (gz, sha) = makeArtifact("Espanol")
		LangpackInstaller.install(filesDir, gz, "Espanol", sha, version = 3, repo = repo)
		repo.putString(Constants.KEY_TYPING_LANGUAGE, Constants.TYPING_LANGUAGE_ENGLISH)
		repo.putString(Constants.KEY_OPTIMIZED_LAYOUT_SOURCE, Constants.TYPING_LANGUAGE_ENGLISH)

		LangpackInstaller.remove(filesDir, "Espanol", repo)

		assertThat(repo.getString(Constants.KEY_OPTIMIZED_LAYOUT_SOURCE, ""))
			.isEqualTo(Constants.TYPING_LANGUAGE_ENGLISH)
	}

	@Test fun `WordDb open prefers an installed langpack over assets`() {
		val (gz, sha) = makeArtifact("Espanol")
		LangpackInstaller.install(filesDir, gz, "Espanol", sha, version = 3, repo = repo)
		// Delete the active copy: open() must rebuild it from the langpack (no Espanol asset needed
		// in this synthetic filesDir; the langpack is the only source).
		File(filesDir, "EspanolDbActive.db").delete()

		val app = ApplicationProvider.getApplicationContext<android.app.Application>()
		WordDb.open(filesDir, app.assets, "Espanol").use { db ->
			assertThat(db.getWordIDByWord("hola")).isNotNull()
		}
		assertThat(File(filesDir, "EspanolDbActive.db").exists()).isTrue()
	}

	@Test fun `WordDb open throws typed exception when no source exists`() {
		val app = ApplicationProvider.getApplicationContext<android.app.Application>()
		var thrown: Throwable? = null
		try {
			WordDb.open(filesDir, app.assets, "Klingon")
		} catch (e: Throwable) {
			thrown = e
		}
		assertThat(thrown).isInstanceOf(WordDb.Companion.MissingLanguageSourceException::class.java)
	}

	@Test fun `pending download map round-trips`() {
		LangpackStore.putPending(repo, PendingDownload("Espanol", 42L, 3, "abc"))
		LangpackStore.putPending(repo, PendingDownload("Francais", 43L, 1, "def"))
		val all = LangpackStore.pendingDownloads(repo)
		assertThat(all.keys).containsExactly("Espanol", "Francais")
		assertThat(all["Espanol"]!!.downloadId).isEqualTo(42L)
		LangpackStore.removePending(repo, "Espanol")
		assertThat(LangpackStore.pendingDownloads(repo).keys).containsExactly("Francais")
	}
}
