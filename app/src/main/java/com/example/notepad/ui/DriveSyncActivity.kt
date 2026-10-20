package com.example.notepad.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.notepad.R
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.NoteRepository
import com.example.notepad.data.drive.DriveBackupHelper
import com.example.notepad.databinding.ActivityDriveSyncBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

/**
 * Dedicated Google Drive sync screen, replacing the old inline Settings rows -- account
 * identity, last-sync time and synced-note count up front, one big "Sync" button, everything
 * else (restore/backup frequency/sign out) tucked into the overflow menu. [SettingsActivity]'s
 * own Drive row now just opens this instead of a popup menu.
 */
class DriveSyncActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDriveSyncBinding
    private lateinit var repository: NoteRepository
    private var isSyncing = false

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleSignInResult(result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDriveSyncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = AppDatabase.getInstance(this)
        repository = NoteRepository(db.noteDao(), applicationContext, db.noteTombstoneDao())

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_restore -> { restoreFromDrive(); true }
                R.id.action_backup_frequency -> { showBackupFrequencyPicker(); true }
                R.id.action_sign_out -> { signOut(); true }
                else -> false
            }
        }

        binding.signInButton.setOnClickListener {
            signInLauncher.launch(DriveBackupHelper.signInClient(this).signInIntent)
        }
        binding.syncNowButton.setOnClickListener {
            DriveBackupHelper.lastSignedInAccount(this)?.let { backupToDrive(it) }
        }

        updateUi()
    }

    override fun onResume() {
        super.onResume()
        // Reflects a sync that just finished in the background (periodic worker, or the
        // immediate one triggered by creating a note) without needing to tap the button again.
        updateUi()
    }

    private fun updateUi() {
        val account = DriveBackupHelper.lastSignedInAccount(this)
        val signedIn = account != null
        binding.signedOutGroup.visibility = if (signedIn) View.GONE else View.VISIBLE
        binding.signedInGroup.visibility = if (signedIn) View.VISIBLE else View.GONE
        binding.syncNowButton.visibility = if (signedIn) View.VISIBLE else View.GONE
        for (id in intArrayOf(R.id.action_restore, R.id.action_backup_frequency, R.id.action_sign_out)) {
            binding.toolbar.menu.findItem(id)?.isVisible = signedIn
        }
        if (account == null) return

        binding.accountNameView.text = account.displayName ?: account.email
        binding.accountEmailView.text = account.email

        val lastSyncMillis = DriveBackupHelper.getLastSyncMillis(this)
        binding.lastSyncValueView.text = if (lastSyncMillis == 0L) {
            getString(R.string.drive_sync_never)
        } else {
            DateUtils.getRelativeTimeSpanString(lastSyncMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        }
        binding.noteCountValueView.text = DriveBackupHelper.getLastSyncNoteCount(this).toString()
    }

    private fun setSyncing(syncing: Boolean) {
        isSyncing = syncing
        binding.syncProgress.visibility = if (syncing) View.VISIBLE else View.GONE
        binding.syncNowButton.isEnabled = !syncing
    }

    private fun handleSignInResult(data: Intent?) {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account: GoogleSignInAccount = task.getResult(ApiException::class.java)
            updateUi()
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

    private fun signOut() {
        DriveBackupHelper.cancelPeriodicBackup(applicationContext)
        DriveBackupHelper.signOut(this) { updateUi() }
    }

    /** Delegates to [DriveBackupHelper.syncNow], whose Mutex also keeps this from racing a
     *  concurrent background sync (periodic, or the one triggered right after creating a note)
     *  into duplicating notes -- see its doc. */
    private fun backupToDrive(account: GoogleSignInAccount) {
        if (isSyncing) return
        setSyncing(true)
        lifecycleScope.launch {
            try {
                DriveBackupHelper.syncNow(applicationContext, repository, account)
                updateUi()
                Toast.makeText(this@DriveSyncActivity, R.string.drive_backup_success, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@DriveSyncActivity, R.string.drive_backup_failed, Toast.LENGTH_SHORT).show()
            } finally {
                setSyncing(false)
            }
        }
    }

    /** Same [DriveBackupHelper.syncNow] call as [backupToDrive] -- merging already pushes the
     *  result back to Drive too, so a note only this device had isn't left out of the next
     *  device's restore either. Kept as a separate menu action (rather than folded into the one
     *  button) because its empty-backup and "N notes added" messaging reads differently from a
     *  plain sync. */
    private fun restoreFromDrive() {
        val account = DriveBackupHelper.lastSignedInAccount(this) ?: return
        if (isSyncing) return
        setSyncing(true)
        lifecycleScope.launch {
            try {
                val result = DriveBackupHelper.syncNow(applicationContext, repository, account)
                if (!result.hadRemoteBackup) {
                    Toast.makeText(this@DriveSyncActivity, R.string.drive_no_backup_found, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                updateUi()
                val added = result.addedCount
                val message = resources.getQuantityString(R.plurals.import_success, added, added)
                Toast.makeText(this@DriveSyncActivity, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@DriveSyncActivity, R.string.drive_restore_failed, Toast.LENGTH_SHORT).show()
            } finally {
                setSyncing(false)
            }
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
                if (DriveBackupHelper.lastSignedInAccount(this) != null) {
                    DriveBackupHelper.schedulePeriodicBackup(this)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.reminder_cancel, null)
            .show()
    }
}
