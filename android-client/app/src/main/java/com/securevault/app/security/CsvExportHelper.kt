package com.securevault.app.security

import android.content.Context
import com.securevault.app.data.entities.PasswordEntity
import java.io.File
import java.io.FileWriter

/**
 * CSV export helper for plaintext credential export.
 *
 * Spec refs:
 *   - PRD F-EXP-02 AC#3 — "generates the CSV file and triggers the Android
 *     system Share Sheet"
 *   - SRS FR-EXP-02a — "client SHALL support CSV exports"
 *   - Architecture.md — "Generate CSV → Share sheet"
 *   - Security_Requirements.md — RESTRICTED vault credentials
 *
 * CSV format: RFC 4180 compliant
 *   - Header: Name,Username,Password,Website URL
 *   - Values containing commas, quotes, or newlines are wrapped in double quotes
 *   - Double quotes within values are escaped as ""
 *
 * Edge cases handled:
 *   - Credential values containing commas → quoted
 *   - Credential values containing double quotes → escaped with ""
 *   - Credential values containing newlines → quoted
 */
object CsvExportHelper {

    private const val CSV_HEADER = "Name,Username,Password,Website URL"

    /**
     * Generates a CSV file containing all vault credentials.
     *
     * PRD F-EXP-02 AC#3 — "generates the CSV file"
     * SRS FR-EXP-02a — "client SHALL support CSV exports"
     *
     * @param context Application context for file access
     * @param credentials List of credentials to export
     * @param decryptedPasswords Map of credential ID → decrypted plaintext password
     * @return File path of the generated CSV, or null on failure
     */
    fun generateCsv(
        context: Context,
        credentials: List<PasswordEntity>,
        decryptedPasswords: Map<String, String>
    ): File? {
        try {
            val exportDir = File(context.cacheDir, "exports")
            exportDir.mkdirs()

            val timestamp = java.text.SimpleDateFormat(
                "yyyyMMdd_HHmmss", java.util.Locale.getDefault()
            ).format(java.util.Date())

            val outputFile = File(exportDir, "SecureVault_Export_$timestamp.csv")

            FileWriter(outputFile).use { writer ->
                // Header row
                writer.write(CSV_HEADER)
                writer.write("\n")

                // Data rows — PRD F-EXP-02 AC#3
                for (credential in credentials) {
                    val name = escapeCsvField(credential.name)
                    val username = escapeCsvField(credential.usernameEmail)
                    val password = escapeCsvField(
                        decryptedPasswords[credential.id] ?: ""
                    )
                    val websiteUrl = escapeCsvField(credential.websiteUrl ?: "")

                    writer.write("$name,$username,$password,$websiteUrl\n")
                }
            }

            return outputFile

        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Escapes a CSV field per RFC 4180.
     *
     * Technical consideration from task-020:
     * "Enforce strict escape rules for values containing quotes or commas"
     *
     * Rules:
     *   - If field contains comma, double-quote, or newline → wrap in quotes
     *   - Double-quote characters within field → escape as ""
     */
    fun escapeCsvField(value: String): String {
        return if (value.contains(',') || value.contains('"') ||
            value.contains('\n') || value.contains('\r')
        ) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}
