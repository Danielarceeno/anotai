## What changed and why

<!-- The "why" matters more than the "what" -- the diff already shows what changed. -->

## Testing checklist

- [ ] If this fixes a bug: a regression test was added, and **actually seen failing** against
      the unfixed code first (reverted the fix / reproduced the bug, confirmed the test goes red,
      restored the fix, confirmed it goes green) -- not just written afterward and assumed to
      cover it. See `docs/TEST_STRATEGY.md`'s Definition of Done.
- [ ] Any testability seam added (an injected clock/dispatcher, a `@VisibleForTesting` bump, a
      nullable test-only hook) is called out explicitly below, separate from the actual behavior
      change.
- [ ] `./gradlew testDebugUnitTest` passes locally.
- [ ] `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.example.notepad.AccessibilityChecksTest`
      passes locally (see `TESTING.md`'s "Running the suite" for why that one class is excluded).
- [ ] No new flaky test introduced -- if one showed up, it's either fixed or added to
      `TESTING.md`'s "Flaky tests" section with what makes it flaky, not silently retried until
      green.
- [ ] `./gradlew jacocoTestCoverageVerification` still passes (coverage hasn't regressed below
      this project's per-package floors).
- [ ] If this touches something in `docs/MANUAL_TEST_CHECKLIST.md`'s scope (widgets, Drive sync,
      biometrics, ...), that manual check was actually run, not just assumed fine.

## Testability seams in this PR (if any)

<!-- List each one and why it was needed. Leave this section out entirely if there are none. -->

## Screenshots / recordings

<!-- For any UI change. -->
