package app.nekolink.android.collector

import app.nekolink.android.protocol.MediaSession
import app.nekolink.android.protocol.PlaybackState

/**
 * Pure mapping from Android media session fields → protocol MediaSession (G1).
 * Unit-testable without framework MediaController.
 *
 * Android PlaybackState constants (framework):
 *  NONE=0, STOPPED=1, PAUSED=2, PLAYING=3, BUFFERING=6, ...
 */
object MediaMapper {
    const val STATE_NONE = 0
    const val STATE_STOPPED = 1
    const val STATE_PAUSED = 2
    const val STATE_PLAYING = 3
    const val STATE_BUFFERING = 6

    fun mapPlaybackState(androidState: Int?): PlaybackState = when (androidState) {
        STATE_PLAYING, STATE_BUFFERING -> PlaybackState.PLAYING
        STATE_PAUSED -> PlaybackState.PAUSED
        STATE_STOPPED, STATE_NONE -> PlaybackState.STOPPED
        else -> PlaybackState.UNKNOWN
    }

    /**
     * Build protocol media from extracted controller fields.
     * Returns null when title is blank (honest empty — no fabricated now-playing).
     */
    fun toMediaSession(
        title: String?,
        artist: String?,
        album: String?,
        sourceApp: String?,
        androidPlaybackState: Int?,
        positionMs: Long?,
        durationMs: Long?,
        updatedAt: String,
        artworkUrl: String? = null,
        artworkHash: String? = null,
    ): MediaSession? {
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return null
        val pos = positionMs?.takeIf { it >= 0 }
        val dur = durationMs?.takeIf { it > 0 }
        return MediaSession(
            title = t,
            artist = artist?.takeIf { it.isNotBlank() },
            album = album?.takeIf { it.isNotBlank() },
            sourceApp = sourceApp?.takeIf { it.isNotBlank() },
            artworkUrl = artworkUrl,
            artworkHash = artworkHash,
            playbackState = mapPlaybackState(androidPlaybackState),
            positionMs = pos,
            durationMs = dur,
            updatedAt = updatedAt,
        )
    }
}
