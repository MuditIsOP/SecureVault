package com.securevault.app.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * Secure clipboard wrapper that enforces 30-second auto-clear.
 *
 * Spec refs:
 *   - PRD F-VAULT-04 AC#2 — "copy the decrypted plaintext password to clipboard"
 *   - PRD F-VAULT-04 AC#3 — "automatically clear clipboard contents exactly 30 seconds"
 *   - SRS FR-VAULT-04a — "client SHALL clear copied credentials after 30 seconds"
 *   - SRS FR-VAULT-04b — "clipboard clearance SHALL execute on a background service"
 *   - Security_Requirements.md §1 STRIDE Info Leak — [MUST] Clear clipboard after 30 seconds
 *   - Architecture.md — "Copy action → Write to clipboard → Background → 30s → Clear"
 *
 * Edge cases handled:
 *   - User copies a different item before 30s: current clip is compared before clearing
 *   - Multiple rapid copies: previous timer is cancelled, only latest runs
 *   - Service terminated by OS: Handler.postDelayed is best-effort; ClipboardClearService
 *     provides a secondary safety net via foreground service
 *
 * Thread safety: All operations execute on the main thread via Handler(Looper.getMainLooper()).
 */
object SecureClipboardManager {

    private const val TAG = "SecureClipboardManager"

    /** 30 seconds — PRD F-VAULT-04 AC#3, Security_Requirements.md §1 */
    private const val CLEAR_DELAY_MS = 30_000L

    /** Label used to identify our clips for comparison before clearing */
    private const val CLIP_LABEL_PASSWORD = "sv_password"
    private const val CLIP_LABEL_USERNAME = "sv_username"
    private const val CLIP_LABEL_WEBSITE = "sv_website"

    private val handler = Handler(Looper.getMainLooper())
    private var pendingClearRunnable: Runnable? = null

    /**
     * Copies a password to the clipboard with 30s auto-clear.
     * PRD F-VAULT-04 AC#2 — "copy decrypted plaintext password to clipboard"
     * PRD F-VAULT-04 AC#3 — "automatically clear after 30 seconds"
     *
     * @param context Application context for ClipboardManager and Toast
     * @param password Decrypted plaintext password to copy
     */
    fun copyPassword(context: Context, password: String) {
        copyToClipboard(context, CLIP_LABEL_PASSWORD, password, "Password copied. Clipboard clears in 30 seconds.")
    }

    /**
     * Copies a username/email to the clipboard with 30s auto-clear.
     * PRD F-VAULT-04 AC#1 — "Copy Username" button
     */
    fun copyUsername(context: Context, username: String) {
        copyToClipboard(context, CLIP_LABEL_USERNAME, username, "Username copied. Clipboard clears in 30 seconds.")
    }

    /**
     * Copies a website URL to the clipboard with 30s auto-clear.
     * PRD F-VAULT-04 AC#1 — "Copy Website" button
     */
    fun copyWebsite(context: Context, websiteUrl: String) {
        copyToClipboard(context, CLIP_LABEL_WEBSITE, websiteUrl, "Website URL copied. Clipboard clears in 30 seconds.")
    }

    /**
     * Core copy implementation with scheduled auto-clear.
     *
     * Architecture.md data flow:
     *   1. Write to clipboard
     *   2. Launch Background clear (Handler + ClipboardClearService)
     *   3. 30s timeout
     *   4. Clear clipboard (only if label still matches)
     */
    private fun copyToClipboard(context: Context, label: String, text: String, toastMessage: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        // Write to clipboard — F-VAULT-04 AC#2
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()

        // Cancel any pending clear — edge case: multiple rapid copies
        cancelPendingClear()

        // Schedule clear in 30 seconds — F-VAULT-04 AC#3
        val clearRunnable = Runnable {
            clearClipboardIfOurs(context, label)
        }
        pendingClearRunnable = clearRunnable
        handler.postDelayed(clearRunnable, CLEAR_DELAY_MS)

        // Also start ClipboardClearService as safety net — SRS FR-VAULT-04b
        try {
            val serviceIntent = android.content.Intent(context, com.securevault.app.service.ClipboardClearService::class.java)
            serviceIntent.putExtra(com.securevault.app.service.ClipboardClearService.EXTRA_CLIP_LABEL, label)
            context.startService(serviceIntent)
        } catch (e: Exception) {
            // Service may not start in background-restricted modes; Handler is primary mechanism
            Log.w(TAG, "ClipboardClearService could not start: ${e.message}")
        }

        Log.d(TAG, "Copied $label to clipboard. Clear scheduled in 30s.")
    }

    /**
     * Clears clipboard contents only if the current clip label still matches ours.
     * Edge case: User copies something else before 30s → we must NOT clear their new content.
     */
    private fun clearClipboardIfOurs(context: Context, expectedLabel: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val currentClip = clipboard.primaryClip

            if (currentClip != null && currentClip.description?.label == expectedLabel) {
                // Clear by replacing with empty clip
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                Log.d(TAG, "Clipboard cleared after 30s timeout (label: $expectedLabel)")
            } else {
                Log.d(TAG, "Clipboard NOT cleared — content changed by user (expected: $expectedLabel)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear clipboard: ${e.message}")
        }
    }

    /**
     * Cancels any pending clear timer.
     * Called when a new copy occurs to prevent clearing the new content.
     */
    fun cancelPendingClear() {
        pendingClearRunnable?.let {
            handler.removeCallbacks(it)
            pendingClearRunnable = null
        }
    }

    /**
     * Forces immediate clipboard clear. Used on app exit or lock.
     */
    fun clearNow(context: Context) {
        cancelPendingClear()
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            Log.d(TAG, "Clipboard force-cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to force-clear clipboard: ${e.message}")
        }
    }
}
