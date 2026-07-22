package app.nekolink.android

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState as AndroidPlaybackState
import app.nekolink.android.collector.MediaSessionManagerBridge
import app.nekolink.android.collector.MediaSessionSamplePath
import app.nekolink.android.protocol.PlaybackState
import app.nekolink.android.service.MediaNotificationListener
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the **default production** media path:
 * [MediaSessionManagerBridge.sampleUsingManager] /
 * [MediaSessionManagerBridge.getActiveSessionFields]
 *
 * Uses a mock [MediaSessionManager] and verifies the real call is
 * `getActiveSessions(nlsComponentName)` — never null, always [MediaNotificationListener].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MediaSessionManagerBridgeTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val appPackage = "app.nekolink.android"
    private val nls: ComponentName =
        MediaSessionSamplePath.toComponentName(appPackage)

    @Test
    fun getActiveSessionFields_callsMsmGetActiveSessions_withNlsComponent() {
        val msm = mock<MediaSessionManager>()
        whenever(msm.getActiveSessions(any())).thenReturn(emptyList())

        val fields = MediaSessionManagerBridge.getActiveSessionFields(
            msm = msm,
            listenerComponent = nls,
            extract = { error("no controllers expected") },
        )
        assertTrue(fields.isEmpty())

        val captor = argumentCaptor<ComponentName>()
        verify(msm).getActiveSessions(captor.capture())
        val passed = captor.firstValue
        assertNotNull(passed)
        assertEquals(MediaNotificationListener::class.java.name, passed.className)
        assertEquals(appPackage, passed.packageName)
        assertEquals(nls.flattenToString(), passed.flattenToString())
    }

    @Test
    fun sampleUsingManager_callsGetActiveSessions_withNls_andMapsG1() {
        val msm = mock<MediaSessionManager>()
        val controller = mock<MediaController>()
        val metadata = mock<MediaMetadata>()
        val playback = mock<AndroidPlaybackState>()

        whenever(metadata.getString(MediaMetadata.METADATA_KEY_TITLE)).thenReturn("bridge-song")
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)).thenReturn(null)
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)).thenReturn("bridge-artist")
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)).thenReturn(null)
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)).thenReturn("bridge-album")
        whenever(metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)).thenReturn(333_000L)
        whenever(playback.state).thenReturn(AndroidPlaybackState.STATE_PLAYING)
        whenever(playback.position).thenReturn(7_777L)
        whenever(controller.packageName).thenReturn("com.player.app")
        whenever(controller.metadata).thenReturn(metadata)
        whenever(controller.playbackState).thenReturn(playback)
        whenever(msm.getActiveSessions(any())).thenReturn(listOf(controller))

        val sampled = MediaSessionManagerBridge.sampleUsingManager(
            packageName = appPackage,
            msm = msm,
            extract = { c ->
                // Same extraction shape AndroidCollector uses
                val meta = c.metadata
                MediaSessionSamplePath.ControllerFields(
                    packageName = c.packageName,
                    title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                        ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                    artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                    album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                    androidPlaybackState = c.playbackState?.state,
                    positionMs = c.playbackState?.position,
                    durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION),
                )
            },
            updatedAt = "2026-07-21T16:00:00Z",
        )

        val captor = argumentCaptor<ComponentName>()
        verify(msm).getActiveSessions(captor.capture())
        assertEquals(MediaNotificationListener::class.java.name, captor.firstValue.className)
        assertEquals(appPackage, captor.firstValue.packageName)

        val media = sampled.media
        assertNotNull(media)
        assertNull(sampled.artworkBytes)
        assertEquals(PlaybackState.PLAYING, media!!.playbackState)
        assertEquals(7_777L, media.positionMs)
        assertEquals(333_000L, media.durationMs)
        assertEquals("bridge-song", media.title)
        assertEquals("com.player.app", media.sourceApp)

        val wire = json.encodeToString(media)
        val obj = json.parseToJsonElement(wire).jsonObject
        assertEquals("playing", obj["playbackState"]!!.jsonPrimitive.content)
        assertEquals("7777", obj["positionMs"]!!.jsonPrimitive.content)
        assertEquals("333000", obj["durationMs"]!!.jsonPrimitive.content)
    }

    @Test
    fun sampleUsingManager_emptyControllers_returnsNull() {
        val msm = mock<MediaSessionManager>()
        whenever(msm.getActiveSessions(any())).thenReturn(emptyList())
        val sampled = MediaSessionManagerBridge.sampleUsingManager(
            packageName = appPackage,
            msm = msm,
            extract = { error("none") },
            updatedAt = "t",
        )
        assertNull(sampled.media)
        assertNull(sampled.artworkBytes)
        verify(msm).getActiveSessions(any())
    }

    @Test(expected = IllegalArgumentException::class)
    fun getActiveSessionFields_rejectsNonNlsComponent() {
        val msm = mock<MediaSessionManager>()
        MediaSessionManagerBridge.getActiveSessionFields(
            msm = msm,
            listenerComponent = ComponentName("x", "y.Fake"),
            extract = { error("none") },
        )
    }
}
