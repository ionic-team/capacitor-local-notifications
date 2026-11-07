package com.capacitorjs.plugins.localnotifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the decision the boot receiver makes for a one-shot whose `at` is in
 * the past: re-arm it only when it never fired.
 */
class NotificationRestorePolicyTest {

    private val window = NotificationRestorePolicy.MISSED_ONE_SHOT_WINDOW_MS
    private val now = 1_800_000_000_000L

    /** Epoch from before every `at` used here, i.e. records belong to this version. */
    private val epoch = now - 10 * window

    @Test
    fun `catches up a one-shot that never fired`() {
        val at = now - 60_000L

        assertTrue(NotificationRestorePolicy.shouldCatchUp(now, at, null, epoch))
    }

    @Test
    fun `does not re-arm a one-shot that fired`() {
        val at = now - 60_000L

        assertFalse(NotificationRestorePolicy.shouldCatchUp(now, at, at, epoch))
    }

    @Test
    fun `does not catch up a one-shot older than the window`() {
        val at = now - window - 1L

        assertFalse(NotificationRestorePolicy.shouldCatchUp(now, at, null, epoch))
    }

    @Test
    fun `catches up a one-shot exactly at the window edge`() {
        val at = now - window

        assertTrue(NotificationRestorePolicy.shouldCatchUp(now, at, null, epoch))
    }

    @Test
    fun `a fired record that was rescheduled keeps its new schedule eligible`() {
        val firedAt = now - 120_000L
        val at = now - 60_000L

        assertTrue(NotificationRestorePolicy.shouldCatchUp(now, at, firedAt, epoch))
    }

    @Test
    fun `honours a custom window`() {
        val at = now - 10_000L

        assertFalse(NotificationRestorePolicy.shouldCatchUp(now, at, null, epoch, windowMs = 5_000L))
    }

    @Test
    fun `does not replay records stored before firedAt existed`() {
        val at = now - 60_000L
        val installEpoch = now - 30_000L

        assertFalse(NotificationRestorePolicy.shouldCatchUp(now, at, null, installEpoch))
    }

    @Test
    fun `catches up a record scheduled after the epoch`() {
        val installEpoch = now - 120_000L
        val at = now - 60_000L

        assertTrue(NotificationRestorePolicy.shouldCatchUp(now, at, null, installEpoch))
    }
}
