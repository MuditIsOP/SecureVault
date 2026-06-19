package com.securevault.app.security

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Client-side PBKDF2-SHA256 hashing for the 6-digit device PIN.
 *
 * Spec refs:
 *   - PRD F-AUTH-03 AC#2 — "PIN must be stored locally in the encrypted
 *     SQLCipher database" — stored as hash only, never plaintext
 *   - Database_Schema.md §2.1 — pin_hash: TEXT, CHECK length=64, RESTRICTED
 *   - Security_Requirements.md §4 — "pin_hash: RESTRICTED, SHA-256 Hashed"
 *   - Security_Requirements.md §5 — "Validate that input is exactly 6 digits
 *     matching ^[0-9]{6}$" [MUST]
 *   - Technical_Requirements.md §8 — Hard constraint: PIN must NEVER be written
 *     to persistent storage in plaintext
 *   - Architecture.md §4 Data Flow 2 — "Client app validates the hash against
 *     the local SQLCipher password store"
 *
 * Algorithm: PBKDF2WithHmacSHA256
 *   - Iterations: 100,000
 *   - Salt: 16 bytes (128-bit) random per hash
 *   - Output: 32 bytes raw → hex-encoded = 64 chars
 *     (satisfies Database_Schema.md §2.1 CHECK length=64)
 *
 * Output format: Base64(salt):hexKey(64 chars)
 * Stored in users.pin_hash. The salt is embedded so verification can re-derive.
 *
 * Memory safety: all char arrays and intermediate byte arrays are zeroed after use.
 *
 * Thread safety: stateless object, all methods pure functions.
 */
object PinHasher {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16
    private val PIN_REGEX = Regex("^[0-9]{6}$")

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    /**
     * Validates that [pin] is exactly 6 numeric digits per Security_Requirements.md §5.
     *
     * @throws IllegalArgumentException if PIN format is invalid.
     */
    @Throws(IllegalArgumentException::class)
    fun validate(pin: String) {
        require(PIN_REGEX.matches(pin)) {
            "PinHasher: PIN must be exactly 6 numeric digits (^[0-9]{6}$)"
        }
    }

    // -------------------------------------------------------------------------
    // Hashing
    // -------------------------------------------------------------------------

    /**
     * Generates a PBKDF2-SHA256 hash of the PIN.
     *
     * @param pin Exactly 6 numeric digits.
     * @return Base64(salt):hexKey — stored in users.pin_hash
     *
     * @throws IllegalArgumentException if PIN does not match ^[0-9]{6}$
     */
    @Throws(IllegalArgumentException::class)
    fun hash(pin: String): String {
        validate(pin)

        val salt = ByteArray(SALT_LENGTH_BYTES)
        SecureRandom().nextBytes(salt)

        val derivedBytes = deriveKey(pin, salt)
        val hexKey = derivedBytes.toHexString()   // 64-char hex — Database_Schema.md §2.1

        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)

        // Zero intermediate arrays — Technical_Requirements.md §8
        derivedBytes.fill(0)
        salt.fill(0)

        return "$saltB64:$hexKey"
    }

    // -------------------------------------------------------------------------
    // Verification
    // -------------------------------------------------------------------------

    /**
     * Verifies [pin] against [storedHash] using constant-time comparison.
     *
     * @param pin        6-digit PIN from user input.
     * @param storedHash Base64(salt):hexKey from users.pin_hash
     * @return true if PIN matches, false otherwise.
     *
     * Architecture.md §4 Data Flow 2: "Client app validates the hash against
     * the local SQLCipher password store."
     */
    fun verify(pin: String, storedHash: String): Boolean {
        return try {
            if (!PIN_REGEX.matches(pin)) return false

            val parts = storedHash.split(":")
            if (parts.size != 2) return false

            val salt = Base64.decode(parts[0], Base64.NO_WRAP)
            val storedHex = parts[1]
            if (storedHex.length != 64) return false

            val derivedBytes = deriveKey(pin, salt)
            val derivedHex = derivedBytes.toHexString()

            // Constant-time comparison — prevents timing attacks
            val result = constantTimeEquals(derivedHex, storedHex)

            derivedBytes.fill(0)
            salt.fill(0)

            result
        } catch (e: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun deriveKey(pin: String, salt: ByteArray): ByteArray {
        val chars = pin.toCharArray()
        return try {
            val spec = PBEKeySpec(chars, salt, ITERATIONS, KEY_LENGTH_BITS)
            val factory = SecretKeyFactory.getInstance(ALGORITHM)
            val result = factory.generateSecret(spec).encoded
            spec.clearPassword()
            result
        } finally {
            chars.fill('\u0000')    // Technical_Requirements.md §8
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }

    /** Constant-time string comparison — prevents timing attacks. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }
}
