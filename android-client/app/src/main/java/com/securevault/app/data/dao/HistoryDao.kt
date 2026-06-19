package com.securevault.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.securevault.app.data.entities.HistoryEntity

/**
 * Room DAO for the `password_history` table.
 *
 * Spec refs:
 *   - Database_Schema.md §2.3 — password_history table schema
 *   - Database_Schema.md §2.3 — FK password_entry_id → vault_passwords(id) CASCADE
 *   - Database_Schema.md §2.3 — Index idx_history_entry (password_entry_id, created_at DESC)
 *   - PRD F-VAULT-03 AC#1 — "previous password added to encrypted history (max 3)"
 *   - PRD F-VAULT-03 AC#2 — "displayed on Details Screen, hidden by default"
 *   - PRD F-VAULT-03 AC#3 — "Eye Icon next to history entry decrypts and displays"
 *   - SRS FR-VAULT-03a — "store historical passwords in encrypted format"
 *   - SRS FR-VAULT-03b — "restrict password history log to maximum of 3 entries"
 *   - Architecture.md — "Credential Password Edit → Insert old value → Limit count to 3"
 *
 * History archival workflow:
 *   1. Before updating encrypted_password in vault_passwords, insert old value here.
 *   2. After insert, call purgeOldestBeyondLimit to enforce 3-entry cap.
 *   3. History entries cascade-delete when parent credential is permanently deleted.
 *
 * Query scoping: History entries are indirectly scoped to the user via the
 * parent vault_passwords FK (which is user-scoped). No direct userId column.
 */
@Dao
interface HistoryDao {

    // -------------------------------------------------------------------------
    // Insert — PRD F-VAULT-03 AC#1
    // -------------------------------------------------------------------------

    /**
     * Inserts a password history record.
     * Called BEFORE updating a credential's encrypted_password to archive the old one.
     * PRD F-VAULT-03 AC#1 — "previous password added to encrypted history."
     * SRS FR-VAULT-03a — "store historical passwords in encrypted format."
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: HistoryEntity)

    // -------------------------------------------------------------------------
    // Read — PRD F-VAULT-03 AC#2, SCR-VLT-03
    // -------------------------------------------------------------------------

    /**
     * Returns the last 3 password history entries for a credential, newest first.
     * PRD F-VAULT-03 AC#2 — "displayed on Password Details Screen."
     * SCR-VLT-03 content: "Password History list — Lists last 3 passwords."
     * Index: idx_history_entry — Database_Schema.md §2.3
     */
    @Query("""
        SELECT * FROM password_history 
        WHERE password_entry_id = :credentialId 
        ORDER BY created_at DESC LIMIT 3
    """)
    suspend fun getLastThree(credentialId: String): List<HistoryEntity>

    /**
     * Returns ALL history entries for a credential (ordered newest first).
     * Used internally for count/purge logic.
     */
    @Query("""
        SELECT * FROM password_history 
        WHERE password_entry_id = :credentialId 
        ORDER BY created_at DESC
    """)
    suspend fun getAllForCredential(credentialId: String): List<HistoryEntity>

    /**
     * Returns the count of history entries for a credential.
     * Used to check if purge is needed before calling purgeOldestBeyondLimit.
     */
    @Query("SELECT COUNT(*) FROM password_history WHERE password_entry_id = :credentialId")
    suspend fun getCountForCredential(credentialId: String): Int

    // -------------------------------------------------------------------------
    // Purge — SRS FR-VAULT-03b (max 3 entries)
    // -------------------------------------------------------------------------

    /**
     * Deletes the oldest history entries exceeding the 3-entry limit.
     * SRS FR-VAULT-03b — "restrict password history log to maximum of 3 entries."
     * Architecture.md — "Limit history count to 3."
     *
     * Implementation: Delete any entry whose id is NOT in the top 3 by created_at.
     * This safely handles any number of excess entries (e.g., data corruption).
     */
    @Query("""
        DELETE FROM password_history 
        WHERE password_entry_id = :credentialId 
        AND id NOT IN (
            SELECT id FROM password_history 
            WHERE password_entry_id = :credentialId 
            ORDER BY created_at DESC 
            LIMIT 3
        )
    """)
    suspend fun purgeOldestBeyondLimit(credentialId: String)

    /**
     * Deletes all history entries for a credential.
     * Used when permanently deleting a credential (though FK CASCADE also handles this).
     */
    @Query("DELETE FROM password_history WHERE password_entry_id = :credentialId")
    suspend fun deleteAllForCredential(credentialId: String)

    // -------------------------------------------------------------------------
    // Convenience — combined insert + purge
    // -------------------------------------------------------------------------

    /**
     * Archives an old password and enforces the 3-entry limit.
     * This is the primary entry point called from AddEditCredentialActivity
     * during edit mode when the password field has changed.
     *
     * Workflow per Architecture.md data flow:
     *   1. Insert old encrypted_password as new HistoryEntity
     *   2. Purge any entries beyond the 3-entry limit
     *
     * Edge case (task-012): Only call this when the password actually changed.
     * If only name/username/url changed, do NOT create a history entry.
     */
    suspend fun archiveAndPurge(history: HistoryEntity) {
        insert(history)
        purgeOldestBeyondLimit(history.passwordEntryId)
    }
}
