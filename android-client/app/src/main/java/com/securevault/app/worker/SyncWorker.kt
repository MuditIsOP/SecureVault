package com.securevault.app.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.securevault.app.data.DatabaseModule
import com.securevault.app.data.entities.SyncQueueEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * WorkManager background sync worker.
 *
 * Spec refs:
 *   - PRD F-SYNC-01 AC#3 — "App schedules a WorkManager background sync task
 *     configured with NetworkType.CONNECTED constraints"
 *   - PRD F-SYNC-01 AC#4 — "Upon connection, the task pushes pending queue
 *     transactions to the MongoDB Atlas gateway API and pulls remote updates"
 *   - PRD F-SYNC-01 AC#5 — "Upon success, processed queue rows are deleted.
 *     Synchronization must execute in <5 seconds"
 *   - SRS FR-SYNC-01b — "client SHALL schedule background sync actions using
 *     Android WorkManager"
 *   - Architecture.md — "Queue transactions → Network returns → WorkManager fires
 *     → POST /v1/sync → Clear queue"
 *   - Security_Requirements.md — CONFIDENTIAL sync queues
 *
 * Technical considerations from task-021:
 *   - Must pin WorkManager version to 2.9.0
 *   - Retry with exponential backoff on timeout
 *   - Conflict resolution rejects handled gracefully
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SyncWorker"

        /** Unique work name for WorkManager — ensures single instance */
        const val UNIQUE_WORK_NAME = "securevault_sync_worker"

        /** Sync interval in minutes — periodic background sync */
        private const val SYNC_INTERVAL_MINUTES = 15L

        /** Maximum retry attempts before marking entry as failed */
        private const val MAX_RETRY_ATTEMPTS = 3

        // TODO: Replace with actual backend URL from BuildConfig
        private const val SYNC_API_URL = "https://securevault-backend-tau.vercel.app/v1/sync"

        /**
         * Schedules the periodic sync worker with network constraints.
         *
         * PRD F-SYNC-01 AC#3 — "WorkManager background sync task configured
         * with NetworkType.CONNECTED constraints"
         * SRS FR-SYNC-01b — "schedule background sync actions using WorkManager"
         *
         * @param context Application context
         */
        fun schedule(context: Context) {
            // Constraints — PRD F-SYNC-01 AC#3: NetworkType.CONNECTED
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // PeriodicWorkRequest with exponential backoff
            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS  // Edge case: exponential backoff on failure
                )
                .build()

            // Enqueue unique periodic work — replaces existing if already scheduled
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )

            Log.d(TAG, "Sync worker scheduled: every ${SYNC_INTERVAL_MINUTES}min, " +
                    "requires CONNECTED network")
        }

        /**
         * Cancels the periodic sync worker.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            Log.d(TAG, "Sync worker cancelled")
        }
    }

    // -------------------------------------------------------------------------
    // doWork — PRD F-SYNC-01 AC#4, AC#5
    // Architecture.md: "WorkManager fires → POST /v1/sync → Clear queue"
    // -------------------------------------------------------------------------

    override suspend fun doWork(): Result {
        Log.d(TAG, "Sync worker started")
        val startTime = System.currentTimeMillis()

        return try {
            withContext(Dispatchers.IO) {
                val db = DatabaseModule.provideDatabase(applicationContext)
                val syncDao = db.syncQueueDao()

                // Get current user ID
                val userId = getUserId(db) ?: return@withContext Result.success()

                // Fetch pending entries — PRD F-SYNC-01 AC#4
                val pendingEntries = syncDao.getPendingEntries(userId)

                // Also retry failed entries
                syncDao.resetFailedToPending(userId)
                val retriedEntries = syncDao.getFailedEntries(userId)
                val allEntries = pendingEntries + retriedEntries

                if (allEntries.isEmpty()) {
                    Log.d(TAG, "No pending sync entries, skipping")
                    return@withContext Result.success()
                }

                Log.d(TAG, "Processing ${allEntries.size} sync entries")

                // Push pending transactions — PRD F-SYNC-01 AC#4
                val successIds = mutableListOf<String>()
                val failedIds = mutableListOf<String>()

                try {
                    val syncResult = pushToGateway(allEntries, userId)

                    if (syncResult) {
                        // All entries synced successfully
                        successIds.addAll(allEntries.map { it.id })
                    } else {
                        // Mark all as failed for retry
                        failedIds.addAll(allEntries.map { it.id })
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Sync push failed: ${e.message}")
                    failedIds.addAll(allEntries.map { it.id })
                }

                // Delete successful entries — PRD F-SYNC-01 AC#5
                if (successIds.isNotEmpty()) {
                    syncDao.deleteByIds(successIds)
                    Log.d(TAG, "Deleted ${successIds.size} synced entries")
                }

                // Mark failed entries
                for (id in failedIds) {
                    syncDao.markFailed(id)
                }

                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "Sync completed in ${elapsed}ms " +
                        "(success: ${successIds.size}, failed: ${failedIds.size})")

                // PRD F-SYNC-01 AC#5 — "must execute in <5 seconds"
                if (elapsed > 5000) {
                    Log.w(TAG, "Sync exceeded 5-second target: ${elapsed}ms")
                }

                if (failedIds.isNotEmpty()) {
                    Result.retry() // Edge case: retry with exponential backoff
                } else {
                    Result.success()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync worker error: ${e.message}")
            Result.retry()
        }
    }

    // -------------------------------------------------------------------------
    // Gateway push — Architecture.md: "POST /v1/sync"
    // -------------------------------------------------------------------------

    /**
     * Pushes pending sync entries to the backend gateway API.
     *
     * PRD F-SYNC-01 AC#4 — "pushes pending queue transactions to the
     * MongoDB Atlas gateway API"
     * Architecture.md — "POST /v1/sync"
     *
     * @param entries List of sync queue entries to push
     * @param userId Current user ID
     * @return true if sync was successful, false otherwise
     */
    private fun pushToGateway(
        entries: List<SyncQueueEntity>,
        userId: String
    ): Boolean {
        // Build sync payload
        val transactionsArray = JSONArray()
        for (entry in entries) {
            val txObj = JSONObject().apply {
                put("id", entry.id)
                put("transactionType", entry.transactionType)
                put("tableName", entry.tableName)
                put("recordId", entry.recordId)
                put("payloadJson", entry.payloadJson ?: JSONObject.NULL)
                put("createdAt", entry.createdAt)
            }
            transactionsArray.put(txObj)
        }

        val payload = JSONObject().apply {
            put("userId", userId)
            put("transactions", transactionsArray)
            put("deviceTimestamp", System.currentTimeMillis())
        }

        // POST to sync endpoint
        val url = URL(SYNC_API_URL)
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                // TODO: Add Firebase Auth token header
                // setRequestProperty("Authorization", "Bearer $token")
                doOutput = true
                connectTimeout = 5000  // PRD F-SYNC-01 AC#5: <5 seconds
                readTimeout = 5000
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "Sync API response: $responseCode")

            responseCode in 200..299

        } catch (e: Exception) {
            Log.e(TAG, "Sync API error: ${e.message}")
            false
        } finally {
            connection.disconnect()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun getUserId(db: com.securevault.app.data.AppDatabase): String? {
        val cursor = db.openHelper.readableDatabase
            .query("SELECT id FROM users LIMIT 1")
        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }
}
