package app.nekolink.android.collector

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager

/**
 * **Default production path** from [AndroidCollector] when no test injection is used:
 *
 * `MediaSessionManager.getActiveSessions(nlsComponentName)` → extract → G1 map.
 *
 * Isolated so unit tests can pass a mock [MediaSessionManager] and
 * `verify(msm).getActiveSessions(nlsComponent)` on the real shipped call.
 */
object MediaSessionManagerBridge {

    /**
     * Calls [MediaSessionManager.getActiveSessions] with a non-null NLS [ComponentName].
     * Rejects non-NLS components (same rule as [MediaSessionSamplePath]).
     */
    fun getActiveSessionFields(
        msm: MediaSessionManager,
        listenerComponent: ComponentName,
        extract: (MediaController) -> MediaSessionSamplePath.ControllerFields,
    ): List<MediaSessionSamplePath.ControllerFields> {
        MediaSessionSamplePath.requireListenerComponent(listenerComponent)
        return msm.getActiveSessions(listenerComponent).map(extract)
    }

    /**
     * Full default production composition used by [AndroidCollector.sampleMedia]:
     * packageName → NLS ComponentName → [getActiveSessionFields] → [MediaMapper].
     * Returns media + optional compressed artwork bytes (hash filled later by AgentCore).
     */
    fun sampleUsingManager(
        packageName: String,
        msm: MediaSessionManager,
        extract: (MediaController) -> MediaSessionSamplePath.ControllerFields,
        updatedAt: String,
    ): MediaSessionSamplePath.SampledMedia {
        return MediaSessionSamplePath.sampleForPackage(
            packageName = packageName,
            getActiveSessions = { cn ->
                getActiveSessionFields(msm, cn, extract)
            },
            updatedAt = updatedAt,
        )
    }
}
