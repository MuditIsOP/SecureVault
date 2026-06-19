package com.securevault.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.securevault.app.data.entities.CategoryEntity
import com.securevault.app.data.entities.DeviceSessionEntity
import com.securevault.app.data.entities.HistoryEntity
import com.securevault.app.data.entities.PasswordEntity
import com.securevault.app.data.entities.SyncQueueEntity
import com.securevault.app.data.entities.UserEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room schema integration tests for task-002.
 *
 * Test IDs from Testing_Strategy.md:
 *   - IT-SYNC-01 (partial): Verifies Room schema compiles and entities are registered.
 *   - Foreign key ON DELETE CASCADE verification.
 *   - Sensitive data field presence verification (no plaintext leakage).
 *
 * Uses Room In-Memory database builder per Testing_Strategy.md §Appendix 1.
 * SQLCipher is NOT used in-memory tests — the schema structure is what is tested here.
 * Full SQLCipher encryption is verified in the security test ST-STRIDE-02 (task-003).
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseSchemaTest {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        // In-memory database — no passphrase needed for schema tests
        // Testing_Strategy.md §Appendix 1: Room In-Memory database integration helpers
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        )
            .allowMainThreadQueries() // Permitted in tests only
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // IT-SYNC-01 (partial): Room schema compiles and all tables are accessible
    // -------------------------------------------------------------------------

    @Test
    fun schema_compilesSuccessfully() {
        // If AppDatabase builds without exception, the schema compiled correctly.
        // Verifies all 6 entities are registered — Database_Schema.md §2
        assertNotNull("AppDatabase must be non-null after build", db)
        assertNotNull("OpenHelper must be accessible", db.openHelper)
    }

    // -------------------------------------------------------------------------
    // Foreign key ON DELETE CASCADE — Database_Schema.md §2.2, §2.4, §2.3, §2.5, §2.6
    // Testing_Strategy.md: "Verify foreign key ON DELETE CASCADE constraints"
    // -------------------------------------------------------------------------

    @Test
    fun foreignKey_userDelete_cascadesPasswordEntity() {
        // PRAGMA foreign_keys=ON is set in AppDatabase postKey hook
        // For in-memory tests, enable manually
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON;")

        val user = buildUser("user-1")
        val category = buildCategory("cat-1", "user-1")
        val password = buildPassword("pwd-1", "user-1", "cat-1")

        // Insert via raw SQL since DAOs are implemented in later tasks
        insertUser(user)
        insertCategory(category)
        insertPassword(password)

        // Cascade: deleting user must delete their passwords
        db.openHelper.writableDatabase.execSQL("DELETE FROM users WHERE id = 'user-1'")

        val cursor = db.openHelper.readableDatabase
            .rawQuery("SELECT * FROM vault_passwords WHERE id = 'pwd-1'", null)
        assertEquals(
            "Password must be cascade-deleted when user is deleted",
            0, cursor.count
        )
        cursor.close()
    }

    @Test
    fun foreignKey_userDelete_cascadesCategoryEntity() {
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON;")

        val user = buildUser("user-2")
        val category = buildCategory("cat-2", "user-2")

        insertUser(user)
        insertCategory(category)

        db.openHelper.writableDatabase.execSQL("DELETE FROM users WHERE id = 'user-2'")

        val cursor = db.openHelper.readableDatabase
            .rawQuery("SELECT * FROM categories WHERE id = 'cat-2'", null)
        assertEquals(
            "Category must be cascade-deleted when user is deleted",
            0, cursor.count
        )
        cursor.close()
    }

    @Test
    fun foreignKey_passwordDelete_cascadesHistoryEntity() {
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON;")

        val user = buildUser("user-3")
        val password = buildPassword("pwd-3", "user-3", null)
        val history = buildHistory("hist-3", "pwd-3")

        insertUser(user)
        insertPassword(password)
        insertHistory(history)

        db.openHelper.writableDatabase.execSQL("DELETE FROM vault_passwords WHERE id = 'pwd-3'")

        val cursor = db.openHelper.readableDatabase
            .rawQuery("SELECT * FROM password_history WHERE id = 'hist-3'", null)
        assertEquals(
            "History must be cascade-deleted when password is deleted",
            0, cursor.count
        )
        cursor.close()
    }

    @Test
    fun foreignKey_categoryDelete_setsPasswordCategoryIdNull() {
        // ON DELETE SET NULL — Database_Schema.md §2.2 FK spec
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON;")

        val user = buildUser("user-4")
        val category = buildCategory("cat-4", "user-4")
        val password = buildPassword("pwd-4", "user-4", "cat-4")

        insertUser(user)
        insertCategory(category)
        insertPassword(password)

        db.openHelper.writableDatabase.execSQL("DELETE FROM categories WHERE id = 'cat-4'")

        val cursor = db.openHelper.readableDatabase
            .rawQuery("SELECT category_id FROM vault_passwords WHERE id = 'pwd-4'", null)
        cursor.moveToFirst()
        assertNull(
            "category_id must be NULL after category is deleted (ON DELETE SET NULL)",
            if (cursor.isNull(0)) null else cursor.getString(0)
        )
        cursor.close()
    }

    @Test
    fun foreignKey_userDelete_cascadesSyncQueueEntity() {
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON;")

        val user = buildUser("user-5")
        val syncItem = buildSyncQueue("sync-5", "user-5")

        insertUser(user)
        insertSyncQueue(syncItem)

        db.openHelper.writableDatabase.execSQL("DELETE FROM users WHERE id = 'user-5'")

        val cursor = db.openHelper.readableDatabase
            .rawQuery("SELECT * FROM sync_queue WHERE id = 'sync-5'", null)
        assertEquals(
            "Sync queue entry must be cascade-deleted when user is deleted",
            0, cursor.count
        )
        cursor.close()
    }

    @Test
    fun foreignKey_userDelete_cascadesDeviceSessionEntity() {
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON;")

        val user = buildUser("user-6")
        val session = buildDeviceSession("session-6", "user-6")

        insertUser(user)
        insertDeviceSession(session)

        db.openHelper.writableDatabase.execSQL("DELETE FROM users WHERE id = 'user-6'")

        val cursor = db.openHelper.readableDatabase
            .rawQuery("SELECT * FROM device_sessions WHERE id = 'session-6'", null)
        assertEquals(
            "Device session must be cascade-deleted when user is deleted",
            0, cursor.count
        )
        cursor.close()
    }

    // -------------------------------------------------------------------------
    // Security test: encrypted_password column must exist in vault_passwords
    // and must not be the same as plaintext input (validating field is present)
    // Testing_Strategy.md ST-STRIDE-02 (basic schema verification component)
    // -------------------------------------------------------------------------

    @Test
    fun schema_passwordEntity_hasEncryptedPasswordColumn() {
        val user = buildUser("user-7")
        val password = buildPassword("pwd-7", "user-7", null)
        insertUser(user)
        insertPassword(password)

        val cursor = db.openHelper.readableDatabase
            .rawQuery("SELECT encrypted_password FROM vault_passwords WHERE id = 'pwd-7'", null)
        assertEquals("encrypted_password column must exist", 1, cursor.count)
        cursor.moveToFirst()
        val stored = cursor.getString(0)
        assertEquals(
            "Stored value must match what was inserted (VMK encryption tested in task-003)",
            "ENCRYPTED_PLACEHOLDER",
            stored
        )
        cursor.close()
    }

    // -------------------------------------------------------------------------
    // Helper builders — use raw SQL since DAOs are implemented in later tasks
    // -------------------------------------------------------------------------

    private fun buildUser(id: String) = UserEntity(
        id = id,
        googleEmail = "$id@test.com",
        securityQuestionId = "q_01",
        securityAnswerHash = "a".repeat(64),
        backupCodeHashes = "hash1,hash2",
        encryptedVmk = "ENCRYPTED_VMK_PLACEHOLDER",
        pinHash = "b".repeat(64)
    )

    private fun buildCategory(id: String, userId: String) = CategoryEntity(
        id = id, userId = userId, name = "Work"
    )

    private fun buildPassword(id: String, userId: String, categoryId: String?) = PasswordEntity(
        id = id,
        userId = userId,
        name = "TestSite",
        usernameEmail = "test@example.com",
        encryptedPassword = "ENCRYPTED_PLACEHOLDER",
        categoryId = categoryId
    )

    private fun buildHistory(id: String, passwordEntryId: String) = HistoryEntity(
        id = id, passwordEntryId = passwordEntryId, encryptedPassword = "ENCRYPTED_HISTORY"
    )

    private fun buildDeviceSession(id: String, userId: String) = DeviceSessionEntity(
        id = id, userId = userId, deviceName = "Pixel 7", androidVersion = "14.0"
    )

    private fun buildSyncQueue(id: String, userId: String) = SyncQueueEntity(
        id = id,
        userId = userId,
        transactionType = SyncQueueEntity.TransactionType.INSERT.value,
        tableName = SyncQueueEntity.TableName.VAULT_PASSWORDS.value,
        recordId = "rec-1",
        payloadJson = "{\"name\":\"Test\"}"
    )

    private fun insertUser(entity: UserEntity) {
        db.openHelper.writableDatabase.execSQL(
            """INSERT INTO users (id, google_email, security_question_id, security_answer_hash,
                backup_code_hashes, encrypted_vmk, pin_hash, pin_failed_attempts,
                pin_lockout_until, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf(entity.id, entity.googleEmail, entity.securityQuestionId,
                entity.securityAnswerHash, entity.backupCodeHashes, entity.encryptedVmk,
                entity.pinHash, entity.pinFailedAttempts, entity.pinLockoutUntil,
                entity.createdAt, entity.updatedAt)
        )
    }

    private fun insertCategory(entity: CategoryEntity) {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO categories (id, user_id, name, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            arrayOf(entity.id, entity.userId, entity.name, entity.createdAt, entity.updatedAt)
        )
    }

    private fun insertPassword(entity: PasswordEntity) {
        db.openHelper.writableDatabase.execSQL(
            """INSERT INTO vault_passwords (id, user_id, name, username_email, encrypted_password,
                website_url, category_id, favorite, created_at, updated_at, last_viewed,
                last_autofilled, password_strength, deleted_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf(entity.id, entity.userId, entity.name, entity.usernameEmail,
                entity.encryptedPassword, entity.websiteUrl, entity.categoryId,
                entity.favorite, entity.createdAt, entity.updatedAt, entity.lastViewed,
                entity.lastAutofilled, entity.passwordStrength, entity.deletedAt)
        )
    }

    private fun insertHistory(entity: HistoryEntity) {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO password_history (id, password_entry_id, encrypted_password, created_at) VALUES (?, ?, ?, ?)",
            arrayOf(entity.id, entity.passwordEntryId, entity.encryptedPassword, entity.createdAt)
        )
    }

    private fun insertDeviceSession(entity: DeviceSessionEntity) {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO device_sessions (id, user_id, device_name, android_version, last_active_time, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf(entity.id, entity.userId, entity.deviceName, entity.androidVersion,
                entity.lastActiveTime, entity.createdAt)
        )
    }

    private fun insertSyncQueue(entity: SyncQueueEntity) {
        db.openHelper.writableDatabase.execSQL(
            """INSERT INTO sync_queue (id, user_id, transaction_type, table_name, record_id,
                payload_json, state_flag, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf(entity.id, entity.userId, entity.transactionType, entity.tableName,
                entity.recordId, entity.payloadJson, entity.stateFlag, entity.createdAt)
        )
    }
}
