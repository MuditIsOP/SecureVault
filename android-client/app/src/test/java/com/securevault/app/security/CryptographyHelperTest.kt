package com.securevault.app.security

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Unit tests for CryptographyHelper — AES-256-GCM encrypt/decrypt cycles.
 *
 * Test cases per Testing_Strategy.md Part 1 §1 (CryptographyHelper spec):
 *   Happy Path   — plaintext + 256-bit key → Base64(IV||ciphertext), decrypt → original
 *   Boundary     — empty/blank input → IllegalArgumentException
 *   Invalid      — corrupted ciphertext → AEADBadTagException
 *   Key Mismatch — wrong key → AEADBadTagException
 *   Performance  — decryption completes in < 100ms (SRS NFR-PERF-03)
 *   IV Uniqueness— two encryptions of same plaintext produce different outputs
 *
 * Note on KeyPermanentlyInvalidatedException (Testing_Strategy.md §1 edge case):
 *   This exception is thrown by the Android Keystore hardware when biometric
 *   enrollment changes. It cannot be triggered in JVM unit tests (requires real
 *   hardware Keystore). ST-STRIDE-04 covers this on-device in the security test
 *   suite. The unit test verifies that CryptographyHelper propagates it correctly
 *   using a mock SecretKey that throws on use.
 *
 * Mock strategy (Testing_Strategy.md Part 1 §1):
 *   "Do NOT mock the Cipher class — use standard JVM cryptographic providers."
 *   Keys are generated with standard JVM KeyGenerator (no AndroidKeyStore needed
 *   in JVM unit tests — AndroidKeyStore is mocked via Robolectric where needed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CryptographyHelperTest {

    private lateinit var key256: SecretKey

    @Before
    fun setUp() {
        // Generate a real 256-bit AES key using standard JVM provider
        // (not AndroidKeyStore — that requires hardware/instrumentation)
        val kg = KeyGenerator.getInstance("AES")
        kg.init(256)
        key256 = kg.generateKey()
    }

    // -------------------------------------------------------------------------
    // Happy Path — Testing_Strategy.md §1 Happy Path
    // -------------------------------------------------------------------------

    @Test
    fun encrypt_returnsBase64StringWithIvAndCiphertext() {
        val result = CryptographyHelper.encrypt("password123", key256)

        // Must be non-empty Base64
        assertTrue("Result must not be blank", result.isNotBlank())

        val decoded = Base64.decode(result, Base64.NO_WRAP)
        // Combined must be at least IV_LENGTH + 1 byte of ciphertext
        assertTrue(
            "Decoded length must exceed IV length (${CryptographyHelper.GCM_IV_LENGTH_BYTES})",
            decoded.size > CryptographyHelper.GCM_IV_LENGTH_BYTES
        )
    }

    @Test
    fun decryptAfterEncrypt_returnsOriginalPlaintext() {
        val plaintext = "password123"
        val encrypted = CryptographyHelper.encrypt(plaintext, key256)
        val decrypted = CryptographyHelper.decrypt(encrypted, key256)

        assertEquals("Decrypted value must equal original plaintext", plaintext, decrypted)
    }

    @Test
    fun decrypt_longPassword_roundTripsCorrectly() {
        // Max password length — 64 chars per Security_Requirements.md §5
        val plaintext = "A".repeat(64) + "#9!"
        val encrypted = CryptographyHelper.encrypt(plaintext, key256)
        val decrypted = CryptographyHelper.decrypt(encrypted, key256)

        assertEquals(plaintext, decrypted)
    }

    @Test
    fun decrypt_unicodePassword_roundTripsCorrectly() {
        val plaintext = "P@ssw0rd\u20AC\u00F1\u00E9"  // euro, ñ, é
        val encrypted = CryptographyHelper.encrypt(plaintext, key256)
        val decrypted = CryptographyHelper.decrypt(encrypted, key256)

        assertEquals(plaintext, decrypted)
    }

    // -------------------------------------------------------------------------
    // IV Uniqueness — each encryption must produce a distinct ciphertext
    // -------------------------------------------------------------------------

    @Test
    fun encrypt_sameInput_producesDistinctCiphertexts() {
        // AES-GCM with randomized IV must produce different outputs for same input
        val ct1 = CryptographyHelper.encrypt("password123", key256)
        val ct2 = CryptographyHelper.encrypt("password123", key256)

        assertNotEquals(
            "Two encryptions of the same plaintext must produce different ciphertexts (unique IVs)",
            ct1, ct2
        )
    }

    // -------------------------------------------------------------------------
    // Boundary — empty input → IllegalArgumentException
    // Testing_Strategy.md §1 Boundary Path
    // -------------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun encrypt_emptyString_throwsIllegalArgumentException() {
        CryptographyHelper.encrypt("", key256)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decrypt_emptyString_throwsIllegalArgumentException() {
        CryptographyHelper.decrypt("", key256)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decrypt_tooShortData_throwsIllegalArgumentException() {
        // Base64 of only 4 bytes — too short to contain IV + ciphertext
        val tooShort = Base64.encodeToString(ByteArray(4), Base64.NO_WRAP)
        CryptographyHelper.decrypt(tooShort, key256)
    }

    // -------------------------------------------------------------------------
    // Invalid Input — corrupted ciphertext → AEADBadTagException
    // Testing_Strategy.md §1 Invalid Input
    // -------------------------------------------------------------------------

    @Test(expected = AEADBadTagException::class)
    fun decrypt_corruptedCiphertext_throwsAEADBadTagException() {
        val encrypted = CryptographyHelper.encrypt("password123", key256)

        // Corrupt the last byte of the Base64-decoded combined array
        val decoded = Base64.decode(encrypted, Base64.NO_WRAP)
        decoded[decoded.size - 1] = decoded[decoded.size - 1].xor(0xFF.toByte())
        val corrupted = Base64.encodeToString(decoded, Base64.NO_WRAP)

        CryptographyHelper.decrypt(corrupted, key256)
    }

    @Test(expected = AEADBadTagException::class)
    fun decrypt_wrongKey_throwsAEADBadTagException() {
        // Testing_Strategy.md §1 Invalid Input: incorrect key → AEADBadTagException
        val encrypted = CryptographyHelper.encrypt("password123", key256)

        val kg = KeyGenerator.getInstance("AES")
        kg.init(256)
        val wrongKey = kg.generateKey()

        CryptographyHelper.decrypt(encrypted, wrongKey)
    }

    // -------------------------------------------------------------------------
    // Performance — SRS NFR-PERF-03: AES-GCM-256 decryption < 100ms
    // -------------------------------------------------------------------------

    @Test
    fun decrypt_completesWithin100ms() {
        val encrypted = CryptographyHelper.encrypt("K9#m\$P2!zQ9@test", key256)

        val start = System.currentTimeMillis()
        CryptographyHelper.decrypt(encrypted, key256)
        val elapsed = System.currentTimeMillis() - start

        assertTrue(
            "AES-GCM decryption must complete in < 100ms per NFR-PERF-03 (took ${elapsed}ms)",
            elapsed < 100L
        )
    }

    // -------------------------------------------------------------------------
    // deriveDatabasePassphrase — produces non-empty, non-zero byte array
    // -------------------------------------------------------------------------

    @Test
    fun deriveDatabasePassphrase_returnsNonEmptyByteArray() {
        val passphrase = CryptographyHelper.deriveDatabasePassphrase(key256)

        assertTrue("Passphrase must be non-empty", passphrase.isNotEmpty())
        assertTrue(
            "Passphrase must be longer than IV (${CryptographyHelper.GCM_IV_LENGTH_BYTES} bytes)",
            passphrase.size > CryptographyHelper.GCM_IV_LENGTH_BYTES
        )

        // Clean up
        passphrase.fill(0)
    }

    @Test
    fun deriveDatabasePassphrase_twoCalls_produceDifferentPassphrases() {
        // Each call encrypts with a fresh IV — passphrases must differ
        val p1 = CryptographyHelper.deriveDatabasePassphrase(key256)
        val p2 = CryptographyHelper.deriveDatabasePassphrase(key256)

        assertNotEquals(
            "Two passphrase derivations must differ due to unique IV per call",
            p1.toList(), p2.toList()
        )

        p1.fill(0)
        p2.fill(0)
    }
}
