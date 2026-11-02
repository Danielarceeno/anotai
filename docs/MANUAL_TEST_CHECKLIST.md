# Manual test checklist

What this project deliberately does **not** try to automate reliably -- either the tooling
can't reach it at all (home-screen launcher interactions, real biometric hardware), or automating
it would need infrastructure (a second physical device, a real Google account, actual OEM
battery-management firmware) well beyond this project's size. Run through this before cutting a
release. Nothing here replaces the automated suite (`TESTING.md`) -- it covers what that suite
structurally cannot.

## Widgets, home screen

- [ ] Place each widget (resizable, 1x1, 2x2 list, 2x2 summary, picked-note) on an actual home
      screen from the widget picker -- not just verify the provider is registered.
- [ ] Resize the resizable widget across its compact/list breakpoint; confirm the layout actually
      swaps, not just that `onAppWidgetOptionsChanged` fires.
- [ ] Remove and re-add a widget; confirm it doesn't come back stuck on stale data.
- [ ] Picked-note widget: place one pointing at a password-protected note, tap it, confirm it
      asks for that note's password before showing content (not the app PIN).

## Google Drive sync, two real devices

- [ ] Sign in to the same Google account on two devices (or two emulators with their own Play
      Services + real network access, not local-only). Create a note on device A, sync, confirm
      it appears on device B after its own sync.
- [ ] Edit the same note on both devices while offline, then let both sync -- confirm the
      newer-`modifiedDate` edit wins and nothing duplicates (this project's merge *logic* is
      unit-tested via fakes; this is the one check that it holds with a real round-trip through
      Drive's actual API, not a stand-in).
- [ ] Delete a note on device A, sync both -- confirm device B trashes it rather than treating it
      as still-active.
- [ ] Revoke Drive access for the app (from the Google Account settings, not inside the app) and
      confirm the next sync attempt fails gracefully with a visible error, not a crash or a
      silent no-op that looks like success.

## Biometric unlock

- [ ] Enroll a real fingerprint/face on a physical device, enable biometric unlock in Settings,
      lock the app, confirm the system biometric prompt actually appears and unlocks on success.
- [ ] Cancel the biometric prompt and confirm the PIN field underneath still works as a fallback.
- [ ] Remove the enrolled biometric from the device's own system settings; confirm the app
      correctly falls back to PIN-only instead of offering a biometric option it can't use.

## Aggressive-battery-management OEMs

- [ ] On a real device from an OEM known for aggressive background-task killing (Xiaomi/MIUI,
      Huawei, Oppo/ColorOS, ...), confirm the daily reminder notification and any scheduled note
      reminders still fire after the device has been idle/locked for several hours, not just in
      an emulator or a stock-Android device.
- [ ] Confirm the periodic Drive backup `WorkManager` job still runs under the OEM's real battery
      optimization settings, not just that it's scheduled correctly (`DriveBackupScheduleTest`
      only verifies the latter).
- [ ] If the OEM shows its own "this app is using battery in the background" warning, confirm the
      app doesn't crash or misbehave if the user disables background activity for it from there.

## Upgrading an old installed version

- [ ] Install an old APK (several versions back, ideally the oldest one still distributed/kept
      around), use it enough to create real data (notes, a PIN, a Drive sign-in), then install
      the current build *over it* (not a fresh install) -- a real upgrade, not just
      `AppDatabaseMigrationTest`'s simulated schema-only migration.
  - [ ] Confirm every pre-existing note, the PIN, and the Drive sign-in state all survive.
  - [ ] Confirm the plaintext-to-SQLCipher database migration (if the old install predates it)
        actually runs and the app opens normally afterward.
- [ ] Repeat on the oldest supported Android version (`minSdk`) and the newest, not just
      whichever device happens to be on hand.

## Restrictive/e-ink OEM devices (e.g. Onyx Boox)

- [ ] Install and do a full manual pass of the app's main flows on an e-ink or similarly
      restrictive OEM device -- these often have non-standard refresh behavior, custom launcher
      restrictions, or missing Play Services that nothing in the automated suite (or even the API
      29-34 CI matrix) represents. Per the mission's own instruction, this stays a manual-only
      environment, never added to CI.

## Android 13+ notification permission

- [ ] On a real (or emulator) device running Android 13+, do a fresh install and confirm the
      `POST_NOTIFICATIONS` system permission prompt actually appears at the expected moment, not
      granted automatically by a test harness's `GrantPermissionRule` (every instrumented test
      that needs this permission grants it up front specifically to avoid the prompt blocking
      Espresso -- so the real first-run prompt flow has no automated coverage at all).
- [ ] Deny it, confirm the app degrades gracefully (reminders just don't show, no crash); grant
      it later from system Settings, confirm reminders start working without needing a reinstall.
