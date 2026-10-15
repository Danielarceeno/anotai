package com.example.notepad.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Generates and stores -- via Android Keystore-backed [EncryptedSharedPreferences], not in plain
 * [android.content.SharedPreferences] -- the random passphrase [AppDatabase] encrypts the local
 * notes database with. Nothing the user ever types or sees; this is encryption-at-rest for the
 * raw sqlite file (protects against it being read off the device directly -- a backup, a rooted
 * device, physical access to the file), a different concern from the PIN app-lock in
 * [com.example.notepad.security.SecurityPrefs], which only gates the UI.
 */
object DatabaseKeyProvider {
    private const val PREFS_NAME = "db_key_prefs"
    private const val KEY_PASSPHRASE = "db_passphrase"
    private const val PASSPHRASE_BYTES = 32

    fun getOrCreatePassphrase(context: Context): CharArray {
        val prefs = encryptedPrefs(context)
        prefs.getString(KEY_PASSPHRASE, null)?.let { return it.toCharArray() }

        val generated = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_PASSPHRASE, generated).apply()
        return generated.toCharArray()
    }

    private fun encryptedPrefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
