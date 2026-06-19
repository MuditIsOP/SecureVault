package com.securevault.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the `vault_passwords` table.
 *
 * Spec refs:
 *   - Database_Schema.md Section 2.2 — full column spec
 *   - Security_Requirements.md Section 4 — encrypted_password is RESTRICTED
 *   - SRS FR-VAULT-02 — AES-256-GCM in-memory encryption before write
 *   - SRS FR-VAULT-07 — soft-delete via deleted_at column
 *   - Architecture.md ADR-02 — full database encrypted via SQLCipher
 *
 * Foreign Keys (Database_Schema.md §2.2):
 *   user_id     → users(id)      ON DELETE CASCADE
 *   category_id → categories(id) ON DELETE SET NULL
 *
 * Indexes (Database_Schema.md §2.2):
 *   idx_passwords_user_search : (user_id, name, username_email, website_url)
 *   idx_passwords_deleted     : (user_id, deleted_at)
 *
 * RESTRICTED field: encrypted_password — AES-256-GCM ciphertext of plaintext
 * password, encrypted via VMK before any write. Never stored in plaintext.
 */
@Entity(
    tableName = "vault_passwords",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE   // Database_Schema.md §2.2 FK spec
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL  // Database_Schema.md §2.2 FK spec
        )
    ],
    indices = [
        // Optimises real-time dashboard search — Database_Schema.md §2.2
        Index(value = ["user_id", "name", "username_email", "website_url"],
            name = "idx_passwords_user_search"),
        // Optimises Trash screen queries — Database_Schema.md §2.2
        Index(value = ["user_id", "deleted_at"],
            name = "idx_passwords_deleted"),
        // Required by Room for FK column on category_id
        Index(value = ["category_id"])
    ]
)
data class PasswordEntity(

    /** Randomly generated UUID string. PRIMARY KEY — Database_Schema.md §2.2 */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** User ID who owns this entry. FK → users(id) CASCADE */
    @ColumnInfo(name = "user_id")
    val userId: String,

    /** Display name of the account/website. CHECK length > 0 enforced at UI layer. */
    @ColumnInfo(name = "name")
    val name: String,

    /** Registered account username/email. CHECK length > 0 enforced at UI layer. */
    @ColumnInfo(name = "username_email")
    val usernameEmail: String,

    /**
     * AES-256-GCM ciphertext of the plaintext password. RESTRICTED.
     * Encrypted by CryptographyHelper using VMK before any write (FR-VAULT-02).
     * CHECK length > 0 enforced at application layer.
     */
    @ColumnInfo(name = "encrypted_password")
    val encryptedPassword: String,

    /** Access URL of the website. Nullable — Database_Schema.md §2.2 */
    @ColumnInfo(name = "website_url")
    val websiteUrl: String? = null,

    /** Category FK. Nullable — SET NULL on category delete. */
    @ColumnInfo(name = "category_id")
    val categoryId: String? = null,

    /**
     * Favorite flag. CHECK (0 or 1) enforced at application layer.
     * Default 0 — Database_Schema.md §2.2 / Data Integrity Rule §5.4
     */
    @ColumnInfo(name = "favorite")
    val favorite: Int = 0,

    /** Creation timestamp (ms) — Database_Schema.md §4 Audit Trail */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /** Last modification timestamp (ms) — Database_Schema.md §4 Audit Trail */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    /** Last viewed timestamp (ms). Nullable — Database_Schema.md §2.2 */
    @ColumnInfo(name = "last_viewed")
    val lastViewed: Long? = null,

    /** Last Android Autofill timestamp (ms). Nullable — Database_Schema.md §2.2 */
    @ColumnInfo(name = "last_autofilled")
    val lastAutofilled: Long? = null,

    /**
     * Password strength evaluation. CHECK (WEAK|MEDIUM|STRONG) enforced at app layer.
     * Default WEAK — Database_Schema.md §2.2
     */
    @ColumnInfo(name = "password_strength")
    val passwordStrength: String = PasswordStrength.WEAK.value,

    /**
     * Soft-delete timestamp (ms). NULL = active. Non-null = trashed.
     * Trash countdown logic — SRS FR-VAULT-07, Database_Schema.md §3.
     */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null
) {
    /** Valid strength values per Database_Schema.md §2.2 CHECK constraint */
    enum class PasswordStrength(val value: String) {
        WEAK("WEAK"),
        MEDIUM("MEDIUM"),
        STRONG("STRONG")
    }
}
