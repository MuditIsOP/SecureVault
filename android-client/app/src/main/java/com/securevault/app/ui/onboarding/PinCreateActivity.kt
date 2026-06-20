package com.securevault.app.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.securevault.app.R
import com.securevault.app.security.PinHasher
import com.securevault.app.data.AppDatabase
import com.securevault.app.data.DatabaseModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SCR-ONB-03 — PIN Creation Screen.
 *
 * Spec refs:
 *   - Screens.md SCR-ONB-03 — Numeric Keypad, PIN Dot Indicators, states, exit point
 *   - PRD F-AUTH-03 AC#1 — "enter and confirm a 6-digit numeric PIN"
 *   - PRD F-AUTH-03 AC#2 — "PIN must be stored locally in the encrypted SQLCipher database"
 *   - SRS FR-AUTH-03 — 6-digit PIN, local validation
 *   - Security_Requirements.md §5 — ^[0-9]{6}$ validation [MUST]
 *   - Database_Schema.md §2.1 — pin_hash column, RESTRICTED
 *   - Technical_Requirements.md §8 — PIN never written to persistent storage in plaintext
 *   - Routes.md RT-ONB-03 — Onboarding Stack, exit → SCR-ONB-04 (BackupCodesActivity)
 *   - Security_Requirements.md §1 [MUST] — FLAG_SECURE
 *   - Design.md §2.1 — background #141218, surface-variant #49454F
 *   - Design.md §3.2 — Numeric Keypad (3×4 grid, 64dp circles), PIN Dot Indicators (6 dots)
 *
 * Two-phase flow:
 *   Phase 1 — "Enter PIN" — user inputs first 6 digits
 *   Phase 2 — "Confirm PIN" — user re-inputs to confirm; mismatch → resets to Phase 1
 *
 * States (Screens.md SCR-ONB-03):
 *   Loading — keys disabled (while DB write happens)
 *   Empty   — blank dots
 *   Error   — "PINs do not match. Please re-enter." → clear and restart Phase 1
 *
 * Exit point: SCR-ONB-04 (BackupCodesActivity) — Routes.md RT-ONB-04
 */
class PinCreateActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IS_RESET = "extra_is_reset"
    }

    private val TAG = "PinCreateActivity"

    private val currentInput = StringBuilder()
    private var firstPin: String? = null
    private var isConfirmPhase = false

    // Dot indicator views — Design.md §3.2 PIN Dot Indicators
    private val dotViews = ArrayList<android.view.View>(6)

    /** Launcher for ChallengeQuestionActivity when re-login requires security question */
    private val challengeLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Security question verified — allow PIN creation
            android.util.Log.i(TAG, "Security question verified, proceeding to PIN creation")
        } else {
            // Failed or cancelled — go back to login
            Toast.makeText(this, "Security question verification required.", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [MUST] FLAG_SECURE — Security_Requirements.md §1
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_pin_create)

        bindDotViews()
        bindKeypad()
        updateTitle()

        // If re-login after logout, verify security question first
        if (intent.getBooleanExtra("require_security_question", false)) {
            launchSecurityChallenge()
        }
    }

    private fun launchSecurityChallenge() {
        val questionText = getSharedPreferences("securevault_prefs", MODE_PRIVATE)
            .getString("security_question_text", "") ?: ""

        val intent = Intent(this, com.securevault.app.ui.auth.ChallengeQuestionActivity::class.java)
        intent.putExtra(com.securevault.app.ui.auth.ChallengeQuestionActivity.EXTRA_QUESTION_TEXT, questionText)
        challengeLauncher.launch(intent)
    }

    // -------------------------------------------------------------------------
    // View binding
    // -------------------------------------------------------------------------

    private fun bindDotViews() {
        val dotIds = listOf(
            R.id.dot_1, R.id.dot_2, R.id.dot_3,
            R.id.dot_4, R.id.dot_5, R.id.dot_6
        )
        dotIds.forEach { id -> dotViews.add(findViewById(id)) }
    }

    /** Design.md §3.2 Numeric Keypad — 3×4 grid, 0-9 + delete + clear */
    private fun bindKeypad() {
        val keyMap = mapOf(
            R.id.key_0 to "0", R.id.key_1 to "1", R.id.key_2 to "2",
            R.id.key_3 to "3", R.id.key_4 to "4", R.id.key_5 to "5",
            R.id.key_6 to "6", R.id.key_7 to "7", R.id.key_8 to "8",
            R.id.key_9 to "9"
        )

        keyMap.forEach { (resId, digit) ->
            findViewById<android.view.View>(resId).setOnClickListener { onDigitPressed(digit) }
        }

        findViewById<android.view.View>(R.id.key_delete).setOnClickListener { onDeletePressed() }
        findViewById<android.view.View>(R.id.key_clear).setOnClickListener { onClearPressed() }
    }

    // -------------------------------------------------------------------------
    // Keypad event handlers
    // -------------------------------------------------------------------------

    private fun onDigitPressed(digit: String) {
        // Maximum 6 digits — PRD F-AUTH-03 AC#1
        if (currentInput.length >= 6) return

        currentInput.append(digit)
        updateDots()

        // Auto-submit when 6 digits entered
        if (currentInput.length == 6) {
            onPinComplete(currentInput.toString())
        }
    }

    private fun onDeletePressed() {
        if (currentInput.isNotEmpty()) {
            currentInput.deleteCharAt(currentInput.length - 1)
            updateDots()
        }
    }

    private fun onClearPressed() {
        currentInput.clear()
        updateDots()
    }

    // -------------------------------------------------------------------------
    // PIN completion logic
    // -------------------------------------------------------------------------

    private fun onPinComplete(pin: String) {
        // Security_Requirements.md §5 — validate ^[0-9]{6}$ before any operation
        try {
            PinHasher.validate(pin)
        } catch (e: IllegalArgumentException) {
            resetPhase1()
            return
        }

        if (!isConfirmPhase) {
            // Phase 1 complete — store first PIN and move to confirmation
            firstPin = pin
            isConfirmPhase = true
            currentInput.clear()
            updateDots()
            updateTitle()
        } else {
            // Phase 2 — confirm
            if (pin == firstPin) {
                onPinConfirmed(pin)
            } else {
                // Screens.md SCR-ONB-03 Error state
                Toast.makeText(
                    this,
                    "PINs do not match. Please re-enter.",
                    Toast.LENGTH_SHORT
                ).show()
                resetPhase1()
            }
        }

        // Zero the pin string reference after use — Technical_Requirements.md §8
        // Note: Kotlin String is immutable, but we null the firstPin ref after confirm
    }

    private fun onPinConfirmed(pin: String) {
        setKeysEnabled(false)   // Loading state — Screens.md SCR-ONB-03

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Hash the PIN on background thread — PRD F-AUTH-03 AC#2
                val pinHash = withContext(Dispatchers.Default) {
                    PinHasher.hash(pin)
                }

                val isReset = intent.getBooleanExtra(EXTRA_IS_RESET, false)

                // Write hash to local SQLCipher DB
                withContext(Dispatchers.IO) {
                    val db = DatabaseModule.provideDatabase(applicationContext)
                    val user = db.userDao().getUser()
                    if (user != null) {
                        db.userDao().updatePin(user.id, pinHash)
                        if (isReset) {
                            com.securevault.app.security.PINLockoutManager.resetLockoutState(applicationContext)
                        }
                    }
                }

                // Clear sensitive state
                firstPin = null
                currentInput.clear()

                if (isReset) {
                    // Navigate to Dashboard directly
                    val intent = Intent(this@PinCreateActivity, Class.forName("com.securevault.app.ui.vault.DashboardActivity"))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                } else {
                    // Navigate to backup codes — SCR-ONB-04, Routes.md RT-ONB-04
                    val intent = Intent(this@PinCreateActivity, BackupCodesActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
            } catch (e: Exception) {
                setKeysEnabled(true)
                Toast.makeText(this@PinCreateActivity,
                    "Failed to save PIN. Please try again.", Toast.LENGTH_LONG).show()
                resetPhase1()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Database write — PRD F-AUTH-03 AC#2
    // -------------------------------------------------------------------------

    /**
     * Writes the pin_hash to the users table in the local SQLCipher database.
     * Called on Dispatchers.IO.
     * Database_Schema.md §2.1 — pin_hash column, RESTRICTED.
     */
    private suspend fun savePinHashToDatabase(pinHash: String) {
        // AppDatabase is already open (DatabaseModule initialized in Application class)
        // Direct SQL update — DAO for users table created in task-011
        val db = DatabaseModule.provideDatabase(applicationContext)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE users SET pin_hash = ?, updated_at = ? WHERE id = (SELECT id FROM users LIMIT 1)",
            arrayOf(pinHash, System.currentTimeMillis())
        )
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    /** Design.md §3.2 PIN Dot Indicators — filled (primary) vs empty (outline) */
    private fun updateDots() {
        for (i in 0 until 6) {
            dotViews[i].isSelected = i < currentInput.length
        }
    }

    private fun updateTitle() {
        val isReset = intent.getBooleanExtra(EXTRA_IS_RESET, false)
        val titleText = if (isConfirmPhase) {
            if (isReset) "Confirm your reset PIN" else "Confirm your PIN"
        } else {
            if (isReset) "Reset your PIN" else "Create your PIN"
        }
        findViewById<TextView>(R.id.tv_pin_title).text = titleText
    }

    private fun resetPhase1() {
        firstPin = null
        isConfirmPhase = false
        currentInput.clear()
        updateDots()
        updateTitle()
        setKeysEnabled(true)
    }

    private fun setKeysEnabled(enabled: Boolean) {
        listOf(
            R.id.key_0, R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4,
            R.id.key_5, R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9,
            R.id.key_delete, R.id.key_clear
        ).forEach { id -> findViewById<android.view.View>(id).isEnabled = enabled }
    }
}
