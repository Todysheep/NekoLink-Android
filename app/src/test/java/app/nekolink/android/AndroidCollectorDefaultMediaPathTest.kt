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
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Drives **shipped** [AndroidCollector.sample] on the **default** production media path:
 * `activeMediaSessions == null` → [app.nekolink.android.collector.MediaSessionManagerBridge.sampleUsingManager]
 * → `MediaSessionManager.getActiveSessions(nlsComponent)` (never null).
 *
 * This is not the inject-hook path; it proves the real collector default wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidCollectorDefaultMediaPathTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

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
    fun sample_defaultPath_callsMsmGetActiveSessions_withNlsComponent_neverNull() {
        val msm = mock<MediaSessionManager>()
        whenever(msm.getActiveSessions(any())).thenReturn(emptyList())

        // No activeMediaSessions inject — default production path only
        val collector = AndroidCollector(context = contextWithMsm(msm))
        val sample = collector.sample()

        val captor = argumentCaptor<ComponentName>()
        verify(msm).getActiveSessions(captor.capture())
        val passed = captor.firstValue
        assertNotNull("default path must not pass null ComponentName", passed)
        assertEquals(MediaNotificationListener::class.java.name, passed.className)
        assertEquals(RuntimeEnvironment.getApplication().packageName, passed.packageName)
        assertNull("empty sessions → honest null media", sample.media)
    }

    @Test
    fun sample_defaultPath_mapsG1Fields_fromMsmControllers() {
        val msm = mock<MediaSessionManager>()
        val controller = mock<MediaController>()
        val metadata = mock<MediaMetadata>()
        val playback = mock<AndroidPlaybackState>()

        whenever(metadata.getString(MediaMetadata.METADATA_KEY_TITLE)).thenReturn("default-path-track")
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)).thenReturn(null)
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)).thenReturn("artist")
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)).thenReturn(null)
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)).thenReturn("album")
        whenever(metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)).thenReturn(111_000L)
        whenever(playback.state).thenReturn(AndroidPlaybackState.STATE_PLAYING)
        whenever(playback.position).thenReturn(2_222L)
        whenever(controller.packageName).thenReturn("com.music.app")
        whenever(controller.metadata).thenReturn(metadata)
        whenever(controller.playbackState).thenReturn(playback)
        whenever(msm.getActiveSessions(any())).thenReturn(listOf(controller))

        val collector = AndroidCollector(context = contextWithMsm(msm))
        val sample = collector.sample()

        val captor = argumentCaptor<ComponentName>()
        verify(msm).getActiveSessions(captor.capture())
        assertEquals(MediaNotificationListener::class.java.name, captor.firstValue.className)

        assertNotNull(sample.media)
        val media = sample.media!!
        assertEquals(PlaybackState.PLAYING, media.playbackState)
        assertEquals(2_222L, media.positionMs)
        assertEquals(111_000L, media.durationMs)
        assertEquals("default-path-track", media.title)
        assertEquals("com.music.app", media.sourceApp)

        val wire = json.encodeToString(media)
        val obj = json.parseToJsonElement(wire).jsonObject
        assertEquals("playing", obj["playbackState"]!!.jsonPrimitive.content)
        assertEquals("2222", obj["positionMs"]!!.jsonPrimitive.content)
        assertEquals("111000", obj["durationMs"]!!.jsonPrimitive.content)
        assertTrue(wire.contains("\"playbackState\""))
        assertTrue(wire.contains("\"positionMs\""))
        assertTrue(wire.contains("\"durationMs\""))
    }

    @Test
    fun sample_defaultPath_securityException_doesNotCrash_nullMedia() {
        val msm = mock<MediaSessionManager>()
        whenever(msm.getActiveSessions(any())).thenThrow(SecurityException("no nls grant"))

        val collector = AndroidCollector(context = contextWithMsm(msm))
        val sample = collector.sample()
        // getActiveSessions was attempted with NLS (SecurityException from MSM)
        verify(msm).getActiveSessions(any())
        assertNull(sample.media)
    }
}
