package com.securevault.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.securevault.app.data.entities.SyncQueueEntity

/**
 * Room DAO for the sync_queue table.
 *
 * Spec refs:
 *   - Database_Schema.md §2.6 — sync_queue table schema
 *   - SRS FR-SYNC-01a — "client SHALL track offline operations in a sync queue"
 *   - PRD F-SYNC-01 AC#2 — "Offline writes must be logged as pending
 *     transactions in a local Sync Queue table"
 *   - Architecture.md — WorkManager Sync Workers, SQLCipher Local Database
 *   - Index: idx_sync_pending (user_id, state_flag) — optimizes WorkManager lookups
 *
 * Hard-delete policy per Database_Schema.md §3:
 *   sync_queue uses hard deletion (DELETE statements).
 */
@Dao
interface SyncQueueDao {

    // -------------------------------------------------------------------------
    // Insert — PRD F-SYNC-01 AC#2 "logged as pending transactions"
    // -------------------------------------------------------------------------

    /**
     * Inserts a new sync queue entry.
     * Called by SyncQueueManager when vault modifications occur offline.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SyncQueueEntity)

    // -------------------------------------------------------------------------
    // Query pending entries — used by SyncWorker
    // -------------------------------------------------------------------------

    /**
     * Returns all pending sync entries for a user, ordered by creation time.
     * Uses idx_sync_pending index for efficient lookup.
     *
     * PRD F-SYNC-01 AC#4 — "pushes pending queue transactions"
     */
    @Query("""
        SELECT * FROM sync_queue 
        WHERE user_id = :userId AND state_flag = 'pending' 
        ORDER BY created_at ASC
    """)
    suspend fun getPendingEntries(userId: String): List<SyncQueueEntity>

    /**
     * Returns all failed sync entries for retry.
     * Edge case: retry with exponential backoff (handled by SyncWorker).
     */
    @Query("""
        SELECT * FROM sync_queue 
        WHERE user_id = :userId AND state_flag = 'failed' 
        ORDER BY created_at ASC
    """)
    suspend fun getFailedEntries(userId: String): List<SyncQueueEntity>

    /**
     * Returns count of pending entries — used for UI badge or sync indicator.
     */
    @Query("SELECT COUNT(*) FROM sync_queue WHERE user_id = :userId AND state_flag = 'pending'")
    suspend fun getPendingCount(userId: String): Int

    // -------------------------------------------------------------------------
    // Delete — PRD F-SYNC-01 AC#5 "processed queue rows are deleted"
    // Database_Schema.md §3: sync_queue uses hard deletion
    // -------------------------------------------------------------------------

    /**
     * Deletes a single processed sync entry after successful push.
     * PRD F-SYNC-01 AC#5 — "Upon success, processed queue rows are deleted"
     */
    @Query("DELETE FROM sync_queue WHERE id = :entryId")
    suspend fun deleteById(entryId: String)

    /**
     * Deletes all sync entries for a list of IDs (batch cleanup after sync).
     */
    @Query("DELETE FROM sync_queue WHERE id IN (:entryIds)")
    suspend fun deleteByIds(entryIds: List<String>)

    /**
     * Deletes all sync entries for a user (used on logout/data wipe).
     */
    @Query("DELETE FROM sync_queue WHERE user_id = :userId")
    suspend fun deleteAllForUser(userId: String)

    // -------------------------------------------------------------------------
    // Update state — mark entries as failed on sync error
    // -------------------------------------------------------------------------

    /**
     * Marks a sync entry as failed.
     * Database_Schema.md §2.6 — state_flag IN ('pending','failed')
     */
    @Query("UPDATE sync_queue SET state_flag = 'failed' WHERE id = :entryId")
    suspend fun markFailed(entryId: String)

    /**
     * Resets failed entries back to pending for retry.
     */
    @Query("UPDATE sync_queue SET state_flag = 'pending' WHERE user_id = :userId AND state_flag = 'failed'")
    suspend fun resetFailedToPending(userId: String)
}
