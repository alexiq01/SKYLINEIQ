package com.example.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.analytics.AnalyticsManager
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.Locale

data class FcmTopicPreferences(
    val generalEnabled: Boolean = true,
    val offersEnabled: Boolean = true,
    val maintenanceEnabled: Boolean = true,
    val topicsSynced: Boolean = false
)

/**
 * Manages Firebase Cloud Messaging (FCM) topic subscriptions, cloud notification channel,
 * dual delivery (Android system notification tray + in-app Notifications Inbox),
 * and background notification-tap routing.
 */
object FcmNotificationManager {

    const val CHANNEL_ID = "skyline_cloud_notifications"
    const val CHANNEL_NAME = "إشعارات Skyline Users السحابية"

    const val TOPIC_ALL = "skyline_all"
    const val TOPIC_OFFERS = "skyline_offers"
    const val TOPIC_MAINTENANCE = "skyline_maintenance"

    const val TYPE_FCM_GENERAL = "fcm_general"
    const val TYPE_FCM_OFFER = "fcm_offer"
    const val TYPE_FCM_MAINTENANCE = "fcm_maintenance"

    const val EXTRA_OPEN_NOTIFICATIONS_TAB = "extra_open_notifications_tab"
    const val EXTRA_FCM_ALREADY_RECORDED = "extra_fcm_already_recorded"
    private const val EXTRA_FCM_INTENT_CONSUMED = "extra_fcm_intent_consumed"

    private const val PREFS_NAME = "skyline_fcm_prefs"
    private const val KEY_OFFERS_ENABLED = "fcm_offers_enabled"
    private const val KEY_MAINTENANCE_ENABLED = "fcm_maintenance_enabled"
    private const val KEY_RECORDED_IDS = "fcm_recorded_message_ids"
    private const val MAX_RECORDED_IDS = 100

    private val _preferencesFlow = MutableStateFlow(FcmTopicPreferences())
    val preferencesFlow: StateFlow<FcmTopicPreferences> = _preferencesFlow.asStateFlow()

    private val _openNotificationsTabFlow = MutableStateFlow(false)
    val openNotificationsTabFlow: StateFlow<Boolean> = _openNotificationsTabFlow.asStateFlow()

    @Volatile
    private var prefsLoaded = false

    fun createCloudNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "إشعارات Firebase الفورية للأخبار والعروض وأعمال الصيانة"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    fun getPreferences(context: Context): FcmTopicPreferences {
        if (!prefsLoaded) {
            synchronized(this) {
                if (!prefsLoaded) {
                    val prefs = getPrefs(context)
                    _preferencesFlow.value = FcmTopicPreferences(
                        generalEnabled = true,
                        offersEnabled = prefs.getBoolean(KEY_OFFERS_ENABLED, true),
                        maintenanceEnabled = prefs.getBoolean(KEY_MAINTENANCE_ENABLED, true),
                        topicsSynced = _preferencesFlow.value.topicsSynced
                    )
                    prefsLoaded = true
                }
            }
        }
        return _preferencesFlow.value
    }

    fun initAndSyncTopics(context: Context) {
        val appContext = context.applicationContext ?: context
        createCloudNotificationChannel(appContext)
        NotificationInboxManager.init(appContext)
        getPreferences(appContext)
        syncTopicSubscriptions(appContext)
    }

    fun syncTopicSubscriptions(context: Context) {
        val appContext = context.applicationContext ?: context
        val current = getPreferences(appContext)
        try {
            if (FirebaseApp.getApps(appContext).isEmpty()) {
                FirebaseApp.initializeApp(appContext)
            }
            val messaging = FirebaseMessaging.getInstance()
            messaging.subscribeToTopic(TOPIC_ALL)
            if (current.offersEnabled) {
                messaging.subscribeToTopic(TOPIC_OFFERS)
            } else {
                messaging.unsubscribeFromTopic(TOPIC_OFFERS)
            }
            if (current.maintenanceEnabled) {
                messaging.subscribeToTopic(TOPIC_MAINTENANCE)
            } else {
                messaging.unsubscribeFromTopic(TOPIC_MAINTENANCE)
            }
            _preferencesFlow.value = current.copy(topicsSynced = true)
        } catch (_: Throwable) {
            // Safe fallback when Play Services or FirebaseApp is unavailable (e.g. unit tests)
        }
    }

    fun setOffersEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext ?: context
        val current = getPreferences(appContext)
        getPrefs(appContext).edit().putBoolean(KEY_OFFERS_ENABLED, enabled).commit()
        _preferencesFlow.value = current.copy(offersEnabled = enabled)
        AnalyticsManager.logCloudTopicPreferenceChanged(TOPIC_OFFERS, enabled)
        try {
            val messaging = FirebaseMessaging.getInstance()
            if (enabled) {
                messaging.subscribeToTopic(TOPIC_OFFERS)
            } else {
                messaging.unsubscribeFromTopic(TOPIC_OFFERS)
            }
        } catch (_: Throwable) {}
    }

    fun setMaintenanceEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext ?: context
        val current = getPreferences(appContext)
        getPrefs(appContext).edit().putBoolean(KEY_MAINTENANCE_ENABLED, enabled).commit()
        _preferencesFlow.value = current.copy(maintenanceEnabled = enabled)
        AnalyticsManager.logCloudTopicPreferenceChanged(TOPIC_MAINTENANCE, enabled)
        try {
            val messaging = FirebaseMessaging.getInstance()
            if (enabled) {
                messaging.subscribeToTopic(TOPIC_MAINTENANCE)
            } else {
                messaging.unsubscribeFromTopic(TOPIC_MAINTENANCE)
            }
        } catch (_: Throwable) {}
    }

    /**
     * Maps incoming FCM type hints or topic paths to canonical inbox types.
     */
    fun resolveNotificationCategory(typeHint: String?, fromTopic: String?): String {
        val normalizedType = typeHint?.trim()?.lowercase(Locale.US).orEmpty()
        val normalizedTopic = fromTopic?.trim()?.lowercase(Locale.US).orEmpty()

        return when {
            normalizedType in setOf("offer", "offers", "promo", "promotion", TYPE_FCM_OFFER) ||
                normalizedType.contains("عرض") ||
                normalizedType.contains("عروض") -> TYPE_FCM_OFFER

            normalizedType in setOf("maintenance", "maint", "outage", "status", TYPE_FCM_MAINTENANCE) ||
                normalizedType.contains("صيانة") -> TYPE_FCM_MAINTENANCE

            normalizedTopic.contains("offer") -> TYPE_FCM_OFFER
            normalizedTopic.contains("maint") -> TYPE_FCM_MAINTENANCE
            else -> TYPE_FCM_GENERAL
        }
    }

    /**
     * Handles an incoming FCM message by recording it into NotificationInboxManager
     * (with messageId deduplication) and posting a system notification when requested.
     *
     * @return true if the notification was newly recorded, false if ignored/deduplicated.
     */
    @Synchronized
    fun handleIncomingCloudMessage(
        context: Context,
        messageId: String?,
        title: String?,
        body: String?,
        typeHint: String? = null,
        fromTopic: String? = null,
        showSystemNotification: Boolean = true
    ): Boolean {
        val appContext = context.applicationContext ?: context
        createCloudNotificationChannel(appContext)
        NotificationInboxManager.init(appContext)

        val cleanTitle = title?.trim().orEmpty()
        val cleanBody = body?.trim().orEmpty()
        if (cleanTitle.isEmpty() && cleanBody.isEmpty()) {
            return false
        }

        val resolvedTitle = cleanTitle.ifEmpty { "إشعار من Skyline Users" }
        val resolvedBody = cleanBody.ifEmpty { resolvedTitle }
        val category = resolveNotificationCategory(typeHint, fromTopic)

        val dedupKey = messageId?.trim()?.takeIf { it.isNotEmpty() }
        if (dedupKey != null && isMessageIdRecorded(appContext, dedupKey)) {
            return false
        }

        if (dedupKey != null) {
            markMessageIdRecorded(appContext, dedupKey)
        }

        NotificationInboxManager.addNotification(
            context = appContext,
            title = resolvedTitle,
            message = resolvedBody,
            type = category
        )

        AnalyticsManager.logCloudNotificationReceived(category)

        if (showSystemNotification) {
            postSystemNotification(
                context = appContext,
                dedupKey = dedupKey ?: "$resolvedTitle|$resolvedBody",
                title = resolvedTitle,
                body = resolvedBody
            )
        }

        return true
    }

    /**
     * Processes a launching or new Intent when the user taps an FCM notification.
     * Ensures background FCM notification payloads (which Android displays directly
     * without invoking onMessageReceived) are also recorded in the in-app inbox
     * and opens the Notifications tab.
     */
    fun handleLaunchedNotificationIntent(context: Context, intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_FCM_INTENT_CONSUMED, false)) return

        val appContext = context.applicationContext ?: context
        val extras = intent.extras
        val messageId = extras?.getString("google.message_id")
            ?: extras?.getString("gcm.message_id")
        val fromTopic = extras?.getString("from")
        val openTabRequested = intent.getBooleanExtra(EXTRA_OPEN_NOTIFICATIONS_TAB, false) ||
            !messageId.isNullOrBlank()

        if (!openTabRequested) return

        intent.putExtra(EXTRA_FCM_INTENT_CONSUMED, true)
        requestOpenNotificationsTab()

        val alreadyRecorded = intent.getBooleanExtra(EXTRA_FCM_ALREADY_RECORDED, false)
        if (!alreadyRecorded) {
            val title = extras?.getString("gcm.notification.title")
                ?: extras?.getString("title")
                ?: extras?.getString("notification_title")
            val body = extras?.getString("gcm.notification.body")
                ?: extras?.getString("body")
                ?: extras?.getString("message")
                ?: extras?.getString("content")
            val typeHint = extras?.getString("type")
                ?: extras?.getString("category")
                ?: extras?.getString("topic")

            if (!title.isNullOrBlank() || !body.isNullOrBlank()) {
                handleIncomingCloudMessage(
                    context = appContext,
                    messageId = messageId,
                    title = title,
                    body = body,
                    typeHint = typeHint,
                    fromTopic = fromTopic,
                    showSystemNotification = false
                )
            }
        }
    }

    fun requestOpenNotificationsTab() {
        _openNotificationsTabFlow.value = true
    }

    fun consumeOpenNotificationsTabRequest() {
        _openNotificationsTabFlow.value = false
    }

    private fun postSystemNotification(
        context: Context,
        dedupKey: String,
        title: String,
        body: String
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return

            val tapIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_NOTIFICATIONS_TAB, true)
                putExtra(EXTRA_FCM_ALREADY_RECORDED, true)
            }

            val notificationId = 3000 + (dedupKey.hashCode() and 0x0FFF)
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val largeIconBitmap = try {
                BitmapFactory.decodeResource(context.resources, R.drawable.ic_skyline_logo)
            } catch (_: Exception) {
                null
            }

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .apply {
                    if (largeIconBitmap != null) {
                        setLargeIcon(largeIconBitmap)
                    }
                }
                .setColor(ContextCompat.getColor(context, R.color.skyline_notification_accent))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setSound(soundUri)
                .setVibrate(longArrayOf(0, 250, 150, 250))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

            notificationManager.notify(notificationId, builder.build())
        } catch (_: Exception) {
        }
    }

    private fun isMessageIdRecorded(context: Context, messageId: String): Boolean {
        return getRecordedMessageIds(context).contains(messageId)
    }

    private fun markMessageIdRecorded(context: Context, messageId: String) {
        try {
            val current = getRecordedMessageIds(context).toMutableList()
            current.remove(messageId)
            current.add(0, messageId)
            while (current.size > MAX_RECORDED_IDS) {
                current.removeAt(current.lastIndex)
            }
            val array = JSONArray()
            current.forEach { array.put(it) }
            getPrefs(context).edit().putString(KEY_RECORDED_IDS, array.toString()).commit()
        } catch (_: Exception) {}
    }

    private fun getRecordedMessageIds(context: Context): List<String> {
        val raw = getPrefs(context).getString(KEY_RECORDED_IDS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val value = array.optString(i)
                    if (value.isNotBlank()) add(value)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return (context.applicationContext ?: context)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    internal fun resetForTesting(context: Context) {
        synchronized(this) {
            getPrefs(context).edit().clear().commit()
            _preferencesFlow.value = FcmTopicPreferences()
            _openNotificationsTabFlow.value = false
            prefsLoaded = false
        }
    }
}
