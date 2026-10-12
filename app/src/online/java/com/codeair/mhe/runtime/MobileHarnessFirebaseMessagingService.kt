package com.codeair.mhe.runtime

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives data-oriented FCM events for the online flavor.
 *
 * Remote backends should prefer data messages for events that need Mobile
 * Harness' centralized notification/deep-link policy. Notification messages
 * sent while the app is backgrounded may be rendered directly by the FCM SDK.
 */
class MobileHarnessFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        val notification = remoteMessage.notification

        val title = data["title"]
            ?.takeIf { it.isNotBlank() }
            ?: notification?.title
            ?: "Mobile Harness"

        val body = data["body"]
            ?.takeIf { it.isNotBlank() }
            ?: notification?.body
            ?: return

        NotificationCoordinator.postRemote(
            context = this,
            title = title.take(MAX_TITLE_LENGTH),
            detail = body.take(MAX_BODY_LENGTH),
            messageId = remoteMessage.messageId,
            route = data["route"],
        )
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        PushTokenStore.save(this, token)
    }

    companion object {
        private const val MAX_TITLE_LENGTH = 120
        private const val MAX_BODY_LENGTH = 4_000
    }
}
