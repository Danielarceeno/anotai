# Test strategy

Short version of this project's approach, for anyone picking up work here who hasn't read all of
`TESTING.md`/`DEV_LOG.md`. See those for the detailed "what's covered" and "what happened" --
this is the "why" and "what's expected of new work."

## The pyramid this project actually uses

Not a textbook 70/20/10 unit/integration/E2E split -- this app's real risk is concentrated in
Room-on-SQLCipher, Activity lifecycle, and real Android framework behavior (WorkManager,
AppWidget, notifications), which plain JVM unit tests can't exercise honestly. The actual shape:

- **Unit tests (`app/src/test/`)** -- pure logic only: `RecurrenceUtils`, `BrazilianHolidays`,
  `DriveBackupHelper.mergeByUuid`, `escapeLikePattern`, JSON parsing. No Android framework, no
  Robolectric (evaluated and rejected -- see `TESTING.md`'s Fase 2 section: SQLCipher's native
  library doesn't run under Robolectric's shadow framework, and almost everything else here
  touches Room anyway). Fast, runs on every push.
- **Instrumented tests (`app/src/androidTest/`)** -- the bulk of this suite, deliberately. Real
  Room database (real SQLCipher encryption, not a mock), real Espresso UI interaction, real
  `WorkManager`/`AppWidgetManager` test harnesses, a handful of real UiAutomator system-UI
  interactions (notification shade). This project trusts a test that exercises the real stack
  over one that mocks it, even at the cost of needing an emulator and running slower.
- **End-to-end** -- kept deliberately few: one full create -> remind -> trash -> restore ->
  backup/restore journey, not a combinatorial explosion of every path through the app. Most
  coverage lives one level down, at the Activity/Repository/DAO level, where a failure points at
  *what* broke instead of just *that* something, somewhere, did.

No mutation testing or formal pyramid-ratio target is enforced -- see `TESTING.md`'s Fase 9
section for why (Pitest evaluated, deferred: unverifiable plugin/Kotlin compatibility without
package-registry access in this environment, and Kotlin's compiler-generated null-checks need
careful filtering to avoid a noisy, untrustworthy score).

## Definition of Done for a change here

- **Every bug fix ships with a regression test that was actually seen failing** against the
  unfixed code, not just written afterward and assumed to cover it (see `TESTING.md`'s "How to
  add a new test").
- **A testability seam is listed and justified separately from the behavior fix it enabled**,
  in the same commit's message or the relevant `TESTING.md` section -- never silently bundled in.
- **The full suite passes** (`./gradlew testDebugUnitTest` and `connectedDebugAndroidTest`,
  the latter excluding `AccessibilityChecksTest` per `TESTING.md`'s "Running the suite") before
  calling anything done, not just the new test in isolation.
- **A new flaky test gets fixed or explicitly documented**, never silently retried into looking
  green and left alone -- `TESTING.md`'s "Flaky tests" section is the record of ones found so far.
- **Coverage shouldn't regress** below `jacocoTestCoverageVerification`'s per-package floors
  (`./gradlew jacocoTestCoverageVerification`) -- these are deliberately set a little under this
  project's actual current baseline, so a real drop fails the check, not just an aspirational
  number nobody could already pass.

## Manual release checklist

See `docs/MANUAL_TEST_CHECKLIST.md` for what this project doesn't try to automate at all (real
Google Drive sync between two physical devices, actually placing/resizing a home-screen widget,
real biometric unlock, aggressive-battery-OEM behavior, upgrading over an old installed version).
Run through that list before cutting a release, in addition to the automated suite passing.
