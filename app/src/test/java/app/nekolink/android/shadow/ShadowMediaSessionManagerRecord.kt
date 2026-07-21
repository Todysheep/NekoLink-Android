package app.nekolink.android.shadow

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric shadow of the **real** [MediaSessionManager] production uses via
 * `context.getSystemService(MEDIA_SESSION_SERVICE)`.
 *
 * Records the [ComponentName] passed to [getActiveSessions] so tests can assert
 * the shipped path called it with [app.nekolink.android.service.MediaNotificationListener]
 * without wrapping Context or injecting alternate loaders into [app.nekolink.android.collector.AndroidCollector].
 */
@Implements(MediaSessionManager::class)
class ShadowMediaSessionManagerRecord {

    @Implementation
    fun getActiveSessions(notificationListener: ComponentName?): MutableList<MediaController> {
        callCount++
        lastNotificationListener = notificationListener
        return ArrayList(controllersToReturn)
    }

    companion object {
        @JvmStatic
        var lastNotificationListener: ComponentName? = null

        @JvmStatic
        var callCount: Int = 0

        @JvmStatic
        var controllersToReturn: List<MediaController> = emptyList()

        @JvmStatic
        fun reset() {
            lastNotificationListener = null
            callCount = 0
            controllersToReturn = emptyList()
        }
    }
}
