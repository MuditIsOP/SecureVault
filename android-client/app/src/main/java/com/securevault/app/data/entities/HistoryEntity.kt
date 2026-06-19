package com.securevault.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the `password_history` table.
 *
 * Spec refs:
 *   - Database_Schema.md Section 2.3 — full column spec
 *   - Security_Requirements.md Section 6 — encrypted_password is RESTRICTED
 *   - SRS FR-VAULT-03 — retain last 3 prior encrypted passwords
 *   - Data Integrity Rule §5.3 — FK blocks orphan history entries
 *
 * Foreign Keys (Database_Schema.md §2.3):
 *   password_entry_id → vault_passwords(id) ON DELETE CASCADE
 *
 * Indexes (Database_Schema.md §2.3):
 *   idx_history_entry : (password_entry_id, created_at DESC)
 *
 * RESTRICTED field: encrypted_password — AES-256-GCM ciphertext of prior
 * password, encrypted via VMK. Never stored in plaintext.
 */
@Entity(
    tableName = "password_history",
    foreignKeys = [
        ForeignKey(
            entity = PasswordEntity::class,
            parentColumns = ["id"],
            childColumns = ["password_entry_id"],
            onDelete = ForeignKey.CASCADE   // Database_Schema.md §2.3 FK spec
        )
    ],
    indices = [
        // Speeds up retrieval of last 3 history entries — Database_Schema.md §2.3
        Index(value = ["password_entry_id", "created_at"],
            name = "idx_history_entry")
    ]
)
data class HistoryEntity(

    /** Randomly generated UUID string. PRIMARY KEY — Database_Schema.md §2.3 */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** FK → vault_passwords(id) CASCADE — Database_Schema.md §2.3 */
    @ColumnInfo(name = "password_entry_id")
    val passwordEntryId: String,

    /**
     * AES-256-GCM ciphertext of the old password. RESTRICTED.
     * Encrypted by CryptographyHelper using VMK before write (FR-VAULT-03).
     * CHECK length > 0 enforced at application layer.
     */
    @ColumnInfo(name = "encrypted_password")
    val encryptedPassword: String,

    /** History record creation timestamp (ms) — Database_Schema.md §2.3 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
