package com.example.notepad.security

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Salted PBKDF2 hashing shared by [SecurityPrefs] (the app-wide PIN) and per-note passwords
 *  (see [com.example.notepad.data.Note.passwordHash]) -- same slow-by-design parameters so
 *  neither is cheaper to brute-force than the other if its storage is ever extracted. */
object PasswordHasher {
    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256

    fun randomSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun hash(secret: String, salt: ByteArray): String {
        val spec = PBEKeySpec(secret.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return key.toHex()
    }

    fun matches(secret: String, salt: ByteArray, expectedHash: String): Boolean =
        expectedHash == hash(secret, salt)

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
