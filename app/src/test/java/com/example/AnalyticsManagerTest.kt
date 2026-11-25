package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.analytics.AnalyticsManager
import com.google.firebase.analytics.FirebaseAnalytics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnalyticsManagerTest {

    private val recordedEvents = mutableListOf<Pair<String, Map<String, Any>>>()

    @Before
    fun setUp() {
        recordedEvents.clear()
        AnalyticsManager.resetForTesting()
        AnalyticsManager.setTestSink(object : AnalyticsManager.AnalyticsSink {
            override fun logEvent(name: String, params: Map<String, Any>) {
                recordedEvents.add(name to params)
            }
        })
    }

    @After
    fun tearDown() {
        AnalyticsManager.resetForTesting()
    }

    @Test
    fun `sanitizeParams strips forbidden PII keys and preserves aggregate metrics`() {
        val sanitized = AnalyticsManager.sanitizeParams(
            mapOf(
                "user_id" to "12345",
                "username" to "ahmed_ali",
                "password" to "secret",
                "pin_code" to "4567",
                "phone" to "07821082008",
                "latitude" to 33.3152,
                "method" to "credentials",
                "result" to "success",
                "interval_hours" to 12,
                "has_session" to true
            )
        )

        assertFalse(sanitized.containsKey("user_id"))
        assertFalse(sanitized.containsKey("username"))
        assertFalse(sanitized.containsKey("password"))
        assertFalse(sanitized.containsKey("pin_code"))
        assertFalse(sanitized.containsKey("phone"))
        assertFalse(sanitized.containsKey("latitude"))

        assertEquals("credentials", sanitized["method"])
        assertEquals("success", sanitized["result"])
        assertEquals(12L, sanitized["interval_hours"])
        assertEquals("true", sanitized["has_session"])
    }

    @Test
    fun `sanitizeIdentifier normalizes event and param names for Firebase rules`() {
        assertEquals("screen_view", AnalyticsManager.sanitizeIdentifier("Screen View!"))
        assertEquals("e_3_days_alert", AnalyticsManager.sanitizeIdentifier("3-days-alert"))
        assertTrue(AnalyticsManager.sanitizeIdentifier("a".repeat(60)).length <= 40)
    }

    @Test
    fun `analytics manager logs app launch, screens, login, and renewal events accurately`() {
        AnalyticsManager.logAppLaunch(hasSession = true, isDarkMode = true, isAppLockEnabled = true)
        AnalyticsManager.logScreenView("home", "HomeScreen")
        AnalyticsManager.logLoginSuccess("auto_gps")
        AnalyticsManager.logPinRenewalResult(success = true)
        AnalyticsManager.logNotificationIntervalChanged(24L)

        assertEquals(5, recordedEvents.size)

        assertEquals(FirebaseAnalytics.Event.APP_OPEN, recordedEvents[0].first)
        assertEquals("true", recordedEvents[0].second["has_session"])
        assertEquals("true", recordedEvents[0].second["dark_mode"])
        assertEquals("true", recordedEvents[0].second["app_lock_enabled"])

        assertEquals(FirebaseAnalytics.Event.SCREEN_VIEW, recordedEvents[1].first)
        assertEquals("home", recordedEvents[1].second[FirebaseAnalytics.Param.SCREEN_NAME])
        assertEquals("HomeScreen", recordedEvents[1].second[FirebaseAnalytics.Param.SCREEN_CLASS])

        assertEquals(FirebaseAnalytics.Event.LOGIN, recordedEvents[2].first)
        assertEquals("auto_gps", recordedEvents[2].second[FirebaseAnalytics.Param.METHOD])

        assertEquals("subscription_renewal", recordedEvents[3].first)
        assertEquals("pin_card", recordedEvents[3].second[FirebaseAnalytics.Param.METHOD])
        assertEquals("success", recordedEvents[3].second["result"])

        assertEquals("notification_interval_changed", recordedEvents[4].first)
        assertEquals(24L, recordedEvents[4].second["interval_hours"])
    }

    @Test
    fun `init and logEvent safely handle Robolectric environment without crashing`() {
        AnalyticsManager.resetForTesting()
        val context = ApplicationProvider.getApplicationContext<Context>()
        AnalyticsManager.init(context)
        AnalyticsManager.logAppLaunch(hasSession = false, isDarkMode = false, isAppLockEnabled = false)
        AnalyticsManager.logScreenView("login", "LoginScreen")
    }
}
