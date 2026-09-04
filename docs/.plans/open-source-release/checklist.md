# Open-source release checklist — JustType and HeadBoard

State as of 2026-09-04 (re-audited; supersedes the 2026-08-06 pass). Every claim below was
re-verified against the working trees of all three repositories, not carried forward.

Ordering matters in one place only: **nothing is published until Section A is complete**,
because publishing is the one step that cannot be undone.

---

## A. Blockers — must be done before anything is public

### A1. Signing — done
Re-evaluated 2026-09-04 after reading the CI workflows, which changes the earlier verdict.

**The published pipeline was never at risk.** Both release workflows fail if the keystore
secret is missing *and* re-verify the output certificate with `apksigner`, refusing to
publish anything signed `CN=Android Debug` or `android@android.com`. My earlier
"silently ships a public key" reading applied to *local* builds only.

**The real exposure was OpenBoard-HB**, which nobody had looked at: its `release` and
`release_unsigned` build types both pointed at the committed AOSP platform test key, and
its CI only runs `assembleDebug` — so there was no guard at all on a release built there.

- [x] AOSP platform test key removed from both repos: HeadBoard's `AOSP-platform-test-key/`
      (including a stale 64 MB APK and a `privapp-permissions` file still naming
      `com.google.projectgameface`) and `HeadBoard/app/platform.keystore`, plus
      OpenBoard-HB's `app/platform.keystore`. No key material remains in any working tree
- [x] Debug builds in both use AGP's default debug keystore; verified the output is
      `CN=Android Debug`
- [x] Release in both goes **unsigned** when the key is absent, instead of falling back —
      a missing key now fails at install rather than producing something forgeable
- [x] OpenBoard-HB release now uses the shared Continuous Path team key, which HeadBoard's
      own comment already said it was meant to
- [x] Corrected a stale comment claiming OpenBoard's signature-level
      `RECEIVE_HEADBOARD_EVENT` forces matching keys. It is declared but never applied to
      any component, so it constrains nothing — that comment would have misled this cleanup
- [ ] Key material is gone from the working trees but remains in **git history** — A3
      (fresh-history snapshot) is what actually removes it from anything published
- [ ] Decide deliberately whether HeadBoard/OpenBoard keep sharing JustType's key and
      alias (`justtype`). It cannot change after first publication
- [x] OpenBoard-HB now has a tag-triggered release workflow with both guards (added
      independently by the build owner while this pass was running); all three repos match

### A2. HeadBoard: remove Google's identity — largely done
- [x] JustType renamed `com.justtype.nativeapp` → `org.continuouspath.justtype` (361 files)
- [x] HeadBoard renamed `com.google.projectgameface` → `org.continuouspath.headboard`
- [x] OpenBoard-HB followed the rename (`604a983`), so the three-app IPC contract is intact
- [x] `app_name` is "HeadBoard" and `service_description` is "HeadBoard's Accessibility
      Service" — this is what the "allow … full control of your device" prompt reads, so
      that prompt no longer says Project Gameface
- [x] Tutorial copy no longer mentions Project Gameface
- [ ] `CONTRIBUTING.md` still requires signing **Google's** CLA at `cla.developers.google.com`
      — wrong and unenforceable for this project. Replace with your own CLA or a DCO
- [ ] `CODE_OF_CONDUCT.md` still routes to `opensource@google.com`
- [x] All 23 remaining "gameface" references renamed, including the three
      SharedPreferences identifiers — safe to do now because there are no users yet
- [x] **Branding artwork replaced.** Rendering the vectors (rather than reading path data)
      showed `headboard_face`, `headboard_face_splash` and `home_img` carried Google's
      GameFace mark, and `branding_200x80` was the literal Google **wordmark** — the splash
      screen was displaying "Google". All four now use HeadBoard's own icon, with the
      branding strip set as a "Continuous Path" wordmark. Original dp footprints kept, so no
      layout reflows. Correction to the earlier entry: `phone_holding` is a generic
      illustration carrying no mark, so it is retained under Apache-2.0 and needs
      attribution, not removal
- [x] `CONTRIBUTING.md` and `CODE_OF_CONDUCT.md` replaced across **all three** repos —
      JustType had neither. Contribution terms are now a DCO (`git commit -s`), chosen over
      a CLA because it needs no signing service, no records and no administration. A stale
      `HeadBoard/contributing.md` still carrying Google's CLA was deleted
- [ ] **Create and monitor the two aliases these documents now publish**:
      `conduct@continuouspath.org` (Code of Conduct reports) and
      `security@continuouspath.org` (NOTICE, and security reports in CONTRIBUTING). A Code
      of Conduct whose report address bounces is worse than none — this blocks publication

### A3. Publish as a fresh-history snapshot, not the existing repositories
Cloning carries the entire commit history. Both repos have history that must not be published:

- **JustType**: Leipzig-derived (CC BY-NC) data, the hieuthi-derived Vietnamese data, and the
  ANC-derived English word list — all removed from the working tree, all still in history
- **HeadBoard**: proprietary Google Sans fonts (`GoogleSans-{Bold,Medium,Regular}.ttf`), present
  from the root commit until their deletion in `63abc15`; plus the platform key material

- [ ] Create the public repos from the current tree with fresh history (or `git filter-repo`,
      but a clean snapshot is simpler and less error-prone)
- [ ] Keep the private full-history repos as the working ones
- [ ] Verify with `git log --all --diff-filter=A --name-only` on the new repo that none of the
      above paths ever appear

### A4. OpenBoard — done, with paperwork outstanding
Split out to `Continuous-Path/OpenBoard-HB`, GPL-3.0 `LICENSE` in place, renamed to follow
the new namespace. Remaining there:
- [ ] `NOTICE` recording the pedigree (AOSP LatinIME Apache-2.0 → OpenBoard 1.4.6 GPL-3.0 →
      this fork) and the m17n LGPL-2.1 Bengali layouts
- [ ] `CHANGES.md` — GPL-3.0 §5(a) requires stating your modifications
- [ ] The inherited loose ends: unsourced prebuilt `.so` blobs, unlicensed `com.majeur`
      emoji tooling, uncredited app icon, dictionaries with no licence text

Full analysis: `openboard-licensing.md`. It **can** be published, cannot be relicensed, and
belongs in its own repository. Its loose ends (unsourced prebuilt `.so` blobs, unlicensed
`com.majeur` emoji tooling, the uncredited app icon, dictionaries with no licence text) are
inherited from upstream but become yours on publication — resolve them there, not here.

<details><summary>Original assessment</summary>

### A4. Decide OpenBoard's fate
`HeadBoard/openboard/` is GPL-3.0 (a fork of openboard-team/openboard 1.4.6, itself from AOSP
LatinIME). It also contains LGPL-2.1 m17n Bengali layout XMLs.

The architecture already protects you: separate Gradle roots, separate `applicationId`s,
separate APKs, communication only via permission-guarded broadcast Intents and package-name
strings — **zero compile-time coupling**, which is the strongest "separate works" position
available. But commingling GPL and Apache in one published repo will still frighten commercial
licensees.

- [ ] **Recommended:** split `openboard/` into its own GPL-3.0 repository; document the Intent
      protocol in the HeadBoard repo under Apache-2.0 so anyone can implement a compatible IME
- [ ] If kept in one repo: root `LICENSE` Apache-2.0 plus a prominent README statement that
      `openboard/` is a separate GPL-3.0 work; never bundle both into one APK or link at build time
- [ ] Either way, preserve the IPC-only coupling. The planned AIDL bound service is still IPC
      and therefore still fine, but do not let it become shared compiled code

</details>

### A5. Copyright holder — resolved
The holder is **Continuous Path Foundation**, a 501(c)(3) nonprofit.

- [x] JustType `LICENSE` and `NOTICE` carry `Copyright 2026 Continuous Path Foundation`
- [ ] **`NOTICE` still has an unfilled placeholder**: `Security and licensing contact:
      <fill in — see docs/.plans/…>`. It ships in every distribution; fill or drop it
- [ ] HeadBoard has **no `NOTICE` at all**, and its `LICENSE` is bare Apache text

**What to put in notices — and what to leave out.**

Include:
- `Copyright <year> Continuous Path Foundation` — the legal entity name, nothing else.
  Apache-2.0 needs only this
- A project URL, so a downstream user can find the source
- One contact address for licensing and security reports. A role alias
  (`opensource@`, `security@`) rather than a person, so it survives staffing changes
- The 501(c)(3) status if you want it visible — it is fair to state and signals the
  mission, but it carries no licensing weight

Leave out:
- **The federal EIN.** It appears on your Form 990, which is already public, so this is
  not a secret — but publishing it in every source distribution invites charity-impersonation
  fraud and gains you nothing legally. Donors who need it can ask, or read the 990
- A postal address or phone number, for the same reason. If a mailing address is ever
  required, use the registered agent's, not a home address
- Individual contributors' names in `NOTICE`. Git history and `AUTHORS`/`CONTRIBUTORS`
  are the right places; `NOTICE` is reserved for attributions the licence obliges, and
  every line added there must be reproduced by every downstream distributor

Year convention: use the year of first publication and update it only on substantive
change — `2026` is right for a first release. Ranges (`2024-2026`) are conventional but
carry no extra legal effect.

---

## B. Required before publication, but not blocking other work

### B1. HeadBoard licensing paperwork
- [x] `NOTICE` written: derived-work lineage from Project GameFace, MediaPipe and its
      prebuilt natives, the `face_landmarker.task` bundle with all three model cards, the
      AndroidX/CameraX/Material/Gson/Kotlin/AutoValue set, and an artwork section splitting
      what was retained (illustrations, tutorial video, permission art — Apache-2.0 with
      attribution) from what was replaced (all Google marks). Includes a trademark
      paragraph disclaiming affiliation
- [x] `LICENSE` copyright line filled (`Copyright 2026 Continuous Path Foundation`);
      README now points at LICENSE, NOTICE, CONTRIBUTING and the Code of Conduct
- [x] The `AOSP-platform-test-key/` directory is gone entirely (A1), so the 64 MB APK went
      with it
- [ ] Pin `com.google.auto.value:auto-value:latest.release` to a real version — a floating
      version means the resolved licence is not reproducible

### B2. JustType language data — Español and Tiếng Việt exclusion lists
Verified 2026-09-04: English has 304 entries of which 97 carry level markers; **Español (226)
and Tiếng Việt (18) have zero** — both are still flat level-1 and unreviewed. Vietnamese has
no LDNOOBW coverage and almost certainly under-blocks at 18 entries.
- [ ] Grade Español into levels; have a Spanish speaker review
- [ ] Have a Vietnamese speaker review and extend
- Process: `docs/.plans/language-resources/exclusion-list-process.md`

### B3. Verify the language-build migration on real hardware
Fully implemented and unit-tested (8 cases) but never observed running on an actual upgrade.
- [ ] Plant an old build stamp and a truncated word list in an active DB, trigger an update
      boundary, confirm the corpus is restored and learned stats survive
- [ ] Time it on a low-end device — English is ~60k rows and it runs on the IME startup path
- Detail: `docs/.plans/language-resources/db-migration.md`

### B5. 16 KB page-size compatibility (HeadBoard) — distribution blocker, not licensing
Android raised this on launch after the 2026-09-04 reinstall:

> This app isn't 16 KB compatible. ELF alignment check failed.
> `lib/arm64-v8a/libmediapipe_tasks_vision_jni.so` — LOAD segment not aligned
> `lib/arm64-v8a/libimage_processing_util_jni.so` — LOAD segment not aligned

Android 15+ devices are moving to 16 KB memory pages, and Google Play requires 16 KB
alignment for apps targeting Android 15+. The unaligned libraries are MediaPipe's
prebuilts, not ours.

- [ ] Move to a MediaPipe release that ships 16 KB-aligned natives (0.10.18+) and rebuild
- [ ] Re-check with the same on-device dialog, or `zipalign -c -P 16 -v`
- Unrelated to licensing, but it gates distribution just as firmly

### B4. Attribution loose ends
- [ ] `EnglishWordsAvoid.txt` is project-curated with public-list lineage (57% overlap with the
      CMU/von Ahn list). Acceptable, but if a formal grant is ever wanted, rebuild from LDNOOBW
      (CC BY-4.0) and re-curate
- [ ] Optional courtesy: email Hieu-Thi Luong (`contact@hieuthi.com`) crediting his syllable
      list. No longer a licensing requirement — his data is fully removed — but he helped

---

## C. Done — recorded so it is not re-litigated

- [x] **Leipzig corpora are CC BY-NC** — verified against Wortschatz terms and the SAW Leipzig
      archive. All derived values purged; case seeds regenerated from Wikipedia
- [x] **hieuthi syllable list is unlicensed** — gist and blog both checked. Purged; Vietnamese
      counts rebuilt from hermitdave + Wikipedia
- [x] **English word list provenance resolved** — empirically traced to ANC Second Release
      (25,555 exact count matches). ANC is LDC-fee-licensed, *not* open, despite the "fully open
      data" line on anc.org, which describes the OANC subset. Replaced with a clean rebuild;
      the old list is archived reference-only and must not ship
- [x] JustType `LICENSE` (Apache-2.0) + `NOTICE`, with language data correctly separated as
      CC BY-SA 4.0
- [x] All JustType runtime dependencies confirmed Apache-2.0 / MIT
- [x] Exclusion lists rebuilt from licensed sources; `dsojevic/profanity-list` and
      `surge-ai/profanity` rejected for having no licence
- [x] `parse_wiktionary_regions.py` restored, so `EspanolRegionTags.txt` is reproducible
- [x] Language-build migration implemented, so corpus updates reach existing installs
- [x] UK/US English regional variants (2,765 GB / 2,534 US tags in one shared database)

---

## D. Judgement calls worth revisiting

Not bugs — decisions that a second opinion might change.

- **Ableist slurs** (`spaz`, `loony`, `nuthouse`, `deaf-mute`, `weak-minded`) are blocked
  outright rather than following the homograph logic applied elsewhere. Defensible for this
  user base; still a product call
- **`chink` / `chinks`** parked as slurs. "A chink in the armour" is legitimate, but a user who
  wants it can add it
- **CC BY-SA on the language data** obliges downstream users to keep derived word lists under
  CC BY-SA. If a commercial licensee objects, the alternative is rebuilding the corpora from
  CC0/CC-BY sources (HPLT v2 is CC0 and covers both es and vi)
- **`theatre`** has no GB tag (Wiktionary evidence is ambiguous); `theater` is tagged US, so the
  pair still skews correctly for US users but not for UK ones
