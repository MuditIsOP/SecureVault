package com.securevault.app.security

import android.content.Context
import android.util.Log
import com.securevault.app.data.DatabaseModule
import com.securevault.app.data.api.AuthApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manages progressive delays, lockouts, local database state, and cloud sync for failed PIN attempts.
 *
 * Spec refs:
 *   - PRD F-AUTH-04 — PIN rate limiting, lockout times, DB sync, bypass blocks
 *   - SRS FR-AUTH-04 — client progressive delay lockouts, remote DB sync
 *   - Security_Requirements.md §6 Brute Force (Cloud Enforced Block)
 *   - Database_Schema.md §2.1 — users.pin_failed_attempts, users.pin_lockout_until
 *   - Testing_Strategy.md Part 1 §2 — unit testing target
 */
object PINLockoutManager {

    private const val TAG = "PINLockoutManager"

    // Cooldown timings in milliseconds
    const val DELAY_30S = 30_000L
    const val DELAY_1M = 60_000L
    const val DELAY_2M = 120_000L
    const val DELAY_5M = 300_000L
    const val DELAY_15M = 900_000L
    const val LOCKOUT_2H = 7_200_000L // 2 hours

    /**
     * Calculates delay period based on failed attempts count.
     * Failed attempts:
     *   1-5: 0 ms
     *   6: 30s
     *   7: 1m
     *   8: 2m
     *   9: 5m
     *   10: 15m
     *   11+: 2 hours (Full Lockout)
     */
    fun getLockoutDelay(failedAttempts: Int): Long {
        return when (failedAttempts) {
            in 0..5 -> 0L
            6 -> DELAY_30S
            7 -> DELAY_1M
            8 -> DELAY_2M
            9 -> DELAY_5M
            10 -> DELAY_15M
            else -> LOCKOUT_2H
        }
    }

    /**
     * Records a failed PIN attempt. Increments count, calculates cooldown/lockout,
     * writes to local SQLCipher Room database, and pushes update to gateway.
     */
    suspend fun recordFailedAttempt(context: Context): LockoutState {
        val db = DatabaseModule.provideDatabase(context)
        val user = db.userDao().getUser() ?: return LockoutState(0, 0L, false)

        val newAttempts = user.pinFailedAttempts + 1
        val delay = getLockoutDelay(newAttempts)
        val lockoutUntil = if (delay > 0) System.currentTimeMillis() + delay else null
        val isFullLockout = newAttempts >= 11

        db.userDao().updateLockoutState(user.id, newAttempts, lockoutUntil)
        Log.d(TAG, "PIN failed attempts: $newAttempts. Lockout until: $lockoutUntil")

        // Sync lockout with backend gateway asynchronously
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AuthApiService.syncLockout(newAttempts, lockoutUntil)
                Log.d(TAG, "Successfully synced lockout state with backend gateway.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync lockout state with backend gateway: ${e.message}")
            }
        }

        return LockoutState(newAttempts, delay, isFullLockout)
    }

    /**
     * Resets failed PIN attempts and lockout timer locally and remote upon successful PIN.
     */
    suspend fun resetLockoutState(context: Context) {
        val db = DatabaseModule.provideDatabase(context)
        val user = db.userDao().getUser() ?: return

        db.userDao().resetLockout(user.id)
        Log.d(TAG, "PIN lockout state reset in local database.")

        // Sync reset with backend gateway
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AuthApiService.syncLockout(0, null)
                Log.d(TAG, "Successfully synced lockout reset with backend gateway.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync lockout reset with backend gateway: ${e.message}")
            }
        }
    }

    /**
     * Checks if the user is in the 2-hour lockout state (attempts >= 11).
     */
    suspend fun isLockedOut(context: Context): Boolean {
        val db = DatabaseModule.provideDatabase(context)
        val user = db.userDao().getUser() ?: return false
        if (user.pinFailedAttempts < 11) return false
        val lockoutUntil = user.pinLockoutUntil ?: return false
        return System.currentTimeMillis() < lockoutUntil
    }

    /**
     * Checks if progressive delay is active (attempts between 6 and 10).
     */
    suspend fun isDelayActive(context: Context): Boolean {
        val db = DatabaseModule.provideDatabase(context)
        val user = db.userDao().getUser() ?: return false
        if (user.pinFailedAttempts !in 6..10) return false
        val lockoutUntil = user.pinLockoutUntil ?: return false
        return System.currentTimeMillis() < lockoutUntil
    }

    /**
     * Gets remaining lockout or progressive delay cooldown time in milliseconds.
     */
    suspend fun getRemainingLockoutTime(context: Context): Long {
        val db = DatabaseModule.provideDatabase(context)
        val user = db.userDao().getUser() ?: return 0L
        val lockoutUntil = user.pinLockoutUntil ?: return 0L
        return maxOf(0L, lockoutUntil - System.currentTimeMillis())
    }

    data class LockoutState(
        val failedAttempts: Int,
        val delayMs: Long,
        val isFullLockout: Boolean
    )
}
