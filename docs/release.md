# Release & Beta Builds

JT has three build types:

| Build type | `applicationId`           | `versionName` | Signing                | When to use                |
|------------|---------------------------|---------------|------------------------|----------------------------|
| `debug`    | `org.continuouspath.justtype`  | `1.0`         | debug key              | day-to-day development     |
| `beta`     | `org.continuouspath.justtype`  | `1.0-beta`    | release key (debug fallback) | tester builds, internal pre-release |
| `release`  | `org.continuouspath.justtype`  | `1.0`         | release key (debug fallback) | public/tester distribution |

`beta` and `release` share the same `applicationId`, so installing one overwrites
the other on a device. They differ structurally so future divergence (e.g.,
distinct app icon, different `DEBUG_EDITING` flag) is a one-line edit.

Both `beta` and `release` enable R8 minification + resource shrinking and run
*dramatically* faster than `debug` on low-end Android devices (the big runtime
win is `debuggable=false`, which lets ART fully AOT/JIT-compile the app).

## Signing — how it works

Release/beta/internal builds sign with the team release key when a machine has
been set up (one time) with `./jt signing-setup`. That command puts:

- the **keystore** at `~/.justtype/justtype-release.jks` (outside every repo —
  it can never be committed, even if `.gitignore` changes), and
- the **password** in the **macOS Keychain** (service `justtype-signing`) —
  never in a plaintext file.

`app/build.gradle` reads both at build time, so plain Android Studio builds
(Build Variants → `release` → Run / Build APK) and `./jt build-release` sign
identically with no per-build prompts. CI overrides the keystore path and
password via the `JUSTTYPE_KEYSTORE_FILE` / `JUSTTYPE_KEYSTORE_PASSWORD` /
`JUSTTYPE_KEY_PASSWORD` gradle properties (repo secrets — see the release
pipeline below).

On a machine that is *not* set up, builds fall back to the **debug key** and
print:

```
jt: release signing not configured — APK signed with the DEBUG key. Run ./jt signing-setup (docs/release.md).
```

Debug-signed release builds install fine for local testing but cannot be
upgraded by (or upgrade to) properly-signed builds — Android refuses with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

Check any machine with `./jt signing-status` (prints keystore path, cert
fingerprint + expiry, and a signed/unsigned verdict).

## Signing — one-time key creation

Done **once ever** (not per machine). The key is the app's permanent identity:
every future update must be signed with it.

1. `./jt signing-setup` → answer `y` to generate. Pick a strong password and
   put it in the shared password manager **immediately** — it cannot be
   recovered.
2. Create a **private** GitHub repo `Continuous-Path/justtype-signing`
   (collaborators: both of us; 2FA on both accounts). Copy
   `~/.justtype/justtype-release.jks` into it with a short README ("JT's
   release signing key; password is in the password manager, never in this
   repo; this repo must stay private forever"). Push.
3. The signing repo + password-manager entry are the key's backups. Losing the
   key after distribution means every installed copy dead-ends (uninstall +
   reinstall for all users).

The password is never stored in this repo, the signing repo, script output, or
any file — only the Keychain (per machine), the password manager (backup), and
GitHub secrets (CI).

## Signing — per-machine setup

**Technical flow:** clone `justtype-signing`, then

```sh
./jt signing-setup <path-to-clone>/justtype-release.jks
# type the password once (verified against the keystore before anything is stored)
./jt signing-status
```

**Boss's Mac:** clone `justtype-signing` in GitHub Desktop (lands in
`~/Documents/GitHub/justtype-signing`), then run the same command in the
JustType folder — either Nic drives, or Claude runs it with him typing the
password from the password manager when prompted. After that his normal
Android Studio flow (Build Variants → `release` → Run) signs automatically;
he never sees the key or password again.

Re-running `signing-setup` is safe (idempotent); it's also how to refresh the
Keychain entry after a password-manager restore or a new macOS user account.

## Building

```sh
./jt build-release        # → app/build/outputs/apk/release/app-release.apk
./jt build-beta           # → app/build/outputs/apk/beta/app-beta.apk
```

To install directly on a connected device:

```sh
./jt install-release
./jt install-beta
```

Verify the signing identity of a built APK:

```sh
$ANDROID_HOME/build-tools/<version>/apksigner verify --verbose --print-certs \
    app/build/outputs/apk/release/app-release.apk
# The SHA-256 digest must match `keytool -list -keystore ~/.justtype/justtype-release.jks`
# and the DN must be CN=JustType, O=Continuous Path (NOT CN=Android Debug).
```

## Tester downloads (release pipeline)

`.github/workflows/release.yml` builds a signed APK on GitHub's servers and
publishes it to the **public** downloads repo
`Continuous-Path/JustType-Releases`. The website's download link stays fixed:

```
https://github.com/Continuous-Path/JustType-Releases/releases/latest/download/justtype.apk
```

Every release also carries a versioned `justtype-vX.Y.apk` for history.

### Cutting a release

1. Bump `versionCode` (and `versionName`) in `app/build.gradle` — every APK
   must have a higher `versionCode` than the previous one. Commit to `main`.
2. Tag and push:

   ```sh
   git tag v1.1 && git push origin v1.1
   ```

   Or run it manually: github.com → Actions → Release → "Run workflow" (works
   for the boss / Claude; the tag is derived from `versionName` + run number).
3. The workflow refuses to publish if the build fell back to the debug key.

### One-time pipeline setup

1. The public downloads repo `Continuous-Path/JustType-Releases` exists (README
   only; the workflow publishes everything else).
2. Add the three secrets to **this** repo (Settings → Secrets → Actions, or):

   ```sh
   base64 -i ~/.justtype/justtype-release.jks | gh secret set JUSTTYPE_KEYSTORE_BASE64
   gh secret set JUSTTYPE_KEYSTORE_PASSWORD    # paste the signing password
   gh secret set RELEASES_TOKEN                # fine-grained PAT: contents read+write
                                               # on Continuous-Path/JustType-Releases only
   ```

## Google Play (planned)

When enrolling in Play App Signing, choose **"use an existing key"** and upload
this key (exported with Google's PEPK tool) — do **not** let Google generate a
fresh one. That keeps the signature identical between sideloaded tester builds
and Play installs, so testers update cleanly without uninstalling. Play then
holds its own copy of the key (a third backup). Switch the workflow to
`bundleRelease` at that point.

## Migration from debug-signed installs

Devices carrying a debug-signed install (all pre-signing test devices) need one
uninstall + reinstall (`./jt install-release -f`, or uninstall from Settings) —
app data on the device is lost, so time it outside testing sessions. The
abandoned pre-signing keystore at `docs/.local/justtype-release.jks` should be
deleted; the real key lives in `~/.justtype/` + the `justtype-signing` repo.

## Conference / demo prep checklist

1. Bump `versionCode` (and optionally `versionName`) in `app/build.gradle`.
   Each new APK distributed must have a higher `versionCode` than the prior one
   on the same device.
2. `./jt check-full` — pre-flight gate (tests + spotless + detekt + lint + coverage).
3. `./jt build-release` — produces the demo APK.
4. Sanity-check the APK metadata:

   ```sh
   $ANDROID_HOME/build-tools/<version>/aapt dump badging \
       app/build/outputs/apk/release/app-release.apk | grep -E 'package|versionName'
   ```

5. Transfer the APK to each demo device (`adb install -r <apk>`) or use
   `./jt install-release` against each device in turn.
6. On every device: enable JT as an input method in system settings, switch
   to it, type a sentence in any text field, confirm the IME UI responds.
   On lower-end hardware especially — this is what the release variant is for.

## Publishing language packs

Downloadable per-language word DBs (see [langpacks.md](./langpacks.md)):

```sh
./gradlew :app:packageLanguageArtifacts
gh release upload langpacks-v1 --repo Continuous-Path/JustType-langpacks dist/langpacks/manifest.json dist/langpacks/*.db.gz --clobber
```

Bump the language's version in `app/src/main/db/langpacks.properties` whenever
its corpus changes, re-run both commands, and the app's Languages screen offers
the update. Slim APKs (English only): `-PbundledLanguages=English`.

## Future work

- **Diverging beta from release.** Today `beta` is `release` + a version suffix.
  Likely future splits: turn off `DEBUG_EDITING` (and hide `DeveloperSettingsActivity`)
  in `release`, keep on in `beta`; or give `beta` a distinct app icon / label so
  testers can tell them apart at a glance.
- **Play Store release.** Switch the release workflow to App Bundle
  (`bundleRelease`) once Play App Signing is enrolled (see above).
