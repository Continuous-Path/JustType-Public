# Resource-Evaluation Policy (licensing dual-track)

Established by Cliff, 2026-08-09. Applies to EVERY external resource we
evaluate from now on: corpora, dictionaries, n-gram data, models, fonts,
libraries.

## Context

JustType is Apache 2.0 (durable goal: commercial enterprises must be able
to integrate it in their devices). BUT the Continuous Path Foundation will
also distribute JustType free, direct to users — and a separate free
build MAY use resources restricted to non-commercial use. So we no longer
simply skip non-commercial resources; we track them on a second list.

## The approach

For every resource evaluated, record it in one of two ledgers (below):

(a) **Commercial-compatible** — usable in the Apache 2.0 distribution.
    Apache 2.0, MIT, BSD, CC BY, CC BY-SA (attribution/share-alike
    obligations noted per resource — SA on DATA is generally fine for a
    built artifact, but record the reasoning), public domain. "Might be
    over-simplifying" is right: some licenses are commercial-OK with
    conditions (e.g. Gemma's custom terms, CC BY-SA's attribution chain)
    — record the CONDITIONS, not just the verdict.

(b) **Non-commercial only** — usable only in the Foundation's free build.
    CC BY-NC and friends. For each: record (1) its assessed VALUE to JT,
    and (2) the DIFFICULTY of reconstructing an analogous resource from
    commercially-clean inputs (the reconstruction path is often the real
    deliverable — e.g. re-counting an n-gram table from a clean corpus).

A resource in ledger (b) with high value + hard reconstruction is a
candidate for the free build. High value + easy reconstruction means:
just do the reconstruction for the main line.

## Ledger (a): commercial-compatible

| resource | license | conditions/notes |
|---|---|---|
| Kaikki/Wiktextract (VN unit inventory) | CC BY-SA | attribution + SA noted; used as data input |
| hermitdave/FrequencyWords (OpenSubtitles counts) | CC BY-SA 4.0 | primary VN syllable counts |
| Wikipedia dumps | CC BY-SA | tail counts + case evidence |
| HPLT corpus (VN NGB tables, held-out) | CC0 | verified for NGB thread |
| OpenBoard-derived code | Apache 2.0 | split-out underway (see licensing audit memory) |
| Vertanen/Kristensson AAC-like corpus (aactext.org/imagine) | CC BY 4.0 (site statement; two embedded test sets carry separate contributor permissions — use sent_*_aac.txt only) | ~6k crowdsourced AAC-register communications; downloaded 2026-08-10 to analyzer corpora/conversational/aac_comm; attribution: EMNLP 2011 paper |
| OpenSubtitles (raw, via OPUS) | free corpus distribution, cite OPUS | session/dialog-shaped text for usage-dynamics replay; counts already in-house via hermitdave CC BY-SA |

## Ledger (b): non-commercial only

| resource | license | value | reconstruction difficulty |
|---|---|---|---|
| Leipzig Corpora | CC BY-NC 4.0 (verified) | register cross-check + mid-sentence case evidence. PURGED from main-line data 2026-08-03 (replaced w/ Wikipedia) — now a ledger-(b) candidate for the free build's cross-checks | MEDIUM (already done once: Wikipedia replacement shipped; known regression: dios case seed) |
| ANC Second Release counts | LDC fee license | QA yardstick for EN list (archived .keep, never shipped) | LOW — OANC 15M subset is the open substitute |
| hieuthi VN gist | unlicensed | superseded by rebuilt VN data; unusable pending author clarification | — (already reconstructed) |
| DailyDialog (~13k multi-turn dialogs) | CC BY-NC-SA 4.0 | session-structured conversation — good for OBSERVATION/diagnostic replay; must NOT fit shipped defaults on it (fitted weights inherit the data's terms) | LOW — OpenSubtitles sessions + AAC corpus cover the register cleanly |
| TalkBank / CABank conversation transcripts | CC BY-NC-SA 3.0 | real adult conversation | same as above |
| Santa Barbara Corpus of Spoken American English | CC BY-ND 3.0 | real spoken transcripts | ND: internal evaluation only, nothing shipped derives from it |

## Standing questions to resolve per new resource

1. Exact license text located and archived? (not just the README claim)
2. Does it taint derived artifacts (DB tables, fitted weights)? Weights
   fitted ON data generally inherit the data's terms for distribution —
   assess per case, record the reasoning.
3. If (b): what is the clean-room reconstruction path and its cost?

English round note (queued): English word-frequency and n-gram sources
MUST be run through this policy before the EN NGB build — record both
ledgers' candidates in this file as they are evaluated. Same for any
on-device LLM (Gemma-class custom licenses are (a)-with-conditions;
Apache/MIT model families are cleanly (a)).
