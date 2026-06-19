package com.securevault.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the `sync_queue` table.
 *
 * Spec refs:
 *   - Database_Schema.md Section 2.6 — full column spec
 *   - SRS FR-SYNC-01 — offline writes logged as pending in Sync Queue
 *   - Testing_Strategy.md Part 1 §4 — SyncQueueManager unit test (IT-SYNC-01)
 *   - Architecture.md §4 Data Flow 3 — entry added to sync_queue with 'pending' state
 *
 * Foreign Keys (Database_Schema.md §2.6):
 *   user_id → users(id) ON DELETE CASCADE
 *
 * Indexes (Database_Schema.md §2.6):
 *   idx_sync_pending : (user_id, state_flag)
 *
 * Check constraints (enforced at application layer in SyncQueueManager):
 *   transaction_type IN ('INSERT','UPDATE','DELETE')
 *   table_name IN ('vault_passwords','categories')
 *   state_flag IN ('pending','failed')
 */
@Entity(
    tableName = "sync_queue",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE   // Database_Schema.md §2.6 FK spec
        )
    ],
    indices = [
        // Optimises background sync WorkManager lookups — Database_Schema.md §2.6
        Index(value = ["user_id", "state_flag"], name = "idx_sync_pending")
    ]
)
data class SyncQueueEntity(

    /** Randomly generated UUID string. PRIMARY KEY — Database_Schema.md §2.6 */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** User ID queue belongs to. FK → users(id) CASCADE */
    @ColumnInfo(name = "user_id")
    val userId: String,

    /**
     * Database operation type. Valid values: INSERT, UPDATE, DELETE.
     * CHECK enforced at application layer in SyncQueueManager.
     * — Database_Schema.md §2.6, Data Integrity Rule §5.5
     */
    @ColumnInfo(name = "transaction_type")
    val transactionType: String,

    /**
     * Target table name. Valid values: vault_passwords, categories.
     * CHECK enforced at application layer in SyncQueueManager.
     * — Database_Schema.md §2.6
     */
    @ColumnInfo(name = "table_name")
    val tableName: String,

    /**
     * Target record primary key ID.
     * CHECK length > 0 enforced at application layer.
     */
    @ColumnInfo(name = "record_id")
    val recordId: String,

    /**
     * JSON payload of the database record. Nullable.
     * NULL for DELETE transactions — Database_Schema.md §2.6
     */
    @ColumnInfo(name = "payload_json")
    val payloadJson: String? = null,

    /**
     * Queue processing status. Default: 'pending'.
     * Valid values: pending, failed.
     * CHECK enforced at application layer in SyncQueueManager.
     * — Database_Schema.md §2.6, SRS FR-SYNC-01
     */
    @ColumnInfo(name = "state_flag")
    val stateFlag: String = StateFlag.PENDING.value,

    /** Queue entry insertion timestamp (ms) — Database_Schema.md §2.6 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    /** Valid state_flag values per Database_Schema.md §2.6 */
    enum class StateFlag(val value: String) {
        PENDING("pending"),
        FAILED("failed")
    }

    /** Valid transaction_type values per Database_Schema.md §2.6 */
    enum class TransactionType(val value: String) {
        INSERT("INSERT"),
        UPDATE("UPDATE"),
        DELETE("DELETE")
    }

    /** Valid table_name values per Database_Schema.md §2.6 */
    enum class TableName(val value: String) {
        VAULT_PASSWORDS("vault_passwords"),
        CATEGORIES("categories")
    }
}
