# Language-build migration — shipping a rebuilt corpus to existing installs

## The problem

`{Lang}DbActive.db` is copied out of the shipped asset (or a downloaded langpack) **once**, then
never re-copied, because it accumulates state the user owns: use counts, last-use times, learned
case counts, the runtime-mutable CaseType byte, phrase links, and whole imported vocabularies.

Consequence, found on-device 2026-08: a device that had JT installed before the English rebuild
was still running the *old* 50,967-word corpus while the APK shipped 60,503 — and the new
"Excluded Words" setting appeared to do nothing, because no word in that stale DB carried the
level bits the setting filters on. Every future corpus update would have failed the same way.

`refreshStaticMetadata` already existed but copies only `layoutJson` / `diacriticSet` — never the
words table.

## Mechanism

**Stamp.** `BuildWordDbTask` writes a `langBuildVersion` metadata row: a short SHA-256 over every
input that shapes the words table (`{Id}WordsRaw.txt`, `{Id}WordsAvoid.txt`, `{Id}RegionTags.txt`,
`{Id}Layout.json`). A content hash rather than a hand-bumped number, so it cannot be forgotten.

**Migration.** `WordDb.migrateLanguageBuild(activeFile, sourceFile)` runs when the source's stamp
differs from the active DB's. It rebuilds the words table from the source, then rewrites the stamp.
An unstamped source (an asset predating this feature) is a no-op — never destructive by default.

**Call sites.** Both paths a new build can arrive by:
- `StartupManager.refreshBundledLanguageMetadata` → `migrateAndRefresh` (app-update boundary,
  bundled languages)
- `LangpackInstaller` update path (downloaded langpack replacing an installed one)

## Preservation rules

| Row | Outcome |
|---|---|
| In both, `useCount = 0` | New build wins entirely — ranking, POS, case seeds, class bits |
| In both, `useCount > 0` | New ranking/POS/class, but the user's use count, last-use time, four case counts, phrase link and CaseType byte survive |
| Old only, carries a non-build class bit | Kept verbatim — an imported vocabulary is not part of the language build |
| Old only, `useCount > 0` | Kept — an update must never take away vocabulary someone relies on |
| Old only, unused | Dropped — the corpus removed it and the user never engaged with it |
| New only | Inserted fresh |

"Build-owned" class bits are `CLASS_JUSTTYPE`, the two offensive levels and the two region-skew
bits; everything else in a mask belongs to the user and is OR'd back on.

`wordID` is reassigned. Verified safe: it is referenced only at runtime (`WordDb`, `WLD`, `JTUI`,
`WordDbAccessor`) and never persisted outside the words table, and the trie is rebuilt from the DB
on load. `freq_class_counts` is recomputed over build-owned rows after the rebuild.

## Tests

`WordDbLanguageBuildMigrationTest` — 8 cases: version gating (runs once, second call no-ops),
unused words take the new build, used words keep learned stats while taking the new ranking,
used-but-dropped words survive, imported vocabulary keeps its class bit, phrase links survive,
unstamped source is a no-op, and the freq-class histogram counts build-owned rows only.

## Open follow-ups

- **On-device verification.** The migration has unit coverage but has not yet been observed
  running on a real upgrade. Test by installing an older build, using a few words, then
  installing a newer one and confirming both the new corpus and the retained stats.
- **Migration cost.** English is ~60k rows; the rebuild is a handful of bulk SQL statements inside
  one transaction, but it runs on the IME startup path. Worth timing on a low-end device — if it
  is slow enough to delay first keystroke, move it off the critical path (background, or a
  "language updating" state).
- **Espanol/TiengViet exclusion lists** are still single-tier (`;profanity` only, no hand grading)
  and Vietnamese remains thin at 18 entries with no LDNOOBW source.
