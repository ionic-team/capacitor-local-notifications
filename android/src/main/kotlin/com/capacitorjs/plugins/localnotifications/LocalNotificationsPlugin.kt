package com.capacitorjs.plugins.localnotifications

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
import android.service.notification.StatusBarNotification
import androidx.activity.result.ActivityResult
import com.getcapacitor.Bridge
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

@CapacitorPlugin(
    name = "LocalNotifications",
    permissions = [
        Permission(strings = [Manifest.permission.POST_NOTIFICATIONS], alias = LocalNotificationsPlugin.LOCAL_NOTIFICATIONS)
    ]
)
class LocalNotificationsPlugin : Plugin() {

    private lateinit var manager: LocalNotificationManager
    lateinit var notificationManager: NotificationManager
    private lateinit var notificationStorage: NotificationStorage
    private lateinit var notificationChannelManager: NotificationChannelManager

    override fun load() {
        super.load()
        notificationStorage = NotificationStorage(context)
        manager = LocalNotificationManager(notificationStorage, activity, context, bridge.config)
        manager.createNotificationChannel()
        notificationChannelManager = NotificationChannelManager(activity)
        notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        staticBridge = bridge
    }

    override fun handleOnNewIntent(data: Intent) {
        super.handleOnNewIntent(data)
        if (Intent.ACTION_MAIN != data.action) {
            return
        }
        val dataJson = manager.handleNotificationActionPerformed(data, notificationStorage)
        if (dataJson != null) {
            notifyListeners("localNotificationActionPerformed", dataJson, true)
        }
    }

    @PluginMethod
    fun schedule(call: PluginCall) {
        if (shouldRequestNotificationPermission()) {
            requestPermissionForAlias(LOCAL_NOTIFICATIONS, call, "scheduleAfterPermission")
        } else {
            doSchedule(call, false)
        }
    }

    /**
     * Update previously scheduled notifications, matched by id. Notifications
     * that are not currently scheduled are ignored.
     */
    @PluginMethod
    fun update(call: PluginCall) {
        if (shouldRequestNotificationPermission()) {
            requestPermissionForAlias(LOCAL_NOTIFICATIONS, call, "updateAfterPermission")
        } else {
            doSchedule(call, true)
        }
    }

    @PermissionCallback
    private fun scheduleAfterPermission(call: PluginCall) {
        doSchedule(call, false)
    }

    @PermissionCallback
    private fun updateAfterPermission(call: PluginCall) {
        doSchedule(call, true)
    }

    /**
     * Whether the notification permission must be requested before scheduling
     * (Android 13+ when not already granted). Preserves the legacy
     * implicit-request-on-schedule behavior.
     */
    private fun shouldRequestNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && getPermissionState(LOCAL_NOTIFICATIONS) != PermissionState.GRANTED

    private fun doSchedule(call: PluginCall, onlyExisting: Boolean) {
        // The exact-alarm prompt only applies to schedule (not update), and only
        // when the caller opts in with exactAlarm:true.
        val honorExact = !onlyExisting && (call.getBoolean("exactAlarm", false) ?: false)
        if (honorExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExactAlarms()) {
            startActivityForResult(
                call,
                Intent(ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + activity.packageName)),
                "exactAlarmScheduleCallback"
            )
            return
        }
        performScheduleNow(call, onlyExisting)
    }

    @ActivityCallback
    private fun exactAlarmScheduleCallback(call: PluginCall, result: ActivityResult) {
        // Returned from the "Alarms & reminders" settings screen; schedule now
        // (exact if granted, otherwise inexact with a warning).
        performScheduleNow(call, false)
    }

    private fun performScheduleNow(call: PluginCall, onlyExisting: Boolean) {
        val localNotifications = LocalNotification.buildNotificationList(call) ?: return
        if (onlyExisting) {
            val savedIds = notificationStorage.getSavedNotificationIds()
            localNotifications.removeAll { n ->
                val id = n.id
                id == null || !savedIds.contains(id.toString())
            }
        }
        val ids = manager.schedule(call, localNotifications)
        if (ids != null) {
            notificationStorage.appendNotifications(localNotifications)
            val result = JSObject()
            val jsArray = JSArray()
            for (i in 0 until ids.length()) {
                try {
                    jsArray.put(JSObject().put("id", ids.getInt(i)))
                } catch (ex: Exception) {
                }
            }
            result.put("notifications", jsArray)
            // exactAlarm was requested but still not permitted -> inexact fallback.
            val honorExact = !onlyExisting && (call.getBoolean("exactAlarm", false) ?: false)
            if (honorExact && !canScheduleExactAlarms()) {
                result.put("warning", LocalNotificationsError.SCHEDULED_INEXACT.toJson())
            }
            call.resolve(result)
        }
    }

    /**
     * Whether the app can schedule exact alarms. Always true below Android 12.
     */
    private fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        return alarmManager == null || alarmManager.canScheduleExactAlarms()
    }

    @PluginMethod
    fun cancel(call: PluginCall) {
        manager.cancel(call)
    }

    @PluginMethod
    fun cancelAll(call: PluginCall) {
        manager.cancelAll()
        call.resolve()
    }

    @PluginMethod
    fun getPending(call: PluginCall) {
        val notifications = notificationStorage.getSavedNotifications()
        val result = LocalNotification.buildLocalNotificationPendingList(notifications)
        call.resolve(result)
    }

    @PluginMethod
    fun registerActionTypes(call: PluginCall) {
        val types = call.getArray("types")
        val typesArray = NotificationAction.buildTypes(types)
        notificationStorage.writeActionGroup(typesArray)
        call.resolve()
    }

    @PluginMethod
    fun areEnabled(call: PluginCall) {
        val data = JSObject()
        data.put("value", manager.areNotificationsEnabled())
        call.resolve(data)
    }

    @PluginMethod
    fun getDeliveredNotifications(call: PluginCall) {
        val notifications = JSArray()
        for (notif in notificationManager.activeNotifications) {
            notifications.put(buildDeliveredNotificationJSObject(notif))
        }
        val result = JSObject()
        result.put("notifications", notifications)
        call.resolve(result)
    }

    private fun buildDeliveredNotificationJSObject(notif: StatusBarNotification): JSObject {
        val jsNotif = JSObject()
        jsNotif.put("id", notif.id)
        jsNotif.put("tag", notif.tag)

        val notification = notif.notification
        if (notification != null) {
            jsNotif.put("title", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
            jsNotif.put("body", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
            jsNotif.put("group", notification.group)
            jsNotif.put("groupSummary", 0 != (notification.flags and Notification.FLAG_GROUP_SUMMARY))

            val extras = JSObject()
            for (key in notification.extras.keySet()) {
                @Suppress("DEPRECATION")
                extras.put(key, notification.extras.get(key))
            }
            jsNotif.put("data", extras)
        }
        return jsNotif
    }

    /**
     * Get the notifications (scheduled and/or delivered) matching the ids.
     */
    @PluginMethod
    fun getByIds(call: PluginCall) {
        val idsArray = call.getArray("ids")
        if (idsArray == null) {
            LocalNotificationsError.MISSING_IDS.reject(call)
            return
        }
        val ids = parseIds(idsArray)
        if (ids == null) {
            LocalNotificationsError.MISSING_IDS.reject(call)
            return
        }

        val notifications = JSArray()
        try {
            val matched = ArrayList<LocalNotification>()
            for (n in notificationStorage.getSavedNotifications()) {
                val nid = n.id
                if (nid != null && ids.contains(nid)) {
                    matched.add(n)
                }
            }
            val scheduled = LocalNotification.buildLocalNotificationPendingList(matched)
            appendNotifications(notifications, scheduled.getJSONArray("notifications"))
        } catch (e: JSONException) {
            LocalNotificationsError.INVALID_NOTIFICATION_FORMAT.reject(call, e)
            return
        }

        for (notif in notificationManager.activeNotifications) {
            if (ids.contains(notif.id)) {
                notifications.put(buildDeliveredNotificationJSObject(notif))
            }
        }

        val result = JSObject()
        result.put("notifications", notifications)
        call.resolve(result)
    }

    /**
     * Get all notifications, optionally filtered by state
     * (SCHEDULED = pending, TRIGGERED = delivered).
     */
    @PluginMethod
    fun getAll(call: PluginCall) {
        val state = call.getString("state")
        val notifications = JSArray()

        try {
            if (state == null || "SCHEDULED" == state) {
                val scheduled = LocalNotification.buildLocalNotificationPendingList(notificationStorage.getSavedNotifications())
                appendNotifications(notifications, scheduled.getJSONArray("notifications"))
            }
            if (state == null || "TRIGGERED" == state) {
                for (notif in notificationManager.activeNotifications) {
                    notifications.put(buildDeliveredNotificationJSObject(notif))
                }
            }
        } catch (e: JSONException) {
            LocalNotificationsError.INVALID_NOTIFICATION_FORMAT.reject(call, e)
            return
        }

        val result = JSObject()
        result.put("notifications", notifications)
        call.resolve(result)
    }

    private fun parseIds(idsArray: JSArray): List<Int>? {
        val ids = ArrayList<Int>()
        try {
            for (o in idsArray.toList<Any>()) {
                if (o is Number) {
                    ids.add(o.toInt())
                }
            }
        } catch (e: JSONException) {
            return null
        }
        return ids
    }

    private fun appendNotifications(target: JSArray, source: JSONArray?) {
        if (source == null) {
            return
        }
        for (i in 0 until source.length()) {
            try {
                target.put(source.get(i))
            } catch (ignored: JSONException) {
            }
        }
    }

    @PluginMethod
    fun removeDeliveredNotifications(call: PluginCall) {
        val notifications = call.getArray("notifications")
        if (notifications == null) {
            LocalNotificationsError.INVALID_REMOVE_LIST.reject(call)
            return
        }

        try {
            for (o in notifications.toList<Any>()) {
                if (o is JSONObject) {
                    val notif = JSObject.fromJSONObject(o)
                    val tag = notif.getString("tag")
                    val id = notif.getInteger("id") ?: continue
                    if (tag == null) {
                        notificationManager.cancel(id)
                    } else {
                        notificationManager.cancel(tag, id)
                    }
                } else {
                    LocalNotificationsError.INVALID_REMOVE_LIST.reject(call)
                }
            }
        } catch (e: JSONException) {
            LocalNotificationsError.INVALID_REMOVE_LIST.reject(call, e)
        }

        call.resolve()
    }

    /**
     * Remove delivered notifications from the notification center by id.
     */
    @PluginMethod
    fun removeDeliveredNotificationsById(call: PluginCall) {
        val idsArray = call.getArray("ids")
        if (idsArray == null) {
            LocalNotificationsError.MISSING_IDS.reject(call)
            return
        }
        val ids = parseIds(idsArray)
        if (ids == null) {
            LocalNotificationsError.MISSING_IDS.reject(call)
            return
        }
        for (id in ids) {
            notificationManager.cancel(id)
        }
        call.resolve()
    }

    @PluginMethod
    fun removeAllDeliveredNotifications(call: PluginCall) {
        notificationManager.cancelAll()
        call.resolve()
    }

    @PluginMethod
    fun createChannel(call: PluginCall) {
        notificationChannelManager.createChannel(call)
    }

    @PluginMethod
    fun deleteChannel(call: PluginCall) {
        notificationChannelManager.deleteChannel(call)
    }

    @PluginMethod
    fun listChannels(call: PluginCall) {
        notificationChannelManager.listChannels(call)
    }

    @PluginMethod
    override fun checkPermissions(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val permissionsResultJSON = JSObject()
            permissionsResultJSON.put("display", getNotificationPermissionText())
            call.resolve(permissionsResultJSON)
        } else {
            super.checkPermissions(call)
        }
    }

    @PluginMethod
    override fun requestPermissions(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || getPermissionState(LOCAL_NOTIFICATIONS) == PermissionState.GRANTED) {
            val permissionsResultJSON = JSObject()
            permissionsResultJSON.put("display", getNotificationPermissionText())
            call.resolve(permissionsResultJSON)
        } else {
            requestPermissionForAlias(LOCAL_NOTIFICATIONS, call, "permissionsCallback")
        }
    }

    @PluginMethod
    fun changeExactNotificationSetting(call: PluginCall) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivityForResult(
                call,
                Intent(ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + activity.packageName)),
                "alarmPermissionsCallback"
            )
        } else {
            checkExactNotificationSetting(call)
        }
    }

    @PluginMethod
    fun checkExactNotificationSetting(call: PluginCall) {
        val permissionsResultJSON = JSObject()
        permissionsResultJSON.put("exact_alarm", getExactAlarmPermissionText())
        call.resolve(permissionsResultJSON)
    }

    @PermissionCallback
    private fun permissionsCallback(call: PluginCall) {
        val permissionsResultJSON = JSObject()
        permissionsResultJSON.put("display", getNotificationPermissionText())
        call.resolve(permissionsResultJSON)
    }

    @ActivityCallback
    private fun alarmPermissionsCallback(call: PluginCall, result: ActivityResult) {
        checkExactNotificationSetting(call)
    }

    private fun getNotificationPermissionText(): String =
        if (manager.areNotificationsEnabled()) "granted" else "denied"

    private fun getExactAlarmPermissionText(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return if (alarmManager.canScheduleExactAlarms()) "granted" else "denied"
        }
        return "granted"
    }

    /**
     * Instance-level dispatch so the companion's [fireReceived] can emit the
     * protected [notifyListeners] event.
     */
    internal fun dispatchReceived(notification: JSObject?) {
        notifyListeners("localNotificationReceived", notification, true)
    }

    companion object {
        const val LOCAL_NOTIFICATIONS = "display"

        private var staticBridge: Bridge? = null

        fun fireReceived(notification: JSObject?) {
            getLocalNotificationsInstance()?.dispatchReceived(notification)
        }

        fun getLocalNotificationsInstance(): LocalNotificationsPlugin? {
            val b = staticBridge
            if (b != null && b.webView != null) {
                val handle = b.getPlugin("LocalNotifications") ?: return null
                return handle.instance as? LocalNotificationsPlugin
            }
            return null
        }
    }
}
