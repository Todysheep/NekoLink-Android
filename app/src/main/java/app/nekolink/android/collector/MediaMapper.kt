package app.nekolink.android.collector

import android.media.session.PlaybackState as AndroidPlaybackState
import app.nekolink.android.protocol.MediaSession
import app.nekolink.android.protocol.PlaybackState

/**
 * Pure mapping from Android media session fields → protocol MediaSession (G1).
 * Uses **framework** [AndroidPlaybackState] constants (not duplicated magic numbers).
 * Called by [MediaSessionSamplePath] after getActiveSessions(ComponentName).
 */
object MediaMapper {

    fun mapPlaybackState(androidState: Int?): PlaybackState = when (androidState) {
        AndroidPlaybackState.STATE_PLAYING,
        AndroidPlaybackState.STATE_BUFFERING,
        -> PlaybackState.PLAYING

        AndroidPlaybackState.STATE_PAUSED -> PlaybackState.PAUSED

        AndroidPlaybackState.STATE_STOPPED,
        AndroidPlaybackState.STATE_NONE,
        -> PlaybackState.STOPPED

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
