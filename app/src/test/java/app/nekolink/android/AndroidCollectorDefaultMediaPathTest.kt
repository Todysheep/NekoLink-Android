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
import org.mockito.InOrder
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * End-to-end proof of the **sole** production media chain on [AndroidCollector]:
 *
 * ```
 * AndroidCollector(context)
 *   .sample()
 *     → sampleMedia()
 *       → context.getSystemService(MEDIA_SESSION_SERVICE)
 *       → MediaSessionManagerBridge.sampleUsingManager
 *         → msm.getActiveSessions(MediaNotificationListener ComponentName)
 *         → MediaMapper → G1 playbackState / positionMs / durationMs
 * ```
 *
 * No alternate inject path exists on the collector.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidCollectorDefaultMediaPathTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun spiedContextWithMsm(msm: MediaSessionManager): Context {
        val app = RuntimeEnvironment.getApplication()
        val spied = spy(app)
        doReturn(msm).whenever(spied).getSystemService(Context.MEDIA_SESSION_SERVICE)
        doReturn(msm).whenever(spied).getSystemService(eq(Context.MEDIA_SESSION_SERVICE))
        return spied
    }

    @Test
    fun sample_solePath_getSystemService_thenGetActiveSessionsNls() {
        val msm = mock<MediaSessionManager>()
        whenever(msm.getActiveSessions(any())).thenReturn(emptyList())
        val context = spiedContextWithMsm(msm)

        val collector = AndroidCollector(context)
        val sample = collector.sample()

        val order: InOrder = inOrder(context, msm)
        order.verify(context, atLeastOnce()).getSystemService(Context.MEDIA_SESSION_SERVICE)
        val captor = argumentCaptor<ComponentName>()
        order.verify(msm).getActiveSessions(captor.capture())

        val nls = captor.firstValue
        assertNotNull(nls)
        assertEquals(MediaNotificationListener::class.java.name, nls.className)
        assertEquals(context.packageName, nls.packageName)
        assertNull(sample.media)

        // Collector has no inject field for media sessions
        val fieldNames = AndroidCollector::class.java.declaredFields.map { it.name }
        assertTrue(
            "activeMediaSessions inject must not exist on AndroidCollector",
            fieldNames.none { it.contains("activeMedia", ignoreCase = true) },
        )
    }

    @Test
    fun sample_solePath_mapsG1_viaGetSystemServiceAndGetActiveSessions() {
        val msm = mock<MediaSessionManager>()
        val controller = mock<MediaController>()
        val metadata = mock<MediaMetadata>()
        val playback = mock<AndroidPlaybackState>()

        whenever(metadata.getString(MediaMetadata.METADATA_KEY_TITLE)).thenReturn("sole-path-track")
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)).thenReturn(null)
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)).thenReturn("artist")
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)).thenReturn(null)
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)).thenReturn("album")
        whenever(metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)).thenReturn(77_000L)
        whenever(playback.state).thenReturn(AndroidPlaybackState.STATE_PLAYING)
        whenever(playback.position).thenReturn(6_666L)
        whenever(controller.packageName).thenReturn("com.sole.player")
        whenever(controller.metadata).thenReturn(metadata)
        whenever(controller.playbackState).thenReturn(playback)
        whenever(msm.getActiveSessions(any())).thenReturn(listOf(controller))

        val context = spiedContextWithMsm(msm)
        val sample = AndroidCollector(context).sample()

        verify(context, atLeastOnce()).getSystemService(Context.MEDIA_SESSION_SERVICE)
        val captor = argumentCaptor<ComponentName>()
        verify(msm).getActiveSessions(captor.capture())
        assertEquals(MediaNotificationListener::class.java.name, captor.firstValue.className)
        assertEquals(context.packageName, captor.firstValue.packageName)

        assertNotNull(sample.media)
        val media = sample.media!!
        assertEquals(PlaybackState.PLAYING, media.playbackState)
        assertEquals(6_666L, media.positionMs)
        assertEquals(77_000L, media.durationMs)
        assertEquals("sole-path-track", media.title)
        assertEquals("com.sole.player", media.sourceApp)

        val wire = json.encodeToString(media)
        val obj = json.parseToJsonElement(wire).jsonObject
        assertEquals("playing", obj["playbackState"]!!.jsonPrimitive.content)
        assertEquals("6666", obj["positionMs"]!!.jsonPrimitive.content)
        assertEquals("77000", obj["durationMs"]!!.jsonPrimitive.content)
        assertTrue(wire.contains("\"playbackState\""))
        assertTrue(wire.contains("\"positionMs\""))
        assertTrue(wire.contains("\"durationMs\""))
    }

    @Test
    fun sample_solePath_sourceOnlyUsesBridge_notInjectBranch() {
        val msm = mock<MediaSessionManager>()
        whenever(msm.getActiveSessions(any())).thenReturn(emptyList())
        val context = spiedContextWithMsm(msm)
        AndroidCollector(context).sample()

        verify(context, atLeastOnce()).getSystemService(Context.MEDIA_SESSION_SERVICE)
        verify(msm).getActiveSessions(any())

        val text = loadCollectorSource()
        assertTrue(text.contains("MediaSessionManagerBridge.sampleUsingManager"))
        assertTrue(!text.contains("activeMediaSessions"))
        assertTrue(
            text.contains("MEDIA_SESSION_SERVICE"),
        )
        val nonComment = text.lineSequence().filter { !it.trimStart().startsWith("//") }
        assertTrue(nonComment.none { it.contains("getActiveSessions(null)") })
    }

    @Test
    fun sample_solePath_securityException_stillHitsGetSystemServiceAndGetActiveSessions() {
        val msm = mock<MediaSessionManager>()
        whenever(msm.getActiveSessions(any())).thenThrow(SecurityException("nls denied"))
        val context = spiedContextWithMsm(msm)

        val sample = AndroidCollector(context).sample()

        verify(context, atLeastOnce()).getSystemService(Context.MEDIA_SESSION_SERVICE)
        val captor = argumentCaptor<ComponentName>()
        verify(msm).getActiveSessions(captor.capture())
        assertEquals(MediaNotificationListener::class.java.name, captor.firstValue.className)
        assertNull(sample.media)
    }

    private fun loadCollectorSource(): String {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            java.io.File(cwd, "src/main/java/app/nekolink/android/collector/AndroidCollector.kt"),
            java.io.File(cwd, "app/src/main/java/app/nekolink/android/collector/AndroidCollector.kt"),
            java.io.File(cwd.parentFile, "app/src/main/java/app/nekolink/android/collector/AndroidCollector.kt"),
        )
        return candidates.first { it.isFile }.readText(Charsets.UTF_8)
    }
}
