package com.securevault.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the `device_sessions` table.
 *
 * Spec refs:
 *   - Database_Schema.md Section 2.5 — full column spec
 *   - Security_Requirements.md Section 2.3 — device binding strategy,
 *     ANDROID_ID as session identifier
 *   - SRS FR-DEV-01 — 3-device concurrent session limit
 *   - Permissions_Matrix.md §4 — owner field bound to JWT uid
 *
 * Foreign Keys (Database_Schema.md §2.5):
 *   user_id → users(id) ON DELETE CASCADE
 *
 * Indexes (Database_Schema.md §2.5):
 *   idx_sessions_user : (user_id)
 *
 * Note: id is derived from Settings.Secure.ANDROID_ID per
 * Security_Requirements.md §2.3 Device Binding Strategy.
 */
@Entity(
    tableName = "device_sessions",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE   // Database_Schema.md §2.5 FK spec
        )
    ],
    indices = [
        // Speeds up device session count checks for 3-device limit — Database_Schema.md §2.5
        Index(value = ["user_id"], name = "idx_sessions_user")
    ]
)
data class DeviceSessionEntity(

    /**
     * Session ID derived from Settings.Secure.ANDROID_ID.
     * PRIMARY KEY — Database_Schema.md §2.5, Security_Requirements.md §2.3
     */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** User ID associated with this session. FK → users(id) CASCADE */
    @ColumnInfo(name = "user_id")
    val userId: String,

    /**
     * User-visible device model string (e.g. "Pixel 7").
     * CHECK length > 0 enforced at application layer.
     */
    @ColumnInfo(name = "device_name")
    val deviceName: String,

    /**
     * Operating system version string (e.g. "14.0").
     * CHECK length > 0 enforced at application layer.
     */
    @ColumnInfo(name = "android_version")
    val androidVersion: String,

    /** Timestamp (ms) of last API connection event — Database_Schema.md §2.5 */
    @ColumnInfo(name = "last_active_time")
    val lastActiveTime: Long = System.currentTimeMillis(),

    /** Session creation timestamp (ms) — Database_Schema.md §2.5 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
