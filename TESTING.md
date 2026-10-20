# Testing

What's covered, how to run it, what's deliberately not covered yet, and the current state of
the CI `instrumented-tests` job. Read `DEV_LOG.md` for the history behind *why* specific tests
exist (most are regression guards for a real bug or user report, not coverage for its own sake).

## Running the suite

```bash
./gradlew testDebugUnitTest           # pure-logic unit tests (host JVM, no device needed)
./gradlew connectedDebugAndroidTest    # Espresso/Room/instrumented tests (needs a connected
                                        # device or emulator with animations disabled)
./gradlew lintDebug
```

Espresso requires animations off on the target device/emulator (Settings → Developer options →
turn off Window/Transition/Animator animation scale). Some OEM builds block the `adb shell
settings put global ...` command that would do this for you, in which case toggle it by hand.

## Unit tests (`app/src/test/`)

Plain JUnit, no Android framework classes involved -- anything that touches `Context`,
`android.graphics.Color`, `org.json.*`, etc. has to be an instrumented test instead, since the
unit-test `android.jar` stub throws on those calls (this project has no Robolectric or mocking
library).

| File | Covers |
|---|---|
| `data/ChecklistItemTest` | `countDone` matches a full `parse` + count, on mixed/empty/blank-line input |
| `data/NoteRepositoryTest` | `escapeLikePattern` -- `%`/`_`/`\` escaping for the FTS5-backed search |
| `data/RecurrenceUtilsTest` | Every `Note.REPEAT_*` mode's `occursOn`, plus `nextOccurrenceOnOrAfter` (what schedules a recurring note's exact-time alarm) |
| `data/RemindersCacheTest` | TTL reuse and `invalidate()` forcing a re-fetch |
| `data/drive/DriveBackupHelperTest` | `mergeByUuid`: newer-wins conflicts, tombstone drops (both directions -- locally deleted and cross-device deleted), duplicate-within-one-backup dedup |
| `ui/NotesSortPrefsTest` | Each sort mode's ordering, pinned-always-first |

## Instrumented tests (`app/src/androidTest/`)

| File | Covers |
|---|---|
| `TestPrereqs` | Not a test itself -- shared `resetAppState()` (clear PIN, unlock, wipe all DB tables) so every test starts clean regardless of what ran before it |
| `CalendarActivityTest` | Cold start of the launcher screen doesn't crash |
| `CalendarDayDialogShowsNotesTest` | Day dialog shows a plain, a recurring, and a pinned note together |
| `CalendarResumeRefreshesReminderTest` | Reminder notification refreshes on `onResume`, not only `onCreate` (regression: it used to not show up until the app was fully restarted) |
| `CreateEditNoteFlowTest` | Create a text note and a checklist note from the notes list, both show up |
| `ChecklistAddRowTest` | The checklist's trailing "add item" row stays usable for 3 items in a row (regression guard for the add-row redesign) |
| `SelectAllNotesTest` | Long-press → select all → bulk delete trashes every note |
| `SortNotesTest` | Picking a sort mode persists it |
| `SettingsThemeTest` | Picking a theme updates the Settings row and `ThemePrefs` |
| `SearchShowsAllNotesTest` | A blank query browses every note instead of showing nothing |
| `SearchFtsSubstringMatchTest` | Mid-word substring matches still work on the FTS5 index (not just whole-token matches) |
| `NoteRepositoryTombstoneSyncTest` | A tombstone synced in from another device's Drive backup actually trashes a still-active local note, against a real Room DB |
| `BackupJsonRoundTripTest` | Plain JSON export/import and the Drive tombstone-carrying format both round-trip, including a legacy (pre-tombstone) backup file |
| `DriveSyncActivityTest` | Signed-out state renders without crashing |
| `ReminderPrefsDefaultTest` | The (no-longer user-configurable) daily reminder time still defaults to 9:00am |
| **`FoldersFlowTest`** *(new)* | Create / rename a folder; deleting one clears `folderId` on its notes instead of deleting them |
| **`TrashFlowTest`** *(new)* | Restoring a trashed note reactivates it and clears its tombstone; "Delete forever" actually removes the row |
| **`SecurityLockFlowTest`** *(new)* | Wrong PIN shows the error and stays locked; correct PIN unlocks (`LockState`) |
| **`ReminderNotifierSingleNoteOpensNoteTest`** *(new)* | Regression guard for this session's change: exactly one note due deep-links the notification straight to it; 2+ notes due still falls back to the calendar |
| **`WidgetProvidersRegisteredTest`** *(new)* | All four widget providers (the resizable one + the new 1x1 / 2x2 list / 2x2 summary) are actually installed and parse a non-zero size from their `appwidget-provider` XML |

### Known gaps (not covered, and why)

- **PIN set/change dialog's own UI** (`SettingsActivity.showSetPinDialog`) -- `SecurityLockFlowTest`
  sets the PIN directly via `SecurityPrefs` to test `LockActivity`'s gating logic in isolation;
  the dialog's validation (`pin_too_short`, `pin_mismatch`) has no test of its own yet.
- **Drive backup/restore against a real Google account** -- `DriveSyncActivityTest` only covers
  the signed-out render. Sign-in requires a registered OAuth client for this machine's signing
  cert (see `DriveBackupHelper`'s doc); the merge *logic* itself is covered thoroughly by
  `DriveBackupHelperTest` + `NoteRepositoryTombstoneSyncTest`, just not the live network calls.
- **The Drive sync `Mutex`'s concurrency guarantee** -- no test actually races two `syncNow` calls
  against each other; verified by code reasoning only (see `DEV_LOG.md`, Session 6).
- **Widget *rendering*** -- `WidgetProvidersRegisteredTest` confirms the four providers are
  installed and sized, not that their `RemoteViews` actually render correctly once placed (no
  reliable way to place a widget via instrumentation without `AppWidgetHost`, which this suite
  doesn't use yet).
- **`NoteFormatting`** (bold/italic/underline) and **`NoteColors`** (`isLight`/`pressColor`) --
  both only usable as instrumented tests (real `android.text.Html`/`android.graphics.Color`), and
  neither has one yet.
- **`ShareImageHelper`**, **swipe-to-delete** (`MainActivity`'s `ItemTouchHelper`), and most of
  `SettingsActivity` beyond theme (notifications toggle, widget range, auto-lock picker, backup
  frequency) have no automated coverage.
- **`NoteEditorActivity.saveIfNeeded()`'s applicationScope fix** (this session) has no direct
  automated regression test -- simulating "the Activity is destroyed mid-save" from Espresso
  isn't straightforward (there's no way to force `onDestroy()` to race a specific coroutine
  suspension point without a seam to inject a delay). `CreateEditNoteFlowTest` exercises the
  normal save path and would catch a *total* regression (note never saves at all), but not the
  specific race this fix addresses.

## CI: why `instrumented-tests` hasn't been confirmed passing

**I could not inspect the actual GitHub Actions run** for this: the `gh` CLI isn't installed in
this environment, and `https://api.github.com/repos/Danielarceeno/anotai/actions/runs` returns a
plain `404` to an unauthenticated request -- consistent with the repository being private (GitHub
returns 404 rather than 403 for a private repo to avoid confirming it exists). I didn't attempt
to extract a token from the local git credential store to work around that.

As the closest available substitute, I ran the exact command the CI job runs
(`./gradlew connectedDebugAndroidTest`) against a real device connected over Wi-Fi ADB. That run
failed before any test even started:

```
Exception thrown during onBeforeAll invocation of plugin AndroidTestApkInstallerPlugin:
INSTALL_FAILED_USER_RESTRICTED: Install canceled by user
```

This is the device (an Onyx Boox e-ink tablet) requiring a tap on its own screen to confirm every
app install over ADB -- a security prompt specific to that physical hardware, not something
GitHub's `ubuntu-latest` + `reactivecircus/android-emulator-runner` emulator has (there's no
physical user to prompt). **It says nothing about why the real CI job fails or hasn't run**; it's
just the local stand-in hitting its own, unrelated obstacle.

Retried once after asking the device's owner to unlock the screen; it failed the same way again
(`INSTALL_FAILED_USER_RESTRICTED`, this time on the plain debug APK, not even the test APK) --
this isn't a one-off dialog that was simply missed, it's a persistent device policy. Likely a
Boox-specific setting (install verification / "Unknown sources" confirmation required per-install
even over ADB) rather than anything in this project. Didn't keep retrying past this -- an actual
device-level setting change is what's needed, not another `./gradlew` run.

### Static read of `.github/workflows/ci.yml`

The workflow itself looks reasonable on paper: `api-level: 30`, `target: default`, `arch:
x86_64`, `disable-animations: true` (addresses the "Animations enabled" Espresso failure already
documented as a real local issue), and the KVM group-permission workaround GitHub-hosted runners
need for hardware-accelerated emulation. Candidate causes for a first real failure, most to least
likely given this app's dependencies:

1. **`play-services-auth` / Google Sign-In on a `default` (non-`google_apis`) system image.**
   `DriveSyncActivityTest` only exercises the signed-out path, but `DriveBackupHelper.signInClient`
   and `GoogleSignIn.getLastSignedInAccount` still touch Play Services classes at class-load time.
   A `default` AOSP image has no Play Services APK at all (unlike `google_apis`); if any of that
   throws instead of degrading to "not signed in", every test that launches an Activity touching
   those paths indirectly (most of them, via the app's own `onCreate` chains) could fail. This is
   the single highest-value thing to try first: switch the workflow's `target` to `google_apis`.
2. **Emulator boot timeout / cold cache on first run.** `reactivecircus/android-emulator-runner`
   caches the AVD snapshot across runs, but a *first-ever* run has nothing to restore from and can
   be slow enough to trip the action's own boot-timeout default, especially stacked with 21
   instrumented tests.
3. **SQLCipher's native library load on the CI runner's x86_64 ABI** -- works locally and on
   physical arm64 hardware per `DEV_LOG.md`; an x86_64 emulator is a different ABI path through the
   same `net.zetetic:android-database-sqlcipher` native bindings, worth confirming explicitly
   rather than assuming it's identical.

None of these are fixed here, since none can be verified without an actual failing log to read.
**Recommended next step**: either install/authenticate the `gh` CLI (`gh auth login`) so a future
session can pull the real run's log with `gh run view --log-failed`, or paste the failing job's
log output directly.
