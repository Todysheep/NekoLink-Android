package app.nekolink.android.collector

import android.content.ComponentName
import android.media.session.PlaybackState
import app.nekolink.android.protocol.MediaSession
import app.nekolink.android.service.MediaNotificationListener

/**
 * Shipped media sample path used by [AndroidCollector].
 *
 * Orchestrates:
 * 1. Require a non-null [ComponentName] for [MediaNotificationListener].
 * 2. Call [getActiveSessions] with that ComponentName (production:
 *    `MediaSessionManager.getActiveSessions(cn)` — never null).
 * 3. Prefer playing/paused/buffering sessions.
 * 4. Map through [MediaMapper] → G1 media (playbackState / positionMs / durationMs).
 *
 * [getActiveSessions] is injectable so tests drive the real entry with a real
 * [ComponentName] (Robolectric) and assert the NLS identity.
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

    fun listenerComponentName(
        packageName: String,
        listenerClassName: String = LISTENER_CLASS_NAME,
    ): Pair<String, String> {
        require(isNotificationListenerClass(listenerClassName)) {
            "getActiveSessions requires MediaNotificationListener, got: $listenerClassName"
        }
        require(packageName.isNotBlank()) { "packageName blank" }
        return packageName to listenerClassName
    }

    /** Production + tests: build the NLS ComponentName passed to getActiveSessions. */
    fun toComponentName(
        packageName: String,
        listenerClassName: String = LISTENER_CLASS_NAME,
    ): ComponentName {
        val (pkg, cls) = listenerComponentName(packageName, listenerClassName)
        return ComponentName(pkg, cls)
    }

    fun requireListenerComponent(listenerComponent: ComponentName) {
        val cls = listenerComponent.className
        require(isNotificationListenerClass(cls)) {
            "getActiveSessions requires MediaNotificationListener ComponentName, got: $cls"
        }
    }

    /**
     * Production entry for media sampling.
     *
     * @param listenerComponent non-null NLS component (never null — API contract)
     * @param getActiveSessions production: `{ cn -> msm.getActiveSessions(cn).map(...) }`
     */
    fun sample(
        listenerComponent: ComponentName,
        getActiveSessions: (ComponentName) -> List<ControllerFields>,
        updatedAt: String,
    ): MediaSession? {
        requireListenerComponent(listenerComponent)
        val sessions = try {
            getActiveSessions(listenerComponent)
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
