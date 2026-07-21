package app.nekolink.android.collector

import android.content.ComponentName
import android.media.session.PlaybackState
import app.nekolink.android.protocol.MediaSession
import app.nekolink.android.service.MediaNotificationListener

/**
 * Shipped media sample path used by [AndroidCollector.sampleMedia].
 *
 * **Production entry:** [sampleForPackage] — builds non-null NLS [ComponentName],
 * calls [getActiveSessions] with it (production: `MediaSessionManager.getActiveSessions(cn)`),
 * then maps G1 fields via [MediaMapper].
 *
 * [getActiveSessions] is injectable so unit tests drive the **same** entry point
 * AndroidCollector uses, with a real ComponentName (Robolectric).
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

    /** Build the non-null NLS ComponentName production passes to getActiveSessions. */
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
     * **Production entry used by [app.nekolink.android.collector.AndroidCollector].**
     *
     * Builds NLS ComponentName from [packageName], then:
     * `getActiveSessions(componentName)` → select active → [MediaMapper] G1 fields.
     *
     * Production lambda:
     * `{ cn -> mediaSessionManager.getActiveSessions(cn).map(::extract) }`
     */
    fun sampleForPackage(
        packageName: String,
        getActiveSessions: (ComponentName) -> List<ControllerFields>,
        updatedAt: String,
    ): MediaSession? {
        val listener = toComponentName(packageName)
        return sample(
            listenerComponent = listener,
            getActiveSessions = getActiveSessions,
            updatedAt = updatedAt,
        )
    }

    /**
     * Lower-level entry: require [listenerComponent] is NLS, call [getActiveSessions] with it.
     * Prefer [sampleForPackage] from production code.
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
