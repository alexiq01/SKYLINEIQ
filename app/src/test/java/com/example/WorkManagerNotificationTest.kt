package com.example

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Data
import com.example.data.api.DashboardData
import com.example.data.api.ServiceData
import com.example.data.api.UnifiedSubscription
import com.example.data.api.UserAccountData
import com.example.data.repository.SkylineRepository
import com.example.data.storage.SessionManager
import com.example.notifications.NotificationInboxManager
import com.example.worker.BootReceiver
import com.example.worker.ExpirationNotificationWorker
import com.example.worker.NotificationScheduler
import com.example.worker.NotificationSettings
import com.example.worker.SubscriptionCheckWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkManagerNotificationTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(context, Configuration.Builder().build())
        }
        workManager = WorkManager.getInstance(context)
        context.getSharedPreferences("skyline_notification_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        NotificationInboxManager.clearAll(context)
        NotificationScheduler.cancelAllScheduledAlerts(context)
        NotificationScheduler.updateNotificationSettings(context, NotificationSettings())
    }

    private fun futureDateString(daysAhead: Long, hoursOffset: Long = 0L): String {
        val millis = System.currentTimeMillis() +
            TimeUnit.DAYS.toMillis(daysAhead) +
            TimeUnit.HOURS.toMillis(hoursOffset)
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
    }

    @Test
    fun `scheduleSubscriptionAlerts enqueues 3-day WorkManager task when expiration is more than 3 days away`() {
        val expDate = futureDateString(daysAhead = 10)
        NotificationScheduler.scheduleSubscriptionAlerts(context, expDate)

        val workInfos = workManager.getWorkInfosForUniqueWork(NotificationScheduler.WORK_EXP_3D).get()
        assertEquals(1, workInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, workInfos.first().state)
        assertTrue(workInfos.first().tags.contains(NotificationScheduler.WORK_EXP_3D))

        val triggerMillis = NotificationScheduler.getThreeDayTriggerMillis(expDate)
        assertNotNull(triggerMillis)
        assertTrue(triggerMillis!! > System.currentTimeMillis() + TimeUnit.DAYS.toMillis(6))
    }

    @Test
    fun `disabling 3-day alerts or background alerts cancels scheduled 3-day WorkManager task`() {
        val expDate = futureDateString(daysAhead = 10)
        NotificationScheduler.scheduleSubscriptionAlerts(context, expDate)
        assertEquals(
            WorkInfo.State.ENQUEUED,
            workManager.getWorkInfosForUniqueWork(NotificationScheduler.WORK_EXP_3D).get().first().state
        )

        NotificationScheduler.updateNotificationSettings(
            context,
            NotificationSettings(backgroundAlertsEnabled = true, alert3DaysEnabled = false)
        )
        NotificationScheduler.scheduleSubscriptionAlerts(context, expDate)
        val afterToggleOff = workManager.getWorkInfosForUniqueWork(NotificationScheduler.WORK_EXP_3D).get()
        assertTrue(afterToggleOff.all { it.state == WorkInfo.State.CANCELLED })

        NotificationScheduler.updateNotificationSettings(
            context,
            NotificationSettings(backgroundAlertsEnabled = false, alert3DaysEnabled = true)
        )
        NotificationScheduler.scheduleSubscriptionAlerts(context, expDate)
        val periodicAfterMasterOff = workManager.getWorkInfosForUniqueWork(NotificationScheduler.PERIODIC_CHECK_WORK_NAME).get()
        assertTrue(periodicAfterMasterOff.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `ExpirationNotificationWorker posts 3-day notification, saves inbox entry, and deduplicates repeat runs`() = runBlocking {
        val expDate = futureDateString(daysAhead = 2, hoursOffset = 12)
        val inputData = Data.Builder()
            .putString(ExpirationNotificationWorker.KEY_ALERT_TYPE, ExpirationNotificationWorker.ALERT_3D)
            .putString(ExpirationNotificationWorker.KEY_EXPIRATION_DATE, expDate)
            .build()

        val workerParams = androidx.work.testing.TestListenableWorkerBuilder<ExpirationNotificationWorker>(context)
            .setInputData(inputData)
            .build()
        val result = workerParams.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        assertTrue(NotificationScheduler.wasAlertNotifiedForDate(context, ExpirationNotificationWorker.ALERT_3D, expDate))

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(notificationManager).allNotifications
        assertEquals(1, posted.size)
        assertEquals(1, NotificationInboxManager.notificationsFlow.value.size)

        // Second execution for same expiration cycle must not duplicate notification or inbox item
        val workerRepeat = androidx.work.testing.TestListenableWorkerBuilder<ExpirationNotificationWorker>(context)
            .setInputData(inputData)
            .build()
        workerRepeat.doWork()
        assertEquals(1, shadowOf(notificationManager).allNotifications.size)
        assertEquals(1, NotificationInboxManager.notificationsFlow.value.size)
    }

    @Test
    fun `BootReceiver reschedules periodic check and 3-day alert from cached subscription`() {
        val session = SessionManager(context)
        val expDate = futureDateString(daysAhead = 12)
        session.saveSession(token = "valid_tok", username = "sky_user")
        session.saveCache(
            UnifiedSubscription(
                user = UserAccountData(id = 1L, name = "Sky User", username = "sky_user"),
                service = ServiceData(profileName = "Nitro", expirationRaw = expDate),
                dashboard = DashboardData(remainingDaysRaw = 12),
                lastUpdatedMillis = System.currentTimeMillis()
            )
        )

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val periodicInfos = workManager.getWorkInfosForUniqueWork(NotificationScheduler.PERIODIC_CHECK_WORK_NAME).get()
        assertTrue(periodicInfos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING })

        val threeDayInfos = workManager.getWorkInfosForUniqueWork(NotificationScheduler.WORK_EXP_3D).get()
        assertEquals(1, threeDayInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, threeDayInfos.first().state)

        // Logout clears user-specific scheduled expiration work
        SkylineRepository(context).logout()
        val afterLogout = workManager.getWorkInfosForUniqueWork(NotificationScheduler.WORK_EXP_3D).get()
        assertTrue(afterLogout.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `SubscriptionCheckWorker triggers 3-day alert automatically when cached subscription is within 3 days`() = runBlocking {
        val session = SessionManager(context)
        val expDate = futureDateString(daysAhead = 2, hoursOffset = 6)
        session.saveSession(token = "valid_tok", username = "sky_user")
        session.saveCache(
            UnifiedSubscription(
                user = UserAccountData(id = 1L, name = "Sky User", username = "sky_user"),
                service = ServiceData(profileName = "Nitro", expirationRaw = expDate),
                dashboard = DashboardData(remainingDaysRaw = 2),
                lastUpdatedMillis = System.currentTimeMillis()
            )
        )

        val worker = androidx.work.testing.TestListenableWorkerBuilder<SubscriptionCheckWorker>(context).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(NotificationScheduler.wasAlertNotifiedForDate(context, ExpirationNotificationWorker.ALERT_3D, expDate))

        session.clearSession()
    }
}
