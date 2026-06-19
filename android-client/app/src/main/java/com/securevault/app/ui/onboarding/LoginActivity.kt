package com.securevault.app.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.securevault.app.R
import com.securevault.app.data.api.AuthApiService
import com.securevault.app.data.api.LoginRequest
import kotlinx.coroutines.launch

/**
 * SCR-ONB-01 — Onboarding & Google Sign-In Screen.
 *
 * Spec refs:
 *   - Screens.md SCR-ONB-01 — full screen spec, states, exit points
 *   - PRD F-AUTH-01 — 4 acceptance criteria
 *   - SRS FR-AUTH-01 — Credential Manager API + Firebase JWT verification
 *   - Routes.md RT-ONB-01 — Root Switch Navigator, back stack behaviour
 *   - Security_Requirements.md §1 [MUST] — FLAG_SECURE application-wide
 *   - Security_Requirements.md §2.3 — device binding via ANDROID_ID
 *   - Design tokens — primary #D0BCFF, on-primary #381E72 (task-004 task spec)
 *   - Technical_Requirements.md §8 — NO hardcoded keys/secrets
 *
 * States implemented per Screens.md SCR-ONB-01:
 *   Loading — buttons disabled, modal spinner shown
 *   Error   — Toast: "Sign-In Failed. Please check internet connection."
 *
 * Exit points (Routes.md RT-ONB-01):
 *   registered=true  → SCR-ATH-02 (PinUnlockActivity) — existing account
 *   registered=false → SCR-ONB-02 (SecurityQuestionActivity) — new account
 *
 * Back navigation: intercepted to exit the app (Routes.md §6 Back Interceptions).
 */
class LoginActivity : AppCompatActivity() {

    private val TAG = "LoginActivity"

    // ViewModel handles Credential Manager coroutine and API call state
    private val viewModel: LoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [MUST] FLAG_SECURE — blocks screenshots/recordings app-wide
        // Security_Requirements.md §1 STRIDE Info Leak, NFR-SEC-02
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_login)

        observeViewModel()
        setupSignInButton()
    }

    // -------------------------------------------------------------------------
    // UI setup
    // -------------------------------------------------------------------------

    private fun setupSignInButton() {
        // Wired to the Google Sign-In button in activity_login.xml
        // PRD F-AUTH-01 AC#2 — tapping triggers Credential Manager
        findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btn_google_sign_in
        ).setOnClickListener {
            startGoogleSignIn()
        }
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            when (state) {
                is LoginUiState.Idle -> setLoadingState(false)
                is LoginUiState.Loading -> setLoadingState(true)
                is LoginUiState.Success -> handleSuccess(state.registered)
                is LoginUiState.Error -> handleError(state.message)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Google Sign-In via Credential Manager
    // PRD F-AUTH-01 AC#2, SRS FR-AUTH-01
    // -------------------------------------------------------------------------

    private fun startGoogleSignIn() {
        viewModel.setLoading()

        val credentialManager = CredentialManager.create(this)

        // GetGoogleIdOption — requests the Google ID Token from the platform
        // Technical_Requirements.md §3 — credentials:1.2.1
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)   // Allow any Google account
            .setServerClientId(getString(R.string.google_server_client_id))
            .setAutoSelectEnabled(false)            // Show account picker
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        lifecycleScope.launch {
            try {
                val result = credentialManager.getCredential(
                    request = request,
                    context = this@LoginActivity
                )
                handleCredentialResult(result)
            } catch (e: GetCredentialCancellationException) {
                // PRD F-AUTH-01 AC#4 — user cancelled → stay on login screen
                Log.d(TAG, "Google Sign-In cancelled by user")
                viewModel.setIdle()
            } catch (e: GetCredentialException) {
                // PRD F-AUTH-01 AC#4 — sign-in failed
                Log.e(TAG, "Credential Manager error: ${e.message}")
                viewModel.setError("Sign-In Failed. Please check internet connection.")
            }
        }
    }

    private fun handleCredentialResult(result: GetCredentialResponse) {
        when (val credential = result.credential) {
            is CustomCredential -> {
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdTokenCredential =
                        GoogleIdTokenCredential.createFrom(credential.data)
                    val idToken = googleIdTokenCredential.idToken

                    // PRD F-AUTH-01 AC#3 — send token to backend for verification
                    sendTokenToBackend(idToken)
                } else {
                    viewModel.setError("Sign-In Failed. Unexpected credential type.")
                }
            }
            else -> {
                viewModel.setError("Sign-In Failed. Please check internet connection.")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Backend login API call — POST /v1/auth/login
    // API_Spec.md §2 POST /v1/auth/login
    // Security_Requirements.md §2.3 — device binding via ANDROID_ID
    // -------------------------------------------------------------------------

    private fun sendTokenToBackend(googleIdToken: String) {
        // Device ID binding — Security_Requirements.md §2.3
        @Suppress("HardwareIds")
        val deviceId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        )

        val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        val androidVersion = android.os.Build.VERSION.RELEASE

        val request = LoginRequest(
            googleIdToken = googleIdToken,
            deviceId = deviceId,
            deviceName = deviceName,
            androidVersion = androidVersion
        )

        lifecycleScope.launch {
            try {
                val response = AuthApiService.login(request)
                viewModel.setSuccess(registered = response.registered)
            } catch (e: Exception) {
                Log.e(TAG, "Backend login failed: ${e.message}")
                viewModel.setError("Sign-In Failed. Please check internet connection.")
            }
        }
    }

    // -------------------------------------------------------------------------
    // State handlers
    // -------------------------------------------------------------------------

    /**
     * PRD F-AUTH-01 AC#1 — first-launch bypasses dashboard.
     * Routes per registered flag:
     *   registered=true  → SCR-ATH-02 (existing account, proceed to PIN)
     *   registered=false → SCR-ONB-02 (new account, start security question setup)
     */
    private fun handleSuccess(registered: Boolean) {
        setLoadingState(false)
        if (registered) {
            // Existing user — go to PIN unlock
            val intent = Intent(this, PinUnlockActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        } else {
            // New user — proceed through onboarding
            val intent = Intent(this, SecurityQuestionActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }
        finish()
    }

    /**
     * SCR-ONB-01 Error state — Toast per Screens.md spec.
     * PRD F-AUTH-01 AC#4 — remain on login screen.
     */
    private fun handleError(message: String) {
        setLoadingState(false)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * SCR-ONB-01 Loading state — disables buttons, modal spinner.
     * Screens.md SCR-ONB-01 State Variations.
     */
    private fun setLoadingState(isLoading: Boolean) {
        findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btn_google_sign_in
        ).isEnabled = !isLoading

        findViewById<android.view.View>(R.id.progress_loading).visibility =
            if (isLoading) android.view.View.VISIBLE else android.view.View.GONE
    }

    // -------------------------------------------------------------------------
    // Back interception — exits app (Routes.md §6 Back Interceptions)
    // -------------------------------------------------------------------------

    @Deprecated("Overriding for back interception per Routes.md §6")
    override fun onBackPressed() {
        // Routes.md §6: "Google Sign-In (RT-ONB-01): Intercepted to exit the application"
        finishAffinity()
    }
}
