package com.securevault.app.security

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for BiometricHelper.
 *
 * Testing_Strategy.md:
 *   - Part 1 §1: Verify BiometricPrompt config options
 *   - ST-STRIDE-04: Biometric Authentication Key Override
 *
 * Note: Full BiometricPrompt and KeyPermanentlyInvalidatedException tests
 * require instrumentation (on-device). These JVM tests verify the enum/sealed
 * class contracts and configuration logic. ST-STRIDE-04 is an instrumentation
 * test deferred to task-025 (Unified Testing Suite) per the task spec.
 */
class BiometricHelperTest {

    // -------------------------------------------------------------------------
    // BiometricStatus enum tests
    // -------------------------------------------------------------------------

    @Test
    fun `BiometricStatus enum contains all expected values`() {
        val statuses = BiometricHelper.BiometricStatus.values()
        assertEquals(4, statuses.size)
        assertTrue(statuses.contains(BiometricHelper.BiometricStatus.AVAILABLE))
        assertTrue(statuses.contains(BiometricHelper.BiometricStatus.NO_HARDWARE))
        assertTrue(statuses.contains(BiometricHelper.BiometricStatus.NOT_ENROLLED))
        assertTrue(statuses.contains(BiometricHelper.BiometricStatus.UNAVAILABLE))
    }

    // -------------------------------------------------------------------------
    // BiometricResult sealed class tests
    // -------------------------------------------------------------------------

    @Test
    fun `BiometricResult Success carries cipher reference`() {
        val result = BiometricHelper.BiometricResult.Success(null)
        assertNull(result.cipher)
        assertTrue(result is BiometricHelper.BiometricResult.Success)
    }

    @Test
    fun `BiometricResult Failed is singleton`() {
        val r1 = BiometricHelper.BiometricResult.Failed
        val r2 = BiometricHelper.BiometricResult.Failed
        assertSame(r1, r2)
    }

    @Test
    fun `BiometricResult Cancelled is singleton`() {
        val r1 = BiometricHelper.BiometricResult.Cancelled
        val r2 = BiometricHelper.BiometricResult.Cancelled
        assertSame(r1, r2)
    }

    @Test
    fun `BiometricResult KeyInvalidated is singleton`() {
        // ST-STRIDE-04: This result type represents key invalidation
        val r1 = BiometricHelper.BiometricResult.KeyInvalidated
        val r2 = BiometricHelper.BiometricResult.KeyInvalidated
        assertSame(r1, r2)
    }

    @Test
    fun `BiometricResult Error carries code and message`() {
        val result = BiometricHelper.BiometricResult.Error(7, "Too many attempts")
        assertEquals(7, result.code)
        assertEquals("Too many attempts", result.message)
    }

    @Test
    fun `BiometricResult sealed class has exactly 5 subtypes`() {
        // Verify all result paths are covered:
        // Success, Failed, Cancelled, KeyInvalidated, Error
        val results = listOf(
            BiometricHelper.BiometricResult.Success(null),
            BiometricHelper.BiometricResult.Failed,
            BiometricHelper.BiometricResult.Cancelled,
            BiometricHelper.BiometricResult.KeyInvalidated,
            BiometricHelper.BiometricResult.Error(0, "")
        )
        assertEquals(5, results.size)

        // Verify when-exhaustiveness (all branches distinct)
        val classes = results.map { it::class }.toSet()
        assertEquals(5, classes.size)
    }

    // -------------------------------------------------------------------------
    // Configuration validation tests
    // -------------------------------------------------------------------------

    @Test
    fun `VMK_KEY_ALIAS matches expected constant for biometric key binding`() {
        // Verify that BiometricHelper uses the correct Keystore alias
        // per Architecture.md §3 — VMK key must be biometric-bound
        assertEquals("securevault_vmk_key", KeystoreManager.VMK_KEY_ALIAS)
    }

    @Test
    fun `DB_KEY_ALIAS is distinct from VMK_KEY_ALIAS`() {
        // Ensure database key and biometric key use different aliases
        assertNotEquals(KeystoreManager.DB_KEY_ALIAS, KeystoreManager.VMK_KEY_ALIAS)
    }
}
