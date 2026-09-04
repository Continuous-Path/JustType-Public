# Archive — pre-rebuild English list ("back pocket")

`EnglishWordsRaw.PRE-2026-08.txt.keep` is the English list as it stood before the
2026-08 license-clean rebuild: inherited from the Kivy-era port as `pwl_clean.txt`
(provenance never recorded, believed to originate from a microbiology-heavy corpus)
and hand-revised heavily over time.

**Kept for reference only — do NOT ship it.** Its provenance is unresolved, so it
cannot be covered by any license we grant. Retained so the hand-curated revisions
remain recoverable (e.g. to mine vocabulary the rebuild missed) while the provenance
question is open. The `.keep` extension keeps it out of the `*WordsRaw.txt` build glob.

If provenance is never established, delete this file before the public snapshot.

## Provenance — resolved 2026-08

Confirmed as **American National Corpus Second Release** frequency data
(`ANC-all-count.zip`): 25,555 of 50,937 entries match ANC counts exactly, 15,760 more
within 2%, only 111 absent. Per-word detail and the licensing caveat are in `../plan.md`.

Status unchanged: **reference only, do not ship.** The Second Release is LDC-fee-licensed
(only the 15M-word OANC subset is "downloadable for any use"), so ANC-derived values stay
out of shipped artifacts even though frequency counts alone are weakly protected.
