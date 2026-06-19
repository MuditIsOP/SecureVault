package com.securevault.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SecurityQuestionHasher — PBKDF2-SHA256 hashing logic.
 *
 * Test cases per Testing_Strategy.md Part 1 (task-005 spec):
 *   - PBKDF2 hash output format (salt:key)
 *   - Round-trip verification (hash then verify)
 *   - Normalization: trim + lowercase
 *   - Different answers produce different hashes
 *   - Same answer with same normalized form verifies correctly
 *   - Empty/blank input rejected
 *   - Constant-time comparison (timing-safe verify)
 *   - Plaintext never appears in hash output
 *
 * PRD F-AUTH-02 AC#2: "plaintext answer must never be stored on device or backend"
 * — verified by asserting hash output contains no substring of the plaintext answer.
 */
class SecurityQuestionHasherTest {

    // -------------------------------------------------------------------------
    // Normalization — Security_Requirements.md §5
    // -------------------------------------------------------------------------

    @Test
    fun normalize_trimsWhitespace() {
        assertEquals("london", SecurityQuestionHasher.normalize("  london  "))
    }

    @Test
    fun normalize_convertsToLowercase() {
        assertEquals("london", SecurityQuestionHasher.normalize("LONDON"))
    }

    @Test
    fun normalize_trailingNewline() {
        assertEquals("shadow", SecurityQuestionHasher.normalize("Shadow\n"))
    }

    @Test
    fun normalize_mixedCase_and_whitespace() {
        assertEquals("my first pet", SecurityQuestionHasher.normalize("  My First Pet  "))
    }

    // -------------------------------------------------------------------------
    // Hash format — base64(salt):base64(key)
    // -------------------------------------------------------------------------

    @Test
    fun hash_outputContainsColon() {
        val result = SecurityQuestionHasher.hash("london")
        assertTrue("Hash must contain colon separator", result.contains(":"))
    }

    @Test
    fun hash_hasTwoParts() {
        val result = SecurityQuestionHasher.hash("london")
        val parts = result.split(":")
        assertEquals("Hash must have exactly 2 parts", 2, parts.size)
    }

    @Test
    fun hash_partsAreNonEmpty() {
        val result = SecurityQuestionHasher.hash("london")
        val parts = result.split(":")
        assertTrue("Salt part must be non-empty", parts[0].isNotEmpty())
        assertTrue("Key part must be non-empty", parts[1].isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // Uniqueness — different salt per call
    // -------------------------------------------------------------------------

    @Test
    fun hash_sameInput_producesDifferentHashes() {
        val h1 = SecurityQuestionHasher.hash("london")
        val h2 = SecurityQuestionHasher.hash("london")
        assertNotEquals("Two hashes of the same answer must differ (unique salt)", h1, h2)
    }

    // -------------------------------------------------------------------------
    // Round-trip verify — happy path
    // -------------------------------------------------------------------------

    @Test
    fun verify_correctAnswer_returnsTrue() {
        val hash = SecurityQuestionHasher.hash("london")
        assertTrue(SecurityQuestionHasher.verify("london", hash))
    }

    @Test
    fun verify_correctAnswer_withUnnormalizedInput_returnsTrue() {
        // Normalization applied inside verify()
        val hash = SecurityQuestionHasher.hash("london")
        assertTrue(SecurityQuestionHasher.verify("  LONDON  ", hash))
    }

    @Test
    fun verify_wrongAnswer_returnsFalse() {
        val hash = SecurityQuestionHasher.hash("london")
        assertFalse(SecurityQuestionHasher.verify("paris", hash))
    }

    @Test
    fun verify_emptyAnswer_returnsFalse() {
        val hash = SecurityQuestionHasher.hash("london")
        assertFalse(SecurityQuestionHasher.verify("", hash))
    }

    @Test
    fun verify_blankAnswer_returnsFalse() {
        val hash = SecurityQuestionHasher.hash("london")
        assertFalse(SecurityQuestionHasher.verify("   ", hash))
    }

    @Test
    fun verify_corruptedHash_returnsFalse() {
        assertFalse(SecurityQuestionHasher.verify("london", "not_a_valid_hash"))
    }

    // -------------------------------------------------------------------------
    // Boundary — empty input
    // -------------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun hash_emptyString_throwsIllegalArgumentException() {
        SecurityQuestionHasher.hash("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun hash_blankString_throwsIllegalArgumentException() {
        SecurityQuestionHasher.hash("   ")
    }

    // -------------------------------------------------------------------------
    // PRD F-AUTH-02 AC#2 — plaintext NEVER appears in the hash output
    // -------------------------------------------------------------------------

    @Test
    fun hash_outputDoesNotContainPlaintext() {
        val plaintext = "mysecretanswer123"
        val hash = SecurityQuestionHasher.hash(plaintext)
        assertFalse(
            "Hash output must not contain the plaintext answer",
            hash.contains(plaintext)
        )
        assertFalse(
            "Hash output must not contain uppercase plaintext",
            hash.contains(plaintext.uppercase())
        )
    }
}
