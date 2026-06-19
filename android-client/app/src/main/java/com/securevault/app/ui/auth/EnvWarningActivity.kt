package com.securevault.app.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.securevault.app.R
import com.securevault.app.security.EnvironmentChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Insecure Host Warning Screen — SCR-ATH-05.
 *
 * Spec refs:
 *   - PRD F-DEV-02 AC#1 — "On app start, checks must detect superuser
 *     binaries and if USB debugging is enabled"
 *   - PRD F-DEV-02 AC#2 — "If root access is detected, displays a
 *     Warning Dialog with a 'Continue' button"
 *   - PRD F-DEV-02 AC#3 — "If USB debugging is active, displays a
 *     Warning Dialog with a 'Continue' button"
 *   - SRS FR-DEV-02b — "client SHALL present alert screens warning
 *     users of environmental vulnerabilities"
 *   - Screens.md SCR-ATH-05 — Insecure Host Warning Screen spec
 *   - Security_Requirements.md §2 — "[MUST] Alert user if root is found"
 *   - Design.md — warning color #E3A857, surface #1C1B1F
 *   - Security_Requirements.md §1 — FLAG_SECURE
 *
 * Data flow (Architecture.md):
 *   App startup → Scan host environment → Check root/debugging →
 *   Threat found → Show this warning activity → User taps Continue →
 *   Proceed to SCR-ATH-02 (PIN Unlock)
 *
 * State variations (Screens.md SCR-ATH-05):
 *   Loading — Scanning checks (ProgressBar)
 *   Error — Scan timeout, falls back to warning
 *
 * Exit points: SCR-ATH-02 (PIN Unlock) via "Continue Anyway" button
 */
class EnvWarningActivity : AppCompatActivity() {

    private lateinit var progressScanning: ProgressBar
    private lateinit var layoutWarnings: View
    private lateinit var cardRootWarning: MaterialCardView
    private lateinit var cardDebugWarning: MaterialCardView
    private lateinit var tvRootTitle: TextView
    private lateinit var tvRootDescription: TextView
    private lateinit var tvDebugTitle: TextView
    private lateinit var tvDebugDescription: TextView
    private lateinit var tvWarningHeader: TextView
    private lateinit var tvWarningSubtitle: TextView
    private lateinit var btnContinue: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [MUST] FLAG_SECURE — Security_Requirements.md §1
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_env_warning)

        bindViews()
        performEnvironmentScan()
    }

    // -------------------------------------------------------------------------
    // View binding
    // -------------------------------------------------------------------------

    private fun bindViews() {
        progressScanning = findViewById(R.id.progress_scanning)
        layoutWarnings = findViewById(R.id.layout_warnings)
        cardRootWarning = findViewById(R.id.card_root_warning)
        cardDebugWarning = findViewById(R.id.card_debug_warning)
        tvRootTitle = findViewById(R.id.tv_root_title)
        tvRootDescription = findViewById(R.id.tv_root_description)
        tvDebugTitle = findViewById(R.id.tv_debug_title)
        tvDebugDescription = findViewById(R.id.tv_debug_description)
        tvWarningHeader = findViewById(R.id.tv_warning_header)
        tvWarningSubtitle = findViewById(R.id.tv_warning_subtitle)
        btnContinue = findViewById(R.id.btn_continue_anyway)

        // "Continue Anyway" — PRD F-DEV-02 AC#2/AC#3
        // Screens.md SCR-ATH-05 — Bypass Warning action
        btnContinue.setOnClickListener {
            proceedToUnlock()
        }
    }

    // -------------------------------------------------------------------------
    // Environment scan — PRD F-DEV-02 AC#1
    // Screens.md SCR-ATH-05 Loading state — "Scanning checks"
    // -------------------------------------------------------------------------

    /**
     * Performs the environment security scan on a background thread.
     *
     * PRD F-DEV-02 AC#1 — "On app start, checks must detect superuser
     * binaries (root access) and if USB debugging is enabled"
     * SRS FR-DEV-02a — "detect rooted systems and active debugging"
     */
    private fun performEnvironmentScan() {
        // Loading state — Screens.md SCR-ATH-05
        showState(ScanState.LOADING)

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    EnvironmentChecker.scan(applicationContext)
                }

                displayScanResults(result)
            } catch (e: Exception) {
                // Error state — Screens.md SCR-ATH-05: "Scan timeout. Falls back to warning"
                showFallbackWarning()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Display results — PRD F-DEV-02 AC#2, AC#3
    // -------------------------------------------------------------------------

    /**
     * Displays the scan results with appropriate warning cards.
     *
     * PRD F-DEV-02 AC#2 — "displays Warning Dialog with Continue button"
     *     for root detection
     * PRD F-DEV-02 AC#3 — "displays Warning Dialog with Continue button"
     *     for USB debugging
     * SRS FR-DEV-02b — "present alert screens warning users"
     */
    private fun displayScanResults(result: EnvironmentChecker.EnvironmentScanResult) {
        if (!result.isCompromised) {
            // No threats — proceed directly to unlock
            proceedToUnlock()
            return
        }

        showState(ScanState.WARNINGS)

        // Header — Design.md warning color #E3A857
        tvWarningHeader.text = "⚠️ Security Warning"
        tvWarningSubtitle.text =
            "Your device environment may compromise the security of your stored credentials."

        // Root warning card — PRD F-DEV-02 AC#2
        if (result.isRooted) {
            cardRootWarning.visibility = View.VISIBLE
            tvRootTitle.text = "🔓 Root Access Detected"
            tvRootDescription.text =
                "This device has superuser (root) access enabled. " +
                "Rooted devices allow apps to bypass Android's security sandbox, " +
                "potentially exposing your encrypted vault data.\n\n" +
                "Attackers or malicious apps with root access could read your " +
                "passwords directly from the encrypted database."
        } else {
            cardRootWarning.visibility = View.GONE
        }

        // USB debugging warning card — PRD F-DEV-02 AC#3
        if (result.isUsbDebuggingEnabled) {
            cardDebugWarning.visibility = View.VISIBLE
            tvDebugTitle.text = "🔌 USB Debugging Enabled"
            tvDebugDescription.text =
                "USB debugging is currently active in your developer settings. " +
                "This allows connected computers to access app data, capture " +
                "screenshots, and execute commands on your device.\n\n" +
                "Consider disabling USB debugging in Settings → Developer Options " +
                "when not actively developing."
        } else {
            cardDebugWarning.visibility = View.GONE
        }

        // "Continue Anyway" button is always visible
        btnContinue.visibility = View.VISIBLE
    }

    /**
     * Fallback warning when scan fails.
     * Screens.md SCR-ATH-05 Error: "Scan timeout. Falls back to warning."
     */
    private fun showFallbackWarning() {
        showState(ScanState.WARNINGS)

        tvWarningHeader.text = "⚠️ Security Check Failed"
        tvWarningSubtitle.text =
            "Unable to verify device security. Proceed with caution."

        cardRootWarning.visibility = View.GONE
        cardDebugWarning.visibility = View.GONE
        btnContinue.visibility = View.VISIBLE
    }

    // -------------------------------------------------------------------------
    // Navigation — Screens.md SCR-ATH-05 exit point: SCR-ATH-02
    // -------------------------------------------------------------------------

    /**
     * Proceeds to PIN Unlock screen.
     *
     * Screens.md SCR-ATH-05 — "Bypass Warning → Proceeds to SCR-ATH-02"
     * Technical: Warning checks must not block developers from bypassing
     */
    private fun proceedToUnlock() {
        // Navigate to PIN Unlock (SCR-ATH-02)
        // The PinUnlockActivity was created in task-007
        try {
            val intent = Intent(
                this,
                Class.forName("com.securevault.app.ui.auth.PinUnlockActivity")
            )
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        } catch (e: ClassNotFoundException) {
            // Fallback if PinUnlockActivity not found
            finish()
        }
    }

    // -------------------------------------------------------------------------
    // State management — Screens.md SCR-ATH-05 state variations
    // -------------------------------------------------------------------------

    private enum class ScanState { LOADING, WARNINGS }

    private fun showState(state: ScanState) {
        progressScanning.visibility =
            if (state == ScanState.LOADING) View.VISIBLE else View.GONE
        layoutWarnings.visibility =
            if (state == ScanState.WARNINGS) View.VISIBLE else View.GONE
    }

    /**
     * Prevent back navigation during environment check.
     * User must explicitly tap "Continue Anyway" to proceed.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Intentionally blocked — user must acknowledge warning
        // Technical: "Warning checks must not block developers from bypassing"
        // → they can bypass via Continue button, not back button
    }
}
