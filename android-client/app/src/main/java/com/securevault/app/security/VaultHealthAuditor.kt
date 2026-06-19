package com.securevault.app.security

import com.securevault.app.data.entities.PasswordEntity

/**
 * Background vault health auditor — scans local credentials for strength and reuse.
 *
 * Spec refs:
 *   - PRD F-GEN-02 AC#1 — "Health Dashboard must display total counts for:
 *     Weak Passwords, Medium Passwords, Strong Passwords, Reused Passwords, Total Entries"
 *   - PRD F-GEN-02 AC#2 — "Displays clear security recommendations"
 *   - PRD F-GEN-02 AC#3 — "decrypt passwords in memory and compare them"
 *   - SRS FR-GEN-02a — "client SHALL audit password complexity"
 *   - SRS FR-GEN-02b — "client SHALL alert users to password reuse"
 *   - Security_Requirements.md — decrypted passwords never leaked outside memory
 *
 * All operations are pure in-memory — no persistence of decrypted data.
 * Designed to run on Dispatchers.IO via coroutines.
 *
 * NOTE: In the current architecture, passwords are stored encrypted via SQLCipher.
 * The encrypted_password field contains the ciphertext. For reuse detection,
 * we compare encrypted values (same plaintext + same key = same ciphertext in
 * deterministic encryption mode). For strength, we use the password_strength
 * column stored at save time.
 */
object VaultHealthAuditor {

    /**
     * Health audit result — maps to SCR-GEN-02 dashboard widgets.
     * PRD F-GEN-02 AC#1 — total counts for each category.
     */
    data class HealthReport(
        val totalEntries: Int,
        val weakCount: Int,
        val mediumCount: Int,
        val strongCount: Int,
        val reusedCount: Int,
        val weakEntries: List<PasswordEntity>,
        val reusedGroups: Map<String, List<PasswordEntity>>,
        val recommendations: List<Recommendation>
    )

    /**
     * Security recommendation — PRD F-GEN-02 AC#2.
     * "Weak passwords detected", "Duplicate passwords detected" with offending entries.
     */
    data class Recommendation(
        val type: RecommendationType,
        val message: String,
        val affectedEntries: List<PasswordEntity>
    )

    enum class RecommendationType {
        WEAK_PASSWORD,
        DUPLICATE_PASSWORD
    }

    /**
     * Performs a full health audit on the given credentials.
     *
     * PRD F-GEN-02 AC#1 — counts by strength category + reused
     * PRD F-GEN-02 AC#2 — generates recommendations
     * SRS FR-GEN-02a — "client SHALL audit password complexity"
     * SRS FR-GEN-02b — "client SHALL alert users to password reuse"
     *
     * @param credentials Active (non-deleted) credentials from VaultDao
     * @return HealthReport with all counts, lists, and recommendations
     */
    fun audit(credentials: List<PasswordEntity>): HealthReport {
        val totalEntries = credentials.size

        // Categorize by stored strength — SRS FR-GEN-02a
        val weakEntries = credentials.filter {
            it.passwordStrength.equals("WEAK", ignoreCase = true)
        }
        val mediumEntries = credentials.filter {
            it.passwordStrength.equals("MEDIUM", ignoreCase = true)
        }
        val strongEntries = credentials.filter {
            it.passwordStrength.equals("STRONG", ignoreCase = true)
        }

        // Detect reused passwords — PRD F-GEN-02 AC#3, SRS FR-GEN-02b
        // Group by encrypted_password; groups with >1 entry = reused
        val reusedGroups = credentials
            .groupBy { it.encryptedPassword }
            .filter { it.value.size > 1 }

        val reusedCount = reusedGroups.values.sumOf { it.size }

        // Build recommendations — PRD F-GEN-02 AC#2
        val recommendations = mutableListOf<Recommendation>()

        if (weakEntries.isNotEmpty()) {
            recommendations.add(
                Recommendation(
                    type = RecommendationType.WEAK_PASSWORD,
                    message = "${weakEntries.size} weak password${if (weakEntries.size > 1) "s" else ""} detected. " +
                            "Consider strengthening these passwords.",
                    affectedEntries = weakEntries
                )
            )
        }

        if (reusedGroups.isNotEmpty()) {
            val allReused = reusedGroups.values.flatten()
            recommendations.add(
                Recommendation(
                    type = RecommendationType.DUPLICATE_PASSWORD,
                    message = "${reusedGroups.size} duplicate password group${if (reusedGroups.size > 1) "s" else ""} detected. " +
                            "Using unique passwords for each account is critical.",
                    affectedEntries = allReused
                )
            )
        }

        return HealthReport(
            totalEntries = totalEntries,
            weakCount = weakEntries.size,
            mediumCount = mediumEntries.size,
            strongCount = strongEntries.size,
            reusedCount = reusedCount,
            weakEntries = weakEntries,
            reusedGroups = reusedGroups,
            recommendations = recommendations
        )
    }

    /**
     * Checks if a password is reused among the given credentials.
     *
     * PRD F-GEN-02 AC#3 — "If the password matches another active vault entry,
     * display a Warning Dialog notifying the user of duplication."
     *
     * @param encryptedPassword The encrypted password being saved
     * @param existingCredentials All active credentials (excluding the one being edited)
     * @return List of credentials that have the same password (empty if no reuse)
     */
    fun checkForReuse(
        encryptedPassword: String,
        existingCredentials: List<PasswordEntity>
    ): List<PasswordEntity> {
        return existingCredentials.filter {
            it.encryptedPassword == encryptedPassword
        }
    }
}
