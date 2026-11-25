package com.example.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives Firebase Cloud Messaging pushes (topic broadcasts, offers, maintenance alerts)
 * and routes them to both the system notification tray and the in-app Notifications Inbox.
 */
class SkylineFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Re-apply topic subscriptions whenever FCM rotates the registration token.
        // Do not log or persist raw registration tokens to analytics.
        FcmNotificationManager.syncTopicSubscriptions(applicationContext)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data

        val title = remoteMessage.notification?.title
            ?: data["title"]
            ?: data["notification_title"]
        val body = remoteMessage.notification?.body
            ?: data["body"]
            ?: data["message"]
            ?: data["content"]
        val typeHint = data["type"]
            ?: data["category"]
            ?: data["topic"]

        FcmNotificationManager.handleIncomingCloudMessage(
            context = applicationContext,
            messageId = remoteMessage.messageId,
            title = title,
            body = body,
            typeHint = typeHint,
            fromTopic = remoteMessage.from,
            showSystemNotification = true
        )
    }
}
