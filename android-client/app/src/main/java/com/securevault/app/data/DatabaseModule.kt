package com.securevault.app.data

import android.content.Context
import android.util.Log
import com.securevault.app.security.CryptographyHelper
import com.securevault.app.security.KeystoreManager

/**
 * Provides the initialised AppDatabase instance with SQLCipher encryption.
 *
 * Spec refs:
 *   - Architecture.md ADR-02 — Room + SQLCipher; passphrase from Keystore
 *   - Architecture.md §3 — "Room <-> SQLCipher <-> Keystore" component chain
 *   - Security_Requirements.md §4 — "Store the SQLCipher database key in the
 *     hardware-backed Android Keystore" [MUST]
 *   - Security_Requirements.md §9.1 — "Generate database keys with 256 bits
 *     of entropy" [MUST]
 *   - Technical_Requirements.md §8 — Hard constraint: passphrase byte array must
 *     be zeroed immediately after the database builder consumes it.
 *   - SRS NFR-SEC-01 — "encrypt the local SQLite database file using SQLCipher
 *     in 256-bit AES-CBC mode" [MUST]
 *
 * Initialization sequence (Architecture.md §4 Data Flow 3):
 *   1. Retrieve or generate the AES-256 DB key from the Android Keystore.
 *   2. Derive the SQLCipher passphrase via CryptographyHelper.
 *   3. Pass the passphrase to AppDatabase.getInstance() (SupportFactory).
 *   4. Zero the passphrase byte array immediately.
 *
 * This object is stateless — it does not retain the passphrase or the key
 * reference beyond the initialization call.
 *
 * In production the caller (Application class or DI graph) invokes
 * [provideDatabase] once at app start and holds the singleton reference.
 */
object DatabaseModule {

    private const val TAG = "DatabaseModule"

    /**
     * Initialises and returns the encrypted AppDatabase singleton.
     *
     * Steps:
     *   1. Get or create the 256-bit Keystore DB key.
     *   2. Derive the SQLCipher passphrase.
     *   3. Open the Room database with the SQLCipher SupportFactory.
     *   4. Immediately zero the passphrase array.
     *
     * @param context Application context.
     * @return Initialised AppDatabase with full SQLCipher encryption active.
     *
     * @throws android.security.keystore.KeyPermanentlyInvalidatedException
     *   if the device's biometric enrollment changed and the Keystore key was
     *   invalidated. Caller must catch this, wipe the database, and re-authenticate.
     */
    fun provideDatabase(context: Context): AppDatabase {
        Log.d(TAG, "Initialising encrypted Room database")

        // Step 1: Retrieve or generate 256-bit Keystore key
        // Security_Requirements.md §9.1 — 256-bit entropy, AndroidKeyStore TEE
        val dbKey = KeystoreManager.getOrCreateDatabaseKey()

        // Step 2: Derive the SQLCipher passphrase from the Keystore key
        // The passphrase is a Keystore-encrypted derivative — never hardcoded
        val passphrase = CryptographyHelper.deriveDatabasePassphrase(dbKey)

        return try {
            // Step 3: Build the database — SupportFactory consumes passphrase here
            // AppDatabase.getInstance() is synchronized; passphrase zeroed in finally
            AppDatabase.getInstance(context, passphrase)
        } finally {
            // Step 4: Zero passphrase immediately after use
            // Technical_Requirements.md §8 — Hard constraint: no plaintext key persistence
            passphrase.fill(0)
            Log.d(TAG, "Database passphrase zeroed after initialization")
        }
    }

    /**
     * Destroys the database encryption key and clears the singleton instance.
     *
     * Called when:
     *   - The Room database file is corrupted (Architecture.md §7 Failure Mode).
     *   - The user deletes their account (Security_Requirements.md §4 PII deletion).
     *
     * After calling this, [provideDatabase] will re-generate a new Keystore key
     * and create a fresh encrypted database on the next call.
     */
    fun destroyDatabaseKey() {
        Log.w(TAG, "Destroying SQLCipher Keystore key — database will be wiped")
        KeystoreManager.deleteKey(KeystoreManager.DB_KEY_ALIAS)
        AppDatabase.clearInstance()
    }
}
