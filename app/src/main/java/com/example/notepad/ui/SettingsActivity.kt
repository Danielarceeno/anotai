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

        repository = NoteRepository(AppDatabase.getInstance(this).noteDao(), applicationContext)

        binding.toolbar.setNavigationOnClickListener { finish() }

        updateReminderTimeText()
        binding.reminderTimeRow.setOnClickListener { showTimePicker() }

        binding.foldersRow.setOnClickListener { startActivity(Intent(this, FoldersActivity::class.java)) }
        binding.trashRow.setOnClickListener { startActivity(Intent(this, TrashActivity::class.java)) }

        binding.exportRow.setOnClickListener { exportLauncher.launch("notepad-backup.json") }
        binding.importRow.setOnClickListener { importLauncher.launch(arrayOf("application/json", "text/*")) }

        updateDriveRow()
        binding.driveBackupRow.setOnClickListener { onDriveRowClicked() }

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

        binding.biometricSwitch.isChecked = SecurityPrefs.isBiometricEnabled(this)
        binding.biometricSwitch.setOnCheckedChangeListener { _, isChecked ->
            SecurityPrefs.setBiometricEnabled(this, isChecked)
        }
    }

    private fun updatePinDependentRows() {
        val pinEnabled = SecurityPrefs.isPinEnabled(this)
        binding.changePinRow.visibility = if (pinEnabled) View.VISIBLE else View.GONE
        val biometricAvailable = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
        binding.biometricRow.visibility = if (pinEnabled && biometricAvailable) View.VISIBLE else View.GONE
        if (!pinEnabled) binding.biometricSwitch.isChecked = false
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
            notes.forEach { repository.insert(it) }
            val message = resources.getQuantityString(R.plurals.import_success, notes.size, notes.size)
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
            Toast.makeText(this, R.string.drive_sign_in_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Checks for another device's newer backup before writing; see [DriveBackupHelper]'s
     *  class doc for why this check exists. */
    private fun backupToDrive(account: GoogleSignInAccount) {
        lifecycleScope.launch {
            try {
                val remoteTime = DriveBackupHelper.remoteBackupModifiedTime(applicationContext, account)
                val lastSynced = DriveBackupHelper.lastSyncedTime(applicationContext)
                if (remoteTime != null && remoteTime > lastSynced) {
                    showDriveConflictDialog(account)
                } else {
                    runDriveBackup(account)
                }
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, R.string.drive_backup_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDriveConflictDialog(account: GoogleSignInAccount) {
        AlertDialog.Builder(this)
            .setTitle(R.string.drive_conflict_title)
            .setMessage(R.string.drive_conflict_message)
            .setPositiveButton(R.string.drive_conflict_restore) { _, _ -> restoreFromDrive(account) }
            .setNegativeButton(R.string.drive_conflict_overwrite) { _, _ ->
                lifecycleScope.launch { runDriveBackup(account) }
            }
            .setNeutralButton(R.string.reminder_cancel, null)
            .show()
    }

    private suspend fun runDriveBackup(account: GoogleSignInAccount) {
        try {
            DriveBackupHelper.backup(applicationContext, account, repository.getAllForExport())
            Toast.makeText(this@SettingsActivity, R.string.drive_backup_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this@SettingsActivity, R.string.drive_backup_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreFromDrive(account: GoogleSignInAccount) {
        lifecycleScope.launch {
            try {
                val notes = DriveBackupHelper.restore(applicationContext, account)
                if (notes == null) {
                    Toast.makeText(this@SettingsActivity, R.string.drive_no_backup_found, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                notes.forEach { repository.insert(it) }
                val message = resources.getQuantityString(R.plurals.import_success, notes.size, notes.size)
                Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, R.string.drive_restore_failed, Toast.LENGTH_SHORT).show()
            }
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
