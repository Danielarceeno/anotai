package com.example.notepad.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.example.notepad.R
import com.example.notepad.databinding.ActivityLockBinding
import com.example.notepad.security.LockState
import com.example.notepad.security.SecurityPrefs

/** Shown instead of [CalendarActivity] when the app's PIN lock (set in Settings) is on. */
class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.unlockButton.setOnClickListener { tryUnlock() }
        binding.pinInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                tryUnlock(); true
            } else {
                false
            }
        }

        if (SecurityPrefs.isBiometricEnabled(this) && canUseBiometrics()) {
            showBiometricPrompt()
        }
    }

    private fun canUseBiometrics(): Boolean {
        val manager = BiometricManager.from(this)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun showBiometricPrompt() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlock()
                }
                // No error-handling branch needed: on failure/cancel the user just falls back
                // to the PIN field that's already on screen underneath the prompt.
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.unlock_title))
            .setNegativeButtonText(getString(R.string.reminder_cancel))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(info)
    }

    private fun tryUnlock() {
        val pin = binding.pinInput.text.toString()
        if (SecurityPrefs.checkPin(this, pin)) {
            unlock()
        } else {
            binding.errorView.visibility = View.VISIBLE
        }
    }

    private fun unlock() {
        LockState.markUnlocked()
        // Preserves a launcher-shortcut action (e.g. "new text note") across the lock screen --
        // CalendarActivity attaches it as this extra before redirecting here, since otherwise
        // unlocking would always land on the plain calendar instead of finishing what the
        // shortcut was for.
        val pendingAction = intent.getStringExtra(EXTRA_PENDING_ACTION)
        startActivity(
            Intent(this, CalendarActivity::class.java).apply { pendingAction?.let { action = it } }
        )
        finish()
    }

    companion object {
        const val EXTRA_PENDING_ACTION = "pending_action"
    }
}
