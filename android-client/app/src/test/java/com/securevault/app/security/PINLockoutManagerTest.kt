package com.securevault.app.security

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for PINLockoutManager progressive delay and lockout calculation logic.
 *
 * Spec refs:
 *   - Testing_Strategy.md Part 1 §2 — PINLockoutManager calculations check
 *   - PRD F-AUTH-04 AC#1, AC#2, AC#3 — delay bounds (1-5 immediate, 6-10 progressive, 11+ lockout)
 */
class PINLockoutManagerTest {

    @Test
    fun getLockoutDelay_attemptsOneToFive_returnsZero() {
        for (attempts in 1..5) {
            assertEquals(
                "Failed attempts $attempts must allow immediate reentry (0ms delay)",
                0L,
                PINLockoutManager.getLockoutDelay(attempts)
            )
        }
    }

    @Test
    fun getLockoutDelay_attemptSix_returnsThirtySeconds() {
        assertEquals(
            "6th failed attempt must return 30s delay",
            30_000L,
            PINLockoutManager.getLockoutDelay(6)
        )
    }

    @Test
    fun getLockoutDelay_attemptSeven_returnsOneMinute() {
        assertEquals(
            "7th failed attempt must return 1m delay",
            60_000L,
            PINLockoutManager.getLockoutDelay(7)
        )
    }

    @Test
    fun getLockoutDelay_attemptEight_returnsTwoMinutes() {
        assertEquals(
            "8th failed attempt must return 2m delay",
            120_000L,
            PINLockoutManager.getLockoutDelay(8)
        )
    }

    @Test
    fun getLockoutDelay_attemptNine_returnsFiveMinutes() {
        assertEquals(
            "9th failed attempt must return 5m delay",
            300_000L,
            PINLockoutManager.getLockoutDelay(9)
        )
    }

    @Test
    fun getLockoutDelay_attemptTen_returnsFifteenMinutes() {
        assertEquals(
            "10th failed attempt must return 15m delay",
            900_000L,
            PINLockoutManager.getLockoutDelay(10)
        )
    }

    @Test
    fun getLockoutDelay_attemptElevenOrMore_returnsTwoHours() {
        for (attempts in 11..15) {
            assertEquals(
                "Failed attempts $attempts must trigger 2-hour lockout",
                7_200_000L,
                PINLockoutManager.getLockoutDelay(attempts)
            )
        }
    }
}
