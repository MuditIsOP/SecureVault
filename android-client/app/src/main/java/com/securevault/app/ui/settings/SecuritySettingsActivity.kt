package com.securevault.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.securevault.app.R
import com.securevault.app.security.BiometricHelper
import com.securevault.app.ui.auth.ChallengeQuestionActivity
import com.securevault.app.ui.onboarding.PinCreateActivity

/**
 * SCR-SET-02 — Security Settings Screen.
 *
 * Spec refs:
 *   - Screens.md SCR-SET-02 — Biometrics switch, Autofill switch, Regenerate Codes, Change PIN
 *   - PRD F-AUTH-06 AC#1 — Toggle Biometric on/off (requires PIN confirmation)
 *   - PRD F-AUTH-06 AC#2 — When active, app launch uses BiometricPrompt
 *   - PRD F-AUTH-06 AC#3 — New enrollment invalidates key → forces re-enable flow
 *   - Security_Requirements.md §1 [MUST] — FLAG_SECURE on auth overlays
 *   - Design.md §2.1 — background #141218, surface #1C1B1F, on-surface #E6E1E5
 *
 * Available actions (SCR-SET-02):
 *   - Biometrics switch: Toggle state, requires PIN confirmation to enable
 *   - Autofill switch: Redirects to Android Autofill settings
 *   - Change PIN: Challenges security question, then prompts for new PIN
 *   - Regenerate Codes: Challenges security question, generates new backup codes
 *
 * State variations:
 *   - Loading: Disables toggle switches
 *   - Error: Failed setup, displays warning
 */
class SecuritySettingsActivity : AppCompatActivity() {

    private lateinit var switchBiometric: MaterialSwitch
    private lateinit var switchAutofill: MaterialSwitch
    private lateinit var tvBiometricStatus: TextView
    private lateinit var tvBiometricWarning: TextView
    private lateinit var cardChangePin: MaterialCardView
    private lateinit var cardRegenerateCodes: MaterialCardView
    private lateinit var progressLoading: ProgressBar

    /** Tracks whether we're waiting for PIN confirmation before enabling biometrics */
    private var pendingBiometricEnable = false

    /**
     * Activity result launcher for PIN confirmation.
     * PRD F-AUTH-06 AC#1 — enabling biometrics requires PIN confirmation.
     */
    private lateinit var pinConfirmLauncher: ActivityResultLauncher<Intent>

    /**
     * Activity result launcher for security question challenge.
     * SCR-SET-02 — Change PIN and Regenerate Codes require challenge token.
     */
    private lateinit var challengeLauncher: ActivityResultLauncher<Intent>

    /** Tracks which action triggered the challenge: "change_pin" or "regenerate_codes" */
    private var pendingChallengeAction: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [MUST] FLAG_SECURE — Security_Requirements.md §1
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_security_settings)

        bindViews()
        setupActivityResultLaunchers()
        setupBiometricSwitch()
        setupAutofillSwitch()
        setupActionCards()
    }

    override fun onResume() {
        super.onResume()
        refreshBiometricState()
    }

    // -------------------------------------------------------------------------
    // View binding
    // -------------------------------------------------------------------------

    private fun bindViews() {
        switchBiometric = findViewById(R.id.switch_biometric)
        switchAutofill = findViewById(R.id.switch_autofill)
        tvBiometricStatus = findViewById(R.id.tv_biometric_status)
        tvBiometricWarning = findViewById(R.id.tv_biometric_warning)
        cardChangePin = findViewById(R.id.card_change_pin)
        cardRegenerateCodes = findViewById(R.id.card_regenerate_codes)
        progressLoading = findViewById(R.id.progress_loading)
    }

    // -------------------------------------------------------------------------
    // Activity result launchers
    // -------------------------------------------------------------------------

    private fun setupActivityResultLaunchers() {
        // PIN confirmation result — for enabling biometrics
        pinConfirmLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK && pendingBiometricEnable) {
                // PIN confirmed — enable biometrics
                BiometricHelper.setBiometricEnabled(this, true)
                refreshBiometricState()
                Toast.makeText(this, "Biometric unlock enabled", Toast.LENGTH_SHORT).show()
            } else {
                // PIN cancelled — revert switch
                switchBiometric.isChecked = false
            }
            pendingBiometricEnable = false
        }

        // Security question challenge result — for Change PIN / Regenerate Codes
        challengeLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                when (pendingChallengeAction) {
                    "change_pin" -> {
                        // Launch PinCreateActivity in reset mode
                        val intent = Intent(this, PinCreateActivity::class.java)
                        intent.putExtra(PinCreateActivity.EXTRA_IS_RESET, true)
                        startActivity(intent)
                    }
                    "regenerate_codes" -> {
                        // Launch BackupCodesActivity for regeneration
                        try {
                            val intent = Intent(this,
                                Class.forName("com.securevault.app.ui.onboarding.BackupCodesActivity"))
                            intent.putExtra("is_regeneration", true)
                            startActivity(intent)
                        } catch (e: ClassNotFoundException) {
                            Toast.makeText(this, "Backup codes screen not available",
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            pendingChallengeAction = null
        }
    }

    // -------------------------------------------------------------------------
    // Biometric switch — PRD F-AUTH-06 AC#1
    // -------------------------------------------------------------------------

    private fun setupBiometricSwitch() {
        switchBiometric.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val status = BiometricHelper.checkBiometricStatus(this)
                when (status) {
                    BiometricHelper.BiometricStatus.AVAILABLE -> {
                        // F-AUTH-06 AC#1 — requires PIN confirmation to enable
                        pendingBiometricEnable = true
                        val intent = Intent(this,
                            Class.forName("com.securevault.app.ui.auth.PinUnlockActivity"))
                        intent.putExtra("extra_is_confirmation", true)
                        pinConfirmLauncher.launch(intent)
                    }
                    BiometricHelper.BiometricStatus.NO_HARDWARE -> {
                        switchBiometric.isChecked = false
                        tvBiometricStatus.text = "No biometric hardware detected"
                        Toast.makeText(this, "This device does not support biometrics",
                            Toast.LENGTH_LONG).show()
                    }
                    BiometricHelper.BiometricStatus.NOT_ENROLLED -> {
                        switchBiometric.isChecked = false
                        tvBiometricStatus.text = "No fingerprint or face enrolled"
                        Toast.makeText(this,
                            "Enroll a fingerprint or face in Android Settings first",
                            Toast.LENGTH_LONG).show()
                    }
                    BiometricHelper.BiometricStatus.UNAVAILABLE -> {
                        switchBiometric.isChecked = false
                        tvBiometricStatus.text = "Biometric hardware temporarily unavailable"
                    }
                }
            } else {
                if (BiometricHelper.isBiometricEnabled(this)) {
                    BiometricHelper.setBiometricEnabled(this, false)
                    refreshBiometricState()
                    Toast.makeText(this, "Biometric unlock disabled", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Refreshes biometric UI state based on current hardware status and preference.
     * Handles F-AUTH-06 AC#3 — key invalidation detection.
     */
    private fun refreshBiometricState() {
        val isEnabled = BiometricHelper.isBiometricEnabled(this)
        val status = BiometricHelper.checkBiometricStatus(this)

        // Suppress listener while updating programmatically
        switchBiometric.setOnCheckedChangeListener(null)
        switchBiometric.isChecked = isEnabled
        setupBiometricSwitch() // Re-attach listener

        tvBiometricWarning.visibility = View.GONE

        when {
            status == BiometricHelper.BiometricStatus.NO_HARDWARE -> {
                switchBiometric.isEnabled = false
                tvBiometricStatus.text = "Not available on this device"
            }
            status == BiometricHelper.BiometricStatus.NOT_ENROLLED -> {
                switchBiometric.isEnabled = false
                tvBiometricStatus.text = "No fingerprint or face enrolled in settings"
            }
            status == BiometricHelper.BiometricStatus.UNAVAILABLE -> {
                switchBiometric.isEnabled = false
                tvBiometricStatus.text = "Temporarily unavailable"
            }
            isEnabled -> {
                switchBiometric.isEnabled = true
                tvBiometricStatus.text = "Fingerprint or face unlock active"
            }
            else -> {
                switchBiometric.isEnabled = true
                tvBiometricStatus.text = "Use fingerprint or face to unlock"
            }
        }
    }

    // -------------------------------------------------------------------------
    // Autofill switch — SCR-SET-02 content
    // -------------------------------------------------------------------------

    private fun setupAutofillSwitch() {
        switchAutofill.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Redirect to Android Autofill settings
                val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                intent.data = android.net.Uri.parse("package:$packageName")
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback for devices that don't support this intent
                    Toast.makeText(this,
                        "Open Settings > System > Autofill to enable SecureVault",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Action cards — SCR-SET-02 available actions
    // -------------------------------------------------------------------------

    private fun setupActionCards() {
        // Change PIN — requires security question challenge first
        // SCR-SET-02: "Tap Change PIN → Challenges question, prompts for new PIN"
        cardChangePin.setOnClickListener {
            pendingChallengeAction = "change_pin"
            val intent = Intent(this, ChallengeQuestionActivity::class.java)
            challengeLauncher.launch(intent)
        }

        // Regenerate Backup Codes — requires security question challenge first
        // SCR-SET-02: "Tap Backup Codes → Challenges question, generates new codes"
        cardRegenerateCodes.setOnClickListener {
            pendingChallengeAction = "regenerate_codes"
            val intent = Intent(this, ChallengeQuestionActivity::class.java)
            challengeLauncher.launch(intent)
        }
    }

    // -------------------------------------------------------------------------
    // Loading state — SCR-SET-02 state variations
    // -------------------------------------------------------------------------

    @Suppress("unused")
    private fun setLoadingState(loading: Boolean) {
        progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
        switchBiometric.isEnabled = !loading
        switchAutofill.isEnabled = !loading
        cardChangePin.isEnabled = !loading
        cardRegenerateCodes.isEnabled = !loading
    }
}
