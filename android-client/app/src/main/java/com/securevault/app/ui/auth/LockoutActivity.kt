package com.securevault.app.ui.auth

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.securevault.app.R
import com.securevault.app.data.api.AuthApiService
import com.securevault.app.data.api.BackupCodesVerifyRequest
import com.securevault.app.security.PINLockoutManager
import com.securevault.app.ui.onboarding.PinCreateActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * SCR-ATH-03 — PIN Lockout Screen.
 *
 * Spec refs:
 *   - Screens.md SCR-ATH-03 — Lockout Screen: countdown timer, disabled entry controls
 *   - PRD F-AUTH-04 AC#3 — "lockout state for exactly 2 hours"
 *   - SRS FR-AUTH-04 — cooldown delay lockouts
 *   - Design.md — error red (#F2B8B5), Display Large typography
 *   - Security_Requirements.md §1 [MUST] — FLAG_SECURE
 *   - Routes.md RT-ATH-03 — routes back to SCR-ATH-02 on expiry; back exits app
 */
class LockoutActivity : AppCompatActivity() {

    private lateinit var tvCountdown: TextView
    private lateinit var btnEmergencyRecovery: MaterialButton
    private var countDownTimer: CountDownTimer? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [MUST] FLAG_SECURE — Security_Requirements.md §1
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_lockout)
        tvCountdown = findViewById(R.id.tv_lockout_countdown)
        btnEmergencyRecovery = findViewById(R.id.btn_emergency_recovery)

        btnEmergencyRecovery.setOnClickListener { showRecoveryDialog() }

        startLockoutCheck()
    }

    private fun startLockoutCheck() {
        CoroutineScope(Dispatchers.Main).launch {
            val remainingMs = withContext(Dispatchers.IO) {
                PINLockoutManager.getRemainingLockoutTime(applicationContext)
            }

            if (remainingMs <= 0L) {
                // If lockout already expired, go back to PIN unlock
                navigateToUnlock()
            } else {
                startCountdown(remainingMs)
            }
        }
    }

    private fun startCountdown(durationMs: Long) {
        countDownTimer?.cancel()

        countDownTimer = object : CountDownTimer(durationMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                updateTimerText(millisUntilFinished)
            }

            override fun onFinish() {
                CoroutineScope(Dispatchers.Main).launch {
                    // Reset lockout state on expiry
                    withContext(Dispatchers.IO) {
                        PINLockoutManager.resetLockoutState(applicationContext)
                    }
                    navigateToUnlock()
                }
            }
        }.start()
    }

    private fun updateTimerText(millis: Long) {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60

        tvCountdown.text = String.format(
            Locale.getDefault(),
            "%02d:%02d:%02d",
            hours,
            minutes,
            seconds
        )
    }

    private fun navigateToUnlock() {
        val intent = Intent(this, PinUnlockActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun showRecoveryDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_backup_code, null)
        val tilBackupCode = view.findViewById<TextInputLayout>(R.id.til_backup_code)
        val etBackupCode = view.findViewById<TextInputEditText>(R.id.et_backup_code)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Emergency Recovery")
            .setMessage("Enter one of your 8-character backup codes (XXXX-XXXX) to verify your identity and reset your PIN.")
            .setView(view)
            .setPositiveButton("Verify", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        val verifyBtn = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        verifyBtn.isEnabled = false

        etBackupCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val code = s?.toString() ?: ""
                val isValid = code.matches(Regex("^[a-zA-Z0-9]{4}-[a-zA-Z0-9]{4}$"))
                verifyBtn.isEnabled = isValid
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        verifyBtn.setOnClickListener {
            val code = etBackupCode.text.toString().uppercase()
            tilBackupCode.error = null
            verifyBtn.isEnabled = false

            scope.launch {
                try {
                    val response = AuthApiService.verifyBackupCode(BackupCodesVerifyRequest(code))
                    dialog.dismiss()

                    val intent = Intent(this@LockoutActivity, PinCreateActivity::class.java).apply {
                        putExtra(PinCreateActivity.EXTRA_IS_RESET, true)
                        putExtra("extra_challenge_token", response.challengeToken)
                    }
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                } catch (e: Exception) {
                    verifyBtn.isEnabled = true
                    tilBackupCode.error = "Invalid backup code. Please try again."
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }

    @Deprecated("Overriding for back interception per Routes.md §6")
    override fun onBackPressed() {
        // Back press exits the app entirely
        finishAffinity()
    }
}
