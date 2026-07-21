package app.nekolink.android

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState as AndroidPlaybackState
import app.nekolink.android.collector.AndroidCollector
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Drives the **shipped** [AndroidCollector.sample] entry on the **sole** production media path
 * (no inject alternate):
 *
 * ```
 * AndroidCollector(context).sample()
 *   → sampleMedia()
 *     → context.getSystemService(MEDIA_SESSION_SERVICE)   // real override via ContextWrapper
 *     → MediaSessionManagerBridge.sampleUsingManager
 *       → msm.getActiveSessions(MediaNotificationListener ComponentName)
 *       → MediaMapper G1 fields
 * ```
 *
 * Uses a real [ContextWrapper] override (not Mockito spy of final getSystemService) so
 * the call into mock [MediaSessionManager] is deterministic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidCollectorMediaWireTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Real subclass override — shipped sampleMedia reads this MSM. */
    private fun contextWithMsm(msm: MediaSessionManager): Context {
        val base = RuntimeEnvironment.getApplication()
        return object : ContextWrapper(base) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.MEDIA_SESSION_SERVICE) return msm
                return super.getSystemService(name)
            }
        }
    }

    @Test
    fun sample_drivesProductionMediaPath_getActiveSessionsWithNlsComponent() {
        val msm = mock<MediaSessionManager>()
        whenever(msm.getActiveSessions(any())).thenReturn(emptyList())

        // Sole production construction
        val collector = AndroidCollector(contextWithMsm(msm))
        val sample = collector.sample()

        val captor = argumentCaptor<ComponentName>()
        verify(msm).getActiveSessions(captor.capture())
        val nls = captor.firstValue
        assertNotNull("shipped sampleMedia must call getActiveSessions with non-null ComponentName", nls)
        assertEquals(MediaNotificationListener::class.java.name, nls.className)
        assertEquals(RuntimeEnvironment.getApplication().packageName, nls.packageName)
        assertNull(sample.media)

        // No inject field on collector
        assertTrue(
            AndroidCollector::class.java.declaredFields.none {
                it.name.contains("activeMedia", ignoreCase = true)
            },
        )
    }

    @Test
    fun sample_mapsG1Fields_afterGetActiveSessionsFromShippedCollector() {
        val msm = mock<MediaSessionManager>()
        val controller = mock<MediaController>()
        val metadata = mock<MediaMetadata>()
        val playback = mock<AndroidPlaybackState>()

        whenever(metadata.getString(MediaMetadata.METADATA_KEY_TITLE)).thenReturn("wire-track")
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)).thenReturn(null)
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)).thenReturn("artist")
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)).thenReturn(null)
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)).thenReturn("album")
        whenever(metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)).thenReturn(120_000L)
        whenever(playback.state).thenReturn(AndroidPlaybackState.STATE_PLAYING)
        whenever(playback.position).thenReturn(8_888L)
        whenever(controller.packageName).thenReturn("com.wire.player")
        whenever(controller.metadata).thenReturn(metadata)
        whenever(controller.playbackState).thenReturn(playback)
        whenever(msm.getActiveSessions(any())).thenReturn(listOf(controller))

        val sample = AndroidCollector(contextWithMsm(msm)).sample()

        val captor = argumentCaptor<ComponentName>()
        verify(msm).getActiveSessions(captor.capture())
        assertEquals(MediaNotificationListener::class.java.name, captor.firstValue.className)

        assertNotNull(sample.media)
        val media = sample.media!!
        assertEquals(PlaybackState.PLAYING, media.playbackState)
        assertEquals(8_888L, media.positionMs)
        assertEquals(120_000L, media.durationMs)
        assertEquals("wire-track", media.title)
        assertEquals("com.wire.player", media.sourceApp)

        val wire = json.encodeToString(media)
        val obj = json.parseToJsonElement(wire).jsonObject
        assertEquals("playing", obj["playbackState"]!!.jsonPrimitive.content)
        assertEquals("8888", obj["positionMs"]!!.jsonPrimitive.content)
        assertEquals("120000", obj["durationMs"]!!.jsonPrimitive.content)
        assertTrue(wire.contains("\"playbackState\""))
        assertTrue(wire.contains("\"positionMs\""))
        assertTrue(wire.contains("\"durationMs\""))
    }

    @Test
    fun sample_securityException_fromGetActiveSessions_nullMedia() {
        val msm = mock<MediaSessionManager>()
        whenever(msm.getActiveSessions(any())).thenThrow(SecurityException("nls denied"))

        val sample = AndroidCollector(contextWithMsm(msm)).sample()
        verify(msm).getActiveSessions(any())
        assertNull(sample.media)
    }
}
