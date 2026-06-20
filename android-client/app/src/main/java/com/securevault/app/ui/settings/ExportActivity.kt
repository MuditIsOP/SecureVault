package com.securevault.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.securevault.app.R
import com.securevault.app.data.DatabaseModule
import com.securevault.app.data.entities.PasswordEntity
import com.securevault.app.security.CryptographyHelper
import com.securevault.app.security.CsvExportHelper
import com.securevault.app.security.KeystoreManager
import com.securevault.app.security.PdfExportHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Export screen — PRD F-EXP-01 (PDF) and F-EXP-02 (CSV).
 *
 * Spec refs:
 *   - PRD F-EXP-01 AC#1 — "Settings → Export → Export PDF must challenge
 *     the user with their Security Question"
 *   - PRD F-EXP-01 AC#2 — "Upon correct entry, user must input PDF encryption password"
 *   - PRD F-EXP-01 AC#3 — "generate password-protected PDF"
 *   - PRD F-EXP-01 AC#4 — "Opens system Share Sheet"
 *   - PRD F-EXP-02 AC#1 — "Settings → Export → Export CSV must challenge
 *     the user with their Security Question"
 *   - PRD F-EXP-02 AC#2 — "Displays Warning Screen stating CSV will store
 *     passwords in unencrypted plaintext"
 *   - PRD F-EXP-02 AC#3 — "generates CSV file and triggers Share Sheet"
 *   - SRS FR-EXP-01a — "client SHALL support password-protected PDF exports"
 *   - SRS FR-EXP-01b — "PDF exports SHALL require authentication confirmation"
 *   - SRS FR-EXP-02a — "client SHALL support CSV exports"
 *   - SRS FR-EXP-02b — "CSV exports SHALL present plaintext warning notification"
 *   - Security_Requirements.md §1 — [MUST] FLAG_SECURE
 *   - Design.md — background #141218, error red #F2B8B5
 *
 * Flow:
 *   Step 1: Security question challenge (shared for both PDF and CSV)
 *   Step 2a (PDF): PDF password input → generate → Share Sheet
 *   Step 2b (CSV): Plaintext warning dialog → generate → Share Sheet
 */
class ExportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EXPORT_TYPE = "export_type"
        const val EXPORT_TYPE_PDF = "pdf"
        const val EXPORT_TYPE_CSV = "csv"
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var layoutStepSecurity: View
    private lateinit var layoutStepPassword: View
    private lateinit var progressExport: ProgressBar
    private lateinit var tvSecurityQuestion: TextView
    private lateinit var etSecurityAnswer: TextInputEditText
    private lateinit var btnVerifyAnswer: MaterialButton
    private lateinit var etPdfPassword: TextInputEditText
    private lateinit var etPdfPasswordConfirm: TextInputEditText
    private lateinit var btnGeneratePdf: MaterialButton

    private var storedQuestionHash: String = ""
    private var storedAnswerHash: String = ""

    /** Export type: "pdf" or "csv" — determined by EXTRA_EXPORT_TYPE intent */
    private var exportType: String = EXPORT_TYPE_PDF

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [MUST] FLAG_SECURE — Security_Requirements.md §1
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_export)

        exportType = intent.getStringExtra(EXTRA_EXPORT_TYPE) ?: EXPORT_TYPE_PDF

        bindViews()
        setupToolbar()
        loadSecurityQuestion()
        setupVerifyButton()
        setupGenerateButton()
    }

    // -------------------------------------------------------------------------
    // View binding
    // -------------------------------------------------------------------------

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar_export)
        layoutStepSecurity = findViewById(R.id.layout_step_security)
        layoutStepPassword = findViewById(R.id.layout_step_password)
        progressExport = findViewById(R.id.progress_export)
        tvSecurityQuestion = findViewById(R.id.tv_security_question)
        etSecurityAnswer = findViewById(R.id.et_security_answer)
        btnVerifyAnswer = findViewById(R.id.btn_verify_answer)
        etPdfPassword = findViewById(R.id.et_pdf_password)
        etPdfPasswordConfirm = findViewById(R.id.et_pdf_password_confirm)
        btnGeneratePdf = findViewById(R.id.btn_generate_pdf)

        // Update toolbar title based on export type
        if (exportType == EXPORT_TYPE_CSV) {
            toolbar.title = "Export CSV"
        }
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener { finish() }
    }

    // -------------------------------------------------------------------------
    // Step 1: Security Question Challenge
    // PRD F-EXP-01 AC#1 / F-EXP-02 AC#1
    // SRS FR-EXP-01b — "require authentication confirmation before generation"
    // -------------------------------------------------------------------------

    private fun loadSecurityQuestion() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val db = DatabaseModule.provideDatabase(applicationContext)
                val userId = withContext(Dispatchers.IO) {
                    val cursor = db.openHelper.readableDatabase
                        .query("SELECT id FROM users LIMIT 1")
                    cursor.use {
                        if (it.moveToFirst()) it.getString(0) else ""
                    }
                }

                // Load security question from users table (set in task-005)
                val questionData = withContext(Dispatchers.IO) {
                    val cursor = db.openHelper.readableDatabase
                        .query(
                            "SELECT security_question, security_answer_hash FROM users WHERE id = ?",
                            arrayOf(userId)
                        )
                    cursor.use {
                        if (it.moveToFirst()) {
                            Pair(
                                it.getString(0) ?: "",
                                it.getString(1) ?: ""
                            )
                        } else {
                            Pair("", "")
                        }
                    }
                }

                storedQuestionHash = questionData.first
                storedAnswerHash = questionData.second

                if (storedQuestionHash.isEmpty()) {
                    tvSecurityQuestion.text = "No security question configured."
                    btnVerifyAnswer.isEnabled = false
                } else {
                    tvSecurityQuestion.text = storedQuestionHash
                }

            } catch (e: Exception) {
                Toast.makeText(this@ExportActivity,
                    "Failed to load security question.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupVerifyButton() {
        btnVerifyAnswer.setOnClickListener {
            val answer = etSecurityAnswer.text?.toString()?.trim() ?: ""

            if (answer.isEmpty()) {
                etSecurityAnswer.error = "Answer is required"
                return@setOnClickListener
            }

            // Verify answer hash — task-005 stores SHA-256 hash
            val answerHash = hashAnswer(answer)
            if (answerHash == storedAnswerHash) {
                // Security_Requirements.md §9.1 — clear answer from memory
                etSecurityAnswer.setText("")

                // Branch based on export type
                when (exportType) {
                    EXPORT_TYPE_PDF -> {
                        // PRD F-EXP-01 AC#2 — proceed to PDF password step
                        layoutStepSecurity.visibility = View.GONE
                        layoutStepPassword.visibility = View.VISIBLE
                    }
                    EXPORT_TYPE_CSV -> {
                        // PRD F-EXP-02 AC#2 — show plaintext warning dialog
                        layoutStepSecurity.visibility = View.GONE
                        showCsvPlaintextWarning()
                    }
                }
            } else {
                etSecurityAnswer.error = "Incorrect answer"
                Toast.makeText(this, "Security answer is incorrect.",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * SHA-256 hash of the security answer — matches task-005 storage format.
     */
    private fun hashAnswer(answer: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(answer.lowercase().trim().toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    // -------------------------------------------------------------------------
    // Step 2a: PDF Password + Generation — PRD F-EXP-01 AC#2, AC#3
    // -------------------------------------------------------------------------

    private fun setupGenerateButton() {
        btnGeneratePdf.setOnClickListener {
            val password = etPdfPassword.text?.toString() ?: ""
            val confirmPassword = etPdfPasswordConfirm.text?.toString() ?: ""

            if (password.isEmpty()) {
                etPdfPassword.error = "Password is required"
                return@setOnClickListener
            }
            if (password.length < 4) {
                etPdfPassword.error = "Password must be at least 4 characters"
                return@setOnClickListener
            }
            if (password != confirmPassword) {
                etPdfPasswordConfirm.error = "Passwords do not match"
                return@setOnClickListener
            }

            generateAndSharePdf(password)
        }
    }

    private fun generateAndSharePdf(pdfPassword: String) {
        progressExport.visibility = View.VISIBLE
        btnGeneratePdf.isEnabled = false

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val (credentials, decryptedPasswords) = fetchAndDecryptCredentials()
                    ?: return@launch

                // Generate PDF — PRD F-EXP-01 AC#3, SRS FR-EXP-01a
                val pdfFile = withContext(Dispatchers.IO) {
                    PdfExportHelper.generatePdf(
                        context = applicationContext,
                        credentials = credentials,
                        pdfPassword = pdfPassword,
                        decryptedPasswords = decryptedPasswords
                    )
                }

                // Security_Requirements.md §9.1 — clear decrypted passwords
                decryptedPasswords.clear()

                if (pdfFile == null || !pdfFile.exists()) {
                    Toast.makeText(this@ExportActivity,
                        "Failed to generate PDF.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Open Share Sheet — PRD F-EXP-01 AC#4
                openShareSheet(pdfFile, "application/pdf", "Share PDF Export")

                // Clear password fields
                etPdfPassword.setText("")
                etPdfPasswordConfirm.setText("")

            } catch (e: Exception) {
                Toast.makeText(this@ExportActivity,
                    "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                progressExport.visibility = View.GONE
                btnGeneratePdf.isEnabled = true
            }
        }
    }

    // -------------------------------------------------------------------------
    // Step 2b: CSV Plaintext Warning + Generation — PRD F-EXP-02 AC#2, AC#3
    // SRS FR-EXP-02b — "CSV exports SHALL present plaintext warning notification"
    // -------------------------------------------------------------------------

    /**
     * Shows the plaintext warning dialog before CSV generation.
     *
     * PRD F-EXP-02 AC#2 — "Displays a Warning Screen stating that the
     * exported CSV will store passwords in unencrypted plaintext"
     * SRS FR-EXP-02b — "CSV exports SHALL present a plaintext warning
     * notification before execution"
     * Design.md — error red #F2B8B5
     */
    private fun showCsvPlaintextWarning() {
        MaterialAlertDialogBuilder(this)
            .setTitle("⚠️ Plaintext Warning")
            .setMessage(
                "The exported CSV file will contain all your passwords " +
                "in UNENCRYPTED PLAINTEXT.\n\n" +
                "Anyone with access to this file will be able to read " +
                "your credentials without any password.\n\n" +
                "Only proceed if you understand the security risks."
            )
            .setPositiveButton("Export Anyway") { _, _ ->
                // User acknowledged warning — proceed with CSV generation
                generateAndShareCsv()
            }
            .setNegativeButton("Cancel") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Generates CSV and opens Share Sheet.
     *
     * PRD F-EXP-02 AC#3 — "generates the CSV file and triggers the
     * Android system Share Sheet"
     * SRS FR-EXP-02a — "client SHALL support CSV exports"
     */
    private fun generateAndShareCsv() {
        progressExport.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val (credentials, decryptedPasswords) = fetchAndDecryptCredentials()
                    ?: return@launch

                // Generate CSV — PRD F-EXP-02 AC#3, SRS FR-EXP-02a
                val csvFile = withContext(Dispatchers.IO) {
                    CsvExportHelper.generateCsv(
                        context = applicationContext,
                        credentials = credentials,
                        decryptedPasswords = decryptedPasswords
                    )
                }

                // Security_Requirements.md §9.1 — clear decrypted passwords
                decryptedPasswords.clear()

                if (csvFile == null || !csvFile.exists()) {
                    Toast.makeText(this@ExportActivity,
                        "Failed to generate CSV.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Open Share Sheet — PRD F-EXP-02 AC#3
                openShareSheet(csvFile, "text/csv", "Share CSV Export")

            } catch (e: Exception) {
                Toast.makeText(this@ExportActivity,
                    "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                progressExport.visibility = View.GONE
            }
        }
    }

    // -------------------------------------------------------------------------
    // Shared: Fetch + Decrypt credentials
    // -------------------------------------------------------------------------

    /**
     * Fetches all active credentials and decrypts passwords in memory.
     * Shared between PDF and CSV export flows.
     *
     * @return Pair of (credentials, decryptedPasswords map), or null if empty
     */
    private suspend fun fetchAndDecryptCredentials():
            Pair<List<PasswordEntity>, MutableMap<String, String>>? {
        val db = DatabaseModule.provideDatabase(applicationContext)
        val userId = withContext(Dispatchers.IO) {
            val cursor = db.openHelper.readableDatabase
                .query("SELECT id FROM users LIMIT 1")
            cursor.use {
                if (it.moveToFirst()) it.getString(0) else ""
            }
        }

        val credentials = withContext(Dispatchers.IO) {
            db.vaultDao().getActiveCredentials(userId)
        }

        if (credentials.isEmpty()) {
            Toast.makeText(this@ExportActivity,
                "No credentials to export.", Toast.LENGTH_SHORT).show()
            return null
        }

        // Decrypt all passwords in memory — Security_Requirements.md RESTRICTED
        val decryptedPasswords = withContext(Dispatchers.IO) {
            val vmkKey = KeystoreManager.getOrCreateVmkKey()
            val map = mutableMapOf<String, String>()
            if (vmkKey != null) {
                for (credential in credentials) {
                    try {
                        val decrypted = CryptographyHelper.decrypt(
                            credential.encryptedPassword, vmkKey
                        )
                        map[credential.id] = decrypted
                    } catch (e: Exception) {
                        map[credential.id] = "••••••••"
                    }
                }
            }
            map
        }

        return Pair(credentials, decryptedPasswords)
    }

    // -------------------------------------------------------------------------
    // Share Sheet — PRD F-EXP-01 AC#4, F-EXP-02 AC#3
    // "Opens the system Share Sheet to permit saving or sending"
    // -------------------------------------------------------------------------

    private fun openShareSheet(file: File, mimeType: String, chooserTitle: String) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "SecureVault Password Export")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, chooserTitle))

        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open share sheet.", Toast.LENGTH_SHORT).show()
        }
    }
}
