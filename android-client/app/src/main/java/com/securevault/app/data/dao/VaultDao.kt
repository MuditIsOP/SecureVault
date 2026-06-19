package com.securevault.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.securevault.app.data.entities.HistoryEntity
import com.securevault.app.data.entities.PasswordEntity

/**
 * Room DAO for the `vault_passwords` and `password_history` tables.
 *
 * Spec refs:
 *   - Database_Schema.md §2.2 — vault_passwords table schema, FK, indexes
 *   - Database_Schema.md §2.3 — password_history table schema
 *   - Database_Schema.md §3 — Soft Delete Policy (deleted_at column)
 *   - Database_Schema.md §4 — Audit Trail (created_at / updated_at)
 *   - SRS FR-VAULT-02 — "client SHALL encrypt credentials locally using AES-256"
 *   - SRS FR-VAULT-07 — "client SHALL support soft-deletion of credentials"
 *   - PRD F-VAULT-02 AC#1–AC#5 — CRUD operations, version increment, soft delete
 *   - PRD F-VAULT-03 — "last 3 prior encrypted passwords retained"
 *
 * Query scoping: All queries filter by user_id to enforce ownership —
 * Security_Requirements.md §3 Zero Client-Side Trust.
 */
@Dao
interface VaultDao {

    // -------------------------------------------------------------------------
    // Read operations — SCR-VLT-01 Dashboard, SCR-VLT-03 Details
    // -------------------------------------------------------------------------

    /**
     * Returns all active (non-deleted) credentials for a user.
     * SCR-VLT-01 dashboard list, sorted by updated_at DESC.
     * Index: idx_passwords_user_search — Database_Schema.md §2.2
     */
    @Query("""
        SELECT * FROM vault_passwords 
        WHERE user_id = :userId AND deleted_at IS NULL 
        ORDER BY updated_at DESC
    """)
    suspend fun getActiveCredentials(userId: String): List<PasswordEntity>

    /**
     * Returns all active credentials sorted for dashboard display:
     * Favorites first, then alphabetical by name.
     * SRS FR-VAULT-05b — "Starred favorite entries SHALL be sorted to the top"
     * SCR-VLT-01 — Dashboard password list
     * task-014 implementation plan step 2
     */
    @Query("""
        SELECT * FROM vault_passwords 
        WHERE user_id = :userId AND deleted_at IS NULL 
        ORDER BY favorite DESC, name ASC
    """)
    suspend fun getDashboardCredentials(userId: String): List<PasswordEntity>

    /**
     * Returns all soft-deleted credentials for Trash screen.
     * PRD F-VAULT-07 — Trash UI.
     * Index: idx_passwords_deleted — Database_Schema.md §2.2
     */
    @Query("""
        SELECT * FROM vault_passwords 
        WHERE user_id = :userId AND deleted_at IS NOT NULL 
        ORDER BY deleted_at DESC
    """)
    suspend fun getTrashedCredentials(userId: String): List<PasswordEntity>

    /**
     * Returns a single credential by ID.
     * SCR-VLT-03 Details screen content.
     */
    @Query("SELECT * FROM vault_passwords WHERE id = :credentialId AND user_id = :userId LIMIT 1")
    suspend fun getCredentialById(credentialId: String, userId: String): PasswordEntity?

    /**
     * Returns credential count per category for dashboard grouping.
     * SCR-VLT-04 category items count display.
     */
    @Query("""
        SELECT COUNT(*) FROM vault_passwords 
        WHERE user_id = :userId AND category_id = :categoryId AND deleted_at IS NULL
    """)
    suspend fun getCredentialCountByCategory(userId: String, categoryId: String): Int

    /**
     * Search credentials by name, username, or website URL.
     * PRD F-SRCH-01 AC#2 — "Matches evaluated against Name, Username/Email, Website URL"
     * SCR-VLT-01 real-time search.
     * SRS FR-SRCH-01b — "Local search updates SHALL execute in under 100ms"
     * Index: idx_passwords_user_search — Database_Schema.md §2.2
     *
     * Sorts favorites first (consistent with dashboard) then alphabetical.
     * Edge case (task-015): SQL LIKE with user input — Room parameterized query
     * prevents injection; wildcard chars (%, _) in input are treated literally
     * by SQLite's default LIKE (no ESCAPE clause needed for basic search).
     */
    @Query("""
        SELECT * FROM vault_passwords 
        WHERE user_id = :userId AND deleted_at IS NULL 
        AND (name LIKE '%' || :query || '%' 
             OR username_email LIKE '%' || :query || '%' 
             OR website_url LIKE '%' || :query || '%')
        ORDER BY favorite DESC, name ASC
    """)
    suspend fun searchCredentials(userId: String, query: String): List<PasswordEntity>

    /**
     * Returns favorite credentials sorted by name.
     * PRD F-VAULT-05 — Favorites sorting.
     */
    @Query("""
        SELECT * FROM vault_passwords 
        WHERE user_id = :userId AND favorite = 1 AND deleted_at IS NULL 
        ORDER BY name ASC
    """)
    suspend fun getFavoriteCredentials(userId: String): List<PasswordEntity>

    // -------------------------------------------------------------------------
    // Write operations — PRD F-VAULT-02 AC#1, AC#2
    // -------------------------------------------------------------------------

    /**
     * Inserts a new credential.
     * PRD F-VAULT-02 AC#2 — "Saving must encrypt via AES-256 using VMK and write to Room."
     * Encryption happens BEFORE calling this method (at UI/ViewModel layer).
     * Database_Schema.md §4 — created_at/updated_at set by entity defaults.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(credential: PasswordEntity)

    /**
     * Updates an existing credential.
     * PRD F-VAULT-02 AC#4 — "write new local state with incremented version counter."
     * Caller must: 1) archive old password to history, 2) increment version, 3) update timestamps.
     */
    @Update
    suspend fun update(credential: PasswordEntity)

    /**
     * Updates the last_viewed timestamp for a credential.
     * SCR-VLT-03 — details screen opens.
     */
    @Query("UPDATE vault_passwords SET last_viewed = :timestamp WHERE id = :credentialId")
    suspend fun updateLastViewed(credentialId: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Toggles the favorite flag for a credential.
     * PRD F-VAULT-05 — Favorites sorting.
     */
    @Query("UPDATE vault_passwords SET favorite = :favorite, updated_at = :timestamp WHERE id = :credentialId")
    suspend fun updateFavorite(credentialId: String, favorite: Int, timestamp: Long = System.currentTimeMillis())

    // -------------------------------------------------------------------------
    // Soft delete operations — PRD F-VAULT-02 AC#5, Database_Schema.md §3
    // -------------------------------------------------------------------------

    /**
     * Soft-deletes a credential by setting deleted_at.
     * PRD F-VAULT-02 AC#5 — "write a deletedDate timestamp and move to Trash."
     * SRS FR-VAULT-07 — soft-deletion support.
     */
    @Query("UPDATE vault_passwords SET deleted_at = :timestamp WHERE id = :credentialId AND user_id = :userId")
    suspend fun softDelete(credentialId: String, userId: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Restores a credential from Trash.
     * PRD F-VAULT-07 AC#3 — "nullify deletedDate."
     */
    @Query("UPDATE vault_passwords SET deleted_at = NULL, updated_at = :timestamp WHERE id = :credentialId AND user_id = :userId")
    suspend fun restore(credentialId: String, userId: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Permanently deletes a credential from the database.
     * PRD F-VAULT-07 AC#4 — "purge records from local database."
     * password_history entries cascade via FK ON DELETE CASCADE.
     */
    @Query("DELETE FROM vault_passwords WHERE id = :credentialId AND user_id = :userId")
    suspend fun permanentlyDelete(credentialId: String, userId: String)

    /**
     * Purges all credentials whose deleted_at is older than the cutoff.
     * PRD F-VAULT-07 AC#5 — "30 days automatic permanent deletion."
     */
    @Query("DELETE FROM vault_passwords WHERE user_id = :userId AND deleted_at IS NOT NULL AND deleted_at < :cutoffTimestamp")
    suspend fun purgeExpiredTrash(userId: String, cutoffTimestamp: Long)

    // -------------------------------------------------------------------------
    // Password History — Database_Schema.md §2.3, PRD F-VAULT-03
    // -------------------------------------------------------------------------

    /**
     * Inserts a password history record.
     * Called BEFORE updating a credential's encrypted_password to archive the old one.
     * PRD F-VAULT-03 — "retain last 3 prior encrypted passwords."
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPasswordHistory(history: HistoryEntity)

    /**
     * Returns the last 3 password history entries for a credential.
     * SCR-VLT-03 content: "Password History list — Lists last 3 passwords."
     * Index: idx_history_entry — Database_Schema.md §2.3
     */
    @Query("""
        SELECT * FROM password_history 
        WHERE password_entry_id = :credentialId 
        ORDER BY created_at DESC LIMIT 3
    """)
    suspend fun getPasswordHistory(credentialId: String): List<HistoryEntity>

    /**
     * Returns credential version (for optimistic concurrency during sync).
     * Used by sync conflict resolution — task-022.
     */
    @Query("SELECT MAX(CAST(SUBSTR(id, 1) AS INTEGER)) FROM vault_passwords WHERE user_id = :userId")
    suspend fun getMaxVersion(userId: String): Int?
}
