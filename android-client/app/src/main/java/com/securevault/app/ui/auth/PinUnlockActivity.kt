package com.securevault.app.ui.auth

import android.content.Intent
import android.os.CountDownTimer
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.securevault.app.R
import com.securevault.app.data.DatabaseModule
import com.securevault.app.security.BiometricHelper
import com.securevault.app.security.PinHasher
import com.securevault.app.security.PINLockoutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SCR-ATH-02 — PIN Unlock Screen.
 *
 * Spec refs:
 *   - Screens.md SCR-ATH-02 — PIN Keypad, PIN dots, Biometrics trigger icon, states
 *   - PRD F-AUTH-03 AC#3 — "display a PIN entry screen; correct PIN must launch
 *     Dashboard in <100ms"
 *   - SRS FR-AUTH-03 — "PIN validation SHALL complete and unlock the cache in <100ms"
 *   - SRS NFR-PERF-01 — PIN screen must render in < 2.0 seconds from app start
 *   - Architecture.md §4 Data Flow 2 — "Client validates hash against local
 *     SQLCipher password store; upon match, local Room DB unlocks"
 *   - Security_Requirements.md §5 — ^[0-9]{6}$ validation before any DB operation
 *   - Routes.md RT-ATH-02 — Auth Stack, exit → SCR-VLT-01 or SCR-ATH-03
 *   - Routes.md §6 — back press exits app
 *   - Security_Requirements.md §1 [MUST] — FLAG_SECURE
 *   - Design.md §2.1 — background #141218, surface-variant #49454F
 *   - Design.md §3.2 — Numeric Keypad, PIN Dot Indicators
 *
 * States (Screens.md SCR-ATH-02):
 *   Loading — keypad locked (during DB hash check)
 *   Error   — "Incorrect PIN. Attempts remaining: [N]" — increments failed counter
 *
 * Exit points:
 *   Correct PIN → SCR-VLT-01 (DashboardActivity)
 *   Failed PIN ≥ threshold → SCR-ATH-03 (LockoutActivity) — handled by task-007
 *
 * Professional variant (SCR-ATH-02-P) — header text differs; handled via Intent extra.
 */
class PinUnlockActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IS_PROFESSIONAL = "extra_is_professional"
        const val EXTRA_IS_CONFIRMATION = "extra_is_confirmation"
        /** Maximum allowed attempts before task-007 PINLockoutManager kicks in */
        const val MAX_ATTEMPTS_BEFORE_LOCKOUT = 5
    }

    private val currentInput = StringBuilder()
    private val dotViews = ArrayList<android.view.View>(6)
    private lateinit var tvDelayMessage: TextView
    private var delayTimer: CountDownTimer? = null
    /** True when launched as a PIN confirmation dialog (e.g. from SecuritySettingsActivity) */
    private var isConfirmationMode = false
    /** Tracks if biometric prompt has been shown this resume cycle */
    private var biometricPromptShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [MUST] FLAG_SECURE — Security_Requirements.md §1
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_pin_unlock)
        tvDelayMessage = findViewById(R.id.tv_delay_message)
        isConfirmationMode = intent.getBooleanExtra(EXTRA_IS_CONFIRMATION, false)

        bindDotViews()
        bindKeypad()
        setupProfessionalVariant()
    }

    override fun onResume() {
        super.onResume()
        checkLockoutAndDelay()
        // PRD F-AUTH-06 AC#2 — show BiometricPrompt on app launch if enabled
        // Do NOT show biometrics in confirmation mode (PIN-only confirmation)
        if (!isConfirmationMode && !biometricPromptShown) {
            attemptBiometricUnlock()
        }
    }

    private fun checkLockoutAndDelay() {
        CoroutineScope(Dispatchers.Main).launch {
            if (PINLockoutManager.isLockedOut(applicationContext)) {
                val intent = Intent(this@PinUnlockActivity, LockoutActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            } else if (PINLockoutManager.isDelayActive(applicationContext)) {
                val remainingMs = PINLockoutManager.getRemainingLockoutTime(applicationContext)
                startDelayTimer(remainingMs)
            } else {
                setKeysEnabled(true)
                tvDelayMessage.visibility = View.GONE
            }
        }
    }

    // -------------------------------------------------------------------------
    // Biometric unlock — PRD F-AUTH-06 AC#2, SRS FR-AUTH-06
    // -------------------------------------------------------------------------

    /**
     * Attempts biometric unlock if enabled and hardware available.
     *
     * PRD F-AUTH-06 AC#2: "When active, app launch displays the system
     * BiometricPrompt supporting fingerprint and face unlock"
     *
     * Handles:
     *   - Success: navigate directly to Dashboard (bypass PIN)
     *   - KeyInvalidated: show warning, force PIN fallback (F-AUTH-06 AC#3)
     *   - Cancelled/Failed: fall through to PIN keypad
     */
    private fun attemptBiometricUnlock() {
        if (!BiometricHelper.isBiometricEnabled(applicationContext)) return
        if (BiometricHelper.checkBiometricStatus(applicationContext)
                != BiometricHelper.BiometricStatus.AVAILABLE) return

        biometricPromptShown = true

        BiometricHelper.showBiometricPrompt(
            this as androidx.fragment.app.FragmentActivity
        ) { result ->
            when (result) {
                is BiometricHelper.BiometricResult.Success -> {
                    // Biometric authenticated — reset lockout and navigate to Dashboard
                    CoroutineScope(Dispatchers.Main).launch {
                        withContext(Dispatchers.IO) {
                            PINLockoutManager.resetLockoutState(applicationContext)
                        }
                        navigateToDashboard()
                    }
                }
                is BiometricHelper.BiometricResult.KeyInvalidated -> {
                    // F-AUTH-06 AC#3: Key invalidated — show warning, force PIN
                    Toast.makeText(this,
                        "Biometric key invalidated. New fingerprint or face detected. Please use PIN.",
                        Toast.LENGTH_LONG).show()
                }
                is BiometricHelper.BiometricResult.Cancelled -> {
                    // User chose "Use PIN" — fall through to keypad
                }
                is BiometricHelper.BiometricResult.Failed -> {
                    Toast.makeText(this, "Biometric authentication failed",
                        Toast.LENGTH_SHORT).show()
                }
                is BiometricHelper.BiometricResult.Error -> {
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startDelayTimer(durationMs: Long) {
        setKeysEnabled(false)
        tvDelayMessage.visibility = android.view.View.VISIBLE

        delayTimer?.cancel()
        delayTimer = object : CountDownTimer(durationMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = millisUntilFinished / 1000 + 1
                tvDelayMessage.text = "PIN entry disabled. Try again in ${sec}s"
            }

            override fun onFinish() {
                setKeysEnabled(true)
                tvDelayMessage.visibility = android.view.View.GONE
            }
        }.start()
    }

    // -------------------------------------------------------------------------
    // View binding
    // -------------------------------------------------------------------------

    private fun bindDotViews() {
        listOf(R.id.dot_1, R.id.dot_2, R.id.dot_3, R.id.dot_4, R.id.dot_5, R.id.dot_6)
            .forEach { id -> dotViews.add(findViewById(id)) }
    }

    private fun bindKeypad() {
        val digits = mapOf(
            R.id.key_0 to "0", R.id.key_1 to "1", R.id.key_2 to "2",
            R.id.key_3 to "3", R.id.key_4 to "4", R.id.key_5 to "5",
            R.id.key_6 to "6", R.id.key_7 to "7", R.id.key_8 to "8",
            R.id.key_9 to "9"
        )

        digits.forEach { (resId, digit) ->
            findViewById<android.view.View>(resId).setOnClickListener { onDigitPressed(digit) }
        }

        findViewById<android.view.View>(R.id.key_delete).setOnClickListener {
            if (currentInput.isNotEmpty()) {
                currentInput.deleteCharAt(currentInput.length - 1)
                updateDots()
            }
        }
    }

    /**
     * SCR-ATH-02-P Professional variant — shows corporate header.
     * Screens.md SCR-ATH-02 Role variant.
     */
    private fun setupProfessionalVariant() {
        val isProfessional = intent.getBooleanExtra(EXTRA_IS_PROFESSIONAL, false)
        if (isProfessional) {
            val headerView = findViewById<TextView?>(R.id.tv_professional_header)
            headerView?.visibility = android.view.View.VISIBLE
        }
    }

    // -------------------------------------------------------------------------
    // Keypad event handlers
    // -------------------------------------------------------------------------

    private fun onDigitPressed(digit: String) {
        if (currentInput.length >= 6) return
        currentInput.append(digit)
        updateDots()

        if (currentInput.length == 6) {
            onPinComplete(currentInput.toString())
        }
    }

    // -------------------------------------------------------------------------
    // PIN verification — PRD F-AUTH-03 AC#3, SRS FR-AUTH-03
    // -------------------------------------------------------------------------

    private fun onPinComplete(pin: String) {
        // Security test: invalid format must NOT trigger DB operation
        // Security_Requirements.md §5 — validate before any decryption
        try {
            PinHasher.validate(pin)
        } catch (e: IllegalArgumentException) {
            currentInput.clear()
            updateDots()
            return
        }

        setKeysEnabled(false)   // SCR-ATH-02 Loading state

        CoroutineScope(Dispatchers.Main).launch {
            val startMs = SystemClock.elapsedRealtime()

            try {
                val storedHash = withContext(Dispatchers.IO) { loadStoredPinHash() }

                if (storedHash == null) {
                    // No PIN stored — redirect to PIN creation
                    Toast.makeText(this@PinUnlockActivity,
                        "No PIN set. Please create a PIN.", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this@PinUnlockActivity,
                        com.securevault.app.ui.onboarding.PinCreateActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                    return@launch
                }

                // PRD F-AUTH-03 AC#3 — verify must complete < 100ms
                val isValid = withContext(Dispatchers.Default) {
                    PinHasher.verify(pin, storedHash)
                }

                val elapsed = SystemClock.elapsedRealtime() - startMs

                if (isValid) {
                    // Reset lockout state locally and on backend
                    withContext(Dispatchers.IO) {
                        PINLockoutManager.resetLockoutState(applicationContext)
                    }

                    // Clear input before navigating — Technical_Requirements.md §8
                    currentInput.clear()

                    if (isConfirmationMode) {
                        // PIN confirmed — return success to caller
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        // Navigate to Dashboard — SCR-VLT-01
                        navigateToDashboard()
                    }
                } else {
                    // Record failed attempt
                    val lockoutState = withContext(Dispatchers.IO) {
                        PINLockoutManager.recordFailedAttempt(applicationContext)
                    }

                    setKeysEnabled(true)
                    currentInput.clear()
                    updateDots()

                    if (lockoutState.isFullLockout) {
                        // Redirect to LockoutActivity
                        val intent = Intent(this@PinUnlockActivity, LockoutActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    } else if (lockoutState.delayMs > 0) {
                        // Start progressive delay countdown timer
                        startDelayTimer(lockoutState.delayMs)
                    } else {
                        val remaining = 11 - lockoutState.failedAttempts
                        Toast.makeText(
                            this@PinUnlockActivity,
                            "Incorrect PIN. Attempts remaining: $remaining",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                setKeysEnabled(true)
                currentInput.clear()
                updateDots()
                Toast.makeText(this@PinUnlockActivity,
                    "Verification error. Please try again.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Database read — Architecture.md §4 Data Flow 2
    // -------------------------------------------------------------------------

    /** Loads the stored pin_hash from the users table. Called on Dispatchers.IO. */
    private fun loadStoredPinHash(): String? {
        val db = DatabaseModule.provideDatabase(applicationContext)
        val cursor = db.openHelper.readableDatabase
            .query("SELECT pin_hash FROM users LIMIT 1")
        return cursor.use {
            if (it.moveToFirst()) it.getString(0).takeIf { h -> h.isNotEmpty() }
            else null
        }
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private fun updateDots() {
        for (i in 0 until 6) {
            dotViews[i].isSelected = i < currentInput.length
        }
    }

    /**
     * Navigates to DashboardActivity, clearing the back stack.
     * Used by both PIN and biometric success paths.
     */
    private fun navigateToDashboard() {
        val intent = Intent(this@PinUnlockActivity,
            Class.forName("com.securevault.app.ui.vault.DashboardActivity"))
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setKeysEnabled(enabled: Boolean) {
        listOf(
            R.id.key_0, R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4,
            R.id.key_5, R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9,
            R.id.key_delete
        ).forEach { id -> findViewById<android.view.View>(id).isEnabled = enabled }
    }

    // -------------------------------------------------------------------------
    // Back interception — exits app (Routes.md §6)
    // -------------------------------------------------------------------------

    @Deprecated("Overriding for back interception per Routes.md §6")
    override fun onBackPressed() {
        // Routes.md §6: "PIN Lock Screen (RT-ATH-02): Intercepted to exit the application"
        finishAffinity()
    }
}
