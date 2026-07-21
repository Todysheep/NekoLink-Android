package app.nekolink.android.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Empty notification listener required so this app appears in
 * 「通知使用权」settings and [android.media.session.MediaSessionManager.getActiveSessions]
 * can return other apps' media sessions after the user grants access.
 *
 * We do not inspect notification content; the service exists only as the
 * BIND_NOTIFICATION_LISTENER_SERVICE identity for media session access (G1).
 */
class MediaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // no-op — media is read via MediaSessionManager, not notification payloads
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // no-op
    }
}
