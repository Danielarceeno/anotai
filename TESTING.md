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

## CI: why `instrumented-tests` was failing (resolved)

Couldn't inspect the actual GitHub Actions run at first: the repo was private and the `gh` CLI
wasn't installed, so `https://api.github.com/repos/Danielarceeno/anotai/actions/runs` returned a
plain `404`. As a local substitute, `./gradlew connectedDebugAndroidTest` was run against a real
device connected over Wi-Fi ADB -- that failed before any test even started
(`INSTALL_FAILED_USER_RESTRICTED`, an Onyx Boox-specific install-confirmation policy, unrelated to
CI, which has no physical user to prompt).

The repo was then made public and `gh auth login` run, which made the real logs readable. The
emulator itself was never the problem: it boots fine (`api-level: 30`/`target: default`/`arch:
x86_64`, ~1.5 minutes to boot), and 31 instrumented tests actually ran. **Three genuinely failed**,
all three caused by this session's own new tests, not by anything pre-existing or CI-environment-specific:

1. **`FoldersFlowTest.deletingAFolder_keepsItsNotesButClearsTheirFolder`** --
   `AmbiguousViewMatcherException`, two `deleteButton` views matched at once. Root cause: a plain
   `@Before` runs *after* `ActivityScenarioRule` has already launched the Activity, so
   `FoldersActivity`'s `onCreate` (which immediately subscribes to `FolderRepository.observeAll()`)
   could render a leftover folder row from an *earlier test method in the same class* (the Room DB
   is a process-wide singleton, not reset between methods on its own) before `resetAppState()`
   cleared it moments later -- racing that stale row's removal against the fresh one's own insert.
   **Fix**: switched to `RuleChain.outerRule(TestPrereqs.resetStateRule()).around(activityRule)`,
   the pattern `CalendarActivityTest`/`SearchShowsAllNotesTest` already use for the exact same
   reason. Applied to `TrashFlowTest` too, which has the identical structure (didn't fail this run,
   but was equally exposed).
2. **`ReminderNotifierSingleNoteOpensNoteTest.severalNotesDue_fallsBackToTheCalendar`** --
   expected no `NoteEditorActivity`-targeting `PendingIntent` to exist, found one anyway. Root
   cause: a `PendingIntent` stays registered with the system until explicitly cancelled;
   `exactlyOneNoteDue_opensThatNoteDirectly` (same class, same request code/target, ran first)
   left one behind that the later method's `FLAG_NO_CREATE` lookup still matched. **Fix**: cancel
   both candidate PendingIntents in `@Before` so every test method starts from a clean slate
   regardless of execution order.
3. **`SettingsThemeTest.pickingDarkTheme_updatesTheSummaryRow`** -- `IllegalStateException: Must
   be called from main thread`, at `Activity.recreate()`. This is a *pre-existing* test this
   session didn't write, but newly broken by one this session did: `SecurityLockFlowTest
   .correctPin_unlocksTheApp` taps "unlock", which (via `LockActivity.unlock()`) starts a real
   `CalendarActivity` that no `ActivityScenarioRule` manages or ever finishes. Left running into
   later tests in the same instrumentation process, that orphaned `CalendarActivity` was still
   alive when `SettingsThemeTest` later changed the theme -- `AppCompatDelegate
   .setDefaultNightMode()` calls `recreate()` on *every* currently-tracked AppCompat activity, and
   something about that orphaned instance's state made that specific call land off the main
   thread. **Fix**: `SecurityLockFlowTest`'s `@After` now uses
   `ActivityLifecycleMonitorRegistry`/`Stage.RESUMED` to find and `finish()` any `CalendarActivity`
   it leaked, so nothing outlives the test.

All three were real bugs in the new tests' own isolation, not the app or the CI environment --
exactly the kind of thing a first real run against a slower, differently-timed emulator surfaces
that a fast local pass might not.

### Round 2: four more, after fixing the above

Pushing the round-1 fixes and watching the next run (`gh run watch`) surfaced four more failures
from the same underlying class of problem -- test state leaking across methods/classes in the
same instrumentation process, not anything environment-specific:

1. **`CalendarResumeRefreshesReminderTest`** failed its own "nothing showing yet" precondition --
   `ReminderNotifierSingleNoteOpensNoteTest` posts a real notification as part of what it's
   testing, and never cancelled it. **Fix**: cancel `ReminderNotifier.NOTIFICATION_ID` in both
   `@Before` and `@After` there.
2. **`SettingsThemeTest`** hit the exact same `Activity.recreate()` / "must be called from main
   thread" failure as round 1, meaning the round-1 fix didn't fully land: `getActivitiesInStage
   (Stage.RESUMED)` right after `perform(click())` can run *before* the newly-started
   `CalendarActivity` actually reaches `RESUMED` (starting an Activity is asynchronous). **Fix**:
   added `Instrumentation.waitForIdleSync()` first, and widened the stage check to
   `CREATED`/`STARTED`/`RESUMED`/`PAUSED`/`STOPPED` instead of only `RESUMED`.
3. **`TrashFlowTest`'s two tests** both failed with the trashed note's row simply never being
   found -- inserting+trashing a note *inside* the `@Test` body, after `TrashActivity` had already
   subscribed to `observeTrashed()`, raced Room's background invalidation-tracker requery against
   the very next (unwaited) Espresso assertion. **Fix**: seed the note *before* launching the
   Activity (`ActivityScenario.launch`, not `ActivityScenarioRule`) instead of chaining
   `resetStateRule`, mirroring `CalendarDayDialogShowsNotesTest`'s existing pattern for the exact
   same reason -- the Activity's very first LiveData emission already has the right data, so
   there's no async round-trip left to race. Applied the same fix to `FoldersFlowTest`'s rename
   and delete tests (same shape, hadn't failed yet but equally exposed).

This is a stronger, more general lesson than round 1's: **inserting/mutating the database from
inside a `@Test` body, after the Activity under test has already launched and subscribed to a
LiveData, is unsafe without an explicit wait** -- Espresso's idle-sync covers the main thread's
own pending work, not an arbitrary background-executor round trip (Room's invalidation tracker)
that hasn't been enqueued back onto main yet by the time the assertion runs. The existing
pre-session tests avoid this either by seeding before launch (`CalendarDayDialogShowsNotesTest`,
`SearchShowsAllNotesTest`) or by polling with a timeout after mutating a running Activity's data
(`SelectAllNotesTest`, `CalendarResumeRefreshesReminderTest`) -- this session's new tests now
follow the same two patterns throughout instead of a bare, unwaited assertion.
