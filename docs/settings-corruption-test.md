# Settings cache-corruption manual test

Used to reproduce the transient "sliders snap to min / weird state in settings UI" bug observed
when the IME service crashes/restarts (e.g. keyboard fails to open in another app, app process
gets killed, on restart `dataStore.data.first()` returns an incomplete `Preferences`).

Mitigations in place (`SettingsRenderer` defensive guard, `PrefsSidecar` + DataStore
`ReplaceFileCorruptionHandler`) should keep the user-visible behavior sane even when the file
is unrecoverable. This script validates that on a real device.

## Prerequisites

- **Debuggable** build installed (`./jt install`). Profileable doesn't allow `run-as`.
- A populated settings state (open JustType Settings, customize a few sliders/toggles).

## Reproduce the corruption

```sh
PKG=org.continuouspath.justtype

# 1. Confirm DataStore file is healthy
adb shell run-as $PKG ls -la files/datastore/
adb shell run-as $PKG cat files/datastore/JustTypePrefs.preferences_pb | wc -c

# 2. Optional: snapshot the healthy state for later comparison
adb shell run-as $PKG cp files/datastore/JustTypePrefs.preferences_pb files/datastore/JustTypePrefs.preferences_pb.healthy

# 3. Corrupt the DataStore file in place (overwrite first 10 bytes with zeros)
adb shell run-as $PKG dd if=/dev/zero of=files/datastore/JustTypePrefs.preferences_pb bs=1 count=10 conv=notrunc

# 4. Force the IME process to die so init re-runs
adb shell am force-stop $PKG

# 5. Start watching the diagnostic tag BEFORE re-launching
adb logcat -c                              # clear
adb logcat -s SettingsInit                 # in a separate terminal

# 6. Open Settings Activity
adb shell am start -n $PKG/.activity.SettingsActivity
```

## Expected outcome

- Logcat shows `SettingsInit ERROR ... DataStore protobuf corrupted; attempting sidecar restore`.
- Followed by either:
  - `Restored N keys from sidecar` (sidecar existed → user data preserved), OR
  - `No sidecar available; returning empty Preferences (user data lost)` (first-corruption-ever case).
- In either case, the Settings UI should render plausibly — sliders at registry defaults or
  restored values, not all jammed to min.
- The on-disk DataStore file has been silently rewritten with whatever the handler returned.

## Restoring the healthy snapshot (cleanup)

```sh
adb shell run-as $PKG cp files/datastore/JustTypePrefs.preferences_pb.healthy files/datastore/JustTypePrefs.preferences_pb
adb shell am force-stop $PKG
adb shell run-as $PKG rm files/datastore/JustTypePrefs.preferences_pb.healthy
```

## Inspecting the sidecar

```sh
adb shell run-as $PKG cat files/prefs_sidecar.json | python3 -m json.tool | head -40
```
