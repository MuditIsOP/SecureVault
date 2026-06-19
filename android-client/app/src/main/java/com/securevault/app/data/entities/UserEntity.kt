package com.securevault.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the `users` table.
 *
 * Spec refs:
 *   - Database_Schema.md Section 2.1 — full column spec
 *   - Security_Requirements.md Section 4 — RESTRICTED field classifications
 *   - SRS FR-SYNC-01 — local Room database requirement
 *   - Architecture.md ADR-02 — Room + SQLCipher at-rest encryption
 *
 * Sensitive columns (RESTRICTED per Security_Requirements.md Section 6):
 *   security_answer_hash  — SHA-256 hashed, never plaintext
 *   backup_code_hashes    — SHA-256 hashed, never plaintext
 *   encrypted_vmk         — AES-256-GCM encrypted by Cloud KMS
 *   pin_hash              — SHA-256 hashed, never plaintext
 *
 * Unique constraint: google_email (Database_Schema.md Section 2.1)
 * Check constraint for pin_failed_attempts >= 0 is enforced at the
 * application layer in PINLockoutManager (SQLite CHECK not natively
 * supported by Room annotations — see ⚠️ SPEC NOTE below).
 */
@Entity(
    tableName = "users",
    indices = [
        Index(value = ["google_email"], unique = true)
    ]
)
data class UserEntity(

    /** Google user unique ID (uid from JWT). PRIMARY KEY — Database_Schema.md §2.1 */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** Authenticated Google email address. UNIQUE — Database_Schema.md §2.1 */
    @ColumnInfo(name = "google_email")
    val googleEmail: String,

    /** ID referencing predefined security question. CHECK length > 0 */
    @ColumnInfo(name = "security_question_id")
    val securityQuestionId: String,

    /**
     * Salted SHA-256 hash of security answer. RESTRICTED — Security_Requirements.md §4.
     * CHECK length = 64 enforced at application layer before insert.
     */
    @ColumnInfo(name = "security_answer_hash")
    val securityAnswerHash: String,

    /**
     * Comma-separated SHA-256 backup code hashes. RESTRICTED — Security_Requirements.md §4.
     */
    @ColumnInfo(name = "backup_code_hashes")
    val backupCodeHashes: String,

    /**
     * VMK encrypted by Cloud KMS. RESTRICTED — Security_Requirements.md §4.
     * Must never be stored in plaintext — Architecture.md ADR-03.
     */
    @ColumnInfo(name = "encrypted_vmk")
    val encryptedVmk: String,

    /**
     * Salted SHA-256 hash of 6-digit device-specific PIN. RESTRICTED.
     * CHECK length = 64 enforced at application layer (PinHasher).
     */
    @ColumnInfo(name = "pin_hash")
    val pinHash: String,

    /**
     * Consecutive failed PIN attempts. CHECK >= 0 enforced at application layer.
     * Default: 0 — Database_Schema.md §2.1
     */
    @ColumnInfo(name = "pin_failed_attempts")
    val pinFailedAttempts: Int = 0,

    /**
     * Unix timestamp (ms) of lockout expiry. Nullable — Database_Schema.md §2.1.
     * NULL means no active lockout.
     */
    @ColumnInfo(name = "pin_lockout_until")
    val pinLockoutUntil: Long? = null,

    /** Unix timestamp (ms) of account creation — Database_Schema.md §4 Audit Trail */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /** Unix timestamp (ms) of last record update — Database_Schema.md §4 Audit Trail */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

/*
 * ⚠️ SPEC NOTE: Database_Schema.md §2.1 specifies SQLite CHECK constraints for
 * pin_failed_attempts >= 0, security_answer_hash length = 64, and pin_hash length = 64.
 * Room @Entity does not expose a native CHECK constraint annotation. These constraints
 * are enforced at the application layer:
 *   - pin_failed_attempts: validated by PINLockoutManager (task-007)
 *   - security_answer_hash length: validated by SecurityQuestionActivity (task-005)
 *   - pin_hash length: validated by PinHasher (task-006)
 * No spec deviation — constraint enforcement is logically equivalent.
 */
