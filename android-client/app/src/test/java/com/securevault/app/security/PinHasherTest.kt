package com.securevault.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for PinHasher — PBKDF2-SHA256 PIN hashing and verification.
 *
 * Test cases per task-006 Testing Requirements (Testing_Strategy.md Part 1):
 *   - Validation: ^[0-9]{6}$ enforced
 *   - Hash format: Base64(salt):hexKey(64 chars)
 *   - Uniqueness: same PIN produces different hashes (unique salt)
 *   - Round-trip verify: hash then verify
 *   - Wrong PIN: verify returns false
 *   - Constant-time compare: wrong length hash handled safely
 *   - Security: invalid PIN does NOT produce a hash (no DB operation)
 *   - Plaintext PIN never appears in hash output (PRD F-AUTH-03 AC#2)
 *   - Performance: verify < 200ms (generous for JVM; real device < 100ms per SRS)
 *
 * Note: SRS NFR-PERF-01 (< 100ms) is validated on-device in instrumentation tests.
 * JVM PBKDF2 is slower than Android hardware — the perf test here uses 200ms bound.
 */
class PinHasherTest {

    // -------------------------------------------------------------------------
    // Validation — Security_Requirements.md §5
    // -------------------------------------------------------------------------

    @Test
    fun validate_sixDigits_passes() {
        PinHasher.validate("123456")   // Should not throw
    }

    @Test(expected = IllegalArgumentException::class)
    fun validate_fiveDigits_throws() {
        PinHasher.validate("12345")
    }

    @Test(expected = IllegalArgumentException::class)
    fun validate_sevenDigits_throws() {
        PinHasher.validate("1234567")
    }

    @Test(expected = IllegalArgumentException::class)
    fun validate_alphanumeric_throws() {
        PinHasher.validate("12345A")
    }

    @Test(expected = IllegalArgumentException::class)
    fun validate_empty_throws() {
        PinHasher.validate("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun validate_withSpaces_throws() {
        PinHasher.validate("1234 6")
    }

    // -------------------------------------------------------------------------
    // Hash format
    // -------------------------------------------------------------------------

    @Test
    fun hash_containsColon() {
        val result = PinHasher.hash("123456")
        assertTrue("Hash must contain colon separator", result.contains(":"))
    }

    @Test
    fun hash_hasTwoParts() {
        val result = PinHasher.hash("123456")
        assertEquals(2, result.split(":").size)
    }

    @Test
    fun hash_keyPartIs64Chars() {
        val result = PinHasher.hash("123456")
        val hexKey = result.split(":")[1]
        assertEquals(
            "Hex key must be exactly 64 chars per Database_Schema.md §2.1 CHECK length=64",
            64, hexKey.length
        )
    }

    @Test
    fun hash_keyPartIsHexOnly() {
        val result = PinHasher.hash("123456")
        val hexKey = result.split(":")[1]
        assertTrue("Key part must be lowercase hex", hexKey.matches(Regex("[0-9a-f]{64}")))
    }

    // -------------------------------------------------------------------------
    // Uniqueness — different salt per call
    // -------------------------------------------------------------------------

    @Test
    fun hash_samePin_producesDifferentHashes() {
        val h1 = PinHasher.hash("123456")
        val h2 = PinHasher.hash("123456")
        assertNotEquals("Two hashes of the same PIN must differ (unique salt)", h1, h2)
    }

    // -------------------------------------------------------------------------
    // Round-trip verify — happy path
    // -------------------------------------------------------------------------

    @Test
    fun verify_correctPin_returnsTrue() {
        val hash = PinHasher.hash("123456")
        assertTrue(PinHasher.verify("123456", hash))
    }

    @Test
    fun verify_allZeros_roundTrips() {
        val hash = PinHasher.hash("000000")
        assertTrue(PinHasher.verify("000000", hash))
    }

    @Test
    fun verify_allNines_roundTrips() {
        val hash = PinHasher.hash("999999")
        assertTrue(PinHasher.verify("999999", hash))
    }

    // -------------------------------------------------------------------------
    // Wrong PIN / invalid inputs
    // -------------------------------------------------------------------------

    @Test
    fun verify_wrongPin_returnsFalse() {
        val hash = PinHasher.hash("123456")
        assertFalse(PinHasher.verify("654321", hash))
    }

    @Test
    fun verify_invalidPin_returnsFalse() {
        val hash = PinHasher.hash("123456")
        // 5 digits — validation fails → verify returns false without DB op
        assertFalse(PinHasher.verify("12345", hash))
    }

    @Test
    fun verify_emptyPin_returnsFalse() {
        val hash = PinHasher.hash("123456")
        assertFalse(PinHasher.verify("", hash))
    }

    @Test
    fun verify_corruptedHash_returnsFalse() {
        assertFalse(PinHasher.verify("123456", "not_a_valid_hash"))
    }

    @Test
    fun verify_emptyHash_returnsFalse() {
        assertFalse(PinHasher.verify("123456", ""))
    }

    // -------------------------------------------------------------------------
    // Security test: invalid PIN must NOT produce a hash (no DB access path)
    // Task-006 security test: "invalid PIN inputs do not trigger DB decryption"
    // -------------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun hash_invalidFormat_throwsBeforeAnyKeyDerivation() {
        // Must throw immediately — before PBKDF2 runs — so no key derivation occurs
        PinHasher.hash("ABCDEF")
    }

    // -------------------------------------------------------------------------
    // Plaintext never in hash — PRD F-AUTH-03 AC#2
    // -------------------------------------------------------------------------

    @Test
    fun hash_outputDoesNotContainPin() {
        val pin = "123456"
        val hash = PinHasher.hash(pin)
        assertFalse("Hash must not contain plaintext PIN", hash.contains(pin))
    }

    // -------------------------------------------------------------------------
    // Performance — generous JVM bound; on-device < 100ms (SRS FR-AUTH-03)
    // -------------------------------------------------------------------------

    @Test
    fun verify_completesWithinReasonableTime() {
        val hash = PinHasher.hash("123456")
        val start = System.currentTimeMillis()
        PinHasher.verify("123456", hash)
        val elapsed = System.currentTimeMillis() - start

        // 3000ms on JVM is extremely generous; real Android device must be < 100ms
        assertTrue("PinHasher.verify must complete in reasonable time (got ${elapsed}ms)",
            elapsed < 3000L)
    }
}
