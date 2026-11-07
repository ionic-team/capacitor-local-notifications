package com.capacitorjs.plugins.localnotifications

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.getcapacitor.JSObject
import java.text.ParseException
import org.json.JSONException

/**
 * Abstracts storage for notification data (and action-type groups).
 */
class NotificationStorage(private val context: Context) {

    /**
     * Direct boot: a schedule that comes due before the user unlocks the device
     * must still be readable. Device-protected storage is app-private but not
     * credential-encrypted, so the boot receiver can read it while the device is
     * still locked.
     */
    private val storageContext: Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext() ?: context
        } else {
            context
        }

    fun appendNotifications(localNotifications: List<LocalNotification>) {
        val editor = getStorage(NOTIFICATION_STORE_ID).edit()
        for (request in localNotifications) {
            if (request.isScheduled()) {
                val id = request.id ?: continue
                editor.putString(id.toString(), request.source)
            }
        }
        editor.apply()
    }

    fun getSavedNotificationIds(): List<String> {
        val all = getStorage(NOTIFICATION_STORE_ID).all
        return if (all != null) ArrayList(all.keys) else ArrayList()
    }

    fun getSavedNotifications(): List<LocalNotification> {
        val all = getStorage(NOTIFICATION_STORE_ID).all ?: return ArrayList()
        val notifications = ArrayList<LocalNotification>()
        for (key in all.keys) {
            val notificationString = all[key] as? String
            val jsNotification = getNotificationFromJSONString(notificationString)
            if (jsNotification != null) {
                try {
                    notifications.add(LocalNotification.buildNotificationFromJSObject(jsNotification))
                } catch (ex: ParseException) {
                }
            }
        }
        return notifications
    }

    fun getNotificationFromJSONString(notificationString: String?): JSObject? {
        if (notificationString == null) return null
        return try {
            JSObject(notificationString)
        } catch (ex: JSONException) {
            null
        }
    }

    fun getSavedNotificationAsJSObject(key: String): JSObject? {
        val notificationString = try {
            getStorage(NOTIFICATION_STORE_ID).getString(key, null)
        } catch (ex: ClassCastException) {
            return null
        } ?: return null
        return try {
            JSObject(notificationString)
        } catch (ex: JSONException) {
            null
        }
    }

    fun getSavedNotification(key: String): LocalNotification? {
        val jsNotification = getSavedNotificationAsJSObject(key) ?: return null
        return try {
            LocalNotification.buildNotificationFromJSObject(jsNotification)
        } catch (ex: ParseException) {
            null
        }
    }

    fun deleteNotification(id: String) {
        val editor = getStorage(NOTIFICATION_STORE_ID).edit()
        editor.remove(id)
        editor.apply()
    }

    /**
     * Mark a stored notification cancelled in place, without disturbing any of
     * its other persisted fields. Used when cancel()/cancelAll() preserves a
     * still-delivered record instead of deleting it, so later classification
     * and reboot-restore can tell it's no longer actually scheduled.
     */
    fun setCancelled(id: String, cancelled: Boolean) {
        val json = getSavedNotificationAsJSObject(id) ?: return
        json.put("cancelled", cancelled)
        val editor = getStorage(NOTIFICATION_STORE_ID).edit()
        editor.putString(id, json.toString())
        editor.apply()
    }

    /**
     * Moves stores written before direct boot support from credential-encrypted
     * to device-protected storage. Only callable while the user is unlocked — the
     * source is unreadable before that — so callers run it from the plugin's
     * load() and from BOOT_COMPLETED, never from LOCKED_BOOT_COMPLETED. The
     * marker lives in device-protected storage, so a second run cannot overwrite
     * records written after the first one.
     */
    fun migrateToDeviceProtectedStorage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val target = context.createDeviceProtectedStorageContext() ?: return
        if (target === context) return
        val meta = target.getSharedPreferences(META_STORE_ID, Context.MODE_PRIVATE)
        if (meta.getBoolean(MIGRATED_KEY, false)) return
        target.moveSharedPreferencesFrom(context, NOTIFICATION_STORE_ID)
        for (notification in getSavedNotifications()) {
            val actionTypeId = notification.actionTypeId ?: continue
            target.moveSharedPreferencesFrom(context, ACTION_TYPES_ID + actionTypeId)
        }
        meta.edit().putBoolean(MIGRATED_KEY, true).apply()
    }

    private fun getStorage(key: String): SharedPreferences =
        storageContext.getSharedPreferences(key, Context.MODE_PRIVATE)

    /**
     * Writes action types (actions shown in a notification) to storage,
     * overriding previous data.
     */
    fun writeActionGroup(typesMap: Map<String, Array<NotificationAction>>?) {
        if (typesMap == null) return
        for (id in typesMap.keys) {
            val editor = getStorage(ACTION_TYPES_ID + id).edit()
            editor.clear()
            val notificationActions = typesMap[id] ?: continue
            editor.putInt("count", notificationActions.size)
            for (i in notificationActions.indices) {
                editor.putString("id$i", notificationActions[i].id)
                editor.putString("title$i", notificationActions[i].title)
                editor.putBoolean("input$i", notificationActions[i].isInput())
            }
            editor.apply()
        }
    }

    /**
     * Retrieve the array of notification actions for an action-type id.
     */
    fun getActionGroup(forId: String): Array<NotificationAction> {
        val storage = getStorage(ACTION_TYPES_ID + forId)
        val count = storage.getInt("count", 0)
        return Array(count) { i ->
            val id = storage.getString("id$i", "")
            val title = storage.getString("title$i", "")
            val input = storage.getBoolean("input$i", false)
            NotificationAction(id, title, input)
        }
    }

    companion object {
        private const val NOTIFICATION_STORE_ID = "NOTIFICATION_STORE"
        private const val ACTION_TYPES_ID = "ACTION_TYPE_STORE"
        private const val META_STORE_ID = "LOCAL_NOTIFICATIONS_META"
        private const val MIGRATED_KEY = "deviceProtectedMigrationDone"
    }
}
