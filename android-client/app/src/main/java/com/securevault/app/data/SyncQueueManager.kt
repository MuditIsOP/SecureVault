package com.securevault.app.data

import android.content.Context
import com.securevault.app.data.dao.SyncQueueDao
import com.securevault.app.data.entities.SyncQueueEntity
import java.util.UUID

/**
 * Manager for sync queue operations.
 *
 * Spec refs:
 *   - PRD F-SYNC-01 AC#2 — "Offline writes (creates, updates, deletes) must
 *     be logged as pending transactions in a local Sync Queue table"
 *   - SRS FR-SYNC-01a — "client SHALL track offline operations in a sync queue"
 *   - Database_Schema.md §2.6 — sync_queue schema
 *   - Database_Schema.md §5.5 — "Sync Queue Type Constraint: transaction_type
 *     check allows only INSERT, UPDATE, or DELETE"
 *   - Architecture.md §4 Data Flow 3 — "entry added to sync_queue with 'pending'"
 *
 * Application-layer check constraints (since Room doesn't support CHECK):
 *   - transaction_type IN ('INSERT', 'UPDATE', 'DELETE')
 *   - table_name IN ('vault_passwords', 'categories')
 *   - state_flag IN ('pending', 'failed')
 *   - record_id.length > 0
 *
 * Usage:
 *   After any vault/category create, update, or delete, call the
 *   corresponding log method to enqueue for sync.
 */
object SyncQueueManager {

    /**
     * Logs a vault credential INSERT to the sync queue.
     *
     * PRD F-SYNC-01 AC#2 — creates logged as pending
     *
     * @param dao SyncQueueDao instance
     * @param userId Current user ID
     * @param recordId ID of the created credential
     * @param payloadJson JSON representation of the record
     */
    suspend fun logCredentialInsert(
        dao: SyncQueueDao,
        userId: String,
        recordId: String,
        payloadJson: String?
    ) {
        validateAndInsert(
            dao, userId,
            SyncQueueEntity.TransactionType.INSERT,
            SyncQueueEntity.TableName.VAULT_PASSWORDS,
            recordId, payloadJson
        )
    }

    /**
     * Logs a vault credential UPDATE to the sync queue.
     *
     * PRD F-SYNC-01 AC#2 — updates logged as pending
     */
    suspend fun logCredentialUpdate(
        dao: SyncQueueDao,
        userId: String,
        recordId: String,
        payloadJson: String?
    ) {
        validateAndInsert(
            dao, userId,
            SyncQueueEntity.TransactionType.UPDATE,
            SyncQueueEntity.TableName.VAULT_PASSWORDS,
            recordId, payloadJson
        )
    }

    /**
     * Logs a vault credential DELETE to the sync queue.
     *
     * PRD F-SYNC-01 AC#2 — deletes logged as pending
     * Database_Schema.md §2.6 — payload_json is NULL for DELETEs
     */
    suspend fun logCredentialDelete(
        dao: SyncQueueDao,
        userId: String,
        recordId: String
    ) {
        validateAndInsert(
            dao, userId,
            SyncQueueEntity.TransactionType.DELETE,
            SyncQueueEntity.TableName.VAULT_PASSWORDS,
            recordId, null
        )
    }

    /**
     * Logs a category INSERT to the sync queue.
     */
    suspend fun logCategoryInsert(
        dao: SyncQueueDao,
        userId: String,
        recordId: String,
        payloadJson: String?
    ) {
        validateAndInsert(
            dao, userId,
            SyncQueueEntity.TransactionType.INSERT,
            SyncQueueEntity.TableName.CATEGORIES,
            recordId, payloadJson
        )
    }

    /**
     * Logs a category UPDATE to the sync queue.
     */
    suspend fun logCategoryUpdate(
        dao: SyncQueueDao,
        userId: String,
        recordId: String,
        payloadJson: String?
    ) {
        validateAndInsert(
            dao, userId,
            SyncQueueEntity.TransactionType.UPDATE,
            SyncQueueEntity.TableName.CATEGORIES,
            recordId, payloadJson
        )
    }

    /**
     * Logs a category DELETE to the sync queue.
     */
    suspend fun logCategoryDelete(
        dao: SyncQueueDao,
        userId: String,
        recordId: String
    ) {
        validateAndInsert(
            dao, userId,
            SyncQueueEntity.TransactionType.DELETE,
            SyncQueueEntity.TableName.CATEGORIES,
            recordId, null
        )
    }

    // -------------------------------------------------------------------------
    // Internal — application-layer CHECK constraint enforcement
    // Database_Schema.md §5.5
    // -------------------------------------------------------------------------

    /**
     * Validates and inserts a sync queue entry.
     *
     * Enforces application-layer CHECK constraints from Database_Schema.md §2.6:
     *   - transaction_type IN ('INSERT','UPDATE','DELETE')
     *   - table_name IN ('vault_passwords','categories')
     *   - record_id.length > 0
     */
    private suspend fun validateAndInsert(
        dao: SyncQueueDao,
        userId: String,
        transactionType: SyncQueueEntity.TransactionType,
        tableName: SyncQueueEntity.TableName,
        recordId: String,
        payloadJson: String?
    ) {
        require(recordId.isNotEmpty()) {
            "record_id must not be empty — Database_Schema.md §2.6 CHECK"
        }

        val entry = SyncQueueEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            transactionType = transactionType.value,
            tableName = tableName.value,
            recordId = recordId,
            payloadJson = payloadJson,
            stateFlag = SyncQueueEntity.StateFlag.PENDING.value
        )

        dao.insert(entry)
    }
}
