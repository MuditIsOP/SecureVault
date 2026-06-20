package com.securevault.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Manages AES-256-GCM symmetric keys inside the hardware-backed Android Keystore.
 *
 * Spec refs:
 *   - Security_Requirements.md §4 — "Store the SQLCipher database key in the
 *     hardware-backed Android Keystore" [MUST]
 *   - Security_Requirements.md §9.1 — "Generate database keys with 256 bits
 *     of entropy" [MUST]
 *   - Security_Requirements.md §2.3 — "Bind local session keys using Android
 *     Keystore keys generated with biometric or PIN validation dependencies"
 *   - Architecture.md §3 — Android Keystore component: "Generates keys; outputs
 *     keys for SQLCipher database encryption"
 *   - Architecture.md §7 Failure Mode — "Biometric key invalidation: App displays
 *     biometric error overlay [...] User must re-enroll."
 *   - Technical_Requirements.md §8 — Hard constraint: VMK must NEVER be written
 *     to persistent storage in plaintext.
 *
 * Two key aliases are managed:
 *   DB_KEY_ALIAS  — SQLCipher database passphrase encryption key.
 *                   NOT user-authentication-required (opened on app start).
 *   VMK_KEY_ALIAS — In-memory VMK wrap/unwrap key.
 *                   Biometric/PIN auth-required; invalidated on new fingerprint.
 *
 * All keys: AES/256-bit, GCM mode, AndroidKeyStore provider.
 */
object KeystoreManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /** Alias for the SQLCipher database encryption key. */
    const val DB_KEY_ALIAS = "securevault_db_key"

    /**
     * Alias for the in-memory VMK wrapping key.
     * Requires user authentication; invalidated on new biometric enrollment.
     * — Security_Requirements.md §2.3, Architecture.md §7
     */
    const val VMK_KEY_ALIAS = "securevault_vmk_key"

    /**
     * Alias for the dedicated biometric authentication key.
     * Per-operation auth — only usable via BiometricPrompt CryptoObject.
     * Separate from VMK to prevent auth timeout from disabling biometrics.
     */
    const val BIOMETRIC_KEY_ALIAS = "securevault_biometric_key"

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    // -------------------------------------------------------------------------
    // Key Generation
    // -------------------------------------------------------------------------

    /**
     * Generates (or re-generates) the SQLCipher database key in the Keystore.
     *
     * Parameters:
     *   - Algorithm: AES/GCM/NoPadding — Technical_Requirements.md §2 AES-GCM 256-bit
     *   - Key size: 256 bits — Security_Requirements.md §9.1
     *   - User auth NOT required — opened automatically on app start to unlock DB
     *   - setRandomizedEncryptionRequired(true) — enforces unique IV per operation
     *
     * Called once during first-launch database initialization in DatabaseModule.
     */
    fun generateDatabaseKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            DB_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)                                // Security_Requirements.md §9.1
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)          // Enforces unique IV
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Generates (or re-generates) the VMK wrapping key in the Keystore.
     *
     * Parameters:
     *   - Algorithm: AES/GCM/NoPadding
     *   - Key size: 256 bits — Security_Requirements.md §9.1
     *   - setUserAuthenticationRequired(true) — key only usable after PIN/biometric
     *   - setInvalidatedByBiometricEnrollment(true) — invalidates on new fingerprint
     *     [MUST] per Security_Requirements.md STRIDE §Elev.Priv mitigation
     *
     * Called during first-login in task-004 (AuthActivity).
     */
    fun generateVmkWrappingKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            VMK_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            // 300-second window after device unlock — key usable for 5 min
            // without requiring BiometricPrompt per operation
            .setUserAuthenticationParameters(300, KeyProperties.AUTH_BIOMETRIC_STRONG or
                    KeyProperties.AUTH_DEVICE_CREDENTIAL)
            // Invalidate if new fingerprint enrolled — Security_Requirements.md §1 STRIDE
            .setInvalidatedByBiometricEnrollment(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Generates a dedicated biometric authentication key.
     *
     * This key is per-operation (no timeout) so it can ONLY be used
     * inside a BiometricPrompt CryptoObject. This prevents the issue
     * where a timeout-based key throws UserNotAuthenticatedException
     * and accidentally disables biometrics.
     *
     * setInvalidatedByBiometricEnrollment(true) ensures new fingerprint
     * enrollment invalidates this key — Security_Requirements.md §1 STRIDE.
     */
    fun generateBiometricKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            BIOMETRIC_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            // Per-operation: 0 timeout means BiometricPrompt must be used each time
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    // -------------------------------------------------------------------------
    // Key Retrieval
    // -------------------------------------------------------------------------

    /**
     * Retrieves an existing key from the Keystore by alias.
     *
     * @throws KeyPermanentlyInvalidatedException if biometric enrollment changed
     *   (VMK_KEY_ALIAS only) — caller must re-generate and force re-authentication.
     * @throws KeyStoreException if the key does not exist.
     */
    @Throws(KeyPermanentlyInvalidatedException::class, KeyStoreException::class)
    fun getKey(alias: String): SecretKey {
        val entry = keyStore.getEntry(alias, null)
            ?: throw KeyStoreException("Key not found in Keystore: $alias")
        return (entry as KeyStore.SecretKeyEntry).secretKey
    }

    /**
     * Returns true if a key with the given alias already exists in the Keystore.
     */
    fun keyExists(alias: String): Boolean {
        return try {
            keyStore.containsAlias(alias)
        } catch (e: KeyStoreException) {
            false
        }
    }

    /**
     * Deletes a key from the Keystore. Used when resetting the database
     * after corruption or during account deletion.
     *
     * Architecture.md §7 Failure Mode: "Local DB file is wiped; client forces
     * re-authentication to retrieve vault from MongoDB Atlas."
     */
    fun deleteKey(alias: String) {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    /**
     * Generates the database key if it does not yet exist, otherwise returns
     * the existing key. Safe to call on every app start.
     */
    fun getOrCreateDatabaseKey(): SecretKey {
        return if (keyExists(DB_KEY_ALIAS)) {
            getKey(DB_KEY_ALIAS)
        } else {
            generateDatabaseKey()
        }
    }
}
