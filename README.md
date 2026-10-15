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

- **Notes**: text or checklist, 8-color palette, pin to top, bulk select/move/delete, undo on delete.
- **Search**: debounced, with the matched term highlighted in results.
- **Calendar**: full-screen monthly grid, swipe or arrow-button navigation, a dot per note due
  that day, Saturday/Sunday day numbers tinted, tap a day to see/add/complete notes due then.
- **Reminders**: due date + recurrence (none, daily, every weekday, weekly, bi-weekly, monthly,
  yearly), with a daily 9am notification and snooze.
- **Folders** and a **recoverable trash** (restore or permanently delete).
- **Security**: optional PIN lock with biometric unlock as a shortcut on top of it.
- **Home-screen widget**: today's notes, kept in sync automatically.
- **Launcher shortcuts**: long-press the app icon for "New text note" / "New checklist".
- **Backup**: manual export/import to a local JSON file, or sign in with Google and back up to
  a private, app-scoped folder in Google Drive, with automatic 12-hourly background backups and
  conflict detection if another device wrote a newer backup since this one last synced.

## Assets

`imagens/logo.png` is the source image the launcher icon (`app/src/main/res/mipmap-*`) was
generated from.

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
│   ├── NoteDao.kt, FolderDao.kt, AppDatabase.kt  Room DB and queries
│   ├── NoteRepository.kt, FolderRepository.kt    thin repository layer over the DAOs
│   ├── RecurrenceUtils.kt                        due-date/recurrence math
│   ├── BackupHelper.kt                           JSON (de)serialization shared by local
│   │                                              export/import and Drive backup
│   └── drive/
│       ├── DriveBackupHelper.kt                  Google sign-in, Drive upload/download,
│       │                                          conflict detection, periodic scheduling
│       └── DriveBackupWorker.kt                  WorkManager job for the 12h auto-backup
├── notification/
│   ├── ReminderScheduler.kt, ReminderNotifier.kt, ReminderPrefs.kt
│   ├── DailyReminderReceiver.kt, SnoozeReceiver.kt, BootReceiver.kt
├── security/
│   └── SecurityPrefs.kt                          salted PIN hash (PBKDF2) + biometric flag
├── widget/
│   ├── TodayNotesWidgetProvider.kt, TodayNotesRemoteViewsService.kt
└── ui/
    ├── CalendarActivity.kt, CalendarGridLayout.kt launcher screen: monthly grid, shortcuts
    ├── MainActivity.kt, NoteAdapter.kt            notes list
    ├── NoteEditorActivity.kt, ChecklistAdapter.kt  create/edit a note
    ├── SearchActivity.kt, SearchViewModel.kt
    ├── FoldersActivity.kt, FolderAdapter.kt
    ├── TrashActivity.kt, TrashAdapter.kt
    ├── SettingsActivity.kt                       reminder time, folders/trash, backup, PIN
    ├── LockActivity.kt                           PIN/biometric unlock screen
    └── ReminderActivity.kt, DayNotesDialog.kt, AddNoteTypeDialog.kt
```

`EVIDENCE.md` documents the reverse-engineering behind the search implementation (`LIKE ...
ESCAPE '\'` over a local SQLite table, not full-text search).

## Testing

- `app/src/test/` — JUnit unit tests for pure logic (recurrence math, search-pattern escaping).
- No instrumentation (`androidTest`) or UI tests yet — see Known limitations.

## Known limitations

- Drive conflict handling is "pick a whole version" (restore cloud / overwrite), not a
  field-level merge.
- No instrumentation/UI test coverage.
- Debug-signed only; no release/minified build configuration yet.

## License

MIT — see [`LICENSE`](LICENSE).
