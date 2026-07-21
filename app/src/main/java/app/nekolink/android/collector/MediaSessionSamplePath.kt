package app.nekolink.android.collector

import android.content.ComponentName
import android.media.session.PlaybackState
import app.nekolink.android.protocol.MediaSession
import app.nekolink.android.service.MediaNotificationListener

/**
 * Shipped media sample path used by [AndroidCollector].
 *
 * Orchestrates:
 * 1. Require the registered [MediaNotificationListener] class name (NLS identity).
 * 2. Invoke [fetchSessions] with that class name — production builds
 *    `ComponentName(pkg, className)` and calls `MediaSessionManager.getActiveSessions(cn)`.
 * 3. Prefer playing/paused/buffering sessions.
 * 4. Map through [MediaMapper] → G1 media (playbackState / positionMs / durationMs).
 *
 * [fetchSessions] is injectable so JVM unit tests drive this real entry without Robolectric.
 */
object MediaSessionSamplePath {

    /** Fully-qualified class name of the empty NotificationListenerService in the manifest. */
    val LISTENER_CLASS_NAME: String = MediaNotificationListener::class.java.name

    const val LISTENER_CLASS_SIMPLE = "MediaNotificationListener"

    data class ControllerFields(
        val packageName: String?,
        val title: String?,
        val artist: String?,
        val album: String?,
        val androidPlaybackState: Int?,
        val positionMs: Long?,
        val durationMs: Long?,
    )

    fun isNotificationListenerClass(className: String): Boolean {
        val c = className.trim()
        return c == LISTENER_CLASS_NAME || c.endsWith(".$LISTENER_CLASS_SIMPLE")
    }

    /**
     * Build the ComponentName production uses for getActiveSessions.
     * Pure string assembly — no ComponentName method reads required by callers who only need
     * the (package, class) pair; Android runtime constructs the real object.
     */
    fun listenerComponentName(packageName: String, listenerClassName: String = LISTENER_CLASS_NAME): Pair<String, String> {
        require(isNotificationListenerClass(listenerClassName)) {
            "getActiveSessions requires MediaNotificationListener, got: $listenerClassName"
        }
        require(packageName.isNotBlank()) { "packageName blank" }
        return packageName to listenerClassName
    }

    fun toComponentName(packageName: String, listenerClassName: String = LISTENER_CLASS_NAME): ComponentName {
        val (pkg, cls) = listenerComponentName(packageName, listenerClassName)
        return ComponentName(pkg, cls)
    }

    /**
     * Production entry for media sampling.
     *
     * @param listenerClassName must be [LISTENER_CLASS_NAME]
     * @param fetchSessions production: build ComponentName + msm.getActiveSessions(cn)
     *        (signature receives the validated NLS class name so tests can assert it)
     */
    fun sample(
        listenerClassName: String,
        fetchSessions: (listenerClassName: String) -> List<ControllerFields>,
        updatedAt: String,
    ): MediaSession? {
        require(isNotificationListenerClass(listenerClassName)) {
            "getActiveSessions requires MediaNotificationListener ComponentName class, got: $listenerClassName"
        }
        val sessions = try {
            fetchSessions(listenerClassName)
        } catch (_: SecurityException) {
            return null
        }
        val active = selectActive(sessions) ?: return null
        return MediaMapper.toMediaSession(
            title = active.title,
            artist = active.artist,
            album = active.album,
            sourceApp = active.packageName,
            androidPlaybackState = active.androidPlaybackState,
            positionMs = active.positionMs,
            durationMs = active.durationMs,
            updatedAt = updatedAt,
        )
    }

    fun selectActive(sessions: List<ControllerFields>): ControllerFields? {
        if (sessions.isEmpty()) return null
        return sessions.firstOrNull { c ->
            val st = c.androidPlaybackState
            st == PlaybackState.STATE_PLAYING ||
                st == PlaybackState.STATE_PAUSED ||
                st == PlaybackState.STATE_BUFFERING
        } ?: sessions.firstOrNull()
    }
}
