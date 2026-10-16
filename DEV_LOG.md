# Dev Log

Running history of work done on Anotaí with Claude Code: what changed, bugs found
(and how), and open items. Read this before starting new work so context from
past sessions isn't lost. Newest entries at the top.

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
