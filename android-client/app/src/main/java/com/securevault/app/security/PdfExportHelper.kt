package com.securevault.app.security

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.securevault.app.data.entities.PasswordEntity
import java.io.File
import java.io.FileOutputStream

/**
 * Native PDF export helper using android.graphics.pdf.PdfDocument.
 *
 * Spec refs:
 *   - PRD F-EXP-01 AC#3 — "generate a password-protected PDF containing
 *     Name, Username, Password, and Website URL fields using Android's
 *     native PdfDocument API"
 *   - SRS FR-EXP-01a — "client SHALL support password-protected PDF exports"
 *   - Architecture.md — Security Modules, offline compilation
 *   - Security_Requirements.md — RESTRICTED vault credentials
 *   - Design.md — background #141218
 *
 * NOTE on password protection:
 *   Android's native PdfDocument API does NOT support PDF encryption.
 *   True PDF password protection requires a PDF encryption library (e.g., iText).
 *   For the initial implementation, we generate the PDF and flag for the user
 *   that the file contains sensitive data. The PDF password entered by the user
 *   is used as a marker for future library integration.
 *
 *   ⚠️ SPEC DEVIATION: PRD F-EXP-01 AC#3 says "password-protected PDF"
 *   Reason: Android native PdfDocument API has no encryption support.
 *   Impact: PDF is generated unencrypted; user is warned.
 *   Action needed: Integrate iText/PDFBox library for production encryption.
 */
object PdfExportHelper {

    private const val PAGE_WIDTH = 595  // A4 width in points (72 dpi)
    private const val PAGE_HEIGHT = 842 // A4 height in points

    private const val MARGIN_LEFT = 40f
    private const val MARGIN_TOP = 60f
    private const val MARGIN_RIGHT = 40f
    private const val LINE_HEIGHT = 18f
    private const val SECTION_GAP = 24f

    /**
     * Generates a PDF document containing all vault credentials.
     *
     * PRD F-EXP-01 AC#3 — "Name, Username, Password, and Website URL fields"
     * SRS FR-EXP-01a — "client SHALL support password-protected PDF exports"
     *
     * @param context Application context for file access
     * @param credentials List of credentials to export
     * @param pdfPassword User-entered PDF password (for future encryption)
     * @param decryptedPasswords Map of credential ID → decrypted plaintext password
     * @return File path of the generated PDF, or null on failure
     */
    fun generatePdf(
        context: Context,
        credentials: List<PasswordEntity>,
        pdfPassword: String,
        decryptedPasswords: Map<String, String>
    ): File? {
        val pdfDocument = PdfDocument()

        try {
            // Paint configurations
            val titlePaint = Paint().apply {
                color = Color.parseColor("#D0BCFF") // Design.md primary
                textSize = 24f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }

            val headerPaint = Paint().apply {
                color = Color.parseColor("#CAC4D0") // Design.md on-surface-variant
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }

            val labelPaint = Paint().apply {
                color = Color.parseColor("#938F99") // Design.md secondary
                textSize = 11f
                isAntiAlias = true
            }

            val valuePaint = Paint().apply {
                color = Color.parseColor("#E6E0E9") // Design.md on-surface
                textSize = 12f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                isAntiAlias = true
            }

            val footerPaint = Paint().apply {
                color = Color.parseColor("#49454F") // Design.md surface-variant
                textSize = 9f
                isAntiAlias = true
            }

            val bgPaint = Paint().apply {
                color = Color.parseColor("#141218") // Design.md background
            }

            val dividerPaint = Paint().apply {
                color = Color.parseColor("#49454F") // Design.md surface-variant
                strokeWidth = 0.5f
            }

            var pageNumber = 1
            var currentY = MARGIN_TOP
            var currentPage: PdfDocument.Page? = null
            var currentCanvas: Canvas? = null

            // Helper to start a new page
            fun startNewPage() {
                currentPage?.let { pdfDocument.finishPage(it) }
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
                currentPage = pdfDocument.startPage(pageInfo)
                currentCanvas = currentPage!!.canvas

                // Dark background — Design.md background #141218
                currentCanvas!!.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat(), bgPaint)

                // Footer
                currentCanvas!!.drawText(
                    "SecureVault — Confidential Export — Page $pageNumber",
                    MARGIN_LEFT,
                    (PAGE_HEIGHT - 20).toFloat(),
                    footerPaint
                )

                currentY = MARGIN_TOP
                pageNumber++
            }

            // Helper to check if we need a new page
            fun ensureSpace(needed: Float) {
                if (currentY + needed > PAGE_HEIGHT - 40) {
                    startNewPage()
                }
            }

            // Start first page
            startNewPage()

            // Title — PRD F-EXP-01
            currentCanvas!!.drawText("SecureVault Password Export", MARGIN_LEFT, currentY, titlePaint)
            currentY += 16f
            currentCanvas!!.drawText(
                "Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date())}",
                MARGIN_LEFT, currentY + LINE_HEIGHT, labelPaint
            )
            currentY += LINE_HEIGHT * 2 + 8f

            currentCanvas!!.drawText(
                "Total Credentials: ${credentials.size}",
                MARGIN_LEFT, currentY, headerPaint
            )
            currentY += SECTION_GAP

            // Divider
            currentCanvas!!.drawLine(MARGIN_LEFT, currentY, PAGE_WIDTH - MARGIN_RIGHT, currentY, dividerPaint)
            currentY += 12f

            // Credential entries — PRD F-EXP-01 AC#3
            // "Name, Username, Password, and Website URL fields"
            for ((index, credential) in credentials.withIndex()) {
                val entryHeight = LINE_HEIGHT * 5 + SECTION_GAP
                ensureSpace(entryHeight)

                // Entry number + Name
                currentCanvas!!.drawText(
                    "${index + 1}. ${credential.name}",
                    MARGIN_LEFT, currentY, headerPaint
                )
                currentY += LINE_HEIGHT + 4f

                // Username/Email
                currentCanvas!!.drawText("Username:", MARGIN_LEFT + 16f, currentY, labelPaint)
                currentCanvas!!.drawText(credential.usernameEmail, MARGIN_LEFT + 100f, currentY, valuePaint)
                currentY += LINE_HEIGHT

                // Password (decrypted)
                val decryptedPwd = decryptedPasswords[credential.id] ?: "••••••••"
                currentCanvas!!.drawText("Password:", MARGIN_LEFT + 16f, currentY, labelPaint)
                currentCanvas!!.drawText(decryptedPwd, MARGIN_LEFT + 100f, currentY, valuePaint)
                currentY += LINE_HEIGHT

                // Website URL
                val url = credential.websiteUrl ?: "—"
                currentCanvas!!.drawText("Website:", MARGIN_LEFT + 16f, currentY, labelPaint)
                currentCanvas!!.drawText(url, MARGIN_LEFT + 100f, currentY, valuePaint)
                currentY += LINE_HEIGHT

                // Divider between entries
                currentY += 4f
                currentCanvas!!.drawLine(MARGIN_LEFT, currentY, PAGE_WIDTH - MARGIN_RIGHT, currentY, dividerPaint)
                currentY += SECTION_GAP / 2
            }

            // Finish last page
            currentPage?.let { pdfDocument.finishPage(it) }

            // Write to file in cache directory
            val exportDir = File(context.cacheDir, "exports")
            exportDir.mkdirs()

            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val outputFile = File(exportDir, "SecureVault_Export_$timestamp.pdf")

            FileOutputStream(outputFile).use { fos ->
                pdfDocument.writeTo(fos)
            }

            return outputFile

        } catch (e: Exception) {
            return null
        } finally {
            pdfDocument.close()

            // Security_Requirements.md §9.1 — memory sanitization
            // PdfDocument resources released; GC handles remaining refs
        }
    }
}
