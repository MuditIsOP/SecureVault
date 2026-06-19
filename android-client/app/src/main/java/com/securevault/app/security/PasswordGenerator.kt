package com.securevault.app.security

import java.security.SecureRandom

/**
 * High-entropy password generator using SecureRandom.
 *
 * Spec refs:
 *   - PRD F-GEN-01 AC#2 — "Length Slider (8 to 64), toggles for Uppercase,
 *     Lowercase, Numbers, Symbols, Exclude Similar Characters"
 *   - SRS FR-GEN-01a — "client SHALL generate random passwords"
 *   - Architecture.md — "Security Modules" component
 *   - Security_Requirements.md — zero-network dependency for generation
 *
 * Uses java.security.SecureRandom for cryptographically secure random selection.
 * All generation is offline/local — no network calls.
 *
 * Edge cases:
 *   - All toggles off → falls back to lowercase (minimum viable charset)
 *   - Exclude similar chars: removes i, l, 1, L, o, 0, O from all pools
 *   - Length clamped to [8, 64] per PRD F-GEN-01 AC#2
 */
object PasswordGenerator {

    private const val MIN_LENGTH = 8   // PRD F-GEN-01 AC#2
    private const val MAX_LENGTH = 64  // PRD F-GEN-01 AC#2

    private const val UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val LOWERCASE = "abcdefghijklmnopqrstuvwxyz"
    private const val NUMBERS = "0123456789"
    private const val SYMBOLS = "!@#\$%^&*()_+-=[]{}|;':\",./<>?"

    /** Characters to exclude when "Exclude Similar" is toggled — PRD F-GEN-01 AC#2 */
    private const val SIMILAR_CHARS = "il1Lo0O"

    private val secureRandom = SecureRandom()

    /**
     * Configuration for password generation.
     * Maps directly to SCR-GEN-01 toggle controls.
     */
    data class GeneratorConfig(
        val length: Int = 16,
        val includeUppercase: Boolean = true,
        val includeLowercase: Boolean = true,
        val includeNumbers: Boolean = true,
        val includeSymbols: Boolean = true,
        val excludeSimilar: Boolean = false
    )

    /**
     * Generates a random password based on the given configuration.
     *
     * PRD F-GEN-01 AC#2 — respects all toggle settings
     * SRS FR-GEN-01a — "client SHALL generate random passwords"
     *
     * @param config Generator configuration from UI controls
     * @return Generated password string
     */
    fun generate(config: GeneratorConfig = GeneratorConfig()): String {
        val length = config.length.coerceIn(MIN_LENGTH, MAX_LENGTH)

        // Build character pool based on toggles — PRD F-GEN-01 AC#2
        val pool = buildCharacterPool(config)

        if (pool.isEmpty()) {
            // Edge case: all toggles off → fallback to lowercase
            return generate(config.copy(includeLowercase = true))
        }

        // Generate password using SecureRandom — SRS FR-GEN-01a
        val password = StringBuilder(length)
        for (i in 0 until length) {
            val index = secureRandom.nextInt(pool.length)
            password.append(pool[index])
        }

        // Ensure at least one character from each enabled category
        // (prevents edge case where random selection misses a category)
        val ensured = ensureCharacterDiversity(password.toString(), config, pool)

        return ensured
    }

    /**
     * Builds the character pool from enabled toggles.
     * Optionally filters out similar characters per PRD F-GEN-01 AC#2.
     */
    private fun buildCharacterPool(config: GeneratorConfig): String {
        val pool = StringBuilder()

        if (config.includeUppercase) pool.append(UPPERCASE)
        if (config.includeLowercase) pool.append(LOWERCASE)
        if (config.includeNumbers) pool.append(NUMBERS)
        if (config.includeSymbols) pool.append(SYMBOLS)

        // Remove similar characters if toggled — PRD F-GEN-01 AC#2
        // "Exclude Similar Characters (e.g., i, l, 1, L, o, 0, O)"
        return if (config.excludeSimilar) {
            pool.toString().filter { it !in SIMILAR_CHARS }
        } else {
            pool.toString()
        }
    }

    /**
     * Ensures the password contains at least one character from each enabled category.
     * If a category is missing, replaces a random position with a character from it.
     */
    private fun ensureCharacterDiversity(
        password: String,
        config: GeneratorConfig,
        pool: String
    ): String {
        val chars = password.toCharArray()
        val usedPositions = mutableSetOf<Int>()

        fun ensureCategory(categoryChars: String, isEnabled: Boolean) {
            if (!isEnabled) return
            val filtered = if (config.excludeSimilar) {
                categoryChars.filter { it !in SIMILAR_CHARS }
            } else {
                categoryChars
            }
            if (filtered.isEmpty()) return

            val hasCategory = chars.any { it in filtered }
            if (!hasCategory) {
                var pos: Int
                do {
                    pos = secureRandom.nextInt(chars.size)
                } while (pos in usedPositions)
                usedPositions.add(pos)
                chars[pos] = filtered[secureRandom.nextInt(filtered.length)]
            }
        }

        ensureCategory(UPPERCASE, config.includeUppercase)
        ensureCategory(LOWERCASE, config.includeLowercase)
        ensureCategory(NUMBERS, config.includeNumbers)
        ensureCategory(SYMBOLS, config.includeSymbols)

        return String(chars)
    }
}
