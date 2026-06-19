package com.securevault.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the `categories` table.
 *
 * Spec refs:
 *   - Database_Schema.md Section 2.4 — full column spec
 *   - Security_Requirements.md Section 6 — category name is CONFIDENTIAL,
 *     protected by table-level SQLCipher encryption
 *   - SRS FR-VAULT-06 — category organization feature
 *   - Database_Schema.md §8 — seed data: Personal, Work, Banking, Shopping, Social
 *
 * Foreign Keys (Database_Schema.md §2.4):
 *   user_id → users(id) ON DELETE CASCADE
 *
 * Indexes (Database_Schema.md §2.4):
 *   idx_categories_user : (user_id)
 */
@Entity(
    tableName = "categories",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE   // Database_Schema.md §2.4 FK spec
        )
    ],
    indices = [
        // Accelerates category dropdown rendering — Database_Schema.md §2.4
        Index(value = ["user_id"], name = "idx_categories_user")
    ]
)
data class CategoryEntity(

    /** Randomly generated UUID string. PRIMARY KEY — Database_Schema.md §2.4 */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** User ID who owns this category. FK → users(id) CASCADE */
    @ColumnInfo(name = "user_id")
    val userId: String,

    /**
     * Category display name. CONFIDENTIAL — Security_Requirements.md §6.
     * Protected by table-level SQLCipher. CHECK length > 0 enforced at UI layer.
     * Seed values: Personal, Work, Banking, Shopping, Social — Database_Schema.md §8.
     */
    @ColumnInfo(name = "name")
    val name: String,

    /** Creation timestamp (ms) — Database_Schema.md §4 Audit Trail */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /** Last modification timestamp (ms) — Database_Schema.md §4 Audit Trail */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
