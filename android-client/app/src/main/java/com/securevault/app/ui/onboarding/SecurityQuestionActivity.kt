package com.securevault.app.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.securevault.app.R
import com.securevault.app.data.api.AuthApiService
import com.securevault.app.data.api.SecurityQuestionSetupRequest
import com.securevault.app.security.SecurityQuestionHasher
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SCR-ONB-02 — Security Question Setup Screen.
 *
 * Spec refs:
 *   - Screens.md SCR-ONB-02 — full screen spec, states, exit points
 *   - PRD F-AUTH-02 AC#1 — "select 1 of 15 predefined security questions"
 *   - PRD F-AUTH-02 AC#2 — PBKDF2-SHA256 on client; plaintext NEVER stored
 *   - SRS FR-AUTH-02 — client SHALL prompt + hash locally
 *   - Security_Requirements.md §5 — normalize answer (trim + lowercase)
 *   - Routes.md RT-ONB-02 — Onboarding Stack, entry from SCR-ONB-01
 *   - Security_Requirements.md §1 [MUST] — FLAG_SECURE
 *   - Design.md §2.1 — surface #1C1B1F, secondary #CCC2DC
 *
 * States (Screens.md SCR-ONB-02):
 *   Loading — inputs disabled, spinner active
 *   Empty   — inline hint "Answer cannot be blank"
 *   Error   — Toast "Connection timed out. Please try again." + Retry
 *
 * Exit point: SCR-ONB-03 (PinCreateActivity) — Routes.md RT-ONB-03
 */
class SecurityQuestionActivity : AppCompatActivity() {

    private val TAG = "SecurityQuestionActivity"

    // 15 predefined security questions — PRD F-AUTH-02 AC#1
    // Database_Schema.md §8 — predefined question references
    private val predefinedQuestions = listOf(
        "What was your first school's name?",
        "What was your childhood nickname?",
        "What was your favorite teacher's name?",
        "What city were you born in?",
        "What is the name of your first pet?",
        "What street did you grow up on?",
        "What was your mother's maiden name?",
        "What was the make of your first car?",
        "What is the name of your oldest sibling?",
        "In what city did you meet your partner?",
        "What was your childhood best friend's name?",
        "What was the name of your first employer?",
        "What high school did you attend?",
        "What was your favourite childhood sports team?",
        "What was the first concert you attended?"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [MUST] FLAG_SECURE — Security_Requirements.md §1
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_security_question)

        setupQuestionDropdown()
        setupContinueButton()
    }

    // -------------------------------------------------------------------------
    // Dropdown setup — PRD F-AUTH-02 AC#1: 15 predefined questions
    // -------------------------------------------------------------------------

    private fun setupQuestionDropdown() {
        val dropdown = findViewById<AutoCompleteTextView>(R.id.dropdown_security_question)
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            predefinedQuestions
        )
        dropdown.setAdapter(adapter)
    }

    // -------------------------------------------------------------------------
    // Continue button — hashes answer and calls setup API
    // -------------------------------------------------------------------------

    private fun setupContinueButton() {
        findViewById<MaterialButton>(R.id.btn_continue).setOnClickListener {
            onContinueTapped()
        }
    }

    private fun onContinueTapped() {
        val dropdown = findViewById<AutoCompleteTextView>(R.id.dropdown_security_question)
        val answerInput = findViewById<TextInputEditText>(R.id.et_security_answer)
        val answerLayout = findViewById<TextInputLayout>(R.id.til_security_answer)

        val selectedQuestion = dropdown.text.toString().trim()
        val rawAnswer = answerInput.text?.toString() ?: ""

        // Validate question selected
        if (selectedQuestion.isEmpty() || !predefinedQuestions.contains(selectedQuestion)) {
            Toast.makeText(this, "Please select a security question.", Toast.LENGTH_SHORT).show()
            return
        }

        // Screens.md SCR-ONB-02 Empty state: "Answer cannot be blank"
        val normalizedAnswer = SecurityQuestionHasher.normalize(rawAnswer)
        if (normalizedAnswer.isEmpty()) {
            answerLayout.error = "Answer cannot be blank"
            return
        }
        answerLayout.error = null

        setLoadingState(true)

        lifecycleScope.launch {
            try {
                // PRD F-AUTH-02 AC#2 — hash on client BEFORE transmission
                // SecurityQuestionHasher normalizes (trim+lowercase) and PBKDF2-hashes
                val answerHash = withContext(Dispatchers.Default) {
                    SecurityQuestionHasher.hash(rawAnswer)
                }

                // Get question ID (index-based for predefined list)
                val questionId = "q_${String.format("%02d", predefinedQuestions.indexOf(selectedQuestion) + 1)}"

                // POST /v1/auth/security-question/setup — API_Spec.md §2
                val request = SecurityQuestionSetupRequest(
                    securityQuestionId = questionId,
                    securityAnswerHash = answerHash,
                    encryptedVmk = ""   // VMK populated by KMS flow; placeholder for onboarding
                )
                AuthApiService.setupSecurityQuestion(request)

                // Navigate to PIN creation — SCR-ONB-03
                val intent = Intent(this@SecurityQuestionActivity, PinCreateActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                setLoadingState(false)
                // Screens.md SCR-ONB-02 Error state
                Toast.makeText(
                    this@SecurityQuestionActivity,
                    "Connection timed out. Please try again.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Loading state — Screens.md SCR-ONB-02
    // -------------------------------------------------------------------------

    private fun setLoadingState(isLoading: Boolean) {
        findViewById<AutoCompleteTextView>(R.id.dropdown_security_question).isEnabled = !isLoading
        findViewById<TextInputEditText>(R.id.et_security_answer).isEnabled = !isLoading
        findViewById<MaterialButton>(R.id.btn_continue).isEnabled = !isLoading
        findViewById<CircularProgressIndicator>(R.id.progress_loading).visibility =
            if (isLoading) android.view.View.VISIBLE else android.view.View.GONE
    }
}

/** Stub — implemented in task-006 */
class PinCreateActivity : AppCompatActivity()
