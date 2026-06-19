package com.securevault.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.securevault.app.data.entities.UserEntity

/**
 * Room Data Access Object for the `users` table.
 *
 * Spec refs:
 *   - Database_Schema.md Section 2.1 — users columns
 *   - Architecture.md §4 Data Flow 2 — Client validates PIN against SQLCipher
 *   - PRD F-AUTH-04 AC#4 — lockout state and attempts counter stored in SQLCipher
 */
@Dao
interface UserDao {

    /**
     * Inserts user record if it doesn't exist.
     * Used after login to ensure a local user record exists.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNotExists(user: UserEntity): Long

    /**
     * Retrieves the single user configuration record.
     * SecureVault runs in single-user mode offline; there is only ever one user row.
     */
    @Query("SELECT * FROM users LIMIT 1")
    suspend fun getUser(): UserEntity?

    /**
     * Updates failed PIN attempts and the lockout expiry timestamp.
     * Enforced at the application layer by PINLockoutManager.
     */
    @Query("UPDATE users SET pin_failed_attempts = :attempts, pin_lockout_until = :lockoutUntil, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateLockoutState(id: String, attempts: Int, lockoutUntil: Long?, updatedAt: Long = System.currentTimeMillis())

    /**
     * Resets failed PIN attempts to 0 and clears the lockout expiry timestamp.
     */
    @Query("UPDATE users SET pin_failed_attempts = 0, pin_lockout_until = NULL, updated_at = :updatedAt WHERE id = :id")
    suspend fun resetLockout(id: String, updatedAt: Long = System.currentTimeMillis())

    /**
     * Updates the user's registered backup code hashes.
     */
    @Query("UPDATE users SET backup_code_hashes = :hashes, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateBackupCodes(id: String, hashes: String, updatedAt: Long = System.currentTimeMillis())

    /**
     * Updates the user's PIN hash and resets all PIN lockout/delay states.
     */
    @Query("UPDATE users SET pin_hash = :newPinHash, pin_failed_attempts = 0, pin_lockout_until = NULL, updated_at = :updatedAt WHERE id = :id")
    suspend fun updatePin(id: String, newPinHash: String, updatedAt: Long = System.currentTimeMillis())
}
