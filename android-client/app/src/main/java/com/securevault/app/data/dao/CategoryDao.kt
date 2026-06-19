package com.securevault.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.securevault.app.data.entities.CategoryEntity

/**
 * Room DAO for the `categories` table.
 *
 * Spec refs:
 *   - Database_Schema.md §2.4 — categories table schema, FK, indexes
 *   - Database_Schema.md §8.1 — Predefined seed categories (Personal, Work, Banking, Shopping, Social)
 *   - SRS FR-VAULT-06 — "The client SHALL organize credentials using category tags"
 *   - PRD F-VAULT-06 AC#2 — "Users must be able to create, edit, or delete custom categories"
 *   - PRD F-VAULT-06 AC#3 — "Deleting a category must set category of assigned passwords
 *     to uncategorized; it must not delete the passwords"
 *   - Database_Schema.md §3 — categories use hard deletion (DELETE)
 *   - Database_Schema.md §4 — Audit trail: created_at / updated_at auto-set by DAO
 *   - Database_Schema.md §5 — vault_passwords.category_id FK ON DELETE SET NULL
 *
 * Predefined categories (Database_Schema.md §8.1):
 *   Personal, Work, Banking, Shopping, Social
 *   Seeded during account initialization — see [seedDefaultCategories].
 */
@Dao
interface CategoryDao {

    // -------------------------------------------------------------------------
    // Read operations — SCR-VLT-04 content inventory
    // -------------------------------------------------------------------------

    /**
     * Returns all categories for a given user, ordered by name.
     * Used by SCR-VLT-04 category folder list.
     * Index: idx_categories_user (user_id) — Database_Schema.md §2.4
     */
    @Query("SELECT * FROM categories WHERE user_id = :userId ORDER BY name ASC")
    suspend fun getCategoriesByUser(userId: String): List<CategoryEntity>

    /**
     * Returns a single category by ID.
     * Used for edit operations — PRD F-VAULT-06 AC#2.
     */
    @Query("SELECT * FROM categories WHERE id = :categoryId LIMIT 1")
    suspend fun getCategoryById(categoryId: String): CategoryEntity?

    /**
     * Returns the count of passwords assigned to a given category.
     * SCR-VLT-04 content inventory: "Custom list with items count display."
     */
    @Query("SELECT COUNT(*) FROM vault_passwords WHERE category_id = :categoryId AND deleted_at IS NULL")
    suspend fun getPasswordCountForCategory(categoryId: String): Int

    /**
     * Checks if a category name already exists for this user.
     * Edge case: "Inserting duplicate category names" — task-010 notes.
     * SCR-VLT-04 Error state: "Category name already exists."
     */
    @Query("SELECT COUNT(*) FROM categories WHERE user_id = :userId AND name = :name COLLATE NOCASE")
    suspend fun countByUserAndName(userId: String, name: String): Int

    // -------------------------------------------------------------------------
    // Write operations — PRD F-VAULT-06 AC#2
    // -------------------------------------------------------------------------

    /**
     * Inserts a single category.
     * Database_Schema.md §4 Audit Trail: created_at/updated_at are set by
     * CategoryEntity default values at construction time.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: CategoryEntity)

    /**
     * Inserts multiple categories (used for seed data).
     * Database_Schema.md §8.1 — 5 default categories per user.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    /**
     * Updates an existing category (name change).
     * PRD F-VAULT-06 AC#2 — "Users must be able to create, edit, or delete"
     * Database_Schema.md §4 — caller must update updated_at before calling.
     */
    @Update
    suspend fun update(category: CategoryEntity)

    // -------------------------------------------------------------------------
    // Delete operations — PRD F-VAULT-06 AC#3
    // -------------------------------------------------------------------------

    /**
     * Deletes a category by ID.
     * Database_Schema.md §3 — categories use hard deletion.
     *
     * IMPORTANT: Before calling this, the caller MUST execute
     * [resetPasswordsForCategory] to set assigned passwords' category_id to NULL.
     * This satisfies:
     *   - PRD F-VAULT-06 AC#3 — "set category of assigned passwords to uncategorized"
     *   - Database_Schema.md §5.2 — vault_passwords(category_id) ON DELETE SET NULL
     *   - SRS FR-VAULT-06 — "database SHALL preserve credentials if categories deleted"
     *
     * Room does not support FK ON DELETE SET NULL natively in all SQLite modes,
     * so we enforce it programmatically via [resetPasswordsForCategory].
     */
    @Query("DELETE FROM categories WHERE id = :categoryId")
    suspend fun deleteById(categoryId: String)

    /**
     * Sets category_id to NULL for all passwords in the given category.
     * Must be called BEFORE [deleteById] to satisfy:
     *   - PRD F-VAULT-06 AC#3 — passwords become uncategorized
     *   - Database_Schema.md §2.3 — category_id is nullable
     *   - IT-VAULT-09 — "resets assigned passwords' category_id value to null"
     */
    @Query("UPDATE vault_passwords SET category_id = NULL, updated_at = :timestamp WHERE category_id = :categoryId")
    suspend fun resetPasswordsForCategory(categoryId: String, timestamp: Long = System.currentTimeMillis())

    // -------------------------------------------------------------------------
    // Seed data — Database_Schema.md §8.1
    // -------------------------------------------------------------------------

    companion object {

        /** Default category names — Database_Schema.md §8.1 */
        val DEFAULT_CATEGORY_NAMES = listOf("Personal", "Work", "Banking", "Shopping", "Social")

        /**
         * Generates the 5 seed CategoryEntity objects for a new user.
         * ID format uses deterministic prefixes per Database_Schema.md §8.1.
         */
        fun createDefaultCategories(userId: String): List<CategoryEntity> {
            val now = System.currentTimeMillis()
            return DEFAULT_CATEGORY_NAMES.mapIndexed { _, name ->
                CategoryEntity(
                    id = "cat_${name.lowercase()}_$userId",
                    userId = userId,
                    name = name,
                    createdAt = now,
                    updatedAt = now
                )
            }
        }
    }
}
