package app.nekolink.android

import android.content.ComponentName
import android.content.Context
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Drives **shipped** [AndroidCollector] with the **pure default constructor**
 * (`AndroidCollector(context)` — no `activeMediaSessions` inject).
 *
 * Installs a mock [MediaSessionManager] via Robolectric
 * `ShadowApplication.setSystemService`, so production code path:
 *
 * `sample()` → `sampleMedia()` → `MediaSessionManagerBridge.sampleUsingManager`
 * → `msm.getActiveSessions(nlsComponent)` is exercised for real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidCollectorDefaultMediaPathTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun installMockMsm(msm: MediaSessionManager): Context {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).setSystemService(Context.MEDIA_SESSION_SERVICE, msm)
        return app
    }

    @Test
    fun sample_pureDefaultCtor_callsMsmGetActiveSessions_withNlsComponent() {
        val msm = mock<MediaSessionManager>()
        whenever(msm.getActiveSessions(any())).thenReturn(emptyList())
        val context = installMockMsm(msm)

        // Pure production construction — only context, no media inject hook
        val collector = AndroidCollector(context)
        val sample = collector.sample()

        val captor = argumentCaptor<ComponentName>()
        verify(msm).getActiveSessions(captor.capture())
        val passed = captor.firstValue
        assertNotNull(passed)
        assertEquals(MediaNotificationListener::class.java.name, passed.className)
        assertEquals(context.packageName, passed.packageName)
        assertNull(sample.media)
    }

    @Test
    fun sample_pureDefaultCtor_mapsG1Fields_fromMsmControllers() {
        val msm = mock<MediaSessionManager>()
        val controller = mock<MediaController>()
        val metadata = mock<MediaMetadata>()
        val playback = mock<AndroidPlaybackState>()

        whenever(metadata.getString(MediaMetadata.METADATA_KEY_TITLE)).thenReturn("shadow-default-track")
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)).thenReturn(null)
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)).thenReturn("artist")
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)).thenReturn(null)
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)).thenReturn("album")
        whenever(metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)).thenReturn(99_000L)
        whenever(playback.state).thenReturn(AndroidPlaybackState.STATE_PLAYING)
        whenever(playback.position).thenReturn(3_333L)
        whenever(controller.packageName).thenReturn("com.music.app")
        whenever(controller.metadata).thenReturn(metadata)
        whenever(controller.playbackState).thenReturn(playback)
        whenever(msm.getActiveSessions(any())).thenReturn(listOf(controller))

        val context = installMockMsm(msm)
        val collector = AndroidCollector(context)
        val sample = collector.sample()

        val captor = argumentCaptor<ComponentName>()
        verify(msm).getActiveSessions(captor.capture())
        assertEquals(MediaNotificationListener::class.java.name, captor.firstValue.className)
        assertEquals(context.packageName, captor.firstValue.packageName)

        assertNotNull(sample.media)
        val media = sample.media!!
        assertEquals(PlaybackState.PLAYING, media.playbackState)
        assertEquals(3_333L, media.positionMs)
        assertEquals(99_000L, media.durationMs)
        assertEquals("shadow-default-track", media.title)
        assertEquals("com.music.app", media.sourceApp)

        val wire = json.encodeToString(media)
        val obj = json.parseToJsonElement(wire).jsonObject
        assertEquals("playing", obj["playbackState"]!!.jsonPrimitive.content)
        assertEquals("3333", obj["positionMs"]!!.jsonPrimitive.content)
        assertEquals("99000", obj["durationMs"]!!.jsonPrimitive.content)
        assertTrue(wire.contains("\"playbackState\""))
        assertTrue(wire.contains("\"positionMs\""))
        assertTrue(wire.contains("\"durationMs\""))
    }

    @Test
    fun sample_pureDefaultCtor_securityException_nullMedia() {
        val msm = mock<MediaSessionManager>()
        whenever(msm.getActiveSessions(any())).thenThrow(SecurityException("no nls grant"))
        val context = installMockMsm(msm)

        val collector = AndroidCollector(context)
        val sample = collector.sample()
        verify(msm).getActiveSessions(any())
        assertNull(sample.media)
    }
}
