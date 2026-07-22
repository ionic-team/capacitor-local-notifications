package com.capacitorjs.plugins.localnotifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.getcapacitor.CapConfig
import java.util.Date

class LocalNotificationRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val um = context.getSystemService(UserManager::class.java)
        if (um == null || !um.isUserUnlocked) return

        val storage = NotificationStorage(context)
        val ids = storage.getSavedNotificationIds()

        val notifications = ArrayList<LocalNotification>(ids.size)
        val updatedNotifications = ArrayList<LocalNotification>()
        for (id in ids) {
            val notification = storage.getSavedNotification(id) ?: continue

            val schedule = notification.schedule
            if (schedule != null) {
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
        localNotificationManager.schedule(null, notifications)
    }
}
