# Testing

What's covered, how to run it, what's deliberately not covered yet, and the current state of
the CI `instrumented-tests` job. Read `DEV_LOG.md` for the history behind *why* specific tests
exist (most are regression guards for a real bug or user report, not coverage for its own sake).

## Running the suite

```bash
./gradlew testDebugUnitTest           # pure-logic unit tests (host JVM, no device needed)
./gradlew connectedDebugAndroidTest \  # Espresso/Room/instrumented tests (needs a connected
  -Pandroid.testInstrumentationRunnerArguments.notClass=com.example.notepad.AccessibilityChecksTest
                                        # device or emulator with animations disabled) --
                                        # excludes AccessibilityChecksTest, see its own doc for why
./gradlew lintDebug
```

Run `AccessibilityChecksTest` on its own, separately, when you actually want its coverage:
```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.notepad.AccessibilityChecksTest
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
| `BackupJsonRoundTripTest` *(extended, Fase 5)* | Plus: empty input, a very large (500k-char) note body, emoji/accented text, and an unknown future-version field being ignored rather than breaking the parse |
| **`SwipeToDeleteTest`** | Swipe left/right both trash the note and show Undo; Undo restores it; swiping is disabled during bulk selection |
| **`SettingsTogglesTest`** / **`SettingsAutoLockTest`** | Notifications toggle, widget range (3/7 days), and auto-lock picker all persist through their own prefs object and reflect back in the row text |
| **`DriveBackupHelperIntervalTest`** | `getIntervalHours`/`setIntervalHours` persistence, independent of the (sign-in-gated) UI row that picks it |
| **`NoteEditorSaveRaceTest`** | A note still saves exactly once even if the Activity is fully destroyed while the save coroutine is mid-flight (regression guard for the historical duplicate/lost-note bug) |
| **`DriveBackupHelperSyncRaceTest`** | Two concurrent `syncNow` calls, raced against a fake in-memory remote, never duplicate the same remote-only note -- the `Mutex` guarantee, finally verified instead of just reasoned about |
| **`TodayNotesWidgetRenderingTest`** | The widget's real per-row `RemoteViews` (via `RemoteViews.apply`, not a full `AppWidgetHost`): blank-title fallback (body preview / checklist label / **locked placeholder, never the real body**), a real title always wins, `hasStableIds`/count |
| **`NotificationTapOpensCalendarDayTest`** | An actual tap on the real, posted notification (via UiAutomator, through the system shade) opens today's day dialog with that note in it |
| **`NoteEditorRotationTest`** *(new, Fase 4)* | A config change (rotation) while editing a brand-new, never-yet-saved text or checklist note keeps what was typed and inserts exactly one row for it, never two (regression guard -- see Session 11's bug writeup below); recreating an *already-saved* note twice in a row still doesn't duplicate it either |
| **`DriveSyncNowIntegrationTest`** *(new, Fase 5)* | `DriveBackupHelper.syncNow` against a real Room database (not just `mergeByUuid`'s pure logic): first sync with no remote backup yet, a no-op resync doesn't duplicate anything, a newer remote copy actually updates the local row, a cross-device tombstone actually trashes the local row, and a remote failure (corrupted file / network error) propagates instead of silently dropping local data |
| **`DriveBackupWorkerTest`** *(new, Fase 5)* | `DriveBackupWorker.doWork()` via `TestListenableWorkerBuilder`: nobody signed in to Drive succeeds without attempting a sync (real, no seam needed); a successful sync succeeds; a failed sync asks WorkManager to retry |
| **`DriveBackupScheduleTest`** *(new, Fase 5)* | `schedulePeriodicBackup`/`triggerImmediateBackup` actually enqueue the unique work request they document enqueueing (via the real, test-mode `WorkManager`), including the network constraint and `triggerImmediateBackup`'s replace-not-stack behavior when called repeatedly |
| **`LockGuardedActivityTest`** *(new, Fase 6)* | Regression guard for the app-wide PIN lock bypass (see Session 13's bug writeup below): launching the exported `SearchActivity` directly while locked opens the lock screen instead of showing notes; an already-open `NoteEditorActivity` locks on the next `onResume` once the auto-lock window elapses, not just on a fresh launch |
| **`DatabaseEncryptionAtRestTest`** *(new, Fase 6)* | The real on-disk SQLCipher database file can't be opened or queried by a plain, unencrypted SQLite reader; a note's plaintext title/body never show up as raw bytes in that file (checked after a forced WAL checkpoint); the DB passphrase itself never appears in plain text in its own (`EncryptedSharedPreferences`-backed) prefs file |
| **`StrictModeMainThreadIOTest`** *(new, Fase 7)* | A normal `CalendarActivity` launch and opening today's day dialog does no disk-write or network I/O on the main thread (`penaltyDeath`, installed on the main thread itself) -- `detectDiskReads()` deliberately left out, see the test's own doc for why |
| **`SearchFtsAtScaleTest`** *(new, Fase 7)* | With 10k notes in the table: FTS5 search still goes through the `notes_fts` index (not a full scan of the real `notes` table, confirmed via `EXPLAIN QUERY PLAN`) and finds a match well within a generous soft time budget |

| **`AccessibilityChecksTest`** *(new, Fase 8 -- run separately, see its own doc and "Running the suite" above)* | Espresso's built-in `AccessibilityChecks` validator against the app's main flows: opening the calendar + today's day dialog, creating a text note, creating a checklist note |

### Accessibility fixes (Fase 8)

Three touch targets below the 48dp minimum, all found by `AccessibilityChecksTest` and fixed:
- `activity_note_editor.xml`'s `titleInput` (40dp -> 48dp) and `doneButton` (40dp x 40dp ->
  48dp x 48dp, with a little padding added so the icon doesn't look oversized at the larger
  bounds).
- `item_checklist_add.xml`'s `newItemText` (`wrap_content`, measuring ~22dp -> `minHeight="48dp"`
  added, keeping `wrap_content` itself so the text's own vertical position doesn't change).

Running `AccessibilityChecksTest` as part of the default full-suite command once (before
excluding it, see "Running the suite") surfaced 13 more failures across
`CalendarHolidaysTest`/`CalendarOpenSpecificDayTest`/`FoldersFlowTest`/`NotePasswordLockTest`/
`SettingsChangeAndRemovePinTest`/`SettingsSetPinDialogTest` -- real signal (`AccessibilityChecks`
has no `disable()`, so it keeps checking every other test that happens to run afterward in the
same process once enabled), but a much larger, separate audit than this one phase's scope. Not
triaged or fixed yet; see "Known gaps" below.

### LeakCanary (Fase 7)

Added as `debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")` -- no dedicated
test needed. It self-installs via its own `ContentProvider` and watches every destroyed Activity
in the background for the whole instrumented test run (every `ActivityScenario`-based test already
launches and destroys at least one), dumping and analyzing the heap if one doesn't get
garbage-collected. Reports to Logcat/a notification rather than failing the test outright --
LeakCanary's own instrumented-test-failure integration (`leakcanary-android-instrumentation` +
`FailTestOnLeakRunListener`) was tried first and crashed every single test on startup
(`IllegalArgumentException: Could not find extra class leakcanary.FailTestOnLeakRunListener`,
the exact listener class/wiring for that integration not being verified against this exact
version); reverted to the plain, self-installing artifact instead, which needs no such wiring.

### Coverage (Fase 9)

`./gradlew jacocoTestReport` combines `testDebugUnitTest` + `connectedDebugAndroidTest` coverage
into one report (`app/build/reports/jacoco/jacocoTestReport/html/index.html`) -- a lot of this
app's actual logic is only unit-tested (business logic) or only instrumented-tested (Activities,
Room-backed repositories), so either one alone understates real coverage badly. Baseline, this
session (line coverage): **57% overall** -- `security` 93%, `com.example.notepad` (Application)
92%, `data` 81%, `data.drive` 59%, `ui` 54%, `notification` 52%, `widget` 25% (RemoteViews/
AppWidgetProvider boilerplate, inherently harder to exercise from a test).

`./gradlew jacocoTestCoverageVerification` enforces **per-package floors**, not one blind global
number, set a little below this actual baseline so it's a real regression gate rather than an
aspirational target nobody could pass today: `data` 70%, `security` 85%, `data.drive` 55%
(split out from the rest of `data` since its real baseline is lower and the real Drive API
client classes are entirely untestable here, see "Known gaps"), `ui` 45%, `widget` 20%. Verified
both halves actually work: lowering `security`'s floor to 99% made the task fail with the exact
expected message, restoring it to 85% passed again.

### Mutation testing (Fase 9) -- evaluated, not added

Pitest (with the Kotlin/arcmutate plugin the mission names) was evaluated, not added. Two
reasons: first, without the usual package-registry access this session had for everything else,
the exact current Pitest/arcmutate Gradle plugin coordinates and their compatibility with this
project's Kotlin 1.9.24/AGP 8.5.2 pin couldn't be verified before wiring them in -- the LeakCanary
instrumentation-listener mistake earlier this same mission (Fase 7, `TESTING.md`'s own account)
is exactly the failure mode of guessing at an unfamiliar tool's exact integration details instead
of verifying them first, and mutation testing's build-time cost (it recompiles and reruns the
suite once per surviving mutant) makes a wrong guess here far more expensive to discover and
unwind than that one was. Second, Pitest's Kotlin support is well known to need careful tuning
(filtering out compiler-generated null-checks and `when`-exhaustiveness branches that inflate the
mutant count without testing anything real) to produce a trustworthy score rather than noise --
not a same-session addition alongside everything else in this phase. `RecurrenceUtils`,
`DriveBackupHelper.mergeByUuid`, `BrazilianHolidays`, and `escapeLikePattern` (the classes the
mission names) are already thoroughly edge-case-tested by hand (see Fase 2's own session); a
mutation score for them is future work, not blocking this phase's own CI/quality-infrastructure
goals.

### Flaky tests (Fase 9)

Known intermittent failures, confirmed by repeated observation across this mission's sessions
(not retried-until-green and declared fixed -- see the mission's own "retry only as diagnosis"
rule):

- **`NoteEditorRotationTest.rotating_afterAddingAChecklistItem_keepsIt_andDoesNotDuplicateTheNote`**
  -- IME/soft-keyboard timing on this emulator; `typeText` occasionally doesn't fully commit
  before the next action reads the field. Already split into separate `perform()` calls (Session
  11's fix for a *different*, related truncation symptom) -- reduced, didn't eliminate, the
  remaining flake. Observed again, confirmed real (not a one-off), while running the full suite
  for this phase's own coverage baseline.
- **`FoldersFlowTest.renamingAFolder_updatesItsRow`**/`creatingAFolder_showsUpInTheList`** --
  RecyclerView not always reflecting a just-applied change before the very next assertion reads
  it. Confirmed as transient by rerunning the class alone clean in the same session it failed
  (Fase 8's own account).

Both are pre-existing environmental/timing flakes, not introduced by any change in this mission --
left as documented known flakes rather than chased further with more `waitForIdleSync`/polling
tweaks that previous attempts already showed only partially help.

### CI structure (Fase 9)

`.github/workflows/ci.yml`, on top of the pre-existing `test-and-lint`/`instrumented-tests` jobs:

- `concurrency` group cancels a still-running workflow run for the same branch/PR when a newer
  commit supersedes it.
- AVD cached across runs (`actions/cache`, not just Gradle/SDK caching `setup-gradle` already
  does) -- a cold AVD boot every single run was the slowest part of that job.
- Logcat dumped and uploaded on instrumented-test failure (`adb logcat -d`), not just the test
  report.
- `jacocoTestReport`'s combined coverage report uploaded as its own artifact, always.
- **New, schedule-triggered jobs** (never run on every push/PR, only nightly/weekly/manual
  `workflow_dispatch`): `api-matrix` (API 33 + 34 on top of the PR-blocking job's API 30),
  `dependency-check` (OWASP Dependency-Check, weekly).
- Action versions already pinned to major-version tags (`@v4`/`@v2`) before this phase; left as
  is rather than pinning to exact commit SHAs, which is a much stricter convention most projects
  of this size don't adopt.

**Not verified end-to-end**: unlike everything else in this mission, this workflow file could not
actually be run anywhere in this environment (no GitHub Actions runner available locally) --
checked for valid YAML syntax only (via a quick Node/js-yaml parse). The `dependency-check/
Dependency-Check_Action@main` step in particular is the one piece guessed from memory without
being able to verify its exact current invocation/output format, the same category of risk the
LeakCanary mistake above fell into -- flagged here explicitly rather than presented as verified
working. Recommend a manual `workflow_dispatch` run (or a throwaway branch push) to confirm before
relying on any of this, especially that one job.

**Branch protection requiring these checks** is a GitHub repository setting
(Settings -> Branches -> branch protection rules), not something a workflow YAML file can
configure -- set it up at `github.com/Danielarceeno/anotai/settings/branches` if wanted,
requiring `test-and-lint` and `instrumented-tests` to pass before merging to `main`.

### Fase 7 tooling evaluation: Macrobenchmark / Baseline Profile

Per the mission's own instruction to evaluate before adding anything: **not added.** Macrobenchmark
needs its own Gradle module (a plain `androidTestImplementation` dependency isn't enough -- it
requires a dedicated `com.android.test` module with its own `androidx.benchmark` plugin and
target-app wiring) plus a non-minified-but-profileable build variant, and its results are only
meaningful compared across *several* runs on a calibrated reference device/emulator -- there's no
existing CI pipeline or reference device here to calibrate against yet, so a number from one local
run would be noise, not a baseline worth gating anything on. The lighter-weight items the same
Fase 7 section actually asks for (no main-thread I/O, FTS5-at-scale timing, leak detection) are
covered instead by `StrictModeMainThreadIOTest`/`SearchFtsAtScaleTest`/LeakCanary below, without
taking on a whole new benchmarking module for this project's current size.

### Known gaps (not covered, and why)

- **13 untriaged accessibility violations** across holiday/folder/note-password/PIN-dialog
  screens, surfaced once by accidentally leaving `AccessibilityChecks` enabled for a full-suite
  run (see the Fase 8 section above). Likely more small touch targets, same category as the three
  already fixed, but reviewing and fixing 13 findings across that many screens is a separate,
  larger piece of work than this one phase -- left for a dedicated accessibility pass.
- **Gradle Managed Devices compatibility matrix** (API 30/33/34, phone + tablet, 200% system
  font, small screen) and **Paparazzi/Roborazzi visual regression** -- both evaluated for Fase 8,
  both deferred: the matrix needs multiple system images this one local setup doesn't have
  pre-provisioned, and visual regression needs a first set of golden images plus a workflow for
  reviewing/approving intentional diffs, neither a quick addition alongside everything else in
  this phase. The e-ink/restrictive-OEM device case (Onyx Boox) stays a manual-only environment,
  never CI, per the mission's own instruction.
- **RTL layout and pseudolocalization (`en-XA`/`ar-XB`)** -- `android:supportsRtl="true"` is
  already set and no layout in this app does anything exotic enough (no absolute positioning,
  no manually-mirrored icons) to expect an RTL-specific bug, but that's an inference from reading
  the layouts, not something actually rendered and checked under RTL. Deferred rather than add a
  speculative test for a failure mode nothing concrete points to yet.
- **3 `HardcodedText` lint warnings**: the "R"/"G"/"B" channel labels in `dialog_color_picker.xml`.
  `values-pt` itself has zero `MissingTranslation`/`ExtraTranslation` findings (fully in sync with
  `values`) -- these three are single-letter color-channel abbreviations, commonly understood as-is
  across locales, of doubtful translation value; left as a known minor finding rather than adding
  three one-letter string resources for them.
- **No PIN attempt throttling.** `LockActivity`/`SecurityPrefs.checkPin` have no rate-limit or
  backoff on repeated wrong guesses -- the Fase 6 mission explicitly anticipated this might not
  exist and asked only to register it as a finding, not implement it unasked (a new feature, not
  a bug fix). A 4-6 digit PIN has only 10k-1M possibilities; PBKDF2's cost only slows an *offline*
  attack against an extracted hash (see `SecurityPrefs`'s own comment) and does nothing against
  repeated guesses through the UI itself.
- **No static-analysis/dependency tooling configured yet** (Lint `warningsAsErrors` for security
  categories, detekt/ktlint, OWASP Dependency-Check, gitleaks). Setting these up properly --
  picking rulesets that don't drown real findings in noise -- is its own piece of work, not a
  quick addition alongside the rest of Fase 6; deferred to a dedicated pass.
- **No release-build (R8-minified) instrumented smoke test yet.** Would catch shrink-related
  breaks in JSON serialization/Room/SQLCipher/reflection that a debug-build test run can't.
  Deferred alongside the tooling above.
- **`allowBackup="true"` with no explicit `dataExtractionRules`/`fullBackupContent`.** Reviewed,
  not changed: the only genuinely sensitive material is either the SQLCipher-encrypted DB itself
  (harmless as an opaque blob backed up to the cloud -- its passphrase lives in Android Keystore,
  which the OS already excludes from Auto Backup on its own) or a PBKDF2 hash+salt (`SecurityPrefs`
  PIN), never a reversible plaintext secret. Explicit exclusion rules would be better defense-in-
  depth than relying on that implicit Keystore behavior, but aren't fixing an active leak.
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
- **`DriveBackupWorker.syncHookForTesting`** (nullable suspend hook, Fase 5) -- lets
  `DriveBackupWorkerTest` force the sync-succeeds and sync-throws-so-retry paths without a real
  signed-in Drive account, which an instrumented test has no way to provide. Null (the real
  sign-in-check + `syncNow` path) outside that one test.

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

## How to add a new test (Fase 10)

Lessons from this mission's own test-strategy overhaul (Sessions 8-16 in `DEV_LOG.md`), as a
checklist rather than a story -- read the relevant session there for the full account if one of
these surprises you.

**Before writing it:**
- A test for a bug fix must go red, then green: write the test against the *unfixed* code first
  (or revert the fix temporarily) and confirm it fails with the expected message, *then* confirm
  the fix makes it pass. A test that was never seen failing proves nothing.
- If writing the test reveals a real app bug (not a bug in the test itself), stop and report it
  -- root cause, repro, suggested fix -- separately from applying the fix, even if the fix seems
  obvious. Don't fix production code just to make a test pass without understanding why it failed.
- A production-code change made only to let a test reach something otherwise untestable (a
  testability seam -- an injected clock/dispatcher, a `@VisibleForTesting` visibility bump, a
  nullable test-only hook) is fine, but list and justify it separately from any actual behavior
  fix in the same change -- don't bury one inside the other.

**Writing it (instrumented, `app/src/androidTest/`):**
- Seed the database *before* launching the Activity under test, not after -- mutating Room once
  an Activity has already subscribed to a `LiveData`/`Flow` from it races the invalidation
  tracker. If you must mutate after launch, poll with a timeout for the UI to reflect it instead
  of asserting immediately.
- Always `.use { }` an `ActivityScenario.launch(...)`, or let `ActivityScenarioRule` own it --
  a bare `launch()` leaks the Activity into later tests in the same process.
- `@Before`/`@After` run on the instrumentation thread, not the main thread. Any call that
  touches UI or main-thread-only state (`AppCompatDelegate.setDefaultNightMode`,
  `scenario.recreate()`, installing a `StrictMode.ThreadPolicy` meant for the app's own main
  thread) needs `InstrumentationRegistry.getInstrumentation().runOnMainSync { }`.
- A plain `@Before` runs *after* `ActivityScenarioRule`/`GrantPermissionRule` have already done
  their thing -- if state needs resetting (or a permission granted) *before* the Activity
  launches, use `RuleChain.outerRule(TestPrereqs.resetStateRule()).around(activityRule)`
  (or similar), not a `@Before` method.
- No `Thread.sleep`, ever. Use `IdlingResource`/`CountingIdlingResource` for a specific
  known-slow operation, `InstrumentationRegistry.getInstrumentation().waitForIdleSync()` for the
  main thread settling, a `pollUntil...(timeoutMs)`-style loop (see `NoteEditorRotationTest`'s
  `pollUntilResumed` for the pattern) for anything else, or `runBlocking` + `withTimeout` for
  suspend code.
- Chaining `typeText(...)` directly into `pressImeActionButton()` (or another `ViewAction`) in
  the *same* `perform()` call can fire the second action before the first one's last character
  actually committed -- split into separate `perform()` calls if you see characters silently
  missing.
- Any `*Prefs`-style singleton value a test changes (`SecurityPrefs.setAutoLockMinutes`, a theme,
  a feature toggle, ...) that isn't already covered by `TestPrereqs.resetAppState()` needs
  restoring in that test's own `@After` -- otherwise it leaks into every later test in the same
  instrumentation run. If more than one test is likely to touch it, add it to
  `TestPrereqs.resetAppState()` itself instead of repeating the restore everywhere.
- Notifications and `PendingIntent`s are real OS state that outlives a single test -- cancel them
  in the shared reset, not per-test.
- Animations must be off on the device/emulator for Espresso (`Settings > Developer options`, or
  `adb shell settings put global *_animation_scale 0`) -- `TestPrereqs`/CI already handle this,
  but a fresh local emulator needs it set once by hand.
- `adb shell input swipe` needs a short duration (~150ms) to register as a fling, not a slow drag.
- `AccessibilityChecks.enable()` has no `disable()` -- once called, it stays enabled for the rest
  of the test *process*, silently checking every other test class that happens to run afterward.
  Keep any test that enables it in its own dedicated class, excluded from the routine full-suite
  command (see "Running the suite" above), not mixed into the regular suite.
- `StrictMode.ThreadPolicy` is per-thread -- installing one from the test's own (instrumentation)
  thread checks nothing real; it has to be installed on the main thread the Activity actually
  runs on.
- `Context.getSharedPreferences()` does a real disk `stat()` on *every* call, not just the first
  -- inherent to the platform API, not a bug to chase with `StrictMode.detectDiskReads()`.

**Verifying it:**
- Run the new test alone first, then the full suite (`notClass=...AccessibilityChecksTest`, see
  "Running the suite") -- a test that passes alone but fails (or makes something *else* fail) in
  the full run almost always means it mutated shared state another test depends on.
- If the emulator itself starts failing unrelated tests mid-run (`cmd: Can't find service:
  package`, `INSTRUMENTATION_ABORTED: System has crashed`, a `HardwareRenderer`/
  `FinalizerWatchdogDaemon` timeout) -- that's the emulator corrupting under load, not your test:
  `./gradlew --stop`, kill `qemu-system`/`emulator`, `adb kill-server`/`start-server`, relaunch
  fresh, re-disable animations, then rerun.

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
