package com.example.admin

import android.content.Context
import android.util.Log
import com.example.data.storage.SessionManager
import com.example.notifications.FcmNotificationManager
import com.example.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Coordinates real-time Firestore listeners on public `app_config/main`
 * and published `broadcasts` across all active app installations.
 */
object RemoteSyncManager {

    private const val TAG = "RemoteSyncManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var configJob: Job? = null

    @Volatile
    private var broadcastsJob: Job? = null

    fun start(context: Context) {
        val appContext = context.applicationContext ?: context
        AppBrandingManager.init(appContext)

        val repository = try {
            AdminFirebaseRepository(appContext)
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore unavailable during RemoteSyncManager start: ${e.message}")
            return
        }

        if (configJob?.isActive != true) {
            configJob = scope.launch {
                repository.observeAppConfig()
                    .catch { error ->
                        Log.w(TAG, "observeAppConfig error: ${error.message}")
                    }
                    .collect { config ->
                        if (config != null) {
                            AppBrandingManager.applyRemoteConfig(appContext, config, applyLauncherSwitch = true)
                            UpdateManager.applyFirestoreUpdateConfig(config)
                        }
                    }
            }
        }

        if (broadcastsJob?.isActive != true) {
            broadcastsJob = scope.launch {
                repository.observePublishedBroadcasts()
                    .catch { error ->
                        Log.w(TAG, "observePublishedBroadcasts error: ${error.message}")
                    }
                    .collect { broadcasts ->
                        val fcmPrefs = FcmNotificationManager.getPreferences(appContext)
                        broadcasts.forEach { item ->
                            val shouldDeliver = when (item.category) {
                                FcmNotificationManager.TYPE_FCM_OFFER -> fcmPrefs.offersEnabled
                                FcmNotificationManager.TYPE_FCM_MAINTENANCE -> fcmPrefs.maintenanceEnabled
                                else -> true
                            }
                            if (shouldDeliver && item.id.isNotBlank()) {
                                FcmNotificationManager.handleIncomingCloudMessage(
                                    context = appContext,
                                    messageId = "bcast_${item.id}",
                                    title = item.title,
                                    body = item.body,
                                    typeHint = item.category,
                                    fromTopic = null,
                                    showSystemNotification = true
                                )
                            }
                        }
                    }
            }
        }

        // If user already signed into Google, quietly refresh their synced directory record
        if (repository.currentUser() != null) {
            scope.launch {
                val ispUsername = try {
                    SessionManager(appContext).getUsername()
                } catch (_: Throwable) {
                    null
                }
                repository.syncCurrentUserProfile(
                    ispUsername = ispUsername,
                    launcherPreset = AppBrandingManager.brandingFlow.value.preset.key
                )
            }
        }
    }
}
