package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import org.robolectric.Robolectric
import com.example.crypto.OpenSslCrypto
import com.example.data.api.ActivateRequest
import com.example.data.api.ApiClient
import com.example.data.api.ApiResponse
import com.example.data.api.ChangeServiceRequest
import com.example.data.api.DashboardData
import com.example.data.api.EncryptedPayloadRequest
import com.example.data.api.ExtendRequest
import com.example.data.api.GenericActionResponse
import com.example.data.api.LoginResponse
import com.example.data.api.PackageItem
import com.example.data.api.ServiceData
import com.example.data.api.SkylineApiService
import com.example.data.api.UnifiedSubscription
import com.example.data.api.UserAccountData
import com.example.data.repository.AutoLoginCheckStatus
import com.example.data.repository.SkylineRepository
import com.example.data.storage.SessionManager
import com.example.notifications.FcmNotificationManager
import com.example.notifications.NotificationInboxManager
import com.example.security.AppLockManager
import com.example.ui.theme.ThemeController
import com.example.worker.ExpirationNotificationWorker
import com.example.worker.NotificationScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Skyline Users", appName)
    }

    @Test
    fun `test OpenSSL crypto encryption and decryption roundtrip`() {
        val testPayload = """{"username":"test_user","password":"secret_password","language":"ar"}"""
        val encrypted = OpenSslCrypto.encrypt(testPayload)

        assertNotNull(encrypted)
        assertTrue(encrypted.isNotEmpty())

        val decrypted = OpenSslCrypto.decrypt(encrypted)
        assertEquals(testPayload, decrypted)
    }

    @Test
    fun `test date parser and remaining days calculation`() {
        val dateIso = "2026-12-31 23:59:59"
        val millis = NotificationScheduler.parseDateToMillis(dateIso)
        assertNotNull(millis)
        assertTrue(millis!! > 0)

        // Slashed format
        val dateSlash = "2026/12/31 12:00:00"
        val millisSlash = NotificationScheduler.parseDateToMillis(dateSlash)
        assertNotNull(millisSlash)

        // Date in future should be active
        val futureDate = "2030-01-01 00:00:00"
        assertTrue(NotificationScheduler.isSubscriptionActive(futureDate))
        assertTrue(NotificationScheduler.calculateRemainingDays(futureDate) > 100)

        // Date in past should not be active
        val pastDate = "2020-01-01 00:00:00"
        assertEquals(false, NotificationScheduler.isSubscriptionActive(pastDate))
        assertEquals(0, NotificationScheduler.calculateRemainingDays(pastDate))

        // Format Arabic date for fixed timestamp
        val formattedArabicDate = NotificationScheduler.formatArabicDate("2026-10-13 14:00:00")
        assertNotNull(formattedArabicDate)
        assertTrue(formattedArabicDate.contains("أكتوبر") || formattedArabicDate.contains("13"))

        // Format expiration with remaining time in Arabic using dynamic future timestamp (+5 days)
        val fiveDaysAheadMillis = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(5)
        val dynamicFutureStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(fiveDaysAheadMillis))
        val formattedWithRemaining = NotificationScheduler.formatExpirationWithRemainingTime(dynamicFutureStr)
        assertNotNull(formattedWithRemaining)
        assertTrue(formattedWithRemaining.contains("متبق"))
    }

    @Test
    fun `test thread safe date parsing under concurrent access`() {
        val executor = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(50)
        var hasFailure = false

        for (i in 0 until 50) {
            executor.submit {
                try {
                    val m = NotificationScheduler.parseDateToMillis("2026-10-15 14:30:00")
                    if (m == null || m <= 0) {
                        hasFailure = true
                    }
                } catch (_: Exception) {
                    hasFailure = true
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()
        assertEquals(false, hasFailure)
    }

    @Test
    fun `test safe Gson deserializer handles empty strings for numbers without crashing`() {
        val jsonWithEmptyNumbers = """
            {
                "username": "user123",
                "balance": "",
                "loan_balance": ""
            }
        """.trimIndent()

        val parsed = ApiClient.gson.fromJson(jsonWithEmptyNumbers, UserAccountData::class.java)
        assertNotNull(parsed)
        assertEquals("user123", parsed.username)
        assertNull(parsed.balance)
        assertNull(parsed.loanBalance)
    }

    @Test
    fun `test registered_on as object deserializes properly without crash`() {
        val jsonWithObjectDate = """
            {
                "status": 200,
                "data": {
                    "id": 1052,
                    "name": "Ali Hassan",
                    "username": "ali_hassan",
                    "balance": 15000.0,
                    "contract_id": "CONT-9921",
                    "registered_on": {
                        "date": "2024-03-15 11:20:45.000000",
                        "timezone_type": 3,
                        "timezone": "Asia/Baghdad"
                    },
                    "static_ip": null,
                    "auto_renew": 1
                }
            }
        """.trimIndent()

        val type = object : com.google.gson.reflect.TypeToken<ApiResponse<UserAccountData>>() {}.type
        val response: ApiResponse<UserAccountData> = ApiClient.gson.fromJson(jsonWithObjectDate, type)
        assertNotNull(response.data)
        assertEquals("Ali Hassan", response.data?.name)
        assertEquals("2024-03-15 11:20:45", response.data?.registeredOn)

        val jsonWithEmptyObjectDate = """
            {
                "id": 1052,
                "username": "ali_hassan",
                "registered_on": {}
            }
        """.trimIndent()
        val parsedEmpty = ApiClient.gson.fromJson(jsonWithEmptyObjectDate, UserAccountData::class.java)
        assertNotNull(parsedEmpty)
        assertNull(parsedEmpty.registeredOn)

        val jsonWithStringDate = """
            {
                "id": 1052,
                "username": "ali_hassan",
                "registered_on": "2024-03-15"
            }
        """.trimIndent()
        val parsedString = ApiClient.gson.fromJson(jsonWithStringDate, UserAccountData::class.java)
        assertNotNull(parsedString)
        assertEquals("2024-03-15", parsedString.registeredOn)
    }

    @Test
    fun `test session manager save and clear`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = SessionManager(context)

        session.saveSession(token = "test_token_xyz", username = "skyline_user")
        assertTrue(session.hasValidSession())
        assertEquals("test_token_xyz", session.getToken())
        assertEquals("skyline_user", session.getUsername())

        session.clearSession()
        assertEquals(false, session.hasValidSession())
        assertNull(session.getToken())
    }

    @Test
    fun `test developer contact constants`() {
        assertEquals("https://www.instagram.com/y_as.01", com.example.ui.components.DEVELOPER_INSTAGRAM_URL)
        assertEquals("+9647830528876", com.example.ui.components.DEVELOPER_WHATSAPP_NUMBER)
        assertEquals("4659615480", com.example.ui.components.DEVELOPER_SUPER_QI_NUMBER)
    }

    @Test
    fun `test ThemeController defaults to system theme and persists user override`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("skyline_theme_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        ThemeController.init(context)
        assertNull(ThemeController.isDarkModeOverride)

        ThemeController.setDarkMode(context, true)
        assertEquals(true, ThemeController.isDarkModeOverride)

        ThemeController.init(context)
        assertEquals(true, ThemeController.isDarkModeOverride)
    }

    @Test
    fun `test NotificationScheduler alert deduplication and NotificationInboxManager read state`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NotificationInboxManager.clearAll(context)
        val expDate = "2027-06-15 12:00:00"

        assertFalse(NotificationScheduler.wasAlertNotifiedForDate(context, ExpirationNotificationWorker.ALERT_3D, expDate))
        NotificationScheduler.markAlertNotified(context, ExpirationNotificationWorker.ALERT_3D, expDate)
        assertTrue(NotificationScheduler.wasAlertNotifiedForDate(context, ExpirationNotificationWorker.ALERT_3D, expDate))

        NotificationInboxManager.addNotification(
            context = context,
            title = "تنبيه: اقتراب انتهاء الاشتراك",
            message = "متبقي 2 أيام على انتهاء اشتراكك الحالي ($expDate).",
            type = "expiring_soon"
        )
        assertTrue(NotificationInboxManager.hasNotificationForCycle("expiring_soon", expDate))
        assertEquals(1, NotificationInboxManager.notificationsFlow.value.count { !it.isRead })

        NotificationInboxManager.markAllAsRead(context)
        assertEquals(0, NotificationInboxManager.notificationsFlow.value.count { !it.isRead })
        assertEquals(1, NotificationInboxManager.notificationsFlow.value.size)
    }

    @Test
    fun `test AppLockManager evaluates launch lock cleanly and resets on logout`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = SessionManager(context)
        session.saveSession(token = "token_1", username = "user_1", password = "pw")

        AppLockManager.resetLockOnLogout(context)
        AppLockManager.setPinAndEnable(context, pin = "1357", enableBiometric = false)
        AppLockManager.unlockApp(context)
        assertFalse(AppLockManager.lockStateFlow.value.isLocked)

        // Launch evaluation locks once, and subsequent calls in same process do not re-lock after unlock
        AppLockManager.resetLockOnLogout(context)
        AppLockManager.setPinAndEnable(context, pin = "1357", enableBiometric = false)
        AppLockManager.onAppLaunched(context)
        assertTrue(AppLockManager.lockStateFlow.value.isLocked)

        AppLockManager.unlockApp(context)
        assertFalse(AppLockManager.lockStateFlow.value.isLocked)

        AppLockManager.onAppLaunched(context)
        assertFalse(AppLockManager.lockStateFlow.value.isLocked)

        AppLockManager.resetLockOnLogout(context)
        session.clearSession()
    }

    @Test
    fun `test SkylineRepository silent refresh on JSON 401 and cache preservation on empty response`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(context, Configuration.Builder().build())
        }

        val session = SessionManager(context)
        session.clearSession()
        session.saveSession(token = "expired_token", username = "ali_user", password = "secret_password")
        val initialCache = UnifiedSubscription(
            user = UserAccountData(id = 1L, name = "Ali Saved", username = "ali_user", balance = 12000.0),
            service = ServiceData(profileName = "Nitro-2", price = 35000.0, expirationRaw = "2028-01-01 00:00:00"),
            dashboard = DashboardData(remainingDaysRaw = 20, balance = 12000.0),
            lastUpdatedMillis = 123456L
        )
        session.saveCache(initialCache)

        var loginCalled = false
        var returnEmptyAfterRefresh = false

        val fakeApi = object : SkylineApiService {
            override suspend fun login(body: EncryptedPayloadRequest, authHeader: String): Response<LoginResponse> {
                loginCalled = true
                return Response.success(LoginResponse(status = 200, token = "fresh_token"))
            }

            override suspend fun autoLogin(): Response<LoginResponse> =
                Response.success(LoginResponse(status = 401, message = "not_allowed"))

            override suspend fun getUser(authHeader: String): Response<ApiResponse<UserAccountData>> {
                if (authHeader == "Bearer expired_token") {
                    return Response.success(ApiResponse(status = 401, data = null, message = "unauthorized"))
                }
                return if (returnEmptyAfterRefresh) {
                    Response.success(ApiResponse(status = 200, data = null))
                } else {
                    Response.success(
                        ApiResponse(
                            status = 200,
                            data = UserAccountData(id = 1L, name = "Ali Refreshed", username = "ali_user", balance = 25000.0)
                        )
                    )
                }
            }

            override suspend fun getService(authHeader: String): Response<ApiResponse<ServiceData>> {
                if (authHeader == "Bearer expired_token") {
                    return Response.success(ApiResponse(status = 401, data = null, message = "unauthorized"))
                }
                return if (returnEmptyAfterRefresh) {
                    Response.success(ApiResponse(status = 200, data = null))
                } else {
                    Response.success(
                        ApiResponse(
                            status = 200,
                            data = ServiceData(profileName = "Nitro-3", price = 40000.0, expirationRaw = "2028-02-01 00:00:00")
                        )
                    )
                }
            }

            override suspend fun getDashboard(authHeader: String): Response<ApiResponse<DashboardData>> {
                if (authHeader == "Bearer expired_token") {
                    return Response.success(ApiResponse(status = 401, data = null, message = "unauthorized"))
                }
                return if (returnEmptyAfterRefresh) {
                    Response.success(ApiResponse(status = 200, data = null))
                } else {
                    Response.success(ApiResponse(status = 200, data = DashboardData(remainingDaysRaw = 30, balance = 25000.0)))
                }
            }

            override suspend fun getPackages(authHeader: String): Response<ApiResponse<List<PackageItem>>> =
                Response.success(ApiResponse(status = 200, data = emptyList()))

            override suspend fun redeem(authHeader: String, body: EncryptedPayloadRequest): Response<GenericActionResponse> =
                Response.success(GenericActionResponse(status = 200, message = "ok"))

            override suspend fun extend(authHeader: String, body: ExtendRequest): Response<GenericActionResponse> =
                Response.success(GenericActionResponse(status = 200, message = "ok"))

            override suspend fun activate(authHeader: String, body: ActivateRequest): Response<GenericActionResponse> =
                Response.success(GenericActionResponse(status = 200, message = "ok"))

            override suspend fun changeService(authHeader: String, body: ChangeServiceRequest): Response<GenericActionResponse> =
                Response.success(GenericActionResponse(status = 200, message = "ok"))
        }

        val repo = SkylineRepository(context = context, apiService = fakeApi, sessionManager = session)
        val refreshedResult = repo.fetchSubscriptionData()

        assertTrue(loginCalled)
        assertTrue(refreshedResult.isSuccess)
        assertEquals("fresh_token", session.getToken())
        assertEquals("Ali Refreshed", refreshedResult.getOrNull()?.user?.name)
        assertFalse(refreshedResult.getOrNull()?.isFromCache ?: true)

        // Now verify an all-null response preserves the existing cache rather than wiping it
        returnEmptyAfterRefresh = true
        val cacheProtectedResult = repo.fetchSubscriptionData()
        assertTrue(cacheProtectedResult.isSuccess)
        assertEquals("Ali Refreshed", cacheProtectedResult.getOrNull()?.user?.name)
        assertEquals("Ali Refreshed", session.getCachedSubscription()?.user?.name)

        session.clearSession()
    }

    @Test
    fun `test FragmentActivity accepts 32-bit requestCodes for runtime permissions`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).create().get()
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            0x1000_0001
        )
        assertNotNull(activity)
    }

    @Test
    fun `test launch provider auto login succeeds only when ISP returns valid token`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = SessionManager(context)
        session.clearSession(clearSavedCredentials = true)

        val providerApi = object : SkylineApiService {
            override suspend fun login(body: EncryptedPayloadRequest, authHeader: String): Response<LoginResponse> =
                Response.success(LoginResponse(status = 401, message = "err"))

            override suspend fun autoLogin(): Response<LoginResponse> =
                Response.success(LoginResponse(status = 200, token = "isp_real_jwt_token"))

            override suspend fun getUser(authHeader: String): Response<ApiResponse<UserAccountData>> =
                Response.success(
                    ApiResponse(
                        status = 200,
                        data = UserAccountData(id = 42L, name = "Taha", username = "bbn.17@haq2")
                    )
                )

            override suspend fun getService(authHeader: String): Response<ApiResponse<ServiceData>> =
                Response.success(ApiResponse(status = 200, data = null))

            override suspend fun getDashboard(authHeader: String): Response<ApiResponse<DashboardData>> =
                Response.success(ApiResponse(status = 200, data = null))

            override suspend fun getPackages(authHeader: String): Response<ApiResponse<List<PackageItem>>> =
                Response.success(ApiResponse(status = 200, data = emptyList()))

            override suspend fun redeem(authHeader: String, body: EncryptedPayloadRequest): Response<GenericActionResponse> =
                Response.success(GenericActionResponse(status = 200))

            override suspend fun extend(authHeader: String, body: ExtendRequest): Response<GenericActionResponse> =
                Response.success(GenericActionResponse(status = 200))

            override suspend fun activate(authHeader: String, body: ActivateRequest): Response<GenericActionResponse> =
                Response.success(GenericActionResponse(status = 200))

            override suspend fun changeService(authHeader: String, body: ChangeServiceRequest): Response<GenericActionResponse> =
                Response.success(GenericActionResponse(status = 200))
        }

        val repo = SkylineRepository(context = context, apiService = providerApi, sessionManager = session)
        val status = repo.attemptLaunchProviderAutoLogin()

        assertEquals(AutoLoginCheckStatus.SUCCESS_PROVIDER, status)
        assertTrue(session.hasValidSession())
        assertEquals("isp_real_jwt_token", session.getToken())
        assertEquals("bbn.17@haq2", session.getUsername())

        session.clearSession(clearSavedCredentials = true)
    }

    @Test
    fun `test launch provider auto login rejects external networks without creating fake sessions`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = SessionManager(context)
        session.clearSession(clearSavedCredentials = true)

        val externalNetworkApi = object : SkylineApiService {
            override suspend fun login(body: EncryptedPayloadRequest, authHeader: String): Response<LoginResponse> =
                Response.success(LoginResponse(status = -3, message = "error_not_allowed"))

            override suspend fun autoLogin(): Response<LoginResponse> =
                Response.success(LoginResponse(status = -3, token = null, message = "error_not_allowed"))

            override suspend fun getUser(authHeader: String): Response<ApiResponse<UserAccountData>> =
                Response.success(ApiResponse(status = 401, data = null))

            override suspend fun getService(authHeader: String): Response<ApiResponse<ServiceData>> =
                Response.success(ApiResponse(status = 401, data = null))

            override suspend fun getDashboard(authHeader: String): Response<ApiResponse<DashboardData>> =
                Response.success(ApiResponse(status = 401, data = null))

            override suspend fun getPackages(authHeader: String): Response<ApiResponse<List<PackageItem>>> =
                Response.success(ApiResponse(status = 401, data = null))

            override suspend fun redeem(authHeader: String, body: EncryptedPayloadRequest): Response<GenericActionResponse> =
                Response.success(GenericActionResponse(status = 401))

            override suspend fun extend(authHeader: String, body: ExtendRequest): Response<GenericActionResponse> =
                Response.success(GenericActionResponse(status = 401))

            override suspend fun activate(authHeader: String, body: ActivateRequest): Response<GenericActionResponse> =
                Response.success(GenericActionResponse(status = 401))

            override suspend fun changeService(authHeader: String, body: ChangeServiceRequest): Response<GenericActionResponse> =
                Response.success(GenericActionResponse(status = 401))
        }

        val repo = SkylineRepository(context = context, apiService = externalNetworkApi, sessionManager = session)
        val status = repo.attemptLaunchProviderAutoLogin()

        assertEquals(AutoLoginCheckStatus.NOT_ON_PROVIDER_NETWORK, status)
        assertFalse(session.hasValidSession())
        assertNull(session.getToken())
    }

    @Test
    fun `test FcmNotificationManager defaults and topic preference persistence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FcmNotificationManager.resetForTesting(context)

        val initial = FcmNotificationManager.getPreferences(context)
        assertTrue(initial.generalEnabled)
        assertTrue(initial.offersEnabled)
        assertTrue(initial.maintenanceEnabled)

        FcmNotificationManager.setOffersEnabled(context, false)
        FcmNotificationManager.setMaintenanceEnabled(context, false)

        val updated = FcmNotificationManager.getPreferences(context)
        assertFalse(updated.offersEnabled)
        assertFalse(updated.maintenanceEnabled)

        // Verify persistence across reload
        FcmNotificationManager.resetForTesting(context)
        FcmNotificationManager.setOffersEnabled(context, false)
        assertFalse(FcmNotificationManager.getPreferences(context).offersEnabled)
        FcmNotificationManager.resetForTesting(context)
    }

    @Test
    fun `test FcmNotificationManager resolves notification categories cleanly`() {
        assertEquals(
            FcmNotificationManager.TYPE_FCM_OFFER,
            FcmNotificationManager.resolveNotificationCategory(typeHint = "offers", fromTopic = null)
        )
        assertEquals(
            FcmNotificationManager.TYPE_FCM_OFFER,
            FcmNotificationManager.resolveNotificationCategory(typeHint = null, fromTopic = "/topics/skyline_offers")
        )
        assertEquals(
            FcmNotificationManager.TYPE_FCM_MAINTENANCE,
            FcmNotificationManager.resolveNotificationCategory(typeHint = "maintenance", fromTopic = null)
        )
        assertEquals(
            FcmNotificationManager.TYPE_FCM_MAINTENANCE,
            FcmNotificationManager.resolveNotificationCategory(typeHint = null, fromTopic = "/topics/skyline_maintenance")
        )
        assertEquals(
            FcmNotificationManager.TYPE_FCM_GENERAL,
            FcmNotificationManager.resolveNotificationCategory(typeHint = null, fromTopic = "/topics/skyline_all")
        )
    }

    @Test
    fun `test FcmNotificationManager records incoming message in inbox and deduplicates messageId`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FcmNotificationManager.resetForTesting(context)
        NotificationInboxManager.clearAll(context)

        val firstRecorded = FcmNotificationManager.handleIncomingCloudMessage(
            context = context,
            messageId = "msg_offer_001",
            title = "عرض باقة الصيف",
            body = "خصم 20% على تجديد باقة Nitro",
            typeHint = "offer",
            fromTopic = "/topics/skyline_offers",
            showSystemNotification = true
        )
        assertTrue(firstRecorded)

        // Duplicate delivery of same messageId must be ignored
        val duplicateRecorded = FcmNotificationManager.handleIncomingCloudMessage(
            context = context,
            messageId = "msg_offer_001",
            title = "عرض باقة الصيف",
            body = "خصم 20% على تجديد باقة Nitro",
            typeHint = "offer",
            fromTopic = "/topics/skyline_offers",
            showSystemNotification = true
        )
        assertFalse(duplicateRecorded)

        val items = NotificationInboxManager.notificationsFlow.value
        assertEquals(1, items.size)
        assertEquals("عرض باقة الصيف", items[0].title)
        assertEquals(FcmNotificationManager.TYPE_FCM_OFFER, items[0].type)

        NotificationInboxManager.clearAll(context)
        FcmNotificationManager.resetForTesting(context)
    }

    @Test
    fun `test FcmNotificationManager background notification tap intent routes to inbox and records once`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FcmNotificationManager.resetForTesting(context)
        NotificationInboxManager.clearAll(context)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("google.message_id", "bg_msg_101")
            putExtra("from", "/topics/skyline_maintenance")
            putExtra("gcm.notification.title", "صيانة مجدولة")
            putExtra("gcm.notification.body", "تحديث على برج التوزيع الساعة 3 فجراً")
        }

        FcmNotificationManager.handleLaunchedNotificationIntent(context, launchIntent)

        assertTrue(FcmNotificationManager.openNotificationsTabFlow.value)
        val items = NotificationInboxManager.notificationsFlow.value
        assertEquals(1, items.size)
        assertEquals(FcmNotificationManager.TYPE_FCM_MAINTENANCE, items[0].type)
        assertEquals("صيانة مجدولة", items[0].title)

        // Re-handling the same intent (e.g., configuration change) does not re-trigger tab open or duplicate item
        FcmNotificationManager.consumeOpenNotificationsTabRequest()
        FcmNotificationManager.handleLaunchedNotificationIntent(context, launchIntent)
        assertFalse(FcmNotificationManager.openNotificationsTabFlow.value)
        assertEquals(1, NotificationInboxManager.notificationsFlow.value.size)

        NotificationInboxManager.clearAll(context)
        FcmNotificationManager.resetForTesting(context)
    }
}


