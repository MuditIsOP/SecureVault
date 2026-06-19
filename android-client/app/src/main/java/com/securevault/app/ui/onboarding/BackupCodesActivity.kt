package com.securevault.app.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.securevault.app.R
import com.securevault.app.data.DatabaseModule
import com.securevault.app.data.api.AuthApiService
import com.securevault.app.data.api.BackupCodesRegenerateRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import com.google.firebase.auth.FirebaseAuth
import com.securevault.app.data.api.SessionStore
import java.security.MessageDigest
import kotlin.random.Random

/**
 * SCR-ONB-04 — Backup Code Generation Screen.
 *
 * Spec refs:
 *   - Screens.md SCR-ONB-04 — monospace codes, clipboard copy, acknowledge action, loading/error states
 *   - PRD F-AUTH-05 AC#1 — generate and display exactly two unique, alphanumeric backup codes (XXXX-XXXX)
 *   - SRS FR-AUTH-05 —Recovery codes generated for offline account resets
 *   - Security_Requirements.md §4 RESTRICTED field — SHA-256 hashed before transmission/sync
 *   - Security_Requirements.md §1 [MUST] — FLAG_SECURE
 *   - Design.md — Roboto Mono, primary #D0BCFF, error red #F2B8B5
 */
class BackupCodesActivity : AppCompatActivity() {

    private lateinit var tvCode1: TextView
    private lateinit var tvCode2: TextView
    private lateinit var btnCopy: MaterialButton
    private lateinit var btnSaved: MaterialButton
    private lateinit var progressLoading: CircularProgressIndicator

    private var code1: String = ""
    private var code2: String = ""
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [MUST] FLAG_SECURE — Security_Requirements.md §1
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_backup_codes)

        tvCode1 = findViewById(R.id.tv_backup_code_1)
        tvCode2 = findViewById(R.id.tv_backup_code_2)
        btnCopy = findViewById(R.id.btn_copy_codes)
        btnSaved = findViewById(R.id.btn_saved_codes)
        progressLoading = findViewById(R.id.progress_loading)

        // Generate two unique codes
        generateCodes()

        btnCopy.setOnClickListener { copyCodesToClipboard() }
        btnSaved.setOnClickListener {
            btnSaved.isEnabled = false
            submitCodes()
        }
    }

    private fun generateCodes() {
        code1 = generateRandomCode()
        do {
            code2 = generateRandomCode()
        } while (code2 == code1)

        tvCode1.text = code1
        tvCode2.text = code2
    }

    private fun generateRandomCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val sb1 = StringBuilder()
        val sb2 = StringBuilder()
        for (i in 0 until 4) {
            sb1.append(chars[Random.nextInt(chars.length)])
            sb2.append(chars[Random.nextInt(chars.length)])
        }
        return "$sb1-$sb2"
    }

    private fun copyCodesToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val textToCopy = "$code1\n$code2"
        val clip = ClipData.newPlainText("SecureVault Recovery Codes", textToCopy)
        clipboard.setPrimaryClip(clip)

        // Visual feedback for 3 seconds per UI_Prompts.md
        scope.launch {
            btnCopy.text = "Copied!"
            btnCopy.isEnabled = false
            delay(3000)
            btnCopy.text = "Copy Codes"
            btnCopy.isEnabled = true
        }
    }

    private fun submitCodes() {
        setLoadingState(true)
        scope.launch {
            val hashes = withContext(Dispatchers.Default) {
                listOf(sha256(code1), sha256(code2))
            }
            uploadAndSaveBackupCodes(hashes)
        }
    }

    private var retryCount = 0
    private val MAX_RETRIES = 3

    private suspend fun uploadAndSaveBackupCodes(hashes: List<String>) {
        // Step 1: Always save to local SQLCipher DB first
        try {
            withContext(Dispatchers.IO) {
                val db = DatabaseModule.provideDatabase(applicationContext)
                val dbUser = db.userDao().getUser()
                if (dbUser != null) {
                    val hashesString = hashes.joinToString(",")
                    db.userDao().updateBackupCodes(dbUser.id, hashesString)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BackupCodesActivity", "Local backup save failed: ${e.message}")
        }

        // Step 2: Best-effort upload to backend (don't block user)
        try {
            val user = FirebaseAuth.getInstance().currentUser
            val freshToken = user?.getIdToken(true)?.await()?.token
            if (freshToken != null) {
                SessionStore.setToken(freshToken)
            }
            AuthApiService.regenerateBackupCodes(
                BackupCodesRegenerateRequest(challengeToken = null, hashedBackupCodes = hashes)
            )
        } catch (e: Exception) {
            android.util.Log.e("BackupCodesActivity", "Backend upload failed (non-blocking): ${e.message}")
        }

        // Step 3: Navigate to Dashboard
        navigateToDashboard()
    }

    private fun navigateToDashboard() {
        try {
            val intent = Intent(this, Class.forName("com.securevault.app.ui.vault.DashboardActivity"))
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        } catch (e: ClassNotFoundException) {
            // Fallback if DashboardActivity is not yet implemented in codebase
            Toast.makeText(this, "Onboarding complete! (Dashboard pending)", Toast.LENGTH_LONG).show()
            setLoadingState(false)
        }
    }

    private fun setLoadingState(isLoading: Boolean) {
        btnSaved.isEnabled = !isLoading
        btnCopy.isEnabled = !isLoading
        progressLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    private fun sha256(input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }
}
