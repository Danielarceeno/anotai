# Anotaí

[![CI](https://github.com/Danielarceeno/anotai/actions/workflows/ci.yml/badge.svg)](https://github.com/Danielarceeno/anotai/actions/workflows/ci.yml)

A native Android notes app written in Kotlin — text and checklist notes, a full-screen
monthly calendar with due-date reminders, folders, a recoverable trash, PIN/biometric lock,
a home-screen widget, launcher shortcuts, and optional Google Drive backup.

> **Personal project, not affiliated with ColorNote.** Anotaí's search behavior and visual
> layout were informed by reverse-engineering a copy of the ColorNote APK for research
> purposes (see [`EVIDENCE.md`](EVIDENCE.md) for the methodology and findings). No code,
> assets, or trademarks from ColorNote/SocialNmobile are included here — everything in
> `app/` is original Kotlin written for this project.

## Features

- **Notes**: text (with bold/italic/underline) or checklist, 8-color palette plus a free custom
  RGB color, an attached photo, pin to top, bulk select/move/delete, undo on delete, share as
  text or as an image, and an optional per-note password that hides the body everywhere outside
  the editor.
- **Search**: debounced, with the matched term highlighted in results.
- **Calendar**: full-screen monthly grid, swipe or arrow-button navigation, a dot per note due
  that day, Saturday/Sunday day numbers *and* weekday-header letters tinted, tap a day to
  see/add/complete notes due then. Optionally shows Brazilian national holidays (fixed dates
  plus Easter-derived ones like Carnaval, computed per year, not hardcoded) tinted on the grid
  and named in the day dialog -- opt-in from Settings, off by default.
- **Overdue cue**: a task still not marked done after its day has passed shows its title in red
  in the day dialog -- on by default, toggleable from Settings.
- **Reminders**: due date + recurrence (none, daily, every weekday, weekly, bi-weekly, monthly,
  yearly, monthly on the same week-of-month + weekday e.g. "every 2nd Friday", or the first
  business day of the month), with a daily 9am notification and snooze, plus an optional exact
  time (or time range) per note with its own alarm. Tapping the notification
  (or a task in the home-screen widget) opens that day's task list, the same popup tapping the
  day on the calendar shows, rather than jumping straight into a single note's editor.
- **Folders** and a **recoverable trash** (restore or permanently delete).
- **Security**: optional PIN lock with biometric unlock as a shortcut on top of it.
- **Home-screen widgets**: a resizable list of today's notes (or the next 3/7 days,
  configurable), fixed 1x1 and 2x2 variants, a 2x2 "done today" progress tile, and a 1x1 tile
  pinned to one chosen note -- all kept in sync automatically.
- **Launcher shortcuts**: long-press the app icon for "New text note" / "New checklist".
- **Backup**: manual export/import to a local JSON file, or sign in with Google from the
  dedicated Sync screen and back up to a private, app-scoped folder in Google Drive -- an
  automatic background sync right after creating a note, plus a configurable periodic
  background sync. Every sync merges note by note (the newer edit wins, deletions are carried
  across devices) instead of one device's backup overwriting the other's. Android's own Auto
  Backup deliberately skips the notes database: it's encrypted with a key that never leaves
  the device, so a restored copy couldn't be opened.

## Getting started

### Requirements

- Android Studio (Koala or newer) or the command-line tools + JDK 17
- Android SDK: `platforms;android-34`, `build-tools;34.0.0`

### Build and run

```bash
git clone <this-repo-url>
cd anotai
./gradlew assembleDebug          # build the debug APK
./gradlew installDebug           # build and install on a connected device/emulator
./gradlew testDebugUnitTest      # unit tests
./gradlew lintDebug              # lint
```

Or open the folder in Android Studio and run the `app` configuration.

### Release build

`./gradlew assembleRelease` always produces a minified, shrunk release APK
(`app/proguard-rules.pro`). To get a *signed* one you can actually install, copy
[`keystore.properties.example`](keystore.properties.example) to `keystore.properties` (gitignored)
and point it at a keystore — generate one with:

```bash
keytool -genkeypair -v -keystore app/anotai-release.jks -alias anotai -keyalg RSA -keysize 2048 -validity 10000
```

Without `keystore.properties`, the release build still succeeds; the APK just isn't signed yet.

### Google Drive backup (optional)

The app works fully offline without this. To enable "sign in with Google" backup, you need
your own OAuth client registered in Google Cloud Console — this can't be preconfigured in the
repo since it's tied to each developer's signing certificate:

1. Create (or pick) a project at [console.cloud.google.com](https://console.cloud.google.com/).
2. **APIs & Services → Library**: enable the **Google Drive API**.
3. **APIs & Services → OAuth consent screen**: fill in the basic app info, and under
   **Test users**, add the Google account(s) you'll sign in with during development (the app
   stays in "Testing" mode — no Google review needed for personal/internal use).
4. **APIs & Services → Credentials → Create credentials → OAuth client ID**:
   - Application type: **Android**
   - Package name: `com.example.notepad`
   - SHA-1 certificate fingerprint: from your debug keystore —
     ```bash
     keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
     ```
5. No further configuration needed in the app itself — Google matches sign-in requests to the
   OAuth client by package name + SHA-1 automatically.

## Project structure

```
app/src/main/java/com/example/notepad/
├── data/
│   ├── Note.kt, Folder.kt, ChecklistItem.kt      Room entities / serialization
│   ├── NoteTombstone.kt, NoteTombstoneDao.kt     deletion markers for the Drive merge
│   ├── NoteDao.kt, FolderDao.kt, AppDatabase.kt  Room DB (SQLCipher), queries, migrations
│   ├── DatabaseKeyProvider.kt                    Keystore-wrapped database passphrase
│   ├── NoteRepository.kt, FolderRepository.kt    thin repository layer over the DAOs
│   ├── RemindersCache.kt                         short-lived memo of "notes with a due date"
│   ├── RecurrenceUtils.kt, DayMath.kt            due-date/recurrence math, DST-safe day steps
│   ├── BrazilianHolidays.kt                      fixed + Easter-derived national holidays
│   ├── BackupHelper.kt                           JSON (de)serialization shared by local
│   │                                              export/import and Drive backup
│   └── drive/
│       ├── DriveBackupHelper.kt                  Google sign-in, Drive upload/download,
│       │                                          per-note merge, periodic scheduling
│       └── DriveBackupWorker.kt                  WorkManager job for the periodic/immediate sync
├── notification/
│   ├── ReminderScheduler.kt, ReminderNotifier.kt, ReminderPrefs.kt   the daily check
│   ├── NoteReminderScheduler.kt, NoteReminderReceiver.kt             per-note exact-time alarms
│   ├── DailyReminderReceiver.kt, SnoozeReceiver.kt, BootReceiver.kt
├── security/
│   ├── SecurityPrefs.kt                          PIN hash, biometric flag, auto-lock state
│   └── PasswordHasher.kt                         salted PBKDF2, shared by PIN and note passwords
├── widget/
│   ├── TodayNotesWidgetProvider.kt, TodayNotesRemoteViewsService.kt  resizable "today" list
│   ├── TodayNotes1x1/2x2/SummaryWidgetProvider.kt                    fixed-size variants
│   └── PickedNoteWidgetProvider.kt, *Prefs.kt                        one chosen note per tile
└── ui/
    ├── CalendarActivity.kt, CalendarGridLayout.kt launcher screen: monthly grid, shortcuts
    ├── MainActivity.kt, PagedNoteAdapter.kt       notes list (paged)
    ├── NoteEditorActivity.kt, ChecklistAdapter.kt  create/edit a note
    ├── NoteFormatting.kt, NoteColors.kt, ShareImageHelper.kt
    ├── SearchActivity.kt, SearchViewModel.kt, NoteAdapter.kt
    ├── FoldersActivity.kt, FolderAdapter.kt
    ├── TrashActivity.kt, TrashAdapter.kt
    ├── SettingsActivity.kt                       theme, calendar, widget range, backup, PIN
    ├── DriveSyncActivity.kt                      Google sign-in and sync screen
    ├── LockActivity.kt, LockGuardedActivity.kt   PIN/biometric unlock screen and its guard
    ├── PickedNoteWidgetConfigActivity.kt         picks the note for a 1x1 note widget
    └── ReminderActivity.kt, DayNotesDialog.kt, AddNoteTypeDialog.kt
```

`EVIDENCE.md` documents the reverse-engineering behind the search implementation's *behavior*:
`LIKE ... ESCAPE '\'`-style substring matching, including mid-word. Under the hood it now runs
against an FTS5 index (`notes_fts`, trigram-tokenized specifically so SQLite can still accelerate
that same `LIKE` substring scan) rather than a full table scan, but the matching behavior itself
is unchanged.

## Testing

- `app/src/test/` — JUnit unit tests for pure logic (recurrence math, search-pattern escaping,
  Drive merge logic, Brazilian holidays).
- `app/src/androidTest/` — Espresso/Room/WorkManager instrumented tests (the bulk of this
  project's coverage, deliberately — see `docs/TEST_STRATEGY.md`). Run with
  `./gradlew connectedDebugAndroidTest` against a connected device/emulator. Espresso requires
  animations to be off on the target device/emulator (Settings → Developer options → turn off
  Window/Transition/Animator animation scale); some OEM builds block
  `adb shell settings put global ...` for this, in which case toggle it by hand in Developer
  options.
- **`TESTING.md`** — full coverage tables, known gaps and why, how to run each layer, and a
  checklist for adding a new test. **`docs/TEST_STRATEGY.md`** — the short version: the testing
  pyramid this project actually follows and the Definition of Done for a change.
  **`docs/MANUAL_TEST_CHECKLIST.md`** — what's deliberately not automated (real Drive sync
  between two devices, placing a home-screen widget, real biometrics, ...). **`DEV_LOG.md`** —
  session-by-session history of bugs found and decisions made, for the "why" behind a specific
  test.

## Security & performance

- The local database is encrypted at rest with SQLCipher (`AppDatabase`/`DatabaseKeyProvider`):
  the per-install passphrase lives in Keystore-backed `EncryptedSharedPreferences`, and an
  existing plaintext database from before this was added is migrated to an encrypted one
  automatically on first launch after updating.
- The PIN lock re-locks the app after a configurable period of inactivity (Settings → Auto-lock,
  default 5 minutes), not only when the process is killed.
- The main notes list is paged from Room (`NoteRepository.observeAllPaged` / `PagedNoteAdapter`)
  instead of loading every note into memory at once; search and folder views still load their
  (typically much smaller) result sets directly.

## License

MIT — see [`LICENSE`](LICENSE).
