package com.example.notepad.security

import com.example.notepad.security.PasswordHasher.fromHex
import com.example.notepad.security.PasswordHasher.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordHasherTest {

    @Test
    fun hash_isDeterministic_forTheSameSecretAndSalt() {
        val salt = PasswordHasher.randomSalt()
        assertEquals(PasswordHasher.hash("secret", salt), PasswordHasher.hash("secret", salt))
    }

    @Test
    fun matches_isTrue_forTheRightSecret() {
        val salt = PasswordHasher.randomSalt()
        val hash = PasswordHasher.hash("correct horse", salt)
        assertTrue(PasswordHasher.matches("correct horse", salt, hash))
    }

    @Test
    fun matches_isFalse_forTheWrongSecret() {
        val salt = PasswordHasher.randomSalt()
        val hash = PasswordHasher.hash("correct horse", salt)
        assertFalse(PasswordHasher.matches("wrong guess", salt, hash))
    }

    @Test
    fun hexRoundTrip_recoversTheOriginalBytes() {
        val salt = PasswordHasher.randomSalt()
        assertEquals(salt.toList(), salt.toHex().fromHex().toList())
    }
}
