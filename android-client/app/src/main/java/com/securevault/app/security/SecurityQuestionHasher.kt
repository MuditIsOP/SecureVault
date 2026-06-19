package com.securevault.app.security

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Client-side PBKDF2-SHA256 hashing for security question answers.
 *
 * Spec refs:
 *   - PRD F-AUTH-02 AC#2 — "plaintext answer must be salted and hashed (using
 *     PBKDF2 with SHA-256) on the client before being sent to the backend;
 *     the plaintext answer must never be stored on the device or backend"
 *   - SRS FR-AUTH-02 — "The plaintext security answer SHALL be salted and hashed
 *     locally prior to network transmission"
 *   - Security_Requirements.md §4 — Security Answer: RESTRICTED, Hashed PBKDF2-SHA256
 *   - Security_Requirements.md §5 Input Validation — "Strip leading/trailing whitespaces
 *     and convert characters to lowercase before generating hashes" [MUST]
 *   - Security_Requirements.md §6 Response Sanitisation — hashes must never appear
 *     in API responses or logs
 *
 * Algorithm: PBKDF2WithHmacSHA256
 *   - Iterations: 100,000 (OWASP minimum for PBKDF2-SHA256 as of 2024)
 *   - Salt: 16 bytes (128-bit) random per hash
 *   - Output: 32 bytes (256-bit) derived key
 *
 * Output format: Base64(salt) + ":" + Base64(derivedKey)
 * This allows the backend to extract the salt for future verification.
 * The full string is stored in users.security_answer_hash (Database_Schema.md §2.1).
 *
 * Thread safety: stateless object, all methods are pure functions.
 *
 * Memory safety: plaintext char arrays are zeroed immediately after use per
 * Technical_Requirements.md §8 hard constraint.
 */
object SecurityQuestionHasher {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16

    // ---------------------------------------------------------------------------
    // Normalization
    // ---------------------------------------------------------------------------

    /**
     * Normalizes the plaintext answer per Security_Requirements.md §5:
     *   1. Strip leading/trailing whitespace
     *   2. Convert to lowercase
     *
     * @param rawAnswer Plaintext answer from user input.
     * @return Normalized answer string ready for hashing.
     */
    fun normalize(rawAnswer: String): String {
        return rawAnswer.trim().lowercase()
    }

    // ---------------------------------------------------------------------------
    // Hashing
    // ---------------------------------------------------------------------------

    /**
     * Generates a PBKDF2-SHA256 hash of the normalized security answer.
     *
     * @param rawAnswer Plaintext answer (will be normalized internally).
     * @return Base64(salt):Base64(derivedKey) — stored in users.security_answer_hash
     *
     * @throws IllegalArgumentException if rawAnswer is blank after normalization.
     *
     * Memory safety: the char array is zeroed after the PBEKeySpec is created.
     */
    @Throws(IllegalArgumentException::class)
    fun hash(rawAnswer: String): String {
        val normalized = normalize(rawAnswer)
        require(normalized.isNotEmpty()) {
            "SecurityQuestionHasher: answer must not be blank after normalization"
        }

        // Generate fresh random salt for this hash
        val salt = ByteArray(SALT_LENGTH_BYTES)
        SecureRandom().nextBytes(salt)

        val derivedKey = deriveKey(normalized, salt)

        // Encode as "base64salt:base64key"
        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val keyB64 = Base64.encodeToString(derivedKey, Base64.NO_WRAP)

        // Zero intermediate arrays
        derivedKey.fill(0)
        salt.fill(0)

        return "$saltB64:$keyB64"
    }

    // ---------------------------------------------------------------------------
    // Verification
    // ---------------------------------------------------------------------------

    /**
     * Verifies a plaintext answer against a stored PBKDF2 hash.
     *
     * Re-derives the key using the stored salt and compares using constant-time
     * equality to prevent timing attacks.
     *
     * @param rawAnswer Plaintext answer from user input (normalized internally).
     * @param storedHash Base64(salt):Base64(derivedKey) from the database.
     * @return true if the answer matches, false otherwise.
     */
    fun verify(rawAnswer: String, storedHash: String): Boolean {
        return try {
            val normalized = normalize(rawAnswer)
            if (normalized.isEmpty()) return false

            val parts = storedHash.split(":")
            if (parts.size != 2) return false

            val salt = Base64.decode(parts[0], Base64.NO_WRAP)
            val storedKey = Base64.decode(parts[1], Base64.NO_WRAP)

            val derivedKey = deriveKey(normalized, salt)

            // Constant-time comparison — prevents timing attacks
            val result = constantTimeEquals(derivedKey, storedKey)

            // Zero intermediate arrays
            derivedKey.fill(0)
            storedKey.fill(0)
            salt.fill(0)

            result
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    private fun deriveKey(normalizedAnswer: String, salt: ByteArray): ByteArray {
        val chars = normalizedAnswer.toCharArray()
        return try {
            val spec = PBEKeySpec(chars, salt, ITERATIONS, KEY_LENGTH_BITS)
            val factory = SecretKeyFactory.getInstance(ALGORITHM)
            val result = factory.generateSecret(spec).encoded
            spec.clearPassword()
            result
        } finally {
            // Zero char array — Technical_Requirements.md §8
            chars.fill('\u0000')
        }
    }

    /**
     * Constant-time byte array comparison — prevents timing attacks on hash comparison.
     * Both arrays must have the same length for this comparison to be meaningful.
     */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}
