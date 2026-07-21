package app.nekolink.android

import app.nekolink.android.collector.MediaMapper
import app.nekolink.android.protocol.PlaybackState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives shipped [MediaMapper] — the real path AndroidCollector uses after
 * MediaSessionManager.getActiveSessions(ComponentName) returns controllers.
 * Proves G1 fields: playbackState / positionMs / durationMs.
 */
class MediaMapperTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun playing_mapsG1Fields_camelCase() {
        val media = MediaMapper.toMediaSession(
            title = "song",
            artist = "artist",
            album = "album",
            sourceApp = "com.spotify.music",
            androidPlaybackState = MediaMapper.STATE_PLAYING,
            positionMs = 12_345L,
            durationMs = 200_000L,
            updatedAt = "2026-07-21T12:00:00Z",
        )
        assertNotNull(media)
        assertEquals(PlaybackState.PLAYING, media!!.playbackState)
        assertEquals(12_345L, media.positionMs)
        assertEquals(200_000L, media.durationMs)
        assertEquals("com.spotify.music", media.sourceApp)

        val text = json.encodeToString(media)
        val v = json.parseToJsonElement(text).jsonObject
        assertEquals("playing", v["playbackState"]!!.jsonPrimitive.content)
        assertEquals("12345", v["positionMs"]!!.jsonPrimitive.content)
        assertEquals("200000", v["durationMs"]!!.jsonPrimitive.content)
        assertEquals("com.spotify.music", v["sourceApp"]!!.jsonPrimitive.content)
        assertTrue(text.contains("\"playbackState\""))
        assertTrue(text.contains("\"positionMs\""))
        assertTrue(text.contains("\"durationMs\""))
    }

    @Test
    fun buffering_mapsToPlaying() {
        val media = MediaMapper.toMediaSession(
            title = "t",
            artist = null,
            album = null,
            sourceApp = "player",
            androidPlaybackState = MediaMapper.STATE_BUFFERING,
            positionMs = 0L,
            durationMs = 1000L,
            updatedAt = "2026-07-21T12:00:00Z",
        )
        assertEquals(PlaybackState.PLAYING, media!!.playbackState)
        assertEquals(0L, media.positionMs)
    }

    @Test
    fun paused_mapsPlaybackState() {
        val media = MediaMapper.toMediaSession(
            title = "t",
            artist = null,
            album = null,
            sourceApp = null,
            androidPlaybackState = MediaMapper.STATE_PAUSED,
            positionMs = 50L,
            durationMs = 100L,
            updatedAt = "2026-07-21T12:00:00Z",
        )
        assertEquals(PlaybackState.PAUSED, media!!.playbackState)
    }

    @Test
    fun blankTitle_returnsNull_honestEmpty() {
        assertNull(
            MediaMapper.toMediaSession(
                title = "  ",
                artist = "a",
                album = null,
                sourceApp = "x",
                androidPlaybackState = MediaMapper.STATE_PLAYING,
                positionMs = 1L,
                durationMs = 2L,
                updatedAt = "t",
            ),
        )
        assertNull(
            MediaMapper.toMediaSession(
                title = null,
                artist = "a",
                album = null,
                sourceApp = "x",
                androidPlaybackState = MediaMapper.STATE_PLAYING,
                positionMs = 1L,
                durationMs = 2L,
                updatedAt = "t",
            ),
        )
    }

    @Test
    fun mapPlaybackState_constantsMatchFramework() {
        // Framework: NONE=0 STOPPED=1 PAUSED=2 PLAYING=3 BUFFERING=6
        assertEquals(PlaybackState.STOPPED, MediaMapper.mapPlaybackState(0))
        assertEquals(PlaybackState.STOPPED, MediaMapper.mapPlaybackState(1))
        assertEquals(PlaybackState.PAUSED, MediaMapper.mapPlaybackState(2))
        assertEquals(PlaybackState.PLAYING, MediaMapper.mapPlaybackState(3))
        assertEquals(PlaybackState.PLAYING, MediaMapper.mapPlaybackState(6))
        assertEquals(PlaybackState.UNKNOWN, MediaMapper.mapPlaybackState(99))
    }
}
