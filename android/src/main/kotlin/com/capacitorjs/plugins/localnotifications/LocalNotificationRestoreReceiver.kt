package com.capacitorjs.plugins.localnotifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.getcapacitor.CapConfig
import java.util.Date

class LocalNotificationRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Runs on LOCKED_BOOT_COMPLETED as well: the user may still be locked, and
        // those are exactly the schedules that must not be lost. Reading the stores
        // before unlock requires device-protected storage (see NotificationStorage);
        // moving them does not, so the migration only runs on BOOT_COMPLETED.
        val storage = NotificationStorage(context)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            storage.migrateToDeviceProtectedStorage()
        }
        val ids = storage.getSavedNotificationIds()

        val notifications = ArrayList<LocalNotification>(ids.size)
        val updatedNotifications = ArrayList<LocalNotification>()
        for (id in ids) {
            val notification = storage.getSavedNotification(id) ?: continue

            val schedule = notification.schedule
            if (schedule != null) {
                if (schedule.isPerpetual() && notification.cancelled) {
                    // Cancelled before reboot while its current instance was still
                    // visible (preserved for TRIGGERED display) — the shade is wiped
                    // on boot, so there's nothing left to preserve it for, and
                    // rescheduling would resurrect an alarm the user explicitly
                    // cancelled.
                    storage.deleteNotification(id)
                    continue
                }
                // Fired one-shot: kept in storage only for TRIGGERED queries — never
                // re-arm it on boot, or it re-fires after every reboot. Same guard the
                // migrator's reconcileOwnStorage already uses.
                if (!schedule.isPerpetual() && notification.isTriggered()) {
                    if (notification.cancelled) storage.deleteNotification(id)
                    continue
                }
                val at = schedule.at
                if (at != null && at.before(Date())) {
                    // Show notifications that would have been delivered while the device was off.
                    val newDateTime = Date().time + 15 * 1000
                    schedule.at = Date(newDateTime)
                    notification.schedule = schedule
                    updatedNotifications.add(notification)
                }
            }

            notifications.add(notification)
        }

        if (updatedNotifications.isNotEmpty()) {
            storage.appendNotifications(updatedNotifications)
        }

        val config = CapConfig.loadDefault(context)
        val localNotificationManager = LocalNotificationManager(storage, null, context, config)
        // Before the first unlock the plugin has never run, so the default channel
        // may not exist yet; on Android 8+ a notification without its channel is
        // dropped. Creating it is idempotent.
        localNotificationManager.createNotificationChannel()
        localNotificationManager.schedule(null, notifications)
    }
}
