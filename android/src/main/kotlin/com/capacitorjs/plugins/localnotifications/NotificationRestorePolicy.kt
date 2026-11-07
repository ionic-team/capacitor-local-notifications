package com.capacitorjs.plugins.localnotifications

/**
 * Decides what the boot receiver does with a one-shot notification whose `at`
 * lies in the past.
 *
 * A one-shot can be in the past for two very different reasons:
 *
 *  - it fired normally, and its record is kept around afterwards so
 *    `getByIds()`/`getAll()` can still describe it, or
 *  - it came due while the device was powered off, so the alarm never fired.
 *
 * Only the second case must be re-armed on boot. `TimedNotificationPublisher`
 * records the fire in the stored notification (`firedAt`, the `at` value it was
 * fired for), which is the only reliable way to tell the two apart: `at` alone
 * cannot, because both land before "now".
 *
 * Records stored before `firedAt` existed carry no marker at all. Those are
 * treated as fired — the epoch marks the first run of this code, and anything
 * scheduled before it cannot be classified. Without that bound, the first boot
 * after an upgrade would replay every notification the user ever received.
 *
 * The window additionally keeps a long-powered-off device from delivering
 * notifications that are days overdue, which matches how a system alarm
 * behaves.
 */
internal object NotificationRestorePolicy {

    /** How late a one-shot may be and still count as missed during a reboot. */
    const val MISSED_ONE_SHOT_WINDOW_MS = 24 * 60 * 60 * 1000L

    /** Delay used when a missed notification is re-armed (existing behaviour). */
    const val CATCH_UP_DELAY_MS = 15 * 1000L

    /**
     * Whether a past one-shot without a live alarm should be re-armed.
     *
     * @param now current wall clock
     * @param atMillis the notification's scheduled time
     * @param firedAtMillis the `at` value the notification was actually fired
     *   for, or null when it never fired (or was stored before this field existed)
     * @param firedAtEpoch first run of the version that records `firedAt`;
     *   records scheduled before it are not classified as missed
     */
    fun shouldCatchUp(
        now: Long,
        atMillis: Long,
        firedAtMillis: Long?,
        firedAtEpoch: Long,
        windowMs: Long = MISSED_ONE_SHOT_WINDOW_MS
    ): Boolean {
        if (firedAtMillis == atMillis) return false
        if (atMillis < firedAtEpoch) return false
        return atMillis >= now - windowMs
    }
}
