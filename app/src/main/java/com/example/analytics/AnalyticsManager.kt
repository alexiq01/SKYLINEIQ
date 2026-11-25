package com.example.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Privacy-first centralized Firebase Analytics manager.
 *
 * Responsibilities:
 * - Initializes FirebaseAnalytics and enables aggregate usage & active-user measurement.
 * - Explicitly clears any User ID (`setUserId(null)`) so all analytics remain strictly anonymous.
 * - Sanitizes event names and parameters, automatically stripping any PII keys.
 * - Wraps SDK calls safely so devices without Google Play Services or JVM unit tests never crash.
 */
object AnalyticsManager {

    interface AnalyticsSink {
        fun logEvent(name: String, params: Map<String, Any>)
    }

    private val FORBIDDEN_PII_KEYS = setOf(
        "user_id", "uid", "username", "user_name", "password", "pass",
        "pin", "pin_code", "card_pin", "phone", "mobile", "tel",
        "ip", "ip_address", "mac", "mac_address", "email",
        "lat", "lng", "latitude", "longitude", "address", "token",
        "access_token", "refresh_token", "full_name", "subscriber_name"
    )

    @Volatile
    private var firebaseAnalytics: FirebaseAnalytics? = null

    @Volatile
    private var initialized: Boolean = false

    private val customSink = AtomicReference<AnalyticsSink?>(null)

    fun init(context: Context) {
        val appContext = context.applicationContext ?: context
        if (initialized && firebaseAnalytics != null) return
        try {
            if (FirebaseApp.getApps(appContext).isEmpty()) {
                FirebaseApp.initializeApp(appContext)
            }
            val instance = FirebaseAnalytics.getInstance(appContext)
            instance.setAnalyticsCollectionEnabled(true)
            // Aggregate statistics only — never attach personal identifiers
            instance.setUserId(null)
            instance.setDefaultEventParameters(null)
            firebaseAnalytics = instance
            initialized = true
        } catch (_: Throwable) {
            initialized = true
        }
    }

    internal fun setTestSink(sink: AnalyticsSink?) {
        customSink.set(sink)
    }

    internal fun resetForTesting() {
        customSink.set(null)
        firebaseAnalytics = null
        initialized = false
    }

    internal fun sanitizeIdentifier(raw: String, maxLength: Int = 40): String {
        val cleaned = raw.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        val prefixed = when {
            cleaned.isEmpty() -> "event_unknown"
            !cleaned[0].isLetter() -> "e_$cleaned"
            else -> cleaned
        }
        return prefixed.take(maxLength)
    }

    internal fun isForbiddenPiiKey(normalizedKey: String): Boolean {
        if (normalizedKey in FORBIDDEN_PII_KEYS) return true
        return normalizedKey.contains("password") ||
            normalizedKey.contains("username") ||
            normalizedKey.contains("user_id") ||
            normalizedKey.contains("phone") ||
            normalizedKey.contains("secret") ||
            normalizedKey.contains("token")
    }

    internal fun sanitizeParams(params: Map<String, Any?>): Map<String, Any> {
        val sanitized = linkedMapOf<String, Any>()
        for ((rawKey, rawValue) in params) {
            if (sanitized.size >= 25) break
            if (rawValue == null) continue
            val key = sanitizeIdentifier(rawKey, maxLength = 40)
            if (isForbiddenPiiKey(key)) continue
            val safeValue: Any = when (rawValue) {
                is Long -> rawValue
                is Int -> rawValue.toLong()
                is Double -> rawValue
                is Float -> rawValue.toDouble()
                is Boolean -> if (rawValue) "true" else "false"
                is String -> rawValue.trim().take(100)
                else -> rawValue.toString().trim().take(100)
            }
            if (safeValue is String && safeValue.isEmpty()) continue
            sanitized[key] = safeValue
        }
        return sanitized
    }

    fun logEvent(eventName: String, params: Map<String, Any?> = emptyMap()) {
        val safeName = sanitizeIdentifier(eventName, maxLength = 40)
        val safeParams = sanitizeParams(params)

        customSink.get()?.let { sink ->
            try {
                sink.logEvent(safeName, safeParams)
            } catch (_: Throwable) {}
            return
        }

        val analytics = firebaseAnalytics ?: return
        try {
            val bundle = Bundle().apply {
                for ((key, value) in safeParams) {
                    when (value) {
                        is Long -> putLong(key, value)
                        is Double -> putDouble(key, value)
                        is String -> putString(key, value)
                    }
                }
            }
            analytics.logEvent(safeName, bundle)
        } catch (_: Throwable) {
            // Safe no-op if Play Services measurement is unavailable
        }
    }

    fun logAppLaunch(
        hasSession: Boolean,
        isDarkMode: Boolean,
        isAppLockEnabled: Boolean
    ) {
        logEvent(
            FirebaseAnalytics.Event.APP_OPEN,
            mapOf(
                "has_session" to hasSession,
                "dark_mode" to isDarkMode,
                "app_lock_enabled" to isAppLockEnabled
            )
        )
    }

    fun logScreenView(screenName: String, screenClass: String = "MainActivity") {
        logEvent(
            FirebaseAnalytics.Event.SCREEN_VIEW,
            mapOf(
                FirebaseAnalytics.Param.SCREEN_NAME to screenName,
                FirebaseAnalytics.Param.SCREEN_CLASS to screenClass
            )
        )
    }

    fun logLoginAttempt(method: String) {
        logEvent(
            "login_attempt",
            mapOf(FirebaseAnalytics.Param.METHOD to method)
        )
    }

    fun logLoginSuccess(method: String) {
        logEvent(
            FirebaseAnalytics.Event.LOGIN,
            mapOf(
                FirebaseAnalytics.Param.METHOD to method,
                "result" to "success"
            )
        )
    }

    fun logLoginFailure(method: String, errorCategory: String) {
        logEvent(
            "login_failed",
            mapOf(
                FirebaseAnalytics.Param.METHOD to method,
                "error_category" to errorCategory
            )
        )
    }

    fun logLogout() {
        logEvent("logout")
    }

    fun logSubscriptionFetch(isRefresh: Boolean, success: Boolean, isFromCache: Boolean) {
        logEvent(
            "subscription_fetch",
            mapOf(
                "trigger" to if (isRefresh) "manual_refresh" else "auto_load",
                "result" to if (success) "success" else "failure",
                "source" to if (isFromCache) "cache" else "network"
            )
        )
    }

    fun logRenewDialogOpened() {
        logEvent("renew_dialog_opened")
    }

    fun logPinRenewalAttempt() {
        logEvent(
            "pin_renewal_attempt",
            mapOf(FirebaseAnalytics.Param.METHOD to "pin_card")
        )
    }

    fun logPinRenewalResult(success: Boolean) {
        logEvent(
            "subscription_renewal",
            mapOf(
                FirebaseAnalytics.Param.METHOD to "pin_card",
                "result" to if (success) "success" else "failure"
            )
        )
    }

    fun logLaunchReminderShown(isExpired: Boolean) {
        logEvent(
            "expiration_reminder_shown",
            mapOf("state" to if (isExpired) "expired" else "expiring_soon")
        )
    }

    fun logLaunchReminderAction(action: String) {
        logEvent(
            "expiration_reminder_action",
            mapOf("action" to action)
        )
    }

    fun logNotificationSettingChanged(setting: String, enabled: Boolean) {
        logEvent(
            "notification_setting_changed",
            mapOf(
                "setting" to setting,
                "state" to if (enabled) "enabled" else "disabled"
            )
        )
    }

    fun logNotificationIntervalChanged(hours: Long) {
        logEvent(
            "notification_interval_changed",
            mapOf("interval_hours" to hours)
        )
    }

    fun logManualBackgroundCheck() {
        logEvent("manual_background_check")
    }

    fun logTestNotificationTriggered(alertType: String) {
        logEvent(
            "test_notification_triggered",
            mapOf("alert_type" to alertType)
        )
    }

    fun logAppLockToggled(enabled: Boolean) {
        logEvent(
            "app_lock_toggled",
            mapOf("state" to if (enabled) "enabled" else "disabled")
        )
    }

    fun logBiometricToggled(enabled: Boolean) {
        logEvent(
            "biometric_toggled",
            mapOf("state" to if (enabled) "enabled" else "disabled")
        )
    }

    fun logAppUnlocked(method: String) {
        logEvent(
            "app_unlocked",
            mapOf(FirebaseAnalytics.Param.METHOD to method)
        )
    }

    fun logThemeChanged(isDark: Boolean) {
        logEvent(
            "theme_changed",
            mapOf("mode" to if (isDark) "dark" else "light")
        )
    }

    fun logSupportAction(channel: String) {
        logEvent(
            "support_action",
            mapOf("channel" to channel)
        )
    }

    fun logInAppUpdateAction(action: String) {
        logEvent(
            "in_app_update_action",
            mapOf("action" to action)
        )
    }

    fun logCloudNotificationReceived(category: String) {
        logEvent(
            "fcm_notification_received",
            mapOf("category" to category)
        )
    }

    fun logCloudTopicPreferenceChanged(topic: String, enabled: Boolean) {
        logEvent(
            "fcm_topic_updated",
            mapOf(
                "topic" to topic,
                "state" to if (enabled) "subscribed" else "unsubscribed"
            )
        )
    }
}
