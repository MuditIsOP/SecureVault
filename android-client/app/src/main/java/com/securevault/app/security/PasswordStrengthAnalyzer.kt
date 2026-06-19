package com.securevault.app.security

/**
 * Local password strength analyzer (zxcvbn-style scoring).
 *
 * Spec refs:
 *   - PRD F-GEN-01 AC#3 — "real-time password Strength Meter"
 *   - SRS FR-GEN-01b — "client SHALL validate generated password strength locally"
 *   - Design.md §3.2 — Strength Meter visuals
 *   - Architecture.md — zero-network dependency for strength calculations
 *
 * Scoring heuristic based on:
 *   - Length contribution (longer = stronger)
 *   - Character diversity (more categories = stronger)
 *   - Entropy estimation (pool size ^ length)
 *   - Penalty for common patterns (sequential, repeated chars)
 *
 * Returns a StrengthResult with score (0-4), label, and color.
 * All computation is offline/local — no network calls.
 */
object PasswordStrengthAnalyzer {

    /** Strength levels matching zxcvbn scoring convention (0-4) */
    enum class StrengthLevel(val label: String, val score: Int) {
        VERY_WEAK("Very Weak", 0),
        WEAK("Weak", 1),
        FAIR("Fair", 2),
        STRONG("Strong", 3),
        VERY_STRONG("Very Strong", 4)
    }

    data class StrengthResult(
        val level: StrengthLevel,
        val score: Int,        // 0-100 for progress bar
        val label: String,
        val colorHex: String   // Design.md color token
    )

    /**
     * Analyzes password strength and returns a StrengthResult.
     *
     * PRD F-GEN-01 AC#3 — "real-time password Strength Meter"
     * SRS FR-GEN-01b — "client SHALL validate generated password strength locally"
     *
     * @param password The password to analyze
     * @return StrengthResult with score, label, and color
     */
    fun analyze(password: String): StrengthResult {
        if (password.isEmpty()) {
            return StrengthResult(StrengthLevel.VERY_WEAK, 0, "Very Weak", "#F44336")
        }

        var score = 0.0

        // Length contribution (max 30 points)
        score += minOf(password.length * 2.0, 30.0)

        // Character diversity (max 30 points)
        val hasLower = password.any { it.isLowerCase() }
        val hasUpper = password.any { it.isUpperCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSymbol = password.any { !it.isLetterOrDigit() }

        var categories = 0
        if (hasLower) categories++
        if (hasUpper) categories++
        if (hasDigit) categories++
        if (hasSymbol) categories++

        score += categories * 7.5 // 4 categories × 7.5 = 30 max

        // Entropy estimation (max 25 points)
        val poolSize = calculatePoolSize(hasLower, hasUpper, hasDigit, hasSymbol)
        val entropy = password.length * Math.log(poolSize.toDouble()) / Math.log(2.0)
        score += minOf(entropy / 4.0, 25.0)

        // Penalties (up to -20 points)
        score -= calculatePenalties(password)

        // Bonus for length > 16 (up to 15 points)
        if (password.length > 16) {
            score += minOf((password.length - 16) * 1.5, 15.0)
        }

        // Clamp to 0-100
        val finalScore = maxOf(0.0, minOf(score, 100.0)).toInt()

        return when {
            finalScore < 20 -> StrengthResult(StrengthLevel.VERY_WEAK, finalScore, "Very Weak", "#F44336")
            finalScore < 40 -> StrengthResult(StrengthLevel.WEAK, finalScore, "Weak", "#FF9800")
            finalScore < 60 -> StrengthResult(StrengthLevel.FAIR, finalScore, "Fair", "#FFC107")
            finalScore < 80 -> StrengthResult(StrengthLevel.STRONG, finalScore, "Strong", "#81C784")
            else -> StrengthResult(StrengthLevel.VERY_STRONG, finalScore, "Very Strong", "#4CAF50")
        }
    }

    /**
     * Maps strength level to vault storage value.
     * Database_Schema.md §2.2 password_strength enum: WEAK, MEDIUM, STRONG
     */
    fun toStorageValue(level: StrengthLevel): String {
        return when (level) {
            StrengthLevel.VERY_WEAK, StrengthLevel.WEAK -> "WEAK"
            StrengthLevel.FAIR -> "MEDIUM"
            StrengthLevel.STRONG, StrengthLevel.VERY_STRONG -> "STRONG"
        }
    }

    private fun calculatePoolSize(
        hasLower: Boolean,
        hasUpper: Boolean,
        hasDigit: Boolean,
        hasSymbol: Boolean
    ): Int {
        var pool = 0
        if (hasLower) pool += 26
        if (hasUpper) pool += 26
        if (hasDigit) pool += 10
        if (hasSymbol) pool += 32
        return maxOf(pool, 1) // Prevent log(0)
    }

    /**
     * Calculates penalties for weak patterns.
     * - Repeated characters
     * - Sequential runs (abc, 123)
     * - All same character
     */
    private fun calculatePenalties(password: String): Double {
        var penalty = 0.0

        // Repeated characters penalty
        val charCounts = password.groupingBy { it }.eachCount()
        val maxRepeat = charCounts.values.maxOrNull() ?: 0
        if (maxRepeat > password.length / 3) {
            penalty += (maxRepeat - password.length / 3) * 3.0
        }

        // Sequential characters penalty (e.g., abc, 123, xyz)
        var sequentialCount = 0
        for (i in 0 until password.length - 2) {
            val c1 = password[i].code
            val c2 = password[i + 1].code
            val c3 = password[i + 2].code
            if (c2 - c1 == 1 && c3 - c2 == 1) {
                sequentialCount++
            }
        }
        penalty += sequentialCount * 2.0

        // All same character
        if (password.toSet().size == 1) {
            penalty += 20.0
        }

        return minOf(penalty, 20.0)
    }
}
