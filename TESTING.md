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
| `data/BrazilianHolidaysTest` | Fixed and Easter-derived (Carnaval, Sexta-feira Santa, Corpus Christi) holidays against the real public calendar for two different years; per-year memoization doesn't leak a stale date across years |
| `ui/NotesSortPrefsTest` | Each sort mode's ordering, pinned-always-first |
| `security/PasswordHasherTest` | PBKDF2 hashing (shared by the app PIN and per-note passwords) is deterministic for the same secret+salt, rejects a wrong secret, and hex round-trips |
| **`data/RecurrenceUtilsEdgeCasesTest`** | Leap years (Feb 29), day-31 monthly anchors in short months, a 5th-weekday-of-month anchor skipping a 4-occurrence month, the year boundary, three timezones, Auckland's DST spring-forward transition, and the (documented, not fixed) first-business-day-doesn't-know-about-holidays limitation |
| **`data/RecurrenceUtilsPropertyTest`** *(Kotest property)* | Daily/weekly/biweekly/every-weekday invariants against many randomized day offsets; no mode ever occurs before its own due date |
| **`data/BrazilianHolidaysMultiYearTest`** | 2024 (leap, Easter in March), 2027 (non-leap, Easter in March), 2028 (leap, Easter in April) -- against each year's real public Easter date, with the dependent moveable holidays computed in-test via `Calendar.add`, not hand-worked-out |
| **`data/drive/MergeByUuidPropertyTest`** *(Kotest property)* | Idempotency, never duplicates a uuid, never loses a note present on either side (unless tombstoned), newer-copy-always-wins, and the tombstone-vs-later-edit interaction -- against many randomized local/remote/tombstone combinations |
| **`data/EscapeLikePatternEdgeCasesTest`** | Quotes/emoji/accented characters pass through unescaped; 1-2 character terms unchanged; a lone wildcard still escaped; a very long string escaped in full |
| **`data/ChecklistItemEdgeCasesTest`** | Leading/trailing/consecutive blank lines, whitespace-only lines, a line with no recognized prefix (fallback to plain unchecked), mixed prefixed/unprefixed lines, full serialize-then-parse round trip |

## Instrumented tests (`app/src/androidTest/`)

| File | Covers |
|---|---|
| `TestPrereqs` | Not a test itself -- shared `resetAppState()` (clear PIN, unlock, wipe all DB tables, cancel any posted reminder notification) so every test starts clean regardless of what ran before it |
| `CalendarActivityTest` | Cold start of the launcher screen doesn't crash |
| `CalendarDayDialogShowsNotesTest` | Day dialog shows a plain, a recurring, and a pinned note together |
| `CalendarResumeRefreshesReminderTest` | Reminder notification refreshes on `onResume`, not only `onCreate` (regression: it used to not show up until the app was fully restarted) |
| **`CalendarHolidaysTest`** *(new)* | Settings-gated Brazilian holidays: off by default shows a plain day title; on shows a fixed (Natal) and a moveable (Carnaval) holiday's name; an ordinary day never gets one |
| **`CalendarOpenSpecificDayTest`** *(new)* | `CalendarActivity.EXTRA_OPEN_DAY_MILLIS` (what the notification/widget now use) opens the right day's dialog *and* jumps the grid to the right month |
| **`CalendarOverdueHighlightTest`** *(new)* | An overdue, not-done task's title is red in the day dialog; a completed or not-yet-due one isn't; the Settings toggle turns it off |
| `CreateEditNoteFlowTest` | Create a text note and a checklist note from the notes list, both show up |
| `ChecklistAddRowTest` | The checklist's trailing "add item" row stays usable for 3 items in a row (regression guard for the add-row redesign) |
| `SelectAllNotesTest` | Long-press → select all → bulk delete trashes every note |
| `SortNotesTest` | Picking a sort mode persists it |
| `SettingsThemeTest` | Picking a theme updates the Settings row and `ThemePrefs` |
| `SearchShowsAllNotesTest` | A blank query browses every note instead of showing nothing |
| `SearchFtsSubstringMatchTest` | Mid-word substring matches still work on the FTS5 index (not just whole-token matches) |
| **`SearchFtsEdgeCasesTest`** | Emoji/accented titles are searchable; unaccented query doesn't match an accented title; 1-2 character queries (below the trigram minimum) still return correct results; ASCII case-folding works but accented-letter case-folding doesn't (documented SQLite limitation, not a bug); a very long query doesn't crash |
| **`AppDatabaseMigrationTest`** | The real Room migration chain, 7 (hand-built schema, see below) through 15: data survives, every added column gets the right default for a pre-existing row, the trashed seed note gets a backfilled tombstone (the active one doesn't), the FTS5 index is populated from the migrated data, the `activeState` index exists |
| **`NotesFtsConsistencyTest`** | `notes_fts` stays in sync through insert/update/trash/restore/permanently-delete; `PRAGMA integrity_check` passes after a heavier sequence of mutations across 30 notes |
| `NoteRepositoryTombstoneSyncTest` | A tombstone synced in from another device's Drive backup actually trashes a still-active local note, against a real Room DB |
| `BackupJsonRoundTripTest` | Plain JSON export/import and the Drive tombstone-carrying format both round-trip, including a legacy (pre-tombstone) backup file |
| `DriveSyncActivityTest` | Signed-out state renders without crashing |
| `ReminderPrefsDefaultTest` | The (no-longer user-configurable) daily reminder time still defaults to 9:00am |
| `FoldersFlowTest` | Create / rename a folder; deleting one clears `folderId` on its notes instead of deleting them |
| `TrashFlowTest` | Restoring a trashed note reactivates it and clears its tombstone; "Delete forever" actually removes the row |
| `SecurityLockFlowTest` | Wrong PIN shows the error and stays locked; correct PIN unlocks (`LockState`) |
| **`ReminderNotifierOpensCalendarDayTest`** *(new, replaces `ReminderNotifierSingleNoteOpensNoteTest`)* | Tapping the daily reminder notification always opens the calendar's day dialog, never a note's own editor -- true whether one note or several are due (the behavior this guards was reverted this session) |
| `WidgetProvidersRegisteredTest` | All four widget providers (the resizable one + the new 1x1 / 2x2 list / 2x2 summary) are actually installed and parse a non-zero size from their `appwidget-provider` XML |
| **`SettingsHolidaysToggleTest`** *(new)* | The "Show Brazilian holidays" switch is off by default and persists through `CalendarPrefs` when flipped |
| **`SettingsOverdueHighlightToggleTest`** *(new)* | The "Highlight overdue tasks" switch is *on* by default (pre-existing behavior being made optional, not a new opt-in) and persists through `CalendarPrefs` when flipped |
| **`SettingsSetPinDialogTest`** | `showSetPinDialog`'s own validation: too-short PIN, mismatched confirmation, a valid PIN enables the lock and (checked against the raw `SharedPreferences` file) is genuinely salted -- same PIN set twice hashes differently |
| **`SettingsChangeAndRemovePinTest`** | Changing the PIN invalidates the old one and accepts the new one; turning the switch off clears `SecurityPrefs` and hides `changePinRow` |
| **`NoteFormattingTest`** | Bold/italic/underline round-trip through `Html.toHtml`/`fromHtml`; toggling the same style twice removes it; no-selection is a no-op; malformed HTML doesn't crash; `plainText()` strips tags only when formatted |
| **`NoteColorsTest`** | `isLight`'s true/false boundary on real swatches; `pressColor` lightens every channel and saturates at white; `resolveArgb` prefers a note's custom color over its palette index; `colorResFor` clamps out-of-range indices |
| **`ShareImageHelperTest`** | Text, checklist, long, and blank-title notes all render to a non-empty file with a valid, readable `FileProvider` `Uri` |
| **`SwipeToDeleteTest`** | Swipe left/right both trash the note and show Undo; Undo restores it; swiping is disabled during bulk selection |
| **`SettingsTogglesTest`** / **`SettingsAutoLockTest`** | Notifications toggle, widget range (3/7 days), and auto-lock picker all persist through their own prefs object and reflect back in the row text |
| **`DriveBackupHelperIntervalTest`** | `getIntervalHours`/`setIntervalHours` persistence, independent of the (sign-in-gated) UI row that picks it |
| **`NoteEditorSaveRaceTest`** | A note still saves exactly once even if the Activity is fully destroyed while the save coroutine is mid-flight (regression guard for the historical duplicate/lost-note bug) |
| **`DriveBackupHelperSyncRaceTest`** | Two concurrent `syncNow` calls, raced against a fake in-memory remote, never duplicate the same remote-only note -- the `Mutex` guarantee, finally verified instead of just reasoned about |
| **`TodayNotesWidgetRenderingTest`** | The widget's real per-row `RemoteViews` (via `RemoteViews.apply`, not a full `AppWidgetHost`): blank-title fallback (body preview / checklist label / **locked placeholder, never the real body**), a real title always wins, `hasStableIds`/count |
| **`NotificationTapOpensCalendarDayTest`** | An actual tap on the real, posted notification (via UiAutomator, through the system shade) opens today's day dialog with that note in it |
| **`NoteEditorRotationTest`** *(new, Fase 4)* | A config change (rotation) while editing a brand-new, never-yet-saved text or checklist note keeps what was typed and inserts exactly one row for it, never two (regression guard -- see Session 11's bug writeup below); recreating an *already-saved* note twice in a row still doesn't duplicate it either |

### Known gaps (not covered, and why)

- **Plaintext-to-SQLCipher migration** (`AppDatabase.migratePlaintextDatabaseIfNeeded`) -- testing
  it safely would need resetting `AppDatabase`'s own static singleton instance mid-process, which
  every *other* test in the same instrumentation run also depends on. Deferred rather than risk
  destabilizing the rest of the suite for one test (see `DEV_LOG.md` Session 9's own account of
  how much time a shared-static-state isolation bug cost elsewhere this same overhaul).
- **Historical schema snapshots for versions 7-14** -- `exportSchema` was `false` since this app's
  first version, so only v15's snapshot exists (generated once this session turned it on).
  `AppDatabaseMigrationTest`'s v7 starting schema is hand-built instead (see its own doc), cross-
  checked against `AppDatabase`'s migration history rather than an actual historical snapshot --
  solid, but not quite the same guarantee a real v7 snapshot would give. Future migrations (16
  onward) won't have this problem.
- **Drive backup/restore against a real Google account** -- `DriveSyncActivityTest` only covers
  the signed-out render. Sign-in requires a registered OAuth client for this machine's signing
  cert (see `DriveBackupHelper`'s doc); the merge *logic* itself is covered thoroughly by
  `DriveBackupHelperTest` + `NoteRepositoryTombstoneSyncTest`, and the sync `Mutex`'s concurrency
  guarantee by `DriveBackupHelperSyncRaceTest`, just not the live network calls or the
  sign-in-gated backup-frequency row itself (`DriveBackupHelperIntervalTest` covers that row's
  underlying persistence directly instead).
- **Widget per-row `fillInIntent`** (`CalendarActivity.EXTRA_OPEN_DAY_MILLIS`) and the **compact/
  full layout switch** (`TodayNotesWidgetProvider.isCompactSize`/`onAppWidgetOptionsChanged`) --
  `TodayNotesWidgetRenderingTest` renders real rows via `RemoteViews.apply`, but that can't read
  back an opaque `fillInIntent` (only the system's widget host combines it with a
  `PendingIntentTemplate` at click time) or report a widget size the system only tracks for an
  *actually-bound* widget id. Closing this needs a real `AppWidgetHost` placement (with a
  `BIND_APPWIDGET` permission grant via `adoptShellPermissionIdentity`), left for later as a
  bigger, more fragile undertaking.

### Testability seams added this session

Small production-code changes made only to let a test exercise something otherwise unreachable,
per this project's own rule that these must be listed and justified separately, not snuck in
alongside a behavior fix:

- **`NoteEditorActivity.unlockIdlingResource`** (`CountingIdlingResource`) -- lets
  `NotePasswordLockTest` wait deterministically for the off-main-thread password check instead of
  polling/sleeping for however long PBKDF2 happens to take. No-op unless a test registers it via
  `IdlingRegistry`.
- **`NoteEditorActivity.saveRaceHookForTesting`** (nullable suspend hook) -- lets
  `NoteEditorSaveRaceTest` force a window where the Activity is destroyed while `saveIfNeeded()`'s
  background save is still in flight. Null (no-op) outside that one test.
- **`DriveBackupHelper.DriveRemoteStore`** (interface) -- lets `DriveBackupHelperSyncRaceTest` race
  two `syncNow` calls against a fake, in-memory remote instead of a real Drive account.
  `account: GoogleSignInAccount` became nullable as part of this (only meaningful when
  `remoteStore` isn't overridden); every production call site already always passes one, so this
  is source-compatible.
- **`ShareImageHelper.renderToBitmap`/`saveToCache`** -- `private` to `@VisibleForTesting`, no
  behavior change, so `ShareImageHelperTest` can exercise them without going through `share()`'s
  real system share-sheet Intent (which Espresso has no way to dismiss).
- **`AppDatabase.MIGRATION_7_8` through `MIGRATION_14_15`** -- `private` to `@VisibleForTesting`,
  no behavior change, so `AppDatabaseMigrationTest` can run the real migration objects directly
  instead of duplicating their SQL into the test.
- **`AppDatabase`'s `exportSchema`** -- `false` to `true` (with `ksp { arg("room.schemaLocation",
  ...) }` and an `androidTest` asset source dir pointing at it). Not a seam exactly, but a real,
  previously-missing piece of migration-testing infrastructure: `MigrationTestHelper` needs a
  target version's schema snapshot to validate a migration's result against.

### Fase 2 tooling evaluation: Robolectric, MockK, kotlinx-coroutines-test, Turbine, Kotest

Per the Fase 2 mission's own instruction to evaluate before adding anything:

- **Robolectric: not added.** `AppDatabase` encrypts at rest with SQLCipher, which loads a native
  library (`SQLiteDatabase.loadLibs()`) -- that doesn't run under Robolectric's JVM shadow
  framework without custom shadows nobody's written for it. Since almost every `Activity` worth
  testing touches Room (directly or via `NoteRepository`), the actual gain would be limited to
  tests that don't need a database at all -- and those are already writable as plain instrumented
  tests (or, where Context isn't even needed, plain JUnit) without taking on a second "shadow
  Android" execution model to maintain alongside the real one.
- **MockK: not added.** Every fake needed so far (`DriveBackupHelper.DriveRemoteStore`) was a
  small, hand-written interface + implementation -- no real need for a mocking library yet, and
  introducing one only for a single interface isn't worth the added dependency.
- **kotlinx-coroutines-test / Turbine: not added.** No current test target is both suspend/Flow-
  based *and* independent of Room (which would hit the same SQLCipher problem as Robolectric
  above) -- `RecurrenceUtils`/`BrazilianHolidays`/`mergeByUuid`/`escapeLikePattern`/`ChecklistItem`
  are all plain synchronous functions.
- **Kotest (`kotest-property-jvm` only): added.** Pure JVM, no Android/SQLCipher conflict, and a
  genuine fit for `RecurrenceUtils.occursOn` and `DriveBackupHelper.mergeByUuid` -- both pure
  functions with real invariants (e.g. "weekly occurs iff the day offset is a multiple of 7",
  "the result never has two notes with the same uuid") worth checking against many randomized
  inputs instead of only hand-picked examples. Scoped to exactly those two files' tests, not
  adopted project-wide.

### A real bug found during Fase 2: `RecurrenceUtils.daysBetween()` and DST

While writing `RecurrenceUtilsEdgeCasesTest`'s timezone/DST cases: `daysBetween()` (backing
`REPEAT_WEEKLY`/`REPEAT_BIWEEKLY`) claimed to be "DST-safe (diffed at local noon)", but adding the
same fixed 12h offset to both sides of a subtraction is an algebraic no-op -- `(b+c)-(a+c)` always
equals `b-a`. When the anchor date is before a "spring forward" DST transition and the checked
date is after it, the real UTC-millisecond distance between their two local midnights is exactly
one hour short of a clean N×24h, so the old millis-based division truncated down and undercounted
by a day -- permanently, for every check past that transition, in any device timezone that
observes DST (not Brazil's own default; Brazil abolished DST in 2019). **Fixed** by diffing real
`java.time.LocalDate` values (`ChronoUnit.DAYS.between`) instead, which compare calendar dates
directly with no UTC-offset arithmetic to get wrong -- needed core library desugaring, since
`java.time` is only built into the platform from API 26 and `minSdk` here is 24. Confirmed as a
real regression test (reverting the fix made `RecurrenceUtilsEdgeCasesTest`'s DST case fail,
restoring it made it pass again).

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

### Round 3: one failure left, and it was round 2's own fix

Pushed round 2's fixes, watched the next run: down to a single failure, `SettingsThemeTest`,
the *exact same* `Activity.recreate()` / main-thread crash as rounds 1 and 2 -- meaning the
`SecurityLockFlowTest` cleanup fix from round 1 wasn't the only (or even the main) source of
leaked Activities by this point. It was round 2's own switch to `ActivityScenario.launch(...)` in
`FoldersFlowTest`/`TrashFlowTest`: a bare, unstored call to that function never gets `.close()`'d
(unlike `ActivityScenarioRule`, which closes its scenario automatically in its own teardown) --
so instead of fixing the leak, round 2 had *multiplied* it from one orphaned Activity
(`SecurityLockFlowTest`'s `CalendarActivity`) to potentially five (`FoldersActivity` x3,
`TrashActivity` x2), each one a live `AppCompatActivity` instance `AppCompatDelegate
.setDefaultNightMode()` would later try to `recreate()`.

**Fix**: wrapped every `ActivityScenario.launch(...)` call in both files in `.use { }` (Kotlin's
standard `Closeable` extension -- `ActivityScenario` implements `AutoCloseable`), so each one is
explicitly closed (which finishes and destroys the Activity) right after its own test body runs,
regardless of whether an assertion inside throws.

The real lesson underneath both round 2 and round 3: `ActivityScenario.launch(...)` and
`ActivityScenarioRule` are *not* interchangeable call-for-call -- the rule's automatic lifecycle
management is easy to take for granted until a plain `.launch()` call is substituted in without
also taking on the responsibility of closing it.

### Round 4: an infra flake, then the actual, much simpler root cause

The very next run failed before any test ran at all: `sdkmanager --install emulator` hit `Error
on ZipFile unknown archive` -- a corrupted/incomplete download on GitHub's runner, unrelated to
this project. `gh run rerun --failed` re-ran just that job; it failed again, same
`SettingsThemeTest` error as every round before.

At this point all three plausible leaked-Activity sources had already been closed off (rounds 1
and 3), yet the exact same failure persisted -- meaning the leaked-Activity theory, while each
individual fix was still correct and worth keeping, was never actually *this* failure's cause.
Downloading the full HTML test report (`gh run download -n instrumented-test-report`, which needed
the authenticated `gh` CLI -- a plain curl download of the artifact zip returns `401` even for a
public repo) finally showed the complete stack trace instead of the 2-line summary `--log-failed`
truncates to:

```
java.lang.IllegalStateException: Must be called from main thread
	at android.app.Activity.recreate(Activity.java:6346)
	at androidx.core.app.ActivityCompat.recreate(ActivityCompat.java:657)
	at androidx.appcompat.app.AppCompatDelegateImpl.updateAppConfiguration(...)
	...
	at androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.java:707)
	at com.example.notepad.ui.ThemePrefs.applyMode(ThemePrefs.kt:29)
	at com.example.notepad.ui.ThemePrefs.setMode(ThemePrefs.kt:21)
	at com.example.notepad.SettingsThemeTest.restoreSystemTheme(SettingsThemeTest.kt:41)
```

The actual root cause the whole time: `SettingsThemeTest`'s own `@After` method calls
`ThemePrefs.setMode()` directly -- and a plain JUnit `@After` runs on the **instrumentation**
thread, not the main/UI thread. `AppCompatDelegate.setDefaultNightMode()` can call `recreate()`
on any currently-active AppCompat activity (here, this same test's own `SettingsActivity`, since
`@After` methods run *before* `ActivityScenarioRule`'s own teardown closes it), and
`Activity.recreate()` asserts it's called on the main thread. This is a genuinely **pre-existing**
bug in a test this session didn't write, that had simply never been exercised against a real CI
run before (consistent with `DEV_LOG.md` Session 6's own admission that this suite was "not yet
verified against a real GitHub Actions run"). **Fix**: wrap the `@After` body in
`InstrumentationRegistry.getInstrumentation().runOnMainSync { ... }`.

**Lesson for next time**: `--log-failed`'s truncated 1-2 line stack traces were enough to point at
the right symptom (`Activity.recreate()`, "must be called from main thread") but actively
misleading about the cause -- they look identical whether the problem is a leaked Activity from
a *different* test or (as it turned out) the failing test's own cleanup code running off the main
thread. Pulling the full HTML report earlier would have found this in one round instead of four.

### Round 5 (Fase 1 of the test-strategy overhaul): the same two lessons, new tests

Adding ~15 new test classes in one pass surfaced the project's own two documented lessons again,
in new tests that hadn't been written yet when those lessons were first learned:

1. **`SettingsSetPinDialogTest`** failed only in a full-suite run, never alone: a plain `@Before`
   runs after `SettingsActivity.onCreate` had already read a PIN left over from
   `SettingsChangeAndRemovePinTest` (set directly via `SecurityPrefs`, never cleared) into
   `pinLockSwitch`'s initial checked state -- the switch launched already *on*, so the test's own
   click turned it *off* instead of opening the dialog, and every assertion after that failed for
   an unrelated reason. **Fix**: the same `RuleChain.outerRule(TestPrereqs.resetStateRule())`
   pattern `CalendarActivityTest` already uses, seeding/resetting before launch instead of after.
2. **`SwipeToDeleteTest`** had the identical shape: notes seeded from a plain `@Before`, which
   races `MainActivity`'s `PagingData` subscription the same way `CalendarDayDialogShowsNotesTest`'s
   own doc already warns about -- only actually lost the race under the load of ~90 other tests
   running first, never alone. Same `RuleChain` fix.

A third, unrelated infra lesson: `NoteEditorSaveRaceTest`'s first version used
`Espresso.pressBack()` to trigger `onPause()`. With no other Activity in that test's task, that
throws `NoActivityResumedException` and kills the whole app process -- and that crash corrupted
the emulator badly enough (`cmd: Can't find service: package` on the next install) that it needed
a full restart, which in turn cascaded into unrelated failures in whatever ran right after on the
next attempt. **Fix**: drive the lifecycle through `ActivityScenario.moveToState()` directly
(`onPause()` always calls `saveIfNeeded()` regardless of why) instead of a real back-stack change.
**Lesson**: a test that can crash the *app process*, not just fail an assertion, can take the rest
of a full-suite run down with it -- worth specifically avoiding, not just tolerating as "a test
failure like any other."

A fourth, purely environmental issue: the emulator died outright (process gone, `adb devices`
empty) once more while chasing `NotificationTapOpensCalendarDayTest`'s own full-suite-only
flakiness (residual notification-shade/window-focus state briefly affecting whichever test ran
shortly after it) -- a plain relaunch + reboot resolved it, nothing code-related. Memory pressure
on the host machine (a separate, very large `connectedDebugAndroidTest` run got killed by the
harness itself for exactly this) was the likely trigger both times; stopping the Gradle daemon
(`./gradlew --stop`) between heavy runs helped.
