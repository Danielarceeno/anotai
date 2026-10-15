package com.example.notepad.security

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Whether the app is locked behind a PIN, and the (hashed, never stored in plain text) PIN itself. */
object SecurityPrefs {
    private const val PREFS_NAME = "security_prefs"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
    private const val KEY_AUTO_LOCK_MINUTES = "auto_lock_minutes"

    /** -1 means "never" (the pre-existing behavior: stays unlocked for the process's whole life). */
    const val AUTO_LOCK_NEVER = -1
    const val DEFAULT_AUTO_LOCK_MINUTES = 5

    fun getAutoLockMinutes(context: Context): Int =
        prefs(context).getInt(KEY_AUTO_LOCK_MINUTES, DEFAULT_AUTO_LOCK_MINUTES)

    fun setAutoLockMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_AUTO_LOCK_MINUTES, minutes).apply()
    }

    // A PIN is typically 4-6 digits, i.e. only 10,000-1,000,000 possibilities -- trivial to
    // brute-force offline from an unsalted single-round hash if the SharedPreferences file is
    // ever extracted (rooted device, backup, etc). A per-install random salt plus PBKDF2 with a
    // deliberately slow iteration count makes precomputation (rainbow tables) useless and brute
    // force expensive, at a cost of a few milliseconds per legitimate unlock attempt.
    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256

    fun isPinEnabled(context: Context): Boolean = prefs(context).contains(KEY_PIN_HASH)

    fun setPin(context: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(context).edit()
            .putString(KEY_PIN_HASH, hash(pin, salt))
            .putString(KEY_PIN_SALT, salt.toHex())
            .apply()
    }

    fun clearPin(context: Context) {
        prefs(context).edit().remove(KEY_PIN_HASH).remove(KEY_PIN_SALT).remove(KEY_BIOMETRIC_ENABLED).apply()
    }

    /** Fingerprint/face unlock, only meaningful as a shortcut on top of an existing PIN. */
    fun isBiometricEnabled(context: Context): Boolean =
        isPinEnabled(context) && prefs(context).getBoolean(KEY_BIOMETRIC_ENABLED, false)

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun checkPin(context: Context, pin: String): Boolean {
        val saltHex = prefs(context).getString(KEY_PIN_SALT, null) ?: return legacyCheckPin(context, pin)
        val storedHash = prefs(context).getString(KEY_PIN_HASH, null) ?: return false
        return storedHash == hash(pin, saltHex.fromHex())
    }

    /** Falls back to the pre-salt unsalted-SHA-256 scheme for a PIN set before this upgrade,
     *  then immediately re-saves it salted so it's migrated on the very next successful unlock. */
    private fun legacyCheckPin(context: Context, pin: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        val legacyHash = digest.joinToString("") { "%02x".format(it) }
        val matches = prefs(context).getString(KEY_PIN_HASH, null) == legacyHash
        if (matches) setPin(context, pin)
        return matches
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return key.toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/** In-memory only: tracks *when* the app was last unlocked, not just whether it ever was, so a
 *  PIN-protected session can expire after [SecurityPrefs.getAutoLockMinutes] of being backgrounded
 *  instead of staying unlocked for the rest of the process's life. */
object LockState {
    private var unlockedAtMillis: Long = 0L

    fun markUnlocked() {
        unlockedAtMillis = System.currentTimeMillis()
    }

    /** True if the app was never unlocked this process, or the configured auto-lock window has
     *  elapsed since the last unlock ([SecurityPrefs.AUTO_LOCK_NEVER] never expires). */
    fun isLocked(context: Context): Boolean {
        if (unlockedAtMillis == 0L) return true
        val timeoutMinutes = SecurityPrefs.getAutoLockMinutes(context)
        if (timeoutMinutes == SecurityPrefs.AUTO_LOCK_NEVER) return false
        val elapsedMillis = System.currentTimeMillis() - unlockedAtMillis
        return elapsedMillis > timeoutMinutes * 60_000L
    }
}
