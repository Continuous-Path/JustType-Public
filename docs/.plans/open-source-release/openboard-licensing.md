# OpenBoard: what you may publish, and under what licence

Short answer: **yes, you can publish it — but only as GPL-3.0, in its own repository, and
you cannot relicense it.** Nothing here is a reason to withhold the code; it is a reason to
keep it separate from the Apache-2.0 work.

## The pedigree, verified

Three layers, each adding obligations to the one beneath:

| Layer | Who | Licence |
|---|---|---|
| AOSP LatinIME | The Android Open Source Project | Apache-2.0 |
| OpenBoard (dslul → openboard-team), v1.4.6 | OpenBoard contributors | **GPL-3.0** |
| This fork | Continuous Path Foundation | inherits GPL-3.0 |

Measured in the tree: 233 of 299 Java/Kotlin sources still carry AOSP Apache-2.0 headers, and
all 252 native JNI sources do. Not one file carries a GPL header — the only GPL artifact in the
whole subtree is the top-level `LICENSE`. The ~66 header-less files are OpenBoard's own Kotlin
rewrites and default to the project licence.

**Your fork's own contribution is small and well-bounded:** two added files
(`IMEEventReceiver.java`, `SyntheticPointerTracker.java`) and 25 modified files, across 46
commits. You own the copyright in those changes.

Also present, inherited from upstream: **LGPL-2.1-or-later** m17n Bengali layout XMLs
(`rowkeys_bengali_unijoy{1,2,3}.xml`, © AIST). LGPL is GPL-compatible, so it rides along
without adding anything you must do differently.

## Why you cannot relicense it

The AOSP layer is Apache-2.0 and *could* be relicensed. The OpenBoard layer cannot: those
contributions are GPL-3.0 and held by dozens of contributors who never signed a CLA assigning
rights to anyone. Relicensing would need permission from all of them. That is why the
Apache-headered files inside a GPL project are not an escape hatch — the *combination* is
GPL-3.0, even though many individual files are permissive.

## What GPL-3.0 actually obliges you to do

Far less than people fear, and none of it blocks commercial use:

- **Ship the source** of the IME to anyone you ship the APK to. A public repository satisfies this.
- **Keep it GPL-3.0**, including your own modifications to those files.
- **State your changes** — GPL-3.0 §5(a). A `CHANGES.md` naming the two added files and
  summarising the 25 modified ones is sufficient and is worth writing while the memory is fresh.
- **Preserve the existing notices**, including the AOSP Apache headers and the m17n LGPL headers.

GPL-3.0 explicitly permits commercial distribution. A licensee can sell a product that includes
this IME; they must simply pass on the source and the same rights for *this component*.

## The recommendation

**Split it into its own repository under GPL-3.0.** Concretely:

1. New repo, e.g. `Continuous-Path/HeadBoard-Keyboard`, containing today's `openboard/` tree.
2. Keep `LICENSE` (GPL-3.0) exactly as it is.
3. Add a `NOTICE`/`README` stating the pedigree: AOSP LatinIME (Apache-2.0) → OpenBoard 1.4.6
   (GPL-3.0) → this fork, plus the m17n LGPL-2.1 layouts, plus the unresolved items below.
4. Add `CHANGES.md` for §5(a).
5. In the HeadBoard repo, document the Intent protocol under Apache-2.0 — action names, extras,
   and the two custom permissions. **This is the part that protects your commercial story:**
   anyone can then write a compatible IME without touching GPL code, and a licensee who does not
   want GPL in their product simply ships their own keyboard.

The architecture already earns you this position, and it is worth stating plainly: separate
Gradle roots, separate application IDs, separate APKs, communication only over permission-guarded
broadcast Intents, and **zero `import org.dslul` anywhere in HeadBoard**. That is the strongest
"separate works" footing available. Preserve it — the planned AIDL bound service is still IPC and
still fine, but do not let it become shared compiled code.

## Loose ends to resolve before publishing that repo

These are inherited from upstream, not created by you, but they will be *your* repository:

- **Four prebuilt `libjni_latinimegoogle.so` blobs** (arm64-v8a, armeabi-v7a, x86, x86_64) with no
  source-provenance record. Symbol dumps look like ordinary AOSP LatinIME, but GPL-3.0 requires
  source for shipped binaries. Either rebuild them from the in-tree JNI sources or drop them —
  `JniLibName.kt` already falls back to `jni_latinime`.
- **The `com.majeur` emoji tooling** (8 Kotlin files) carries no header or licence at all.
- **The app icon** is credited to "Marco TLS" with no licence stated.
- **`dictionaries/`** ships no licence text, and `main_bn.dict` has no corresponding wordlist.
- **Checked-in build outputs** under `tools/*/bin/**` including `.class` files — delete.

## If you would rather not publish it at all

That is a legitimate option and costs you little. OpenBoard is optional to the product; excluding
it removes the GPL question entirely, and the Intent protocol documentation alone is enough for
someone else to build a compatible keyboard. Choose this if the loose ends above are more work
than the component is worth to you. But there is no legal reason to withhold it.
