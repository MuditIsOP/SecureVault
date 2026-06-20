package com.securevault.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

import com.google.android.material.materialswitch.MaterialSwitch
import com.securevault.app.R
import com.securevault.app.security.BiometricHelper
import com.securevault.app.ui.auth.ChallengeQuestionActivity
import com.securevault.app.ui.onboarding.PinCreateActivity

/**
 * SCR-SET-02 — Security Settings Fragment.
 *
 * Fragment version of SecuritySettingsActivity for in-place display within DashboardActivity.
 *
 * Spec refs:
 *   - Screens.md SCR-SET-02 — Biometrics switch, Autofill switch, Regenerate Codes, Change PIN
 *   - PRD F-AUTH-06 AC#1 — Toggle Biometric on/off (requires PIN confirmation)
 *   - PRD F-AUTH-06 AC#2 — When active, app launch uses BiometricPrompt
 *   - PRD F-AUTH-06 AC#3 — New enrollment invalidates key → forces re-enable flow
 *   - Design.md §2.1 — background #141218, surface #1C1B1F, on-surface #E6E1E5
 *
 * Available actions (SCR-SET-02):
 *   - Biometrics switch: Toggle state, requires PIN confirmation to enable
 *   - Autofill switch: Redirects to Android Autofill settings
 *   - Change PIN: Challenges security question, then prompts for new PIN
 *   - Regenerate Codes: Challenges security question, generates new backup codes
 */
class SettingsFragment : Fragment() {

    private lateinit var switchBiometric: MaterialSwitch
    private lateinit var switchAutofill: MaterialSwitch
    private lateinit var tvBiometricStatus: TextView
    private lateinit var tvBiometricWarning: TextView
    private lateinit var cardChangePin: View
    private lateinit var cardRegenerateCodes: View
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
        setupActivityResultLaunchers()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupBiometricSwitch()
        setupAutofillSwitch()
        setupActionCards()
        setupLogoutButton(view)
    }

    override fun onResume() {
        super.onResume()
        if (::switchBiometric.isInitialized) {
            refreshBiometricState()
        }
    }

    // -------------------------------------------------------------------------
    // View binding
    // -------------------------------------------------------------------------

    private fun bindViews(view: View) {
        switchBiometric = view.findViewById(R.id.switch_biometric)
        switchAutofill = view.findViewById(R.id.switch_autofill)
        tvBiometricStatus = view.findViewById(R.id.tv_biometric_status)
        tvBiometricWarning = view.findViewById(R.id.tv_biometric_warning)
        cardChangePin = view.findViewById(R.id.card_change_pin)
        cardRegenerateCodes = view.findViewById(R.id.card_regenerate_codes)
        progressLoading = view.findViewById(R.id.progress_loading)
    }

    // -------------------------------------------------------------------------
    // Activity result launchers — must be registered before STARTED
    // -------------------------------------------------------------------------

    private fun setupActivityResultLaunchers() {
        // PIN confirmation result — for enabling biometrics
        pinConfirmLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == AppCompatActivity.RESULT_OK && pendingBiometricEnable) {
                // PIN confirmed — enable biometrics
                BiometricHelper.setBiometricEnabled(requireContext(), true)
                refreshBiometricState()
                Toast.makeText(requireContext(), "Biometric unlock enabled", Toast.LENGTH_SHORT).show()
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
            if (result.resultCode == AppCompatActivity.RESULT_OK) {
                when (pendingChallengeAction) {
                    "change_pin" -> {
                        // Launch PinCreateActivity in reset mode
                        val intent = Intent(requireContext(), PinCreateActivity::class.java)
                        intent.putExtra(PinCreateActivity.EXTRA_IS_RESET, true)
                        startActivity(intent)
                    }
                    "regenerate_codes" -> {
                        // Launch BackupCodesActivity for regeneration
                        try {
                            val intent = Intent(requireContext(),
                                Class.forName("com.securevault.app.ui.onboarding.BackupCodesActivity"))
                            intent.putExtra("is_regeneration", true)
                            startActivity(intent)
                        } catch (e: ClassNotFoundException) {
                            Toast.makeText(requireContext(), "Backup codes screen not available",
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
                val status = BiometricHelper.checkBiometricStatus(requireContext())
                when (status) {
                    BiometricHelper.BiometricStatus.AVAILABLE -> {
                        // F-AUTH-06 AC#1 — requires PIN confirmation to enable
                        pendingBiometricEnable = true
                        val intent = Intent(requireContext(),
                            Class.forName("com.securevault.app.ui.auth.PinUnlockActivity"))
                        intent.putExtra("extra_is_confirmation", true)
                        pinConfirmLauncher.launch(intent)
                    }
                    BiometricHelper.BiometricStatus.NO_HARDWARE -> {
                        switchBiometric.isChecked = false
                        tvBiometricStatus.text = "No biometric hardware detected"
                        Toast.makeText(requireContext(), "This device does not support biometrics",
                            Toast.LENGTH_LONG).show()
                    }
                    BiometricHelper.BiometricStatus.NOT_ENROLLED -> {
                        switchBiometric.isChecked = false
                        tvBiometricStatus.text = "No fingerprint or face enrolled"
                        Toast.makeText(requireContext(),
                            "Enroll a fingerprint or face in Android Settings first",
                            Toast.LENGTH_LONG).show()
                    }
                    BiometricHelper.BiometricStatus.UNAVAILABLE -> {
                        switchBiometric.isChecked = false
                        tvBiometricStatus.text = "Biometric hardware temporarily unavailable"
                    }
                }
            } else {
                if (BiometricHelper.isBiometricEnabled(requireContext())) {
                    BiometricHelper.setBiometricEnabled(requireContext(), false)
                    refreshBiometricState()
                    Toast.makeText(requireContext(), "Biometric unlock disabled", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Refreshes biometric UI state based on current hardware status and preference.
     * Handles F-AUTH-06 AC#3 — key invalidation detection.
     */
    private fun refreshBiometricState() {
        val ctx = requireContext()
        val isEnabled = BiometricHelper.isBiometricEnabled(ctx)
        val status = BiometricHelper.checkBiometricStatus(ctx)

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
                intent.data = android.net.Uri.parse("package:${requireContext().packageName}")
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback for devices that don't support this intent
                    Toast.makeText(requireContext(),
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
            val intent = Intent(requireContext(), ChallengeQuestionActivity::class.java)
            intent.putExtra(ChallengeQuestionActivity.EXTRA_QUESTION_TEXT, getSavedQuestionText())
            challengeLauncher.launch(intent)
        }

        // Regenerate Backup Codes — requires security question challenge first
        // SCR-SET-02: "Tap Backup Codes → Challenges question, generates new codes"
        cardRegenerateCodes.setOnClickListener {
            pendingChallengeAction = "regenerate_codes"
            val intent = Intent(requireContext(), ChallengeQuestionActivity::class.java)
            intent.putExtra(ChallengeQuestionActivity.EXTRA_QUESTION_TEXT, getSavedQuestionText())
            challengeLauncher.launch(intent)
        }

        // Export Vault row
        view?.findViewById<View>(R.id.row_export)?.setOnClickListener {
            val intent = Intent(requireContext(), com.securevault.app.ui.settings.ExportActivity::class.java)
            startActivity(intent)
        }
    }

    /** Reads the stored security question text from SharedPreferences */
    private fun getSavedQuestionText(): String {
        return requireContext()
            .getSharedPreferences("securevault_prefs", android.content.Context.MODE_PRIVATE)
            .getString("security_question_text", "What is your security question?") ?: "What is your security question?"
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

    // -------------------------------------------------------------------------
    // Logout
    // -------------------------------------------------------------------------

    private fun setupLogoutButton(view: View) {
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_logout)
            .setOnClickListener {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Log Out")
                    .setMessage("Are you sure you want to log out? You'll need to sign in again to access your vault.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Log Out") { _, _ ->
                        performLogout()
                    }
                    .show()
            }
    }

    private fun performLogout() {
        // Sign out of Firebase
        com.google.firebase.auth.FirebaseAuth.getInstance().signOut()

        // Clear session token
        com.securevault.app.data.api.SessionStore.clearToken()

        // Clear local PIN hash so re-login goes through security question flow
        try {
            val db = com.securevault.app.data.DatabaseModule.provideDatabase(requireContext())
            db.openHelper.writableDatabase.execSQL(
                "UPDATE users SET pin_hash = '', pin_failed_attempts = 0, pin_lockout_until = NULL"
            )
        } catch (e: Exception) {
            android.util.Log.e("SettingsFragment", "Failed to clear PIN: ${e.message}")
        }

        // Clear SharedPreferences but preserve security question text (needed for re-login)
        val prefs = requireContext().getSharedPreferences("securevault_prefs", android.content.Context.MODE_PRIVATE)
        val questionText = prefs.getString("security_question_text", "") ?: ""
        prefs.edit().clear().apply()
        if (questionText.isNotEmpty()) {
            prefs.edit().putString("security_question_text", questionText).apply()
        }

        // Delete VMK key — new key will be created on re-login
        com.securevault.app.security.KeystoreManager.deleteKey(
            com.securevault.app.security.KeystoreManager.VMK_KEY_ALIAS
        )

        // Navigate to login and clear the back stack
        val intent = Intent(requireContext(),
            com.securevault.app.ui.onboarding.LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        activity?.finish()
    }
}
