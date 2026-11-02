# Dev Log

Running history of work done on Anotaí with Claude Code: what changed, bugs found
(and how), and open items. Read this before starting new work so context from
past sessions isn't lost. Newest entries at the top.

---

## Session 18 — Remove LeakCanary (2026-11-02)

Installing the debug build to a physical device showed an extra "Leaks" launcher icon alongside
Anotaí's own -- LeakCanary's self-installing artifact (Fase 7, see `TESTING.md`) registers its own
activity-alias for this inside the app's package. Removed at the user's request:

- **`app/build.gradle.kts`**: dropped the `debugImplementation("com.squareup.leakcanary:
  leakcanary-android:2.14")` line and its explanatory comment.
- **`TESTING.md`**: marked the "LeakCanary (Fase 7)" section as removed, explained why, and added
  "no automatic memory-leak detection" to "Known gaps."
- Rebuilt (`./gradlew assembleDebug`) and reinstalled on the device; confirmed no leak-related
  package remains (`adb shell pm list packages | grep -i leak` returns nothing).

Trade-off worth knowing: automatic leak detection during instrumented tests is gone along with the
icon. If it's ever wanted back without the extra launcher icon, `leakcanary-object-watcher-android`
alone (no UI) is the option to evaluate first.

---

## Session 17 — Test strategy overhaul, Phase 10: documentation and process (2026-11-02)

Final phase of the Sessions 8-17 test-strategy overhaul. Pure documentation, no code changes.

- **`TESTING.md`**: new "How to add a new test" section, consolidating every lesson from
  Sessions 8-16 (seed-before-launch, `.use { }`, main-thread-only calls needing `runOnMainSync`,
  `RuleChain` ordering, no `Thread.sleep`, the `typeText`+`pressImeActionButton` truncation trap,
  `*Prefs` state leaking across tests, `AccessibilityChecks`'s missing `disable()`,
  `StrictMode.ThreadPolicy` being per-thread, `getSharedPreferences()`'s inherent disk stat, and
  the emulator-corruption recovery steps) into one reference checklist instead of it only living
  scattered across past session writeups.
- **`docs/TEST_STRATEGY.md`** (new): the testing pyramid this project actually follows (instrumented-
  heavy, not a textbook unit-heavy split, because the real risk lives in Room-on-SQLCipher and
  Activity lifecycle, which unit tests can't exercise honestly) and the Definition of Done for a
  change here (regression test proven red-before-green, testability seams listed separately, full
  suite green, no new silent flaky test, coverage floors held).
- **`docs/MANUAL_TEST_CHECKLIST.md`** (new): what this project doesn't try to automate at all --
  real widget placement/resizing, real two-device Google Drive sync, real biometric hardware,
  aggressive-battery-OEM behavior, upgrading over an old installed version, the e-ink/restrictive-
  OEM manual-only environment (per the mission's own instruction), and the Android 13+
  notification-permission *prompt itself* (every instrumented test grants it up front via
  `GrantPermissionRule` specifically to avoid blocking Espresso, so the real first-run prompt flow
  has zero automated coverage).
- **`.github/PULL_REQUEST_TEMPLATE.md`** (new): a testing checklist mirroring the Definition of
  Done above, plus a spot to call out any testability seam explicitly.
- **`README.md`**'s Testing section updated to point at all of the above instead of just
  describing `app/src/test`/`app/src/androidTest` on their own.

### Where the whole mission (Sessions 8-17) landed

Real bugs found and fixed, each with a regression test proven red-before-green: a duplicate-note-
on-rotation bug (Session 11), an app-wide PIN lock bypass including a complete external-app
exploit via the exported `SearchActivity` (Session 13), 3 accessibility touch targets below 48dp
(Session 15), and (from the earlier Sessions 8-10, before this summary's own detail) a DST
day-loss bug in `RecurrenceUtils` and the historical duplicate-save-on-double-trigger bug. One
finding reported and explicitly *not* fixed as a bug (`Context.getSharedPreferences()`'s own
disk-stat behavior, inherent to the platform). Coverage baseline established at 57% overall with
per-package floors enforced. CI extended with coverage upload, AVD caching, a nightly API matrix,
and a weekly dependency scan -- the one piece of this whole mission that couldn't be verified
end-to-end in this environment (no GitHub Actions runner available locally), flagged honestly
rather than presented as tested.

---

## Session 14 — Test strategy overhaul, Phase 7: performance (2026-11-01)

Covered the feasible Fase 7 items; evaluated and deferred Macrobenchmark/Baseline Profile (own
Gradle module + calibrated reference device needed, not a fit for this project's current CI-less
setup -- see `TESTING.md`'s tooling-evaluation note).

- **`StrictModeMainThreadIOTest`** (new) + a permanent `StrictMode` check in
  `NotepadApplication.onCreate()` (debug builds only, via `ApplicationInfo.FLAG_DEBUGGABLE` since
  `buildFeatures.buildConfig` isn't enabled here, `penaltyLog` not `penaltyDeath` so it's a
  diagnostic aid, not a crash risk, for every debug/manual session). Found a real StrictMode hit
  on the first try: `Context.getSharedPreferences()` does its own internal `File.exists()` disk
  stat on *every* call, not just the first, flagged right at `SecurityPrefs.isPinEnabled` in
  `CalendarActivity.onCreate`'s very first line. Reported to the user before deciding what to do
  about it, per this mission's own rule -- concluded (with the user) that this is inherent to the
  platform API itself and essentially universal across Android apps using `SharedPreferences` the
  normal way, not a bug this app introduced; fixing it for real would mean every `*Prefs` object
  caching its own `SharedPreferences` instance, a genuine but separate refactor. Dropped
  `detectDiskReads()` from both the permanent check and the dedicated test (kept
  `detectDiskWrites()`/`detectNetwork()`, which are far less expected on the main thread and
  would still catch something actually worth fixing); the dedicated test now passes clean with a
  hard `penaltyDeath`.
- **LeakCanary**, `debugImplementation` only -- see `TESTING.md` for the one wrong turn
  (`leakcanary-android-instrumentation` + a guessed listener class that doesn't exist in this
  version, crashing every test on startup) before landing on the plain self-installing artifact.
- **`SearchFtsAtScaleTest`** (new): 10k notes (bulk-inserted inside one transaction, not through
  `NoteRepository.insert` one at a time), `EXPLAIN QUERY PLAN` confirming the FTS5 trigram index
  is actually used (not a full `notes` table scan), and a generous soft time budget (not a hard
  CI gate -- emulator performance varies too much run to run) that passed with room to spare.
  First version of the query-plan test asserted the wrong thing (`"SCAN notes_fts VIRTUAL TABLE
  INDEX ..."` is SQLite's normal way of describing *any* virtual-table access, not a red flag) --
  a mistake in the test's own logic, not a finding about the app; fixed to check for the real
  signal instead (the `notes` table itself being a rowid lookup, not a full scan).

No production bugs found this phase beyond the StrictMode finding above (resolved as "not a bug,
inherent to the platform" rather than something to fix). Full suite green (137 tests).

---

## Session 15 — Test strategy overhaul, Phase 8: accessibility, i18n (2026-11-02)

Covered the feasible Fase 8 items; evaluated and deferred the Gradle Managed Devices compatibility
matrix and Paparazzi/Roborazzi visual regression (both need infra this project doesn't have
provisioned yet -- see `TESTING.md`'s known gaps).

- **`AccessibilityChecksTest`** (new): Espresso's built-in `AccessibilityChecks` validator against
  the app's main flows. Found and fixed 3 real touch targets below the 48dp minimum:
  `activity_note_editor.xml`'s `titleInput` (40dp) and `doneButton` (40dp x 40dp), and
  `item_checklist_add.xml`'s `newItemText` (`wrap_content`, ~22dp) -- `minHeight="48dp"` added to
  the latter rather than a fixed height, so the text's own vertical position doesn't shift.
- **Real structural lesson, costly one**: `AccessibilityChecks.enable()` has no way to disable
  itself once called. First attempt wired it into `CalendarActivityTest`/`CreateEditNoteFlowTest`
  directly via a shared `AccessibilityChecksRule` -- running the resulting full suite once
  surfaced 13 *new* failures across completely unrelated screens (holidays dialog, folders,
  note-password unlock, PIN dialogs) that happened to run afterward in the same instrumentation
  process and had simply never been audited for this. Real signal, but a much bigger separate
  audit than this one phase's scope (left as a documented known gap, not fixed blind). Reverted
  those two classes back to plain, and moved the check into one new, explicitly separate
  `AccessibilityChecksTest` class instead -- documented in `TESTING.md`'s "Running the suite" to
  be excluded from the routine full-suite command (via `-Pandroid.testInstrumentationRunnerArguments
  .notClass=...`) and run on its own when its coverage is actually wanted.
- **i18n**: `lintDebug` found zero `MissingTranslation`/`ExtraTranslation` -- `values-pt` is
  already fully in sync with `values`. Only 3 `HardcodedText` warnings, all single-letter
  color-channel labels ("R"/"G"/"B") in the custom color picker -- documented, not worth three
  one-letter string resources for.

Full suite green afterward (137 tests, `AccessibilityChecksTest` excluded as documented) -- one
isolated `FoldersFlowTest` failure on the first run turned out to be an unrelated, pre-existing
timing flake (RecyclerView not yet reflecting a rename), confirmed by rerunning that class alone
clean; not something this phase's changes caused.

---

## Session 16 — Test strategy overhaul, Phase 9: coverage, CI, flaky tests (2026-11-02)

Covered JaCoCo coverage + per-package floors, consolidated the known flaky-test list, and
extended the CI workflow. Evaluated and deferred Pitest mutation testing (see `TESTING.md`'s own
account -- the same "verify an unfamiliar tool's exact integration before wiring it in" lesson
Fase 7's LeakCanary mistake already cost this mission once, applied proactively this time instead
of after breaking something).

- **JaCoCo**: `jacocoTestReport` (combined unit + instrumented coverage) and
  `jacocoTestCoverageVerification` (per-package floors, not one blind global number) added to
  `app/build.gradle.kts`. Baseline: 57% overall line coverage -- `security` 93%, `data` 81%,
  `ui` 54%, `widget` 25% (lowest, inherently hard to unit-test RemoteViews/AppWidgetProvider
  boilerplate). Verified the verification task actually enforces its floors, not just reports
  numbers: temporarily raising `security`'s floor to 99% failed with the exact expected message;
  restoring it to 85% passed again.
- **Flaky tests consolidated**: `NoteEditorRotationTest`'s checklist case (IME timing, observed
  again this session, confirming it's a real recurring flake, not a one-off) and
  `FoldersFlowTest`'s rename/create cases (RecyclerView update timing, confirmed transient by
  rerunning the class alone clean). Documented in `TESTING.md` rather than chased further --
  previous attempts already showed diminishing returns on more polling/waitForIdleSync tweaks.
- **CI** (`.github/workflows/ci.yml`): `concurrency` cancel-in-progress group, AVD caching,
  logcat-on-failure upload, coverage report upload, and two new schedule-only jobs (`api-matrix`
  for API 33/34, `dependency-check` weekly). **Could not be run anywhere in this environment** (no
  GitHub Actions runner available locally) -- checked for valid YAML syntax only. Flagged the
  `dependency-check/Dependency-Check_Action` step specifically as unverified (guessed from memory,
  not confirmed against current docs) rather than presented as tested; recommend a manual
  `workflow_dispatch` run before relying on it. Branch protection requiring these checks is a
  GitHub repo setting, not something this file can configure -- documented where to set it.

Full local suite still green throughout (the `debug.enableUnitTestCoverage`/
`enableAndroidTestCoverage` flags JaCoCo needs didn't change any test's behavior or timing
meaningfully).

---

## Session 13 — Test strategy overhaul, Phase 6: app-wide PIN lock bypass (2026-11-01)

Started Fase 6 (security review) with the manifest: every `exported` component, `allowBackup`,
PIN storage, and SQLCipher. Found a real, significant bug while reviewing the PIN lock itself.

### Bug found and fixed this session
**The app's PIN lock was only ever checked in `CalendarActivity.onCreate`/`onResume`.** Every
other screen (`NoteEditorActivity`, `TrashActivity`, `FoldersActivity`, `SettingsActivity`,
`DriveSyncActivity`, `SearchActivity`, `PickedNoteWidgetConfigActivity`) trusted that the user
could only have reached it by passing through the calendar's lock gate first. Two concrete holes
in that assumption:

1. **Auto-lock did nothing outside the calendar screen.** Sitting in any other screen while the
   configured auto-lock window elapsed never re-prompted for the PIN on returning to it -- only
   `CalendarActivity.onResume` checked `LockState.isLocked`, and coming back to a *different*
   screen (the app was merely backgrounded, not closed, so the Activity instance survives and
   gets `onResume`, not a fresh `onCreate`) never passes through there at all.
2. **`SearchActivity` -- `exported="true"`, required so the system search box can reach it --
   had no check whatsoever.** Any other app on the device could launch it directly via a plain
   explicit `Intent` (no permission required for an exported Activity) and see the full notes
   list -- titles and body previews, respecting only per-note passwords, never the app PIN. A
   complete bypass of the lock from entirely outside this app. `PickedNoteWidgetConfigActivity`
   (also exported, for the widget host) had the identical exposure for the same reason.

Reported to the user before fixing, per this mission's own rule; fix authorized, proceeded with a
regression test proving red-before/green-after.

**Fix**: a new shared base class, `LockGuardedActivity`, with one protected helper
(`redirectToLockScreenIfLocked()`) that every affected Activity now calls as the very first thing
in both `onCreate` (right after `super.onCreate`) and `onResume`. `CalendarActivity` itself was
left untouched -- it already had its own, slightly different version of this same check (carrying
a pending launcher-shortcut action through the lock screen, which none of the others need) and is
already covered by its own tests.

`LockGuardedActivityTest` proves both holes: launching `SearchActivity` directly while locked now
opens `LockActivity` instead of showing notes; an already-open `NoteEditorActivity` now locks on
the next `onResume` once the auto-lock window elapses, not only on a fresh launch. Verified:
reverting the fix turned both cases red with exactly the expected failure message; restoring it
turned both green again.

One test-isolation bug surfaced by the new test itself (not a production bug): the test sets
`SecurityPrefs.setAutoLockMinutes(context, 0)` to make `LockState.isLocked()` deterministically
true without `Thread.sleep`, but nothing reset it afterward -- it leaked into every later test in
the same instrumentation run that sets a PIN, sending them to the lock screen instead of whatever
they were actually testing (8 unrelated tests failed on the first full-suite run because of this).
Fixed in the test's own `@After` *and* in `TestPrereqs.resetAppState()` (shared by nearly every
instrumented test), since `autoLockMinutes` was never part of that reset before and any future
test touching it would have hit the same leak.

Full suite green after both fixes (131 tests).

Continued Fase 6 with `DatabaseEncryptionAtRestTest`: the real on-disk SQLCipher file rejects a
plain `android.database.sqlite.SQLiteDatabase` reader (confirmed by forcing an actual query, not
just `openDatabase()` -- that alone can succeed regardless, format validation is lazy and only
happens on first real read), a note's plaintext title/body never appear as raw bytes in the file
(checked after a forced `PRAGMA wal_checkpoint(FULL)`, since WAL batches writes before they land
in the main file), and the DB passphrase itself never appears in plain text in its own
`EncryptedSharedPreferences`-backed prefs file. All passed first try -- no bug here.

Also chased down a flaky `LockGuardedActivityTest` assertion: `startActivity`/`finish()` called
from inside `onResume()` don't preempt that same `onResume()` call -- the Activity that called
them still fully reaches RESUMED first, and the system only processes the actual transition to
the new Activity afterward. A single immediate check right after `waitForIdleSync()` could still
race that gap (the exact same class of timing issue `SecurityLockFlowTest` already documented for
its own, similar check) -- failed intermittently in full-suite runs, passed every time run alone
or in a small group, which is what gave it away. Fixed with a short poll-with-timeout instead of
one check (no `Thread.sleep`).

Also recovered from two separate emulator/system crashes mid-run this session (`cmd: Can't find
service: package` once, a `FinalizerWatchdogDaemon` `HardwareRenderer` timeout another time, plus
one background Gradle run killed outright by the host for low system memory) -- same recovery
each time: `./gradlew --stop`, kill `qemu-system`/`emulator`, `adb kill-server`/`start-server`,
fresh relaunch, re-disable animations. Full suite green after recovery (134 tests, 0 failures).

---

## Session 12 — Test strategy overhaul, Phase 5: backup, Drive and sync (2026-11-01)

Covered the Fase 5 mission items against `DriveBackupHelper`/`DriveBackupWorker`:

- `DriveSyncNowIntegrationTest` (new): `syncNow` end-to-end against a real Room database, using
  the same `DriveRemoteStore` fake pattern `DriveBackupHelperSyncRaceTest` already established --
  first sync, idempotent resync, a newer remote copy actually landing as a local update, a
  cross-device tombstone actually trashing a local row, and a remote-side failure propagating
  instead of silently losing local data. `mergeByUuid`'s own pure-function logic was already
  thoroughly unit-tested (`DriveBackupHelperTest`); this is the integration layer on top of it.
- `DriveBackupWorkerTest` (new) + a small testability seam, `DriveBackupWorker
  .syncHookForTesting` (nullable suspend hook, null outside tests): lets the test force the
  sync-succeeds and sync-throws-so-retry `Result` branches without a real signed-in Drive account,
  which an instrumented test has no way to provide. The "nobody signed in" branch needed no seam
  at all -- the test device genuinely isn't signed in.
- `DriveBackupScheduleTest` (new): `schedulePeriodicBackup`/`triggerImmediateBackup` actually
  enqueue what they document, via `WorkManagerTestInitHelper`'s test-mode `WorkManager` (added
  `androidx.work:work-testing` as an `androidTestImplementation` dependency).
- `BackupJsonRoundTripTest` extended: empty input, a 500k-char note body, emoji/accented text, and
  an unknown future-version JSON field being ignored rather than breaking the parse.

No bugs found this phase -- `syncNow`'s existing Mutex/merge/tombstone/error-propagation behavior
held up under all of the above. Left out, per the mission's own scoping: a real-Drive-account
manual test roadmap (belongs in `docs/MANUAL_TEST_CHECKLIST.md`, not CI) -- deferred to Fase 10's
documentation pass rather than writing it in isolation now.

Also recovered from an emulator/package-manager corruption mid-run (`cmd: Can't find service:
package`, same failure mode as prior sessions) after an unrelated test
(`SettingsSetPinDialogTest`) triggered an `INSTRUMENTATION_ABORTED: System has crashed` --
full `qemu-system`/`emulator` kill, `adb kill-server`/`start-server`, fresh relaunch, re-disable
animations. Full suite green after recovery (129 tests, 0 failures).

---

## Session 11 — Test strategy overhaul, Phase 4: editor rotation bug (2026-11-01)

Started Fase 4 (the editor/security-critical flows) by writing `NoteEditorRotationTest` to check
that a config change (screen rotation) while editing a note doesn't lose or duplicate it.

### Bug found and fixed this session
**Rotating a brand-new, never-yet-saved note duplicated it in the database.**

Root cause: `NoteEditorActivity.saveIfNeeded()` (called from `onPause()`) inserts a new `Note` row
asynchronously on `NotepadApplication.applicationScope`, then sets this Activity instance's own
`note` field once that insert completes. A config change tears down and recreates the Activity
*before* that background insert necessarily finishes. The recreated instance reads the same
original `Intent` (still missing `EXTRA_NOTE_ID`, since the first instance never got the chance to
rewrite it), so it has no way to tell "this note already has a row in flight" from "this is a
genuinely new note" — it treats it as new again, and the next `onPause()` inserts a second row.
Confirmed by the test's own assertion failing with `found 2 rows expected:<1> but was:<2>`
(reproduced for both a text note and a checklist note). Bonus related finding, same root cause:
for a checklist note specifically, items added but not yet identified by a row id were also lost
from the UI entirely on `recreate()` (no automatic RecyclerView/adapter-data restoration, unlike
`EditText`'s own automatic view-state restoration, which is why the *typed text* fields merely
*looked* unaffected even though the underlying row still duplicated).

Reported to the user before fixing, per this mission's own rule; fix authorized, proceeded with a
regression test proving red-before/green-after.

**Fix**: `Note.uuid` is a stable, client-generated id already set (by a default constructor
parameter) at `Note` construction time -- synchronously, unlike the row `id`, which only exists
after the insert completes. Generating that uuid explicitly, synchronously, in `saveIfNeeded()`
(instead of leaving it to `Note`'s own default generator buried inside the async coroutine) lets
`NoteEditorActivity` carry it through `onSaveInstanceState()`/`onCreate(savedInstanceState)` as
`pendingNewNoteUuid`, so a recreated instance can look the row up by uuid (`NoteDao.getByUuid`,
new) instead of treating the note as new again.

That alone wasn't quite enough: `onCreate`'s own uuid lookup can itself race ahead of the *first*
instance's still-in-flight insert and come back empty. First attempt at closing that race (always
generating a fresh uuid per save attempt) actually made it worse -- it just meant a *second*,
differently-uuid'd row got inserted once the recovered instance eventually saved too. The real fix
has two parts: (1) reuse the already-pending uuid across save attempts instead of regenerating a
new one each time, and (2) move the "does a row for this uuid already exist?" check from
`onCreate` time into `saveIfNeeded()`'s own coroutine, right before deciding insert vs. update --
the last word on that question has to come from right next to the write itself, not from a lookup
that happened earlier and may already be stale.

Verified: with the fix reverted, `NoteEditorRotationTest`'s text-note and checklist-note cases both
fail with the exact duplicate-row assertion (`found 2 rows`); restored, all three cases
(text, checklist, and recreating an already-saved note twice) pass repeatedly.

Two unrelated test-only flakes surfaced and fixed along the way (not production bugs): the soft
keyboard's own auto-capitalization of the first typed letter made a case-sensitive text assertion
flaky (now compared case-insensitively), and chaining `typeText(...)` directly into
`pressImeActionButton()` in one `perform()` call sometimes fired the IME action before the last
character committed, truncating a checklist item (split into two separate `perform()` calls,
matching the pattern `ChecklistAddRowTest`/`CreateEditNoteFlowTest` already use).

Full suite (unit + instrumented) green after the fix.

---

## Session 10 — Test strategy overhaul, Phase 3: database and migrations (2026-11-01)

Continuation of Sessions 8-9's mission: real Room migration tests, FTS5 consistency through the
notes lifecycle, and a database integrity check.

**Foundational change**: `AppDatabase.exportSchema` had been `false` since the app's first
version, so there were no historical schema JSON snapshots to test migrations against. Turned it
on (`ksp { arg("room.schemaLocation", ...) }`, wired into `androidTest` as a readable asset) --
this generates a real snapshot per version from now on. No snapshot exists yet for versions 7-14
though, so `AppDatabaseMigrationTest`'s "before" database (version 7) is built by hand with plain
SQL instead, derived mechanically from `Note`'s current columns minus everything each later
migration adds -- documented as a known, intentional limitation rather than a silently-assumed
historical fact.

**A real infra surprise**: the plain Android framework SQLite build on the test device has no
FTS5 module compiled in at all ("no such module: fts5") -- only SQLCipher's own bundled SQLite
(what `AppDatabase` actually uses in production) does. `MigrationTestHelper` defaults to the
plain framework factory, which silently can't run `MIGRATION_12_13` (creates `notes_fts`) at all.
Switched the test to SQLCipher's own `SupportFactory`/`SQLiteDatabase`, matching production.

**What got added**: `AppDatabaseMigrationTest` runs the real 7->15 migration chain (the actual
registered `Migration` objects, made `@VisibleForTesting` for this -- not duplicated SQL) against
seed data, and checks data survival, correct defaults for every added column, the tombstone
backfill (trashed note gets one, active one doesn't), the FTS index being populated from the
migrated data, and the `activeState` index existing. `NotesFtsConsistencyTest` covers the FTS
index staying in sync through insert/update/trash/restore/permanently-delete, plus
`PRAGMA integrity_check` after a heavier 30-note mutation sequence.

**Deferred, and why**: testing the plaintext-to-SQLCipher migration itself
(`migratePlaintextDatabaseIfNeeded`) would need resetting `AppDatabase`'s static singleton
instance mid-process -- every other test in the same instrumentation run shares that same
singleton, and this overhaul has already spent real time this session chasing exactly this class
of shared-static-state isolation bug (Session 8's Round 5). Not worth the risk for one test;
documented as a known gap in `TESTING.md` instead of silently skipped.

**Suite**: 86 unit + 109 instrumented, both green.

## Session 9 — Test strategy overhaul, Phase 2: the unit-logic layer (2026-11-01)

Continuation of Session 8's mission, moving from Phase 1 (instrumented coverage gaps) to Phase 2
(stronger pure-logic unit tests): `RecurrenceUtils`, `BrazilianHolidays`, `mergeByUuid`,
`escapeLikePattern`/FTS search, `ChecklistItem`.

**Tooling decision** (full reasoning in `TESTING.md`): evaluated Robolectric, MockK,
kotlinx-coroutines-test, and Turbine before adding anything, per the mission's own instruction.
Added only `kotest-property-jvm`, scoped to `RecurrenceUtils`/`mergeByUuid`'s property tests.
Skipped the rest -- Robolectric specifically because `AppDatabase`'s SQLCipher native library
doesn't run under its JVM shadow framework, which would have left almost every Activity test
(anything touching Room) still needing a real device/emulator anyway.

**A real bug found and fixed**: `RecurrenceUtils.daysBetween()` (backing `REPEAT_WEEKLY`/
`REPEAT_BIWEEKLY`) silently lost a day's count across a DST "spring forward" transition --
its own comment claimed to be DST-safe via a "diffed at local noon" adjustment that was actually
a mathematical no-op (adding the same constant to both sides of a subtraction changes nothing).
Any weekly/biweekly reminder would silently skip one occurrence right after the first DST
transition following its due date, permanently, in any device timezone that observes DST (not
Brazil's own default). Fixed by diffing real `java.time.LocalDate` values instead of raw millis,
which needed core library desugaring added (`minSdk` 24, `java.time` only native from API 26).
Confirmed as a genuine regression test: reverting the fix made the new DST test fail, restoring
it made it pass again -- full detail and the repro in `TESTING.md`.

**What got added**: edge-case and property-based tests for all five Phase 2 target areas --
leap years / short months / a 5th-weekday-of-month anchor / the year boundary / three timezones
/ Auckland's real 2026 DST transition for `RecurrenceUtils`; three more years (leap, Easter in
March and April) against real public Easter dates for `BrazilianHolidays`; idempotency / never-
duplicates-a-uuid / never-loses-a-newer-note / tombstone-vs-edit as Kotest properties for
`mergeByUuid`; quotes/emoji/accents/short-terms/long-strings for `escapeLikePattern` and the real
FTS5 index; blank-line/no-prefix/round-trip edge cases for `ChecklistItem`. Full file list in
`TESTING.md`'s unit/instrumented tables.

**A second real-world limitation found, documented rather than "fixed"**: SQLite's default `LIKE`
case-folding is ASCII-only -- searching `"AÇÚCAR"` (uppercased, accented) doesn't match a title
spelled `"Açúcar"`, even though the all-lowercase, correctly-accented query does. Not a bug in
this app's own code (no ICU extension is loaded); pinned as a test documenting current behavior
rather than silently assumed to work, so a future change to the search collation is a deliberate
one, not an accidental regression nobody notices.

**Suite**: 86 unit (`testDebugUnitTest`) + 103 instrumented (`connectedDebugAndroidTest`), both
green at the end -- one unrelated, pre-existing flake in `SelectAllNotesTest` (not touched this
session) during a full-suite run, confirmed passing again in isolation.

## Session 8 — Test strategy overhaul, Phase 1: closing TESTING.md's "Known gaps" (2026-11-01)

Also includes an earlier, separate batch of feature work this session that got committed first,
in its own commit, before the test-strategy mission started: per-note password lock, a free
custom RGB/hex note color, a single-note picker home-screen widget, and a calendar-dot color fix.
See that commit's own message for details; the rest of this entry is about the test work.

Mission: work through `TESTING.md`'s "Known gaps" list (previously 10 items), one small commit
per item, full suite green after each, never editing production code just to make a test pass
(any seam listed and justified separately), every regression test proven to actually fail against
the bug/race it guards before being trusted.

**What got added**: ~15 new instrumented test classes plus one new unit test
(`PasswordHasherTest`, actually from the earlier feature-work commit) covering: the PIN dialog's
own validation + change/remove flow, `NoteFormatting`/`NoteColors`, `ShareImageHelper`,
swipe-to-delete, the remaining Settings rows (notifications/widget range/auto-lock), a real
regression test for `NoteEditorActivity.saveIfNeeded()`'s `applicationScope` fix, a real race test
for `DriveBackupHelper.syncNow`'s `Mutex` (abstracted the remote half behind a new
`DriveRemoteStore` interface for exactly this), the widget's actual rendered `RemoteViews` rows
(via `RemoteViews.apply`, not a full `AppWidgetHost`), and an actual tap on the real posted
notification via UiAutomator. Full details and the testability seams this needed are in
`TESTING.md` itself now, not duplicated here.

**Real bugs found, all in this session's own new tests, not the app**:
- `NotePasswordLockTest` (written last session): the soft keyboard, left open after `typeText`,
  silently covered `unlockNoteButton` and ate the next click -- Espresso reports `click()` as
  "performed" either way, no feedback that a different window intercepted the touch. Confirmed via
  logcat: not even a `Log.d` placed directly inside the button's own `OnClickListener` fired.
- `SettingsSetPinDialogTest` and `SwipeToDeleteTest` both broke only in a full-suite run (never
  alone): a plain `@Before` runs after the Activity's `onCreate` already read/subscribed to state
  a *different* test (running earlier in the same process) had left behind. Same
  `RuleChain.outerRule(TestPrereqs.resetStateRule())` fix the project had already learned once.
- `NoteEditorSaveRaceTest`'s first version used `Espresso.pressBack()` with no other Activity in
  its task -- throws `NoActivityResumedException` and kills the whole app process, which corrupted
  the emulator badly enough to need a full restart and cascaded into unrelated failures in
  whatever ran right after on the next attempt. Fixed by driving the lifecycle through
  `ActivityScenario.moveToState()` directly instead.
- `NotificationTapOpensCalendarDayTest` left enough residual notification-shade/window-focus
  state that a different test shortly after it intermittently failed in two separate full-suite
  runs. Fixed with a more thorough teardown (explicit shade collapse + double back-press to
  unwind what the test itself opened, on top of `pressHome`).

**Infra, not code**: the emulator died outright (process gone) once while chasing the last bug
above, separately from the crash the save-race test caused -- host memory pressure under
back-to-back full-suite runs was the likely common cause both times (the harness itself killed
one such run for exactly this); stopping the Gradle daemon between heavy runs and a plain
emulator relaunch were enough to recover, nothing code-related.

**Suite**: 59 unit (`testDebugUnitTest`) + 96 instrumented (`connectedDebugAndroidTest`), both
green, confirmed on a clean, fully-booted emulator (`medium_phone`, API 36) at the end.

**Left out of Phase 1, and why** (both already documented as known gaps in `TESTING.md`, not
silently dropped): the widget's per-row `fillInIntent` and its compact/full layout switch both
need a real `AppWidgetHost` placement (with a `BIND_APPWIDGET` permission grant) to test, which
`RemoteViews.apply` alone can't reach -- bigger, more fragile, left for later. Backup frequency's
own Settings row is gated behind a real signed-in Drive account (a separate, pre-existing known
gap); its underlying persistence is covered directly instead, without going through that UI.

## Session 7 — Brazilian holidays, overdue cue, notification/widget navigation (2026-10-31)

User-driven session with three iterations on the same feature, each refined after direct
feedback -- notable mainly for how much the overdue cue changed shape between asks.

### Brazilian national holidays (new, opt-in)
`BrazilianHolidays` (new, `data/`): fixed-date holidays plus the Easter-derived moveable ones
(Carnaval, Sexta-feira Santa, Corpus Christi) computed per year via the standard Anonymous
Gregorian algorithm, not a hardcoded table -- correct for any year without maintenance. Results
memoized per year (`cachedYear`/`cachedHolidays`) since the calendar grid's 42 cells would
otherwise redo the Easter arithmetic up to 42 times for the same year.

Gated behind a new `CalendarPrefs.isHolidaysEnabled` (off by default) and a matching Settings
switch ("Show Brazilian holidays"). When on: a holiday's day-number is tinted green
(`calendar_holiday_number`, wins over the plain Saturday/Sunday red/blue tint) on the grid, and
its name is appended to the day dialog's title (e.g. "Fri, December 25 — Natal").

### Overdue-task cue: three iterations
1. **First pass**: a day strictly before today with an incomplete reminder tinted its whole
   calendar-grid cell with a pale red wash (`calendar_overdue_background`, a translucent overlay).
2. **User feedback — "não queria que ficasse vermelhinho a célula, mas sim só as letras quando
   clico na tarefa"**: reverted the cell tint entirely. Moved the cue into `DayNotesDialog`
   instead -- only the overdue task's own title *text* turns red (`task_overdue_text`), computed
   locally per row (`isOverdue(dayMillis, isDoneToday)`) rather than carried on `CalendarDay`.
   Also made it live: checking the "done" toggle inside the same dialog clears the red
   immediately (`applyOverdueColor` called again from the checkbox's listener), not just on next
   open.
2a. **Follow-up — "o vermelho... igual dos dias de sáb"**: `task_overdue_text` changed from its
    own shade (`#D32F2F`) to the exact same red already used for Saturday day-numbers
    (`calendar_saturday_number`, `#E57373`), for visual consistency rather than a third red in
    the app.
3. **User feedback — wanted it as a Settings option too**: added
   `CalendarPrefs.isOverdueHighlightEnabled` (on by default, unlike every other toggle in this
   file -- it's gating pre-existing behavior, not introducing new) and a "Highlight overdue
   tasks" switch. `DayNotesDialog.isOverdue` now short-circuits to `false` when the context's
   `CalendarPrefs` value is off.

### Notification and widget: stop deep-linking into the note editor
**User feedback — "quero que não vá para dentro da notificação e sim para mesma coisa quando
clico no dia e aparece a task"**: tapping the daily reminder notification used to jump straight
into a single due note's own editor when exactly one note was due (added in an earlier session,
now reverted); the home-screen widget's per-note taps always did. Both now open the same day
dialog tapping that day on the calendar shows:
- `ReminderNotifier.showNotification` dropped its `notes.size == 1` branch -- always builds a
  `CalendarActivity` + `EXTRA_OPEN_TODAY` intent now, regardless of how many notes are due.
- `CalendarActivity` gained a new, more general `EXTRA_OPEN_DAY_MILLIS` (a specific start-of-day
  millis) alongside the existing `EXTRA_OPEN_TODAY`, plus `navigateCalendarToMonthOf` so the
  grid itself jumps to the right month first, not just the dialog showing the right notes over
  whatever month happened to already be displayed.
- The widget's `TodayNotesRemoteViewsFactory` (in `TodayNotesRemoteViewsService.kt`) now tracks
  each row's own matched due day (`WidgetEntry(note, dayStart)`, not just the `Note`), since a
  widget configured to show 3/7 days can have rows for different days -- each one's tap now opens
  *its own* day's dialog via `EXTRA_OPEN_DAY_MILLIS`, not always "today", and not the note's
  editor.

### Test-suite ripple from the behavior reversion
`ReminderNotifierSingleNoteOpensNoteTest` (asserted the old single-note-opens-editor behavior)
replaced with `ReminderNotifierOpensCalendarDayTest` (asserts the new always-calendar behavior).
`CalendarOverdueHighlightTest` rewritten from checking a grid cell's background color to checking
`DayNotesDialog` title text color via a small custom Espresso matcher
(`TextView.currentTextColor`), since the cue moved there. New `CalendarOpenSpecificDayTest`
covers `EXTRA_OPEN_DAY_MILLIS` landing on the right day *and* the right month. New
`CalendarHolidaysTest`, `SettingsHolidaysToggleTest`, `SettingsOverdueHighlightToggleTest` cover
the two new Settings switches end to end.

Fixing these surfaced a real cross-test notification leak, same class of bug `TESTING.md`'s round
2 already documented for a different pair of tests: a test that inserts a note due today and
resumes `CalendarActivity` (whose `onResume` always runs an immediate reminder check) posts a
*real* system notification as a side effect, which can still be in flight when the next test
class's `@Before` runs. Rather than patch each new test individually, moved the fix into
`TestPrereqs.resetAppState()` itself (cancels `ReminderNotifier.NOTIFICATION_ID` on every reset,
not just in the reminder-specific test classes) -- the shared root-cause fix this class of flake
has needed since round 2.

### Verification
- `BrazilianHolidays`' fixed and Easter-derived dates checked against the *actual* public
  calendar for 2025/2026 (not re-derived from the same algorithm under test), including a
  cross-year check that the per-year memoization doesn't leak a stale Easter date.
- Local emulator (`medium_phone` AVD, started fresh this session since `adb`/`emulator` weren't
  on `PATH` -- the real SDK was under `AppData/Local/Android/Sdk`, not the project's bundled
  `cmdline-tools`): **44 instrumented tests, 0 failures**, run with the user's physical phone
  temporarily `adb disconnect`'d first so the automated suite's `TestPrereqs.resetAppState()`
  (wipes all DB tables) wouldn't touch their real data.
- Manual pass on both the emulator and the user's physical phone (connected over Wi-Fi ADB,
  `adb connect <ip>:<port>`): overdue task's title red in the day dialog with the cell itself
  unchanged, color turning back to normal live on checking it done, holiday name/tint showing
  with the Settings toggle on and not with it off, both new Settings switches reflecting their
  defaults (holidays off, overdue-highlight on).

### Known gaps
- No automated test actually simulates tapping a *posted* notification or a *placed* widget row
  (both fundamentally hard to drive from Espresso -- see `TESTING.md`'s existing widget-rendering
  gap) -- the `PendingIntent`-target checks (`ReminderNotifierOpensCalendarDayTest`) and the
  `EXTRA_OPEN_DAY_MILLIS` navigation test (`CalendarOpenSpecificDayTest`) cover the intent-wiring
  side; the widget's own `fillInIntent` construction has no test at all (same pre-existing gap
  `WidgetProvidersRegisteredTest`'s doc already calls out for widget rendering generally).
- `BrazilianHolidays` only covers *national* holidays -- no state/municipal ones (would need a
  per-state setting, not asked for).

### Files touched
`data/BrazilianHolidays.kt` (new), `ui/CalendarPrefs.kt`, `ui/CalendarActivity.kt`,
`ui/DayNotesDialog.kt`, `ui/SettingsActivity.kt`, `res/layout/activity_settings.xml`,
`res/values(-pt)/strings.xml`, `res/values/colors.xml`, `notification/ReminderNotifier.kt`,
`widget/TodayNotesWidgetProvider.kt`, `widget/TodayNotesRemoteViewsService.kt`.
Tests: `app/src/test/java/com/example/notepad/data/BrazilianHolidaysTest.kt` (new),
`app/src/androidTest/java/com/example/notepad/{CalendarHolidaysTest,CalendarOpenSpecificDayTest,
CalendarOverdueHighlightTest,ReminderNotifierOpensCalendarDayTest,SettingsHolidaysToggleTest,
SettingsOverdueHighlightToggleTest,TestPrereqs}.kt` (first six new/rewritten, `TestPrereqs`
modified); `ReminderNotifierSingleNoteOpensNoteTest.kt` deleted.

---

## Session 6 — Drive sync rework, performance pass, checklist redesign (2026-10-19 to 2026-10-20)

Long, multi-part session driven by a mix of user bug reports and UX requests, not a single
theme. Grouped below by topic, roughly chronological within each group.

### Calendar visual polish
- **Weekday header (D/S) tinted** to match the existing Saturday/Sunday day-number colors
  (`calendar_saturday_number`/`calendar_sunday_number`) — previously only the numbers were
  tinted, not the `DOM`/`SÁB` header letters.
- **Day-cell press animation** (scale down + overshoot bounce back, plus a light haptic) on
  tap. Reacting to `ACTION_DOWN` immediately turned out to also flash on a month-swipe's
  starting cell (`CalendarGridLayout` doesn't intercept until past touch-slop) — fixed by
  delaying the press-down by `TAP_PRESS_DELAY_MS` (100ms, same idea as
  `ViewConfiguration.getTapTimeout`) so a swipe-start has time to cancel it first; a fast
  deliberate tap still gets feedback, just compressed into one beat instead of skipped.
- **Day dialog**: chevron icons (matching month nav) instead of double-arrow media icons,
  "Add" → "Create" with a filled pill-shaped button instead of a plain text button, a darker
  dialog background in dark mode (`#262626` → `#3A3A3A`, previously too close to the near-black
  app background to read as its own surface). **Rounded corners took two attempts** — the first
  (`shape_dialog_background.xml` as the content view's background) was invisible, because
  `AlertDialog`'s own window paints its own default square panel *behind* whatever `setView()`
  provides; fixed by setting the dialog window's background to a transparent `ColorDrawable`.

### Drive backup: notes duplicating (two separate root causes)
User report: "quando sincronizei pela primeira vez, ainda duplicou" / "ainda está duplicando".
Two distinct bugs, found and fixed in sequence:

1. **`mergeByUuid`'s `remoteOnly` list wasn't deduped by uuid.** If the remote Drive backup file
   itself had two entries for the same uuid (plausible from a backup written before this
   merge/tombstone logic existed at all), restoring onto a device with neither copy let both
   through as separate new notes. Fix: `.distinctBy { it.uuid }` on that filter.
   - A one-time "Remove duplicate notes" Settings tool (`NoteRepository.deduplicateByUuid`,
     keeps the most-recently-modified copy per uuid, trashes the rest through the normal
     tombstone-recording `delete` path) was added so the user could clean up notes already
     duplicated by this bug, used once, then **removed** per their follow-up request — the
     actual `mergeByUuid` fix stayed.
2. **Concurrent syncs racing each other.** After adding "sync immediately after creating a
   note" (see below), two sync runs landing close together — e.g. the periodic worker firing
   right as a just-created note's immediate sync kicks off, or either racing the sync screen's
   own manual button — could each call `restore` before either had called `backup` with its
   result, so both independently decided the same remote-only note needed inserting locally:
   two local rows, same uuid, genuinely duplicated. `mergeByUuid`'s `distinctBy` only guards a
   duplicate *within* one read, not two overlapping reads/writes. Fix: consolidated the three
   call sites (`DriveSyncActivity`'s manual backup/restore, the first-sign-in backup, and
   `DriveBackupWorker`'s periodic/immediate runs) onto one new `DriveBackupHelper.syncNow`,
   wrapped in a single process-wide `Mutex` — only one sync actually runs at a time.

### New dedicated Drive Sync screen
Replaced `SettingsActivity`'s old single row (opened a plain `PopupMenu`: back up now / restore
/ sign out) with `DriveSyncActivity`: account name/email up front, "last sync" as a relative
time and a live "notes synced" count (`DriveBackupHelper` now persists both in its own prefs,
updated in `backup()`), one big filled "Sync" button, everything else (restore, backup
frequency, sign out) in the toolbar's overflow menu. `SettingsActivity`'s Drive row just
navigates there now.

**Creating a new note** (not every edit) also triggers an immediate one-off background sync
(`DriveBackupHelper.triggerImmediateBackup`, a `WorkManager` one-time request reusing
`DriveBackupWorker`) instead of only relying on the periodic schedule, which could leave a
freshly-created note unsynced for hours.

### Settings reorganization
- Split the old single "Appearance" section (which had grown to six unrelated rows: theme,
  vertical-swipe, notifications, reminder time, folders, trash) into Appearance / Calendar /
  Notifications / Organization, matching how Widget/Backup/Security already had their own
  headers.
- **Removed the daily-reminder-time picker row** per explicit user request ("só tirar a opção
  de escolher o horário") — the daily 9am catch-all check itself still runs (nothing removed
  from `ReminderPrefs`'s default or `ReminderScheduler`), it's just no longer
  user-configurable from the UI. Anyone who'd already picked a custom time keeps it.
- Backup frequency picker moved into `DriveSyncActivity`'s overflow menu (see above).

### Performance pass (6 items from a code-reading audit, not profiler output)
1. **`AppDatabase` warm-up off the main thread**: its first call does real synchronous I/O
   (SQLCipher native lib load, unwrapping the Keystore-backed passphrase, opening/migrating the
   SQLite file) on whichever thread calls it — every Activity called it directly in `onCreate`,
   landing that work on the main thread during the launcher Activity's cold start. Now
   triggered from a background coroutine in `NotepadApplication.onCreate` instead.
2. **`ChecklistItem.countDone`**: a single-pass count replacing `parse(body)` (full
   `List<ChecklistItem>` allocation) just to show "n/m done" on every list-row bind — was
   re-running on every scroll-triggered rebind and Paging3 refresh.
3. **Attached photos downsampled** to 1600px on the long edge and re-encoded (JPEG, quality 85)
   before being stored, instead of a byte-for-byte copy of the picker's original file (a modern
   phone photo can be several MB). EXIF rotation is baked into the pixels first
   (`decodeAndOrientBitmap`), since re-encoding to a fresh JPEG would otherwise silently drop
   that tag and the photo could come out sideways.
4. **`RemindersCache`**: a 2-second-TTL memo of `getAllReminders()`. A single note mutation
   fans out to independent consumers (`ReminderNotifier.checkAndNotify`, the widget's
   `RemoteViewsFactory`) that each re-scanned the same "every active note with a due date"
   query within milliseconds of each other.
5. **New `Index(["activeState"])`** on `Note` — the existing `(folderId, activeState)` compound
   index doesn't help a query filtering on `activeState` alone (the main list's "every folder"
   case, the trash screen), which fell back to a full table scan. `MIGRATION_13_14` adds it for
   existing installs; verified the migration's raw SQL matches Room's own generated `CREATE
   INDEX` exactly by inspecting the KSP-generated `AppDatabase_Impl`.
6. **Investigated trimming the Google Drive/Sign-In dependency stack** (`grpc-context`,
   `opencensus-api`/`-contrib-http-util`, pulled in transitively, inflating the dependency
   graph with code this app's Drive calls never use) for APK size. **Measured, didn't just
   assume**: built `assembleRelease` before (17,167,070 bytes) and after excluding those
   artifacts — identical byte count. R8 already tree-shakes the unused code regardless of
   whether the dependency is declared; excluding it only broke the release build (R8 couldn't
   resolve classes `OpenCensusUtils` still references, needing `-dontwarn` rules to work around
   something that bought nothing). Reverted; no safe win available there.

### Custom recurrence rules
Added two repetition types, requested with examples like "toda sexta-feira da 2ª semana" /
"primeiro dia útil do mês":
- `REPEAT_MONTHLY_NTH_WEEKDAY`: both the weekday and which occurrence of it (1st..5th) are
  derived from the note's own due date, not stored as separate fields — creating the note on
  "the 2nd Friday" is itself what encodes the rule.
- `REPEAT_FIRST_BUSINESS_DAY_OF_MONTH`: the first Mon–Fri day of every month (no holiday
  calendar, same "business day" = "not a weekend" definition `REPEAT_EVERY_WEEKDAY` already
  uses).

Both appended after `REPEAT_YEARLY` (values 7/8), not inserted earlier in `Note`'s existing
list — `repetition` is a plain int already persisted for every existing note, so renumbering
would silently change what they mean.

### Checklist redesign
- **Icons modernized**: the drag handle and the editor's folder-row icon both reused
  `android.R.drawable.ic_menu_sort_by_size` (a "sort" icon, wrong for a folder row and only
  vaguely grip-like for a drag handle) — replaced with purpose-made vectors (6-dot grip, a
  real folder icon, a filled add-circle for the add-item icon).
- **"Add item" row restructured**, per direct comparison against a reference screenshot: it
  used to be a separate, differently-styled input bar fixed below the checklist
  `RecyclerView` — structurally different from the item rows above it, which is why an
  icon-only pass still read as "the same as before". Now it's the `RecyclerView`'s own
  trailing row (`ChecklistAdapter.VIEW_TYPE_ADD`), styled identically to a real item, just
  empty with a muted "+ Add item" placeholder. Submitting it (IME "Next") inserts a real row
  just above it; the add-row stays last, cleared, ready for the next item, so adding several
  items in one sitting is one continuous flow. `ItemTouchHelper.canDropOver` keeps a dragged
  real item from being dropped onto/past this row.

### Test/CI work
Across this session: FTS5 search regression tests, a tombstone-sync integration test against a
real Room DB (not just the pure merge function), a calendar-day-dialog end-to-end test, a
backup-JSON round-trip test (previously zero coverage for `BackupHelper`'s serialization), and
a `connectedDebugAndroidTest` CI job (`reactivecircus/android-emulator-runner`) — **not yet
verified against a real GitHub Actions run**, only locally.

Final pass this session (emulator only, no physical device): **50 unit tests + 21 instrumented
tests, all passing.** Three new regression tests added for the areas above with the thinnest
coverage:
- `ChecklistAddRowTest` — adds three items in a row through the trailing add-row, guarding
  against it not being recreated (or not staying trailing) after each submit.
- `DriveSyncActivityTest` — smoke test for the new screen's signed-out state (no coverage
  existed for it at all).
- `ReminderPrefsDefaultTest` — locks in the 9am default now that it's no longer
  user-configurable, so a future change to it wouldn't be caught by anything else.

### Known gaps
- The Drive-sync `Mutex` fix has no automated concurrency test — this project has no mocking
  library and `GoogleSignInAccount`/network calls aren't easily fakeable with plain JUnit, so
  it's verified by code reasoning only. Worth a real-world confirmation from the user that
  duplicates don't reappear after normal day-to-day use (create notes, let auto-sync and the
  periodic schedule both fire near each other).
- Espresso tests reliably fail on the user's own physical phone (not the emulator) with
  "Animations or transitions are enabled on the target device" — a pre-existing, documented
  environment limitation (some OEM builds block the `adb shell settings put global` that would
  fix it), not a regression from this session. All verification this session was done against
  the emulator only, per explicit request.
- The CI workflow change (instrumented-tests job) hasn't actually run on GitHub Actions yet —
  written to match the standard `reactivecircus/android-emulator-runner` setup, but only
  confirmed working locally.

### Files touched (non-exhaustive, grouped by topic)
Calendar/dialog: `ui/CalendarActivity.kt`, `ui/DayNotesDialog.kt`,
`res/layout/dialog_day_notes.xml`, `res/drawable/shape_dialog_background.xml` (new),
`res/values-night/colors.xml`.
Drive: `data/drive/DriveBackupHelper.kt`, `data/drive/DriveBackupWorker.kt`,
`ui/DriveSyncActivity.kt` (new), `res/layout/activity_drive_sync.xml` (new),
`res/menu/menu_drive_sync.xml` (new), `res/drawable/ic_google_logo.xml` (new),
`ui/SettingsActivity.kt`, `ui/NoteEditorActivity.kt`.
Settings layout: `res/layout/activity_settings.xml`.
Performance: `NotepadApplication.kt`, `data/ChecklistItem.kt`, `data/RemindersCache.kt` (new),
`data/NoteRepository.kt`, `data/Note.kt`, `data/AppDatabase.kt` (migration 13→14),
`widget/TodayNotesRemoteViewsService.kt`.
Recurrence: `data/Note.kt`, `data/RecurrenceUtils.kt`,
`res/values(-pt)/strings.xml` (`repetition_options`).
Checklist: `ui/ChecklistAdapter.kt`, `ui/NoteEditorActivity.kt`,
`res/layout/activity_note_editor.xml`, `res/layout/item_checklist.xml`,
`res/layout/item_checklist_add.xml` (new), `res/drawable/ic_drag_handle.xml` (new),
`res/drawable/ic_add_circle.xml` (new), `res/drawable/ic_folder.xml` (new).
Tests: `app/src/test/java/com/example/notepad/data/{ChecklistItemTest,RemindersCacheTest,
RecurrenceUtilsTest,DriveBackupHelperTest}.kt`, `app/src/androidTest/java/com/example/notepad/
{ChecklistAddRowTest,DriveSyncActivityTest,ReminderPrefsDefaultTest,
CalendarDayDialogShowsNotesTest,NoteRepositoryTombstoneSyncTest,BackupJsonRoundTripTest,
SearchFtsSubstringMatchTest,CreateEditNoteFlowTest}.kt`, `.github/workflows/ci.yml`.

---

## Session 5 — Calendar date picker + configurable swipe axis (2026-10-16)

User request: tapping the month label should jump straight to a chosen day;
month-change swipe should be left/right only by default, with up/down swipe
as an opt-in Settings toggle that also gets its own (vertical) animation.

### Added
- **Tapping "October 2026"** opens a plain `DatePickerDialog` (defaulting to
  whatever day is currently selected) and, on confirmation, both moves the
  grid to that month and opens that day's dialog in one step -- a shortcut
  past however many swipes/taps it'd otherwise take to reach a date months
  away. `monthLabel` got a ripple background + `contentDescription` so it
  reads as tappable.
- **`CalendarPrefs`** (new, same `SharedPreferences` file as the existing
  battery-prompt flag): `isVerticalSwipeEnabled`/`setVerticalSwipeEnabled`,
  off by default.
- **`CalendarGridLayout`** now gates vertical swipes behind a
  `verticalSwipeEnabled` property instead of always treating up/down the
  same as left/right: with it off, a vertical drag isn't even intercepted
  (falls through as a no-op, doesn't eat the touch for nothing), and
  `onSwipe` now reports which axis triggered it (`(next: Boolean, vertical:
  Boolean) -> Unit`) so the caller can animate along the same axis the user
  actually swiped.
- **Settings** gained a "Swipe up/down to change month" switch (Appearance
  section, above Reminder notifications) wired to `CalendarPrefs`.
  `CalendarActivity` reads it on both `onCreate` and `onResume` (the latter
  so flipping it in Settings and coming straight back applies immediately).
- `changeMonth`'s slide/fade animation now takes a `vertical` flag and
  animates `translationY` instead of `translationX` when the vertical axis
  triggered it; prev/next buttons and the date-picker jump stay horizontal
  (buttons always `vertical = false`; the picker jump has no swipe
  direction to animate along at all, so it's an instant re-render, same as
  it already was before this session).

### Verification
- Manual pass on the emulator: date picker opens at the right default day,
  jumping to a month 2+ away both re-renders the grid *and* opens that day's
  dialog correctly, no crash.
- Horizontal swipe still works both directions post-change.
- Vertical swipe correctly does nothing with the setting off (confirmed by
  swiping and seeing the month unchanged).
- Vertical swipe correctly changes the month both directions once the
  Settings toggle is turned on (confirmed after returning to the calendar,
  exercising the new `onResume` re-read).
- **One test-script false alarm worth recording**: the first vertical-swipe
  attempt (300ms duration via `adb shell input swipe`) appeared to do
  nothing and briefly looked like a bug. It wasn't -- a 150ms swipe over the
  same distance worked immediately. `adb`'s synthetic swipe needs to be fast
  enough to clear `GestureDetector`'s fling velocity threshold; a slower
  "swipe" can clear the distance threshold but not the velocity one and so
  never fires `onFling` at all. Noted here (and in the testing-notes section
  below) so this isn't re-diagnosed as an app bug next time.

### Files touched
`ui/CalendarGridLayout.kt`, `ui/CalendarActivity.kt`, `ui/CalendarPrefs.kt`
(new), `ui/SettingsActivity.kt`, `res/layout/activity_calendar.xml`,
`res/layout/activity_settings.xml`, `res/values(-pt)/strings.xml`.

---

## Session 4 — Drive backup resurrecting deleted notes (2026-10-16)

User report: "quando faço backup agora pelo celular, ele volta com coisas que
já exclui" (after backing up/restoring via Google Drive, notes already
deleted on the phone come back).

### Root cause
`DriveBackupHelper.mergeByUuid(local, remote)` only ever compares *active*
notes on the local side (`NoteRepository.getAllForExport()` filters out
trashed ones). So the moment a note is trashed, its uuid vanishes from
`local` entirely -- to the merge, a just-deleted note and a genuinely new
note only Drive has look identical ("uuid present only in `remote`"), and
both get unioned back in by `applyMergedNotes`. Worse, the merged result is
then **written back to Drive**, so the deleted note gets re-saved into the
backup file too -- every subsequent backup/restore kept resurrecting it, not
just the first one. This was already called out as a known limitation in the
README ("Drive sync... never deletes"), but it turned out to bite a single
device, not just two devices syncing -- deleting a note and then just backing
up the same phone was enough to see it come back.

### Fix: tombstones
Added `NoteTombstone(uuid, deletedAt)` (new Room table, migration 11→12,
`NoteTombstoneDao`). `NoteRepository.delete(id)` now records a tombstone for
that note's uuid; `NoteRepository.restore(id)` (undo from Trash) clears it
again, since an explicit restore means the user wants it back. `mergeByUuid`
gained a `tombstones: Map<String, Long>` parameter (default empty, so old
call sites/tests still compile): a remote-only note whose uuid is tombstoned
is dropped from the merge *unless* the remote copy's `modifiedDate` is newer
than the deletion -- i.e. someone (possibly this same device, on another
day) genuinely edited it after the deletion, which should still win through,
same as any other newer-edit-wins conflict in this function.

The migration also **backfills a tombstone for every note already sitting in
the trash** at upgrade time (using `modifiedDate` as a stand-in for "when it
was deleted," since trashing doesn't bump that field today) -- otherwise
notes trashed before this fix shipped would still have resurrected once more
after the first post-fix backup.

All three places that merge against Drive now pass `repository.getTombstones()`
through: `SettingsActivity`'s manual "back up now" and "restore backup"
flows, and `DriveBackupWorker`'s unattended periodic background backup (the
one place this user likely hit without even opening Settings).

`NoteRepository`'s constructor gained a required third parameter
(`NoteTombstoneDao`) -- every call site across the app (`MainActivity`,
`CalendarActivity`, `NoteEditorActivity`, `SearchActivity`,
`SettingsActivity`, `TrashActivity`, `ReminderNotifier`,
`NoteReminderReceiver`, `BootReceiver`, `DriveBackupWorker`) was updated to
pass `AppDatabase.getInstance(...).noteTombstoneDao()`.

### Verification
- Unit tests added to `DriveBackupHelperTest`: a tombstoned note is dropped
  from the merge; a remote edit made *after* the deletion still wins through;
  an unrelated tombstone doesn't affect anything. All pass, plus the full
  existing suite + lint.
- Installed the new build **over** the emulator's existing (pre-fix, DB
  version 11, with a real trashed note in it) install to actually exercise
  `MIGRATION_11_12` on real data instead of just a fresh install: no crash,
  no `SQLiteException` in logcat, app opened normally post-migration
  (including past the PIN lock screen, confirming the migration didn't
  corrupt unrelated tables either).
- **Not verified end-to-end against real Drive**: this environment has no
  Google Cloud OAuth client registered for the debug build's signing
  certificate (see `DriveBackupHelper`'s own doc comment -- sign-in would
  fail with a developer-facing error, not something fixable from inside the
  app or this session). The fix is verified at the unit level (the exact
  function that was wrong) and via the migration smoke test above; the user
  should confirm on their own signed-in device that a deleted note no longer
  comes back after "Back up now".

### Files touched
`data/NoteTombstone.kt` (new), `data/NoteTombstoneDao.kt` (new),
`data/AppDatabase.kt` (migration 11→12), `data/NoteRepository.kt`,
`data/drive/DriveBackupHelper.kt`, `data/drive/DriveBackupWorker.kt`,
`ui/SettingsActivity.kt`, plus the constructor-signature ripple through every
other `NoteRepository(...)` call site listed above.

---

## Session 3 — Swipe-to-delete, resizable widget, full test pass (2026-10-16)

### Added
- **Swipe-to-delete in the notes list** (`MainActivity.kt`): swipe a card left or
  right to trash it immediately, with the same "Note moved to trash" + Undo
  snackbar the bulk-delete path already used. Disabled while in selection mode
  (long-press) so it can't fight with a selection drag. Shows a red background +
  trash icon while dragging (`ItemTouchHelper.SimpleCallback`).
- **Resizable/responsive home-screen widget** (`TodayNotesWidgetProvider.kt`):
  below ~180dp wide or ~100dp tall, the widget now swaps to a compact tile
  (`widget_today_notes_compact.xml`: weekday + day-of-month, tap to open today,
  small "+" corner button) instead of a cramped/unreadable list. Switches live
  as the user resizes via `onAppWidgetOptionsChanged`. `minResizeWidth/Height`
  in `widget_today_notes_info.xml` lowered to 60dp so the compact size is
  actually reachable from the launcher's resize handles.

### Bugs found and fixed this session
1. **Notes were silently created twice** (real, pre-existing bug, not something
   this session introduced — caught by the test battery below).
   `NoteEditorActivity.saveIfNeeded()` was called from *three* places: the
   "done" checkmark's click listener, the back-press callback, and `onPause()`.
   Both the checkmark and back-press explicitly called `saveIfNeeded()` and
   then triggered `finish()`, which **also** calls `onPause()` → `saveIfNeeded()`
   again. Since `note` (the in-memory reference to the saved row) is only
   assigned *after* the async `repository.insert()` coroutine actually
   completes, two calls close enough together both still see `note == null`
   and both insert a new row. Fix: removed the two redundant explicit calls
   (onPause() alone is necessary and sufficient — it fires for every way this
   screen can go away), and added a `saveInFlight` reentrancy guard directly in
   `saveIfNeeded()` as defense-in-depth. Verified fixed: created a note via the
   checkmark button post-fix, only one row appeared, confirmed by navigating
   away and back.
2. **Two crashes in work done in *this same session*, both the identical root
   cause**, caught during on-device testing before they reached the user:
   - `CalendarActivity`'s month-change animation (`monthSlideDistancePx`) and
   - `MainActivity`'s new swipe-to-delete `ItemTouchHelper` (loading
     `android.R.drawable.ic_menu_delete` via `ContextCompat.getDrawable`)

   both computed a value in a **plain property initializer**, which runs in the
   Activity's constructor — *before* the base `Context` is attached — so
   `resources`/`getResources()` was still null, crashing on launch with
   `NullPointerException: Attempt to invoke virtual method
   'android.content.res.Resources android.content.Context.getResources()' on a
   null object reference`. Fix in both places: wrap the computation in `by
   lazy { ... }` so it's deferred to first actual use (well after `onCreate`).
   **Rule of thumb for future work**: never call `resources`, `getColor`,
   `ContextCompat.getDrawable`, etc. in a top-level `private val` field
   initializer on an `Activity` — always `by lazy { }` or move it into
   `onCreate()`.
3. A `<size>`-less `<shape>` drawable used as a `CheckBox.buttonDrawable`
   silently renders nothing (no intrinsic size for `CompoundButton` to lay out)
   — this was from session 2, re-confirmed still fine this session.

### Full manual test pass on an emulator (Pixel-class AVD, cold install)
Covered and confirmed working: today-highlight + full-screen calendar grid,
month-change animation (buttons + swipe, both directions), day dialog
styling, reminder time range, widget tap → today's dialog (via direct intent,
see note below), text note create/edit, **checklist note create/check/count**,
**folders (create + no crash)**, **search (title match + highlight)**,
**PIN lock (set, wrong-PIN rejection, correct-PIN unlock)**, **swipe-to-delete
+ undo + trash restore**, **trash "Delete forever"**, bulk select/delete.

**Known test-environment limitation, not an app bug**: placing the actual
home-screen widget via adb-driven drag-and-drop gestures (`input
draganddrop`) on the Pixel Launcher could not be made reliable after a couple
of attempts — the launcher's widget-picker sheet needs a genuine long-press-
then-drag that `adb shell input` doesn't faithfully reproduce. Verified the
provider logic by code review + the app compiling/linting clean instead.
**If you want to see the compact widget with your own eyes, drag the Anotaí
widget onto the home screen and resize it down past roughly a 2-cell width —
it should switch from the list to the day-number tile.**

### Files touched
`ui/MainActivity.kt`, `ui/NoteEditorActivity.kt`, `ui/CalendarActivity.kt`
(lazy fix only), `widget/TodayNotesWidgetProvider.kt`,
`res/layout/widget_today_notes_compact.xml` (new),
`res/xml/widget_today_notes_info.xml`.

---

## Session 2 — Calendar polish (2026-10-16)

- **Today indicator**: originally a small circle around just the day number;
  per feedback, changed to a rounded-rectangle outline around the *whole* day
  cell (`shape_today_outline.xml`, drawn last in `item_calendar_day.xml` so it
  sits on top of the grid lines).
- **Month-change animation**: slide + fade transition (direction-aware: next
  month slides from the right, previous from the left) instead of an instant
  content swap, on both the prev/next buttons and the swipe gesture. Guarded
  with `isAnimatingMonthChange` so a fast double-tap can't start overlapping
  animations. **This is what first hit the `by lazy` bug described above.**

---

## Session 1 — Calendar/reminder feature batch (2026-10-16)

- **Today's date highlighted** in the calendar grid (later changed in Session
  2 from a circle to a full-cell outline).
- **Day-notes dialog restyled** to match ColorNote reference screenshots: a
  colored bar on the left edge of each note row (was a small inline swatch),
  and a round green "mark done" toggle (filled = pending, hollow = done) in
  place of the default square `CheckBox` look. Needed a `<size>` tag on the
  toggle's shape drawables — see bug #3 above.
- **Calendar grid fills the screen**: removed a reserved empty strip below the
  grid (`activity_calendar.xml`); the FAB is now allowed to overlap the last
  row of days.
- **Reminder time *range*** (e.g. "9:00 – 17:00"), not just a single instant:
  added `Note.reminderEndTimeMinutes` (Room migration 10→11), a second
  time-picker chained after the first in `ReminderActivity`, and window-aware
  filtering in `ReminderNotifier`/`NoteReminderScheduler` — a note with an end
  time only shows/fires while the current time of day falls inside the
  window; a note with no end time keeps the old "shows all day once due"
  behavior, so existing notes aren't affected.
- **Widget tap opens today's day dialog directly** (`CalendarActivity
  .EXTRA_OPEN_TODAY`) instead of just the bare month view.
- **Fixed a real RecyclerView crash on delete**
  (`IndexOutOfBoundsException: Inconsistency detected. Invalid view holder
  adapter position`) in the notes list. Root cause: `MainActivity` was calling
  `adapter.refresh()` manually right after a delete, racing a second
  `PagingData` generation against the one Room's own query invalidation was
  already producing automatically. Fix: removed the manual `refresh()` call,
  and set `binding.recyclerView.itemAnimator = null` (Paging3's own diffing +
  RecyclerView's predictive-layout item animator don't mix well; this is a
  known rough edge, not specific to this app).

### Files touched
`data/Note.kt`, `data/AppDatabase.kt` (migration 10→11), `data/BackupHelper.kt`
(export/import of the new field), `notification/NoteReminderScheduler.kt`,
`notification/ReminderNotifier.kt`, `ui/CalendarActivity.kt`,
`ui/DayNotesDialog.kt`, `ui/MainActivity.kt`, `ui/NoteEditorActivity.kt`,
`ui/ReminderActivity.kt`, `widget/TodayNotesWidgetProvider.kt`, plus several
new/changed drawables and `values(-pt)/strings.xml`, `values/colors.xml`.

---

## Open ideas not yet implemented
(From a broader brainstorm — not committed to, just noted so they aren't
re-proposed from scratch next time.)
- Weekly/agenda calendar view, not just monthly.
- Search inside checklist items (currently only title/body).
- Multiple widget sizes beyond the compact/full split (e.g. a dedicated
  month-grid widget).
- Per-note PIN lock (today it's whole-app only).
- Export to PDF/Markdown in addition to the existing JSON export.

## Testing notes for whoever drives the emulator next
- `adb shell input text "..."` is unreliable for masked/password `EditText`
  fields on this AVD/launcher combo — characters can land mid-string instead
  of appending, especially right after an error-state redraw. For PIN entry
  (or anything similarly masked), prefer `adb shell input keyevent
  KEYCODE_1/2/3/4/...` one digit at a time; it was 100% reliable where
  `input text` wasn't. Always confirm field bounds with `uiautomator dump`
  before tapping — screenshot-estimated coordinates are frequently off by
  enough to miss small buttons.
- Placing a home-screen widget via adb gestures is not reliably automatable
  on the Pixel Launcher (see Session 3 note above) — don't sink time into it
  past one or two attempts; verify widget code by review + build instead, and
  ask the user to eyeball the real placement if it matters.
- `adb shell input swipe x1 y1 x2 y2 <duration>` needs a short enough
  duration to actually register as a fling (not just a drag) with
  `GestureDetector` — 300ms over ~1000px was silently ignored where 150ms
  over the same distance worked immediately (see Session 5). If a swipe
  gesture that should trigger something visibly does nothing, try a shorter
  duration before concluding it's an app bug.
