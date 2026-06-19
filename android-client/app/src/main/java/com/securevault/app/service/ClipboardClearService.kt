package com.securevault.app.service

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Background service that clears clipboard after 30 seconds.
 *
 * Spec refs:
 *   - SRS FR-VAULT-04b — "clipboard clearance SHALL execute on a background service"
 *   - Security_Requirements.md §1 STRIDE Info Leak — [MUST] Clear clipboard after 30 seconds
 *   - Architecture.md — "Launch Background Service → 30s timeout → Clear clipboard"
 *   - PRD F-VAULT-04 AC#3 — "automatically clear clipboard contents exactly 30 seconds"
 *
 * This service acts as a secondary safety net alongside SecureClipboardManager's
 * Handler.postDelayed. It survives activity destruction and ensures clipboard
 * is cleared even if the user navigates away from the app.
 *
 * Edge cases:
 *   - OS kills service: START_NOT_STICKY prevents restart (clipboard may persist,
 *     but SecureClipboardManager handler is the primary mechanism)
 *   - Battery optimization: Service runs for exactly 30s then self-stops
 *   - Multiple rapid copies: Each startService call resets the timer
 */
class ClipboardClearService : Service() {

    companion object {
        private const val TAG = "ClipboardClearService"
        const val EXTRA_CLIP_LABEL = "extra_clip_label"

        /** 30 seconds — PRD F-VAULT-04 AC#3 */
        private const val CLEAR_DELAY_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var clearRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val clipLabel = intent?.getStringExtra(EXTRA_CLIP_LABEL) ?: "sv_password"

        Log.d(TAG, "Service started. Will clear clipboard (label: $clipLabel) in 30s.")

        // Cancel any existing timer — edge case: multiple rapid copies
        clearRunnable?.let { handler.removeCallbacks(it) }

        clearRunnable = Runnable {
            clearClipboardIfOurs(clipLabel)
            stopSelf(startId)
        }

        handler.postDelayed(clearRunnable!!, CLEAR_DELAY_MS)

        // START_NOT_STICKY — do NOT restart if killed; Handler in SecureClipboardManager
        // is the primary mechanism
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        clearRunnable?.let { handler.removeCallbacks(it) }
        Log.d(TAG, "Service destroyed.")
    }

    /**
     * Clears clipboard only if current clip label matches what we copied.
     * Edge case (task-013): User copies something else before 30s → skip clear.
     */
    private fun clearClipboardIfOurs(expectedLabel: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val currentClip = clipboard.primaryClip

            if (currentClip != null && currentClip.description?.label == expectedLabel) {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                Log.d(TAG, "Clipboard cleared by service after 30s (label: $expectedLabel)")
            } else {
                Log.d(TAG, "Clipboard NOT cleared — content changed by user")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear clipboard in service: ${e.message}")
        }
    }
}
