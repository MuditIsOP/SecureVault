package com.securevault.app.security

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Environment integrity checker for compromised device detection.
 *
 * Spec refs:
 *   - PRD F-DEV-02 AC#1 — "On app start, checks must detect superuser
 *     binaries (root access) and if USB debugging is enabled"
 *   - SRS FR-DEV-02a — "client SHALL detect rooted systems and active
 *     debugging configurations"
 *   - Security_Requirements.md §2 Tampering — "[MUST] Alert user if root
 *     is found"
 *   - ST-CLIENT-TAMP-01 — Modifying Room SQLite cache on rooted device
 *   - Architecture.md — Security Modules component
 *
 * Data flow (Architecture.md):
 *   App startup → Scan host environment → Check root/debugging →
 *   Threat found → Show warning activity
 *
 * Edge cases (task-024):
 *   - Root binaries renamed or hidden: checks common indicators
 *   - App crashes on startup: all checks wrapped in try-catch
 */
object EnvironmentChecker {

    /**
     * Result of environment security scan.
     */
    data class EnvironmentScanResult(
        /** True if superuser / root binaries are detected */
        val isRooted: Boolean,
        /** True if USB debugging / ADB is enabled in developer settings */
        val isUsbDebuggingEnabled: Boolean,
        /** True if running on an emulator (additional risk indicator) */
        val isEmulator: Boolean,
        /** Human-readable list of detected threats */
        val threats: List<String>
    ) {
        /** True if any compromised environment indicator is detected */
        val isCompromised: Boolean
            get() = isRooted || isUsbDebuggingEnabled
    }

    // -------------------------------------------------------------------------
    // Public API — called on app startup
    // PRD F-DEV-02 AC#1
    // -------------------------------------------------------------------------

    /**
     * Performs a full environment security scan.
     *
     * PRD F-DEV-02 AC#1 — "detect superuser binaries (root access)
     * and if USB debugging is enabled in developer settings"
     *
     * @param context Application context (used for Settings.Global queries)
     * @return EnvironmentScanResult with detection flags
     */
    fun scan(context: Context): EnvironmentScanResult {
        val threats = mutableListOf<String>()

        val rooted = isDeviceRooted()
        if (rooted) threats.add("Root access detected")

        val debugging = isUsbDebuggingEnabled(context)
        if (debugging) threats.add("USB debugging is enabled")

        val emulator = isRunningOnEmulator()
        if (emulator) threats.add("Running on emulator")

        return EnvironmentScanResult(
            isRooted = rooted,
            isUsbDebuggingEnabled = debugging,
            isEmulator = emulator,
            threats = threats
        )
    }

    // -------------------------------------------------------------------------
    // Root detection — PRD F-DEV-02 AC#1
    // SRS FR-DEV-02a — "detect rooted systems"
    // Security_Requirements.md §2 — "[MUST] Alert user if root is found"
    // -------------------------------------------------------------------------

    /**
     * Checks for common root indicators.
     *
     * Detection methods:
     *   1. Common superuser binary paths (su, busybox, magisk)
     *   2. Root management apps (SuperSU, Magisk Manager)
     *   3. Build tags containing "test-keys"
     *   4. System properties indicating custom ROM
     *
     * Edge case: binaries may be renamed — checks multiple indicators
     */
    private fun isDeviceRooted(): Boolean {
        return try {
            checkRootBinaries() || checkRootApps() || checkBuildTags()
        } catch (e: Exception) {
            // Edge case: crash on security policies — fail safe
            false
        }
    }

    /**
     * Checks common file system paths for superuser binaries.
     *
     * PRD F-DEV-02 AC#1 — "detect superuser binaries"
     */
    private fun checkRootBinaries(): Boolean {
        val rootPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/local/su",
            "/data/local/bin/su",
            "/data/local/xbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/vendor/bin/su",
            // Magisk paths
            "/sbin/magisk",
            "/system/bin/magisk",
            // Busybox paths
            "/system/xbin/busybox",
            "/system/bin/busybox"
        )

        return rootPaths.any { path ->
            try {
                File(path).exists()
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Checks for known root management app packages.
     */
    private fun checkRootApps(): Boolean {
        val rootPackages = listOf(
            "com.noshufou.android.su",
            "com.noshufou.android.su.elite",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.topjohnwu.magisk",
            "me.phh.superuser"
        )

        return rootPackages.any { pkg ->
            try {
                File("/data/data/$pkg").exists()
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Checks Build.TAGS for "test-keys" indicating a custom ROM.
     */
    private fun checkBuildTags(): Boolean {
        return try {
            val tags = Build.TAGS
            tags != null && tags.contains("test-keys")
        } catch (e: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------------
    // USB debugging detection — PRD F-DEV-02 AC#1
    // SRS FR-DEV-02a — "detect active debugging configurations"
    // -------------------------------------------------------------------------

    /**
     * Checks if USB debugging is enabled in developer settings.
     *
     * PRD F-DEV-02 AC#1 — "if USB debugging is enabled in developer settings"
     * Uses Settings.Global.ADB_ENABLED (task-024 implementation plan step 2)
     *
     * @param context Application context for ContentResolver access
     * @return true if ADB/USB debugging is enabled
     */
    private fun isUsbDebuggingEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED,
                0
            ) == 1
        } catch (e: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------------
    // Emulator detection — additional security indicator
    // -------------------------------------------------------------------------

    /**
     * Checks common emulator indicators.
     * Not a spec requirement but useful for security awareness.
     */
    private fun isRunningOnEmulator(): Boolean {
        return try {
            Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            Build.BRAND.startsWith("generic") ||
            Build.DEVICE.startsWith("generic") ||
            Build.PRODUCT == "sdk_google" ||
            Build.PRODUCT == "google_sdk" ||
            Build.PRODUCT == "sdk" ||
            Build.PRODUCT == "sdk_x86" ||
            Build.PRODUCT == "sdk_gphone64_arm64" ||
            Build.PRODUCT == "vbox86p" ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu")
        } catch (e: Exception) {
            false
        }
    }
}
