# Dev Log

Running history of work done on Anotaí with Claude Code: what changed, bugs found
(and how), and open items. Read this before starting new work so context from
past sessions isn't lost. Newest entries at the top.

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
