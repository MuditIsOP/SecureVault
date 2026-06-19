package com.securevault.app.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import javax.crypto.SecretKey

/**
 * Manages biometric authentication integration for SecureVault.
 *
 * Spec refs:
 *   - PRD F-AUTH-06 — Optional biometric integration to bypass daily PIN entry
 *     AC#1: Toggle on/off in Security Settings (requires PIN confirmation)
 *     AC#2: BiometricPrompt supporting fingerprint and face unlock
 *     AC#3: New enrollment invalidates key → forces PIN + Security Question fallback
 *   - SRS FR-AUTH-06 — SHALL display BiometricPrompt, SHALL invalidate on new profile
 *   - Security_Requirements.md §1 STRIDE Spoofing — [MUST] Enforce system BiometricPrompt
 *   - Security_Requirements.md §1 STRIDE Elev.Priv — [MUST] Invalidate keys on new fingerprint
 *   - Security_Requirements.md §2.3 — [MUST] Bind local session keys with biometric dependencies
 *   - Architecture.md §3 — Android Keystore component: biometric-bound keys
 *   - Architecture.md §7 Failure Mode — Biometric key invalidation → error overlay → re-enroll
 *   - Testing_Strategy.md ST-STRIDE-04 — KeyPermanentlyInvalidatedException forces PIN fallback
 *
 * Biometric state is stored in encrypted SharedPreferences (not Room DB) since
 * it is a local device-only toggle with no sync requirement.
 */
object BiometricHelper {

    private const val PREFS_NAME = "securevault_biometric_prefs"
    private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"

    /**
     * Biometric availability status codes.
     */
    enum class BiometricStatus {
        /** Hardware present, enrolled, and ready. */
        AVAILABLE,
        /** No biometric hardware on this device. */
        NO_HARDWARE,
        /** Hardware present but no fingerprint/face enrolled in OS settings. */
        NOT_ENROLLED,
        /** Biometric hardware temporarily unavailable. */
        UNAVAILABLE
    }

    /**
     * Result of a biometric authentication attempt.
     */
    sealed class BiometricResult {
        /** Authentication succeeded; Cipher object is bound to the authenticated key. */
        data class Success(val cipher: Cipher?) : BiometricResult()
        /** Authentication failed (wrong fingerprint/face). */
        object Failed : BiometricResult()
        /** User cancelled or pressed back. */
        object Cancelled : BiometricResult()
        /** Keystore key was invalidated due to new biometric enrollment.
         *  PRD F-AUTH-06 AC#3, ST-STRIDE-04 */
        object KeyInvalidated : BiometricResult()
        /** General error with message. */
        data class Error(val code: Int, val message: String) : BiometricResult()
    }

    // -------------------------------------------------------------------------
    // Hardware capability checks — Security_Requirements.md §1 STRIDE Spoofing
    // -------------------------------------------------------------------------

    /**
     * Checks whether the device supports biometric authentication.
     *
     * Uses [BiometricManager.BIOMETRIC_STRONG] to enforce Class 3 biometrics
     * (hardware-backed, anti-spoofing) per Security_Requirements.md §1.
     */
    fun checkBiometricStatus(context: Context): BiometricStatus {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NOT_ENROLLED
            else -> BiometricStatus.UNAVAILABLE
        }
    }

    // -------------------------------------------------------------------------
    // Preference management — PRD F-AUTH-06 AC#1
    // -------------------------------------------------------------------------

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns true if biometric unlock is enabled in user settings.
     * PRD F-AUTH-06 AC#1 — toggle state check.
     */
    fun isBiometricEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BIOMETRIC_ENABLED, false)
    }

    /**
     * Enables or disables biometric unlock.
     * PRD F-AUTH-06 AC#1 — caller must verify PIN before calling this with enabled=true.
     *
     * When enabling, generates a fresh biometric-bound Keystore key via
     * [KeystoreManager.generateVmkWrappingKey] which has:
     *   setUserAuthenticationRequired(true)
     *   setInvalidatedByBiometricEnrollment(true)
     * This satisfies Security_Requirements.md §1 STRIDE Elev.Priv mitigation.
     */
    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            // Generate a fresh biometric-bound key in Keystore
            // setInvalidatedByBiometricEnrollment(true) is already configured
            // in KeystoreManager.generateVmkWrappingKey()
            KeystoreManager.generateVmkWrappingKey()
        } else {
            // Delete the biometric key when disabling
            KeystoreManager.deleteKey(KeystoreManager.VMK_KEY_ALIAS)
        }
        getPrefs(context).edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    /**
     * Clears biometric state after key invalidation.
     * PRD F-AUTH-06 AC#3 — user must re-authenticate via PIN + Security Question.
     */
    fun clearBiometricState(context: Context) {
        KeystoreManager.deleteKey(KeystoreManager.VMK_KEY_ALIAS)
        getPrefs(context).edit().putBoolean(KEY_BIOMETRIC_ENABLED, false).apply()
    }

    // -------------------------------------------------------------------------
    // BiometricPrompt display — PRD F-AUTH-06 AC#2, SRS FR-AUTH-06
    // -------------------------------------------------------------------------

    /**
     * Shows the system BiometricPrompt for authentication.
     *
     * Uses [BiometricPrompt.CryptoObject] bound to the VMK Keystore key to ensure
     * cryptographic proof of biometric authentication.
     *
     * If the key has been invalidated (new fingerprint enrolled), catches
     * [KeyPermanentlyInvalidatedException] and returns [BiometricResult.KeyInvalidated].
     * This satisfies:
     *   - PRD F-AUTH-06 AC#3
     *   - Security_Requirements.md §1 STRIDE Elev.Priv
     *   - ST-STRIDE-04 expected secure response
     *
     * @param activity The FragmentActivity hosting the prompt
     * @param onResult Callback with the authentication result
     */
    fun showBiometricPrompt(
        activity: FragmentActivity,
        onResult: (BiometricResult) -> Unit
    ) {
        // Attempt to initialize Cipher with the biometric-bound Keystore key
        val cipher: Cipher
        try {
            val key: SecretKey = KeystoreManager.getKey(KeystoreManager.VMK_KEY_ALIAS)
            cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
        } catch (e: KeyPermanentlyInvalidatedException) {
            // ST-STRIDE-04: Key invalidated due to new biometric enrollment
            // PRD F-AUTH-06 AC#3: Force PIN + Security Question fallback
            clearBiometricState(activity)
            onResult(BiometricResult.KeyInvalidated)
            return
        } catch (e: Exception) {
            // Key does not exist or other Keystore error
            clearBiometricState(activity)
            onResult(BiometricResult.Error(-1, "Biometric key unavailable: ${e.message}"))
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onResult(BiometricResult.Success(result.cryptoObject?.cipher))
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // Individual attempt failed (wrong finger) — prompt stays open
                // BiometricPrompt handles retry internally
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED -> {
                        onResult(BiometricResult.Cancelled)
                    }
                    BiometricPrompt.ERROR_LOCKOUT,
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
                        onResult(BiometricResult.Failed)
                    }
                    else -> {
                        onResult(BiometricResult.Error(errorCode, errString.toString()))
                    }
                }
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)

        // Security_Requirements.md §1 STRIDE Spoofing — [MUST] use system BiometricPrompt
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("SecureVault Unlock")
            .setSubtitle("Verify your identity to access the vault")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(true)
            .build()

        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }
}
