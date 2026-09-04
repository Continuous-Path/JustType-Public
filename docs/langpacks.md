# Language Packs (downloadable per-language word DBs)

JustType scales to many languages by downloading each language's word database on demand instead of
bundling every DB in the APK. **English is always bundled** (offline-safe default and fallback);
other languages ship as *langpacks* hosted as static files — no server code, just a `manifest.json`
plus one gzipped SQLite artifact per language.

## Server contract

The app fetches `manifest.json` from `BuildConfig.LANGPACK_MANIFEST_URL` (developer override:
Developer Settings → langpack manifest URL field). Schema (`formatVersion` 1; parsers ignore
unknown keys, and an `llm` block per language is reserved for future model delivery):

```json
{
  "formatVersion": 1,
  "minAppVersionCode": 1,
  "generated": "2026-07-14T06:08:08Z",
  "languages": [
    {
      "id": "Espanol", "endonym": "Español", "localeCode": "es",
      "db": {
        "url": ".../langpacks-v1/EspanolDb-v1.db.gz",
        "bytes": 5132747,
        "installedBytes": 12296192,
        "sha256": "hex of the .gz exactly as served",
        "version": 1
      }
    }
  ]
}
```

- `id`/`endonym`/`localeCode` follow `CanonicalLanguages` naming (`{id}Db.db` filenames).
- `bytes` = compressed download size; `installedBytes` = uncompressed DB size. The app preflights
  disk space using both (staging volume + internal ×2 + headroom).
- `sha256` is verified before anything else trusts the download.
- `version` is a monotonically-increasing integer per language; a manifest version greater than the
  installed one shows an UPDATE action (explicit and destructive: learned usage resets — see
  "Update semantics" below).

## On-device flow (see `org.continuouspath.justtype.langpack`)

1. `LanguagesActivity` (Settings → Languages) lists manifest + installed languages.
2. Download rides the system `DownloadManager` (resumes across process death; staged in the
   app-specific external dir).
3. `LangpackInstaller`: SHA-256 while gunzipping → SQLite sanity check → atomic promote to
   `filesDir/langpacks/{Id}Db.db` (the pristine per-language "asset") → copy to `{Id}DbActive.db`
   → `LanguageRegistry.upsert(present = true, dbVersion = …)` → TTS voice-picker hook.
4. `WordDb.open` resolution: active DB → legacy rename (English) → **downloaded langpack** →
   bundled asset → typed `MissingLanguageSourceException` (callers fall back to English).
   `resetToDefaults` re-copies from the langpack when present — no re-download needed.

## Update semantics

Learned usage (useCount/useTime/case counts, vocabulary membership) lives **inside** the active DB,
so updating a langpack replaces it and resets that language's learned usage (custom words and
phrases live elsewhere and survive). The UPDATE action therefore always confirms with the user.
A usage-preserving merge is future work.

## Publishing

```sh
./gradlew :app:packageLanguageArtifacts        # → dist/langpacks/{Id}Db-v{n}.db.gz + manifest.json
gh release upload langpacks-v1 --repo Continuous-Path/JustType-langpacks dist/langpacks/manifest.json dist/langpacks/*.db.gz --clobber
```

- The catalog `app/src/main/db/langpacks.properties` decides what gets published; bump
  `{Id}.version` whenever a corpus changes (artifact filenames embed the version, so old clients'
  cached manifests keep resolving).
- Interim host: the fixed GitHub Release tag `langpacks-v1` (assets get replaced in place; the
  BuildConfig URL stays stable). Moving to the Foundation's server later = upload the same files
  and change `LANGPACK_MANIFEST_URL`.
- `-PlangpackBaseUrl=…` overrides the artifact base URL baked into a generated manifest.

## Bundling controls

- `bundledLanguages` (default `English,Espanol`): which languages are generated into APK assets.
  Must include English. Slim build: `./jt raw -- assembleRelease -PbundledLanguages=English`.
  Non-bundled languages still build (to `app/build/langpacks-db/`) for packaging.
- One-off bundled build from a published artifact (once corpora leave the repo):
  `./gradlew :app:fetchLanguageDb -PfetchLanguage=Espanol -PfetchUrl=… -PfetchSha256=…`
- Adding a language = drop `{Id}WordsRaw.txt` (and optional `{Id}RegionTags.txt`) into
  `app/src/main/db/` — the `build{Id}Db` task is registered automatically — then add catalog
  entries and publish.

## UI strings

UI strings for all languages stay compiled into the APK (~50–100 KB per language; Android cannot
add resource locales at runtime). If APK size ever becomes a concern, regional build flavors via
`resConfigs` are the documented fallback — a build-config-only change.
