package app.nekolink.android

import android.content.ComponentName
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives shipped [MediaSessionSamplePath.sample] with a **real non-null**
 * [ComponentName] for [MediaNotificationListener], simulating
 * `MediaSessionManager.getActiveSessions(cn)` (never null).
 *
 * Robolectric provides working ComponentName getters on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MediaSessionSamplePathTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val packageName = "app.nekolink.android"
    private val listener: ComponentName =
        MediaSessionSamplePath.toComponentName(packageName)

    @Test
    fun toComponentName_isNonNullNls() {
        assertNotNull(listener)
        assertEquals(packageName, listener.packageName)
        assertEquals(MediaNotificationListener::class.java.name, listener.className)
        assertTrue(MediaSessionSamplePath.isNotificationListenerClass(listener.className))
        assertFalse(MediaSessionSamplePath.isNotificationListenerClass("other.FakeListener"))
    }

    @Test
    fun sampleForPackage_callsGetActiveSessions_withNonNullNlsComponentName() {
        var captured: ComponentName? = null
        var callCount = 0
        // Same production entry AndroidCollector.sampleMedia uses
        val sampled = MediaSessionSamplePath.sampleForPackage(
            packageName = packageName,
            getActiveSessions = { cn ->
                // Production: msm.getActiveSessions(cn) — cn must be NLS ComponentName
                captured = cn
                callCount++
                assertNotNull("getActiveSessions must not receive null ComponentName", cn)
                assertEquals(MediaNotificationListener::class.java.name, cn.className)
                assertEquals(packageName, cn.packageName)
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
        assertEquals(1, callCount)
        assertNotNull(captured)
        assertEquals(listener.flattenToString(), captured!!.flattenToString())
        val media = sampled.media
        assertNotNull(media)
        assertNull(sampled.artworkBytes)
        assertNull(media!!.artworkHash)
        assertEquals(PlaybackState.PLAYING, media.playbackState)
        assertEquals(12_345L, media.positionMs)
        assertEquals(200_000L, media.durationMs)
        assertEquals("com.spotify.music", media.sourceApp)

        val wire = json.encodeToString(media)
        val v = json.parseToJsonElement(wire).jsonObject
        assertEquals("playing", v["playbackState"]!!.jsonPrimitive.content)
        assertEquals("12345", v["positionMs"]!!.jsonPrimitive.content)
        assertEquals("200000", v["durationMs"]!!.jsonPrimitive.content)
    }

    @Test
    fun sample_prefersPlayingOverIdle() {
        val sampled = MediaSessionSamplePath.sample(
            listenerComponent = listener,
            getActiveSessions = {
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
        val media = sampled.media
        assertEquals("now", media!!.title)
        assertEquals(99L, media.positionMs)
        assertEquals(PlaybackState.PLAYING, media.playbackState)
    }

    @Test
    fun sample_paused_mapsG1_withFrameworkConstant() {
        val sampled = MediaSessionSamplePath.sample(
            listenerComponent = listener,
            getActiveSessions = {
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
        val media = sampled.media
        assertEquals(PlaybackState.PAUSED, media!!.playbackState)
        assertEquals(50L, media.positionMs)
        assertEquals(500L, media.durationMs)
    }

    @Test
    fun sample_securityException_returnsNull() {
        val sampled = MediaSessionSamplePath.sample(
            listenerComponent = listener,
            getActiveSessions = { throw SecurityException("no nls") },
            updatedAt = "t",
        )
        assertNull(sampled.media)
        assertNull(sampled.artworkBytes)
    }

    @Test
    fun sample_emptySessions_returnsNull() {
        val sampled = MediaSessionSamplePath.sample(
            listenerComponent = listener,
            getActiveSessions = { emptyList() },
            updatedAt = "t",
        )
        assertNull(sampled.media)
        assertNull(sampled.artworkBytes)
    }

    @Test
    fun sample_carriesArtworkBytes_hashNullUntilEnsure() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        val sampled = MediaSessionSamplePath.sample(
            listenerComponent = listener,
            getActiveSessions = {
                listOf(
                    MediaSessionSamplePath.ControllerFields(
                        packageName = "p",
                        title = "with-art",
                        artist = null,
                        album = null,
                        androidPlaybackState = AndroidPlaybackState.STATE_PLAYING,
                        positionMs = 1L,
                        durationMs = 2L,
                        artworkBytes = png,
                    ),
                )
            },
            updatedAt = "t",
        )
        assertNotNull(sampled.media)
        assertNull(sampled.media!!.artworkHash)
        assertTrue(sampled.artworkBytes.contentEquals(png))
    }

    @Test(expected = IllegalArgumentException::class)
    fun sample_rejectsNonNlsComponent() {
        MediaSessionSamplePath.sample(
            listenerComponent = ComponentName("other.pkg", "other.FakeListener"),
            getActiveSessions = { emptyList() },
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
