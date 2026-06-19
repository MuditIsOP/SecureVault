package com.securevault.app.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.securevault.app.R
import com.securevault.app.data.api.AuthApiService
import com.securevault.app.data.api.SecurityQuestionVerifyRequest
import com.securevault.app.security.SecurityQuestionHasher
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SCR-ATH-01 — Security Question Challenge Screen.
 *
 * Spec refs:
 *   - Screens.md SCR-ATH-01 — full screen spec, states, exit points
 *   - PRD F-AUTH-02 AC#3 — challenge gates for: new device login, re-auth,
 *     account switch, CSV/PDF export, PIN change, question change, backup code regen
 *   - API_Spec.md §2 POST /v1/auth/security-question/verify
 *   - Security_Requirements.md §5 — normalize answer before hashing
 *   - Security_Requirements.md §6 Brute Force — adaptive delay (1s, 2s, 5s, 30s+)
 *   - Routes.md RT-ATH-01 — Auth Stack entry from SCR-ONB-01 or re-auth paths
 *   - Permissions_Matrix.md §3 C2 & C3 — verification token required for admin changes
 *   - Security_Requirements.md §1 [MUST] — FLAG_SECURE
 *   - Design.md §2.1 — surface #1C1B1F, secondary #CCC2DC
 *
 * States (Screens.md SCR-ATH-01):
 *   Loading — inputs disabled, spinner active
 *   Empty   — inline error "Answer field is empty"
 *   Error   — Toast "Incorrect answer. [N] attempts remaining."
 *
 * Exit point: SCR-ATH-02 (PinUnlockActivity) after success — Routes.md RT-ATH-02
 *
 * Intent extras:
 *   EXTRA_QUESTION_TEXT  — the display text of the user's security question
 *   EXTRA_PURPOSE        — reason for challenge: "login", "export", "pin_change", etc.
 * Result:
 *   EXTRA_CHALLENGE_TOKEN — short-lived JWT challenge token returned to caller
 */
class ChallengeQuestionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QUESTION_TEXT = "extra_question_text"
        const val EXTRA_PURPOSE = "extra_purpose"
        const val EXTRA_CHALLENGE_TOKEN = "extra_challenge_token"

        /** Adaptive delay thresholds per Security_Requirements.md §6 Brute Force */
        private val ADAPTIVE_DELAYS_MS = longArrayOf(0L, 0L, 2_000L, 5_000L, 30_000L)
    }

    private var failedAttempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [MUST] FLAG_SECURE — Security_Requirements.md §1
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_challenge_question)

        var questionText = intent.getStringExtra(EXTRA_QUESTION_TEXT) ?: ""
        // Fallback: read from SharedPreferences if intent didn't provide it
        if (questionText.isEmpty()) {
            questionText = getSharedPreferences("securevault_prefs", MODE_PRIVATE)
                .getString("security_question_text", "") ?: ""
        }
        // Last resort fallback
        if (questionText.isEmpty()) {
            questionText = "Answer your security question"
        }
        findViewById<TextView>(R.id.tv_security_question).text = questionText

        setupVerifyButton()
    }

    // -------------------------------------------------------------------------
    // Verify button — POST /v1/auth/security-question/verify
    // -------------------------------------------------------------------------

    private fun setupVerifyButton() {
        findViewById<MaterialButton>(R.id.btn_verify).setOnClickListener {
            onVerifyTapped()
        }
    }

    private fun onVerifyTapped() {
        val answerInput = findViewById<TextInputEditText>(R.id.et_answer)
        val answerLayout = findViewById<TextInputLayout>(R.id.til_answer)
        val rawAnswer = answerInput.text?.toString() ?: ""

        // Screens.md SCR-ATH-01 Empty state
        val normalized = SecurityQuestionHasher.normalize(rawAnswer)
        if (normalized.isEmpty()) {
            answerLayout.error = "Answer field is empty"
            return
        }
        answerLayout.error = null

        setLoadingState(true)

        lifecycleScope.launch {
            // Adaptive delay before processing — Security_Requirements.md §6
            val delayIndex = minOf(failedAttempts, ADAPTIVE_DELAYS_MS.size - 1)
            val delayMs = ADAPTIVE_DELAYS_MS[delayIndex]
            if (delayMs > 0) {
                kotlinx.coroutines.delay(delayMs)
            }

            try {
                val request = SecurityQuestionVerifyRequest(securityAnswer = normalized)
                val response = AuthApiService.verifySecurityQuestion(request)

                // Success — pass challenge token back to caller
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_CHALLENGE_TOKEN, response.challengeToken)
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            } catch (e: com.securevault.app.data.api.AuthException) {
                setLoadingState(false)
                failedAttempts++
                val remaining = maxOf(0, 5 - failedAttempts)
                // Screens.md SCR-ATH-01 Error state
                Toast.makeText(
                    this@ChallengeQuestionActivity,
                    "Incorrect answer. $remaining attempt${if (remaining == 1) "" else "s"} remaining.",
                    Toast.LENGTH_LONG
                ).show()
                answerInput.text?.clear()
            } catch (e: Exception) {
                setLoadingState(false)
                Toast.makeText(this@ChallengeQuestionActivity,
                    "Connection error. Please try again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Loading state — Screens.md SCR-ATH-01
    // -------------------------------------------------------------------------

    private fun setLoadingState(isLoading: Boolean) {
        findViewById<TextInputEditText>(R.id.et_answer).isEnabled = !isLoading
        findViewById<MaterialButton>(R.id.btn_verify).isEnabled = !isLoading
        findViewById<CircularProgressIndicator>(R.id.progress_loading).visibility =
            if (isLoading) android.view.View.VISIBLE else android.view.View.GONE
    }
}
