package app.nekolink.android

import android.media.session.PlaybackState as AndroidPlaybackState
import app.nekolink.android.collector.MediaMapper
import app.nekolink.android.collector.MediaSessionSamplePath
import app.nekolink.android.protocol.PlaybackState
import app.nekolink.android.service.MediaNotificationListener
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the **shipped** [MediaSessionSamplePath.sample] entry used by AndroidCollector:
 * validated NLS class name → fetchSessions(className) [production: getActiveSessions(ComponentName)]
 * → MediaMapper G1 fields with framework [AndroidPlaybackState] constants.
 */
class MediaSessionSamplePathTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val nlsClass = MediaSessionSamplePath.LISTENER_CLASS_NAME

    @Test
    fun listenerClass_isMediaNotificationListener() {
        assertEquals(MediaNotificationListener::class.java.name, nlsClass)
        assertTrue(MediaSessionSamplePath.isNotificationListenerClass(nlsClass))
        assertFalse(MediaSessionSamplePath.isNotificationListenerClass("other.FakeListener"))
        val pair = MediaSessionSamplePath.listenerComponentName("app.nekolink.android", nlsClass)
        assertEquals("app.nekolink.android", pair.first)
        assertEquals(nlsClass, pair.second)
    }

    @Test
    fun sample_callsFetchWithNlsClassName_mapsG1() {
        var capturedClass: String? = null
        var fetchCount = 0
        val media = MediaSessionSamplePath.sample(
            listenerClassName = nlsClass,
            fetchSessions = { className ->
                capturedClass = className
                fetchCount++
                // Production would: ComponentName(pkg, className) then msm.getActiveSessions(cn)
                val (pkg, cls) = MediaSessionSamplePath.listenerComponentName("app.nekolink.android", className)
                assertEquals(nlsClass, cls)
                assertEquals("app.nekolink.android", pkg)
                listOf(
                    MediaSessionSamplePath.ControllerFields(
                        packageName = "com.spotify.music",
                        title = "song",
                        artist = "artist",
                        album = "album",
                        androidPlaybackState = AndroidPlaybackState.STATE_PLAYING,
                        positionMs = 12_345L,
                        durationMs = 200_000L,
                    ),
                )
            },
            updatedAt = "2026-07-21T12:00:00Z",
        )
        assertEquals(1, fetchCount)
        assertEquals(nlsClass, capturedClass)
        assertNotNull(media)
        assertEquals(PlaybackState.PLAYING, media!!.playbackState)
        assertEquals(12_345L, media.positionMs)
        assertEquals(200_000L, media.durationMs)
        assertEquals("com.spotify.music", media.sourceApp)
        assertEquals("song", media.title)

        val wire = json.encodeToString(media)
        val v = json.parseToJsonElement(wire).jsonObject
        assertEquals("playing", v["playbackState"]!!.jsonPrimitive.content)
        assertEquals("12345", v["positionMs"]!!.jsonPrimitive.content)
        assertEquals("200000", v["durationMs"]!!.jsonPrimitive.content)
    }

    @Test
    fun sample_prefersPlayingOverIdle() {
        val media = MediaSessionSamplePath.sample(
            listenerClassName = nlsClass,
            fetchSessions = {
                listOf(
                    MediaSessionSamplePath.ControllerFields(
                        packageName = "idle.app",
                        title = "idle",
                        artist = null,
                        album = null,
                        androidPlaybackState = AndroidPlaybackState.STATE_NONE,
                        positionMs = 0L,
                        durationMs = 1L,
                    ),
                    MediaSessionSamplePath.ControllerFields(
                        packageName = "com.player",
                        title = "now",
                        artist = "a",
                        album = null,
                        androidPlaybackState = AndroidPlaybackState.STATE_PLAYING,
                        positionMs = 99L,
                        durationMs = 1000L,
                    ),
                )
            },
            updatedAt = "t",
        )
        assertEquals("now", media!!.title)
        assertEquals(99L, media.positionMs)
        assertEquals(PlaybackState.PLAYING, media.playbackState)
    }

    @Test
    fun sample_paused_mapsG1_withFrameworkConstant() {
        val media = MediaSessionSamplePath.sample(
            listenerClassName = nlsClass,
            fetchSessions = {
                listOf(
                    MediaSessionSamplePath.ControllerFields(
                        packageName = "p",
                        title = "paused-track",
                        artist = null,
                        album = null,
                        androidPlaybackState = AndroidPlaybackState.STATE_PAUSED,
                        positionMs = 50L,
                        durationMs = 500L,
                    ),
                )
            },
            updatedAt = "t",
        )
        assertEquals(PlaybackState.PAUSED, media!!.playbackState)
        assertEquals(50L, media.positionMs)
        assertEquals(500L, media.durationMs)
    }

    @Test
    fun sample_securityException_returnsNull() {
        val media = MediaSessionSamplePath.sample(
            listenerClassName = nlsClass,
            fetchSessions = { throw SecurityException("no nls") },
            updatedAt = "t",
        )
        assertNull(media)
    }

    @Test
    fun sample_emptySessions_returnsNull() {
        val media = MediaSessionSamplePath.sample(
            listenerClassName = nlsClass,
            fetchSessions = { emptyList() },
            updatedAt = "t",
        )
        assertNull(media)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sample_rejectsNonNlsClass() {
        MediaSessionSamplePath.sample(
            listenerClassName = "other.pkg.FakeListener",
            fetchSessions = { emptyList() },
            updatedAt = "t",
        )
    }

    @Test
    fun mapPlaybackState_usesFrameworkConstants_viaShippedMapper() {
        assertEquals(PlaybackState.PLAYING, MediaMapper.mapPlaybackState(AndroidPlaybackState.STATE_PLAYING))
        assertEquals(PlaybackState.PLAYING, MediaMapper.mapPlaybackState(AndroidPlaybackState.STATE_BUFFERING))
        assertEquals(PlaybackState.PAUSED, MediaMapper.mapPlaybackState(AndroidPlaybackState.STATE_PAUSED))
        assertEquals(PlaybackState.STOPPED, MediaMapper.mapPlaybackState(AndroidPlaybackState.STATE_STOPPED))
        assertEquals(PlaybackState.STOPPED, MediaMapper.mapPlaybackState(AndroidPlaybackState.STATE_NONE))
    }
}
