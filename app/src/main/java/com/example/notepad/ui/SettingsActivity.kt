package com.example.notepad.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.lifecycle.lifecycleScope
import com.example.notepad.R
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.BackupHelper
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.data.drive.DriveBackupHelper
import com.example.notepad.databinding.ActivitySettingsBinding
import com.example.notepad.databinding.DialogSetPinBinding
import com.example.notepad.notification.ReminderPrefs
import com.example.notepad.notification.ReminderScheduler
import com.example.notepad.security.SecurityPrefs
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch
import java.util.Locale

/** One screen for everything that isn't a note: reminder time, folders, trash, backup, PIN lock. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repository: NoteRepository

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { exportTo(it) }
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importFrom(it) }
    }
    private val driveSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleDriveSignInResult(result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = AppDatabase.getInstance(this)
        repository = NoteRepository(db.noteDao(), applicationContext, db.noteTombstoneDao())

        binding.toolbar.setNavigationOnClickListener { finish() }

        updateThemeValueText()
        binding.themeRow.setOnClickListener { showThemePicker() }

        binding.verticalSwipeSwitch.isChecked = CalendarPrefs.isVerticalSwipeEnabled(this)
        binding.verticalSwipeSwitch.setOnCheckedChangeListener { _, isChecked ->
            CalendarPrefs.setVerticalSwipeEnabled(this, isChecked)
        }

        binding.notificationsSwitch.isChecked = ReminderPrefs.isEnabled(this)
        binding.notificationsSwitch.setOnCheckedChangeListener { _, isChecked ->
            ReminderPrefs.setEnabled(this, isChecked)
            if (!isChecked) {
                androidx.core.app.NotificationManagerCompat.from(this)
                    .cancel(com.example.notepad.notification.ReminderNotifier.NOTIFICATION_ID)
            }
        }

        updateReminderTimeText()
        binding.reminderTimeRow.setOnClickListener { showTimePicker() }

        binding.foldersRow.setOnClickListener { startActivity(Intent(this, FoldersActivity::class.java)) }
        binding.trashRow.setOnClickListener { startActivity(Intent(this, TrashActivity::class.java)) }

        updateWidgetRangeText()
        binding.widgetRangeRow.setOnClickListener { showWidgetRangePicker() }

        binding.exportRow.setOnClickListener { exportLauncher.launch("notepad-backup.json") }
        binding.importRow.setOnClickListener { importLauncher.launch(arrayOf("application/json", "text/*")) }

        updateDriveRow()
        binding.driveBackupRow.setOnClickListener { onDriveRowClicked() }

        updateBackupFrequencyText()
        binding.backupFrequencyRow.setOnClickListener { showBackupFrequencyPicker() }

        binding.removeDuplicatesRow.setOnClickListener { removeDuplicateNotes() }

        binding.pinLockSwitch.isChecked = SecurityPrefs.isPinEnabled(this)
        updatePinDependentRows()
        binding.pinLockSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                showSetPinDialog(onCancel = { binding.pinLockSwitch.isChecked = false })
            } else {
                SecurityPrefs.clearPin(this)
                updatePinDependentRows()
            }
        }
        binding.changePinRow.setOnClickListener { showSetPinDialog(onCancel = {}) }

        updateAutoLockValueText()
        binding.autoLockRow.setOnClickListener { showAutoLockPicker() }

        binding.biometricSwitch.isChecked = SecurityPrefs.isBiometricEnabled(this)
        binding.biometricSwitch.setOnCheckedChangeListener { _, isChecked ->
            SecurityPrefs.setBiometricEnabled(this, isChecked)
        }
    }

    private fun updatePinDependentRows() {
        val pinEnabled = SecurityPrefs.isPinEnabled(this)
        binding.changePinRow.visibility = if (pinEnabled) View.VISIBLE else View.GONE
        binding.autoLockRow.visibility = if (pinEnabled) View.VISIBLE else View.GONE
        val biometricAvailable = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
        binding.biometricRow.visibility = if (pinEnabled && biometricAvailable) View.VISIBLE else View.GONE
        if (!pinEnabled) binding.biometricSwitch.isChecked = false
    }

    private fun updateAutoLockValueText() {
        val minutes = SecurityPrefs.getAutoLockMinutes(this)
        binding.autoLockValue.text = when (minutes) {
            SecurityPrefs.AUTO_LOCK_NEVER -> getString(R.string.auto_lock_never)
            1 -> getString(R.string.auto_lock_one_minute)
            else -> getString(R.string.auto_lock_minutes, minutes)
        }
    }

    private fun showAutoLockPicker() {
        val options = intArrayOf(1, 5, 15, 30, SecurityPrefs.AUTO_LOCK_NEVER)
        val labels = options.map {
            when (it) {
                SecurityPrefs.AUTO_LOCK_NEVER -> getString(R.string.auto_lock_never)
                1 -> getString(R.string.auto_lock_one_minute)
                else -> getString(R.string.auto_lock_minutes, it)
            }
        }.toTypedArray()
        val current = options.indexOf(SecurityPrefs.getAutoLockMinutes(this)).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_auto_lock_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                SecurityPrefs.setAutoLockMinutes(this, options[which])
                updateAutoLockValueText()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.reminder_cancel, null)
            .show()
    }

    private fun updateThemeValueText() {
        binding.themeValue.text = getString(
            when (ThemePrefs.getMode(this)) {
                ThemePrefs.MODE_LIGHT -> R.string.theme_light
                ThemePrefs.MODE_DARK -> R.string.theme_dark
                else -> R.string.theme_system
            }
        )
    }

    private fun showThemePicker() {
        val labels = arrayOf(getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark))
        val modes = intArrayOf(ThemePrefs.MODE_SYSTEM, ThemePrefs.MODE_LIGHT, ThemePrefs.MODE_DARK)
        val current = modes.indexOf(ThemePrefs.getMode(this)).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_theme_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                ThemePrefs.setMode(this, modes[which])
                updateThemeValueText()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.reminder_cancel, null)
            .show()
    }

    private fun updateReminderTimeText() {
        val hour = ReminderPrefs.getHour(this)
        val minute = ReminderPrefs.getMinute(this)
        binding.reminderTimeValue.text = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
    }

    private fun showTimePicker() {
        TimePickerDialog(
            this,
            { _, hour, minute ->
                ReminderPrefs.setTime(this, hour, minute)
                ReminderScheduler.scheduleNext(this)
                updateReminderTimeText()
            },
            ReminderPrefs.getHour(this),
            ReminderPrefs.getMinute(this),
            true
        ).show()
    }

    private fun exportTo(uri: Uri) {
        lifecycleScope.launch {
            try {
                BackupHelper.export(applicationContext, uri, repository.getAllForExport())
                Toast.makeText(this@SettingsActivity, R.string.export_success, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importFrom(uri: Uri) {
        lifecycleScope.launch {
            val notes = BackupHelper.import(applicationContext, uri)
            if (notes == null) {
                Toast.makeText(this@SettingsActivity, R.string.import_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            notes.forEach { note ->
                val id = repository.insert(note)
                com.example.notepad.notification.NoteReminderScheduler.schedule(applicationContext, note.copy(id = id))
            }
            val message = resources.getQuantityString(R.plurals.import_success, notes.size, notes.size)
            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateWidgetRangeText() {
        binding.widgetRangeValue.text = when (com.example.notepad.widget.WidgetPrefs.getRangeDays(this)) {
            3 -> getString(R.string.widget_range_3_days)
            7 -> getString(R.string.widget_range_7_days)
            else -> getString(R.string.widget_range_today)
        }
    }

    private fun showWidgetRangePicker() {
        val daysOptions = intArrayOf(1, 3, 7)
        val labels = arrayOf(
            getString(R.string.widget_range_today),
            getString(R.string.widget_range_3_days),
            getString(R.string.widget_range_7_days)
        )
        val current = daysOptions.indexOf(com.example.notepad.widget.WidgetPrefs.getRangeDays(this)).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_widget_range_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                com.example.notepad.widget.WidgetPrefs.setRangeDays(this, daysOptions[which])
                updateWidgetRangeText()
                com.example.notepad.widget.TodayNotesWidgetProvider.notifyNotesChanged(applicationContext)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.reminder_cancel, null)
            .show()
    }

    /** One-time cleanup for notes duplicated by the (now-fixed) DriveBackupHelper.mergeByUuid
     *  bug where a remote backup file with a duplicate entry for the same note got restored onto
     *  a device that had neither copy yet. */
    private fun removeDuplicateNotes() {
        lifecycleScope.launch {
            val removed = repository.deduplicateByUuid()
            val message = if (removed == 0) {
                getString(R.string.duplicates_removed_none)
            } else {
                resources.getQuantityString(R.plurals.duplicates_removed_message, removed, removed)
            }
            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDriveRow() {
        val account = DriveBackupHelper.lastSignedInAccount(this)
        binding.driveBackupSubtitle.text = if (account != null) {
            getString(R.string.drive_signed_in_as, account.email)
        } else {
            getString(R.string.settings_drive_not_signed_in)
        }
    }

    private fun updateBackupFrequencyText() {
        val hours = DriveBackupHelper.getIntervalHours(this)
        binding.backupFrequencyValue.text = if (hours == 24) {
            getString(R.string.backup_frequency_daily)
        } else {
            getString(R.string.backup_frequency_hours, hours)
        }
    }

    private fun showBackupFrequencyPicker() {
        val hoursOptions = intArrayOf(6, 12, 24)
        val labels = hoursOptions.map {
            if (it == 24) getString(R.string.backup_frequency_daily) else getString(R.string.backup_frequency_hours, it)
        }.toTypedArray()
        val current = hoursOptions.indexOf(DriveBackupHelper.getIntervalHours(this)).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_backup_frequency_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                DriveBackupHelper.setIntervalHours(this, hoursOptions[which])
                updateBackupFrequencyText()
                // Only an already-running schedule needs re-applying; signing in later will pick
                // up the new interval on its own first call to schedulePeriodicBackup.
                if (DriveBackupHelper.lastSignedInAccount(this) != null) {
                    DriveBackupHelper.schedulePeriodicBackup(this)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.reminder_cancel, null)
            .show()
    }

    private fun onDriveRowClicked() {
        val account = DriveBackupHelper.lastSignedInAccount(this)
        if (account == null) {
            driveSignInLauncher.launch(DriveBackupHelper.signInClient(this).signInIntent)
            return
        }
        val popup = PopupMenu(this, binding.driveBackupRow)
        popup.menu.add(getString(R.string.drive_backup_now)).setOnMenuItemClickListener {
            backupToDrive(account)
            true
        }
        popup.menu.add(getString(R.string.drive_restore)).setOnMenuItemClickListener {
            restoreFromDrive(account)
            true
        }
        popup.menu.add(getString(R.string.drive_sign_out)).setOnMenuItemClickListener {
            DriveBackupHelper.cancelPeriodicBackup(applicationContext)
            DriveBackupHelper.signOut(this) { updateDriveRow() }
            true
        }
        popup.show()
    }

    private fun handleDriveSignInResult(data: Intent?) {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account: GoogleSignInAccount = task.getResult(ApiException::class.java)
            updateDriveRow()
            DriveBackupHelper.schedulePeriodicBackup(applicationContext)
            backupToDrive(account) // first sign-in doubles as the first backup
        } catch (e: ApiException) {
            // statusCode 10 (DEVELOPER_ERROR) means there's no OAuth client registered in Google
            // Cloud Console for this app's package name + signing certificate -- not something
            // fixable from inside the app; the status code is the fastest way to tell that case
            // apart from an actual runtime failure (network, cancelled, etc).
            android.util.Log.e("DriveSignIn", "Sign-in failed: statusCode=${e.statusCode} ${e.message}", e)
            Toast.makeText(this, R.string.drive_sign_in_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Merges this device's notes against whatever's currently in Drive (per-note, by
     *  [com.example.notepad.data.Note.uuid] -- see [DriveBackupHelper.mergeByUuid]) before
     *  writing anything, so neither side's independent changes since the last sync get silently
     *  thrown away by the other. */
    private fun backupToDrive(account: GoogleSignInAccount) {
        lifecycleScope.launch {
            try {
                val local = repository.getAllForExport()
                val remoteData = DriveBackupHelper.restore(applicationContext, account)
                val merged = if (remoteData != null) {
                    val tombstones = repository.mergeTombstones(remoteData.tombstones)
                    val result = DriveBackupHelper.mergeByUuid(local, remoteData.notes, tombstones)
                    applyMergeResult(result)
                    result.notes
                } else {
                    local
                }
                DriveBackupHelper.backup(applicationContext, account, merged, repository.getTombstones())
                Toast.makeText(this@SettingsActivity, R.string.drive_backup_success, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, R.string.drive_backup_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Same per-note merge as [backupToDrive], then also pushes the merged result back to Drive
     *  so a note only this device had isn't left out of the next device's restore either. */
    private fun restoreFromDrive(account: GoogleSignInAccount) {
        lifecycleScope.launch {
            try {
                val remoteData = DriveBackupHelper.restore(applicationContext, account)
                if (remoteData == null) {
                    Toast.makeText(this@SettingsActivity, R.string.drive_no_backup_found, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val local = repository.getAllForExport()
                val tombstones = repository.mergeTombstones(remoteData.tombstones)
                val result = DriveBackupHelper.mergeByUuid(local, remoteData.notes, tombstones)
                applyMergeResult(result)
                DriveBackupHelper.backup(applicationContext, account, result.notes, repository.getTombstones())
                val added = result.notes.size - local.size
                val message = resources.getQuantityString(R.plurals.import_success, added, added)
                Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, R.string.drive_restore_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Applies a [DriveBackupHelper.mergeByUuid] result locally: a merged note that kept an
     *  existing local id is an update (whether its content changed or not, which is cheap and
     *  simpler than diffing); one with `id == 0` is a note this device didn't have yet.
     *  [DriveBackupHelper.MergeResult.locallyDeletedIds] are trashed too -- notes this device
     *  still had active, but that the merge found a newer tombstone for from another device. */
    private suspend fun applyMergeResult(result: DriveBackupHelper.MergeResult) {
        for (note in result.notes) {
            val saved = if (note.id == 0L) note.copy(id = repository.insert(note)) else note.also { repository.update(it) }
            com.example.notepad.notification.NoteReminderScheduler.schedule(applicationContext, saved)
        }
        for (id in result.locallyDeletedIds) {
            repository.trashLocally(id)
            com.example.notepad.notification.NoteReminderScheduler.cancel(applicationContext, id)
        }
    }

    private fun showSetPinDialog(onCancel: () -> Unit) {
        val dialogBinding = DialogSetPinBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.set_pin_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.settings_pin_lock_title, null)
            .setNegativeButton(R.string.reminder_cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = dialogBinding.newPinInput.text.toString()
                val confirm = dialogBinding.confirmPinInput.text.toString()
                when {
                    pin.length < 4 -> showPinError(dialogBinding, R.string.pin_too_short)
                    pin != confirm -> showPinError(dialogBinding, R.string.pin_mismatch)
                    else -> {
                        SecurityPrefs.setPin(this, pin)
                        updatePinDependentRows()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showPinError(dialogBinding: DialogSetPinBinding, messageRes: Int) {
        dialogBinding.pinErrorView.setText(messageRes)
        dialogBinding.pinErrorView.visibility = View.VISIBLE
    }
}
