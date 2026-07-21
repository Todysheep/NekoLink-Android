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
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Proves the **default production media chain** end-to-end on shipped code:
 *
 * ```
 * AndroidCollector(context)          // pure default ctor — no activeMediaSessions inject
 *   .sample()
 *     → sampleMedia()                // activeMediaSessions == null branch
 *       → MediaSessionManagerBridge.sampleUsingManager
 *         → context.getSystemService(MEDIA_SESSION_SERVICE)
 *         → msm.getActiveSessions(nlsComponent)   // never null
 *         → MediaMapper G1 fields
 * ```
 *
 * Uses Mockito [spy] on the Application so we can stub/verify
 * [Context.getSystemService] without changing collector production code.
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
        // Stub only MEDIA_SESSION_SERVICE; everything else delegates to real app
        doReturn(msm).whenever(spied).getSystemService(Context.MEDIA_SESSION_SERVICE)
        return spied
    }

    @Test
    fun sample_defaultCtor_chain_getSystemService_bridge_getActiveSessions_nls() {
        val msm = mock<MediaSessionManager>()
        whenever(msm.getActiveSessions(any())).thenReturn(emptyList())
        val context = spiedContextWithMsm(msm)

        // Pure default construction — only Context, inject remains null
        val collector = AndroidCollector(context)
        val sample = collector.sample()

        // 1) Production code asked for MediaSessionManager via getSystemService
        verify(context, atLeastOnce()).getSystemService(Context.MEDIA_SESSION_SERVICE)

        // 2) Default bridge path called msm.getActiveSessions with NLS ComponentName
        val captor = argumentCaptor<ComponentName>()
        verify(msm).getActiveSessions(captor.capture())
        val nls = captor.firstValue
        assertNotNull("must not pass null ComponentName to getActiveSessions", nls)
        assertEquals(MediaNotificationListener::class.java.name, nls.className)
        assertEquals(context.packageName, nls.packageName)

        // 3) Honest empty when no controllers
        assertNull(sample.media)
    }

    @Test
    fun sample_defaultCtor_mapsG1_afterGetSystemServiceAndGetActiveSessions() {
        val msm = mock<MediaSessionManager>()
        val controller = mock<MediaController>()
        val metadata = mock<MediaMetadata>()
        val playback = mock<AndroidPlaybackState>()

        whenever(metadata.getString(MediaMetadata.METADATA_KEY_TITLE)).thenReturn("chain-track")
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)).thenReturn(null)
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)).thenReturn("a")
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)).thenReturn(null)
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)).thenReturn("alb")
        whenever(metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)).thenReturn(55_000L)
        whenever(playback.state).thenReturn(AndroidPlaybackState.STATE_PLAYING)
        whenever(playback.position).thenReturn(4_444L)
        whenever(controller.packageName).thenReturn("com.chain.player")
        whenever(controller.metadata).thenReturn(metadata)
        whenever(controller.playbackState).thenReturn(playback)
        whenever(msm.getActiveSessions(any())).thenReturn(listOf(controller))

        val context = spiedContextWithMsm(msm)
        val collector = AndroidCollector(context) // default ctor only
        val sample = collector.sample()

        verify(context, atLeastOnce()).getSystemService(Context.MEDIA_SESSION_SERVICE)
        val captor = argumentCaptor<ComponentName>()
        verify(msm).getActiveSessions(captor.capture())
        assertEquals(MediaNotificationListener::class.java.name, captor.firstValue.className)

        assertNotNull(sample.media)
        val media = sample.media!!
        assertEquals(PlaybackState.PLAYING, media.playbackState)
        assertEquals(4_444L, media.positionMs)
        assertEquals(55_000L, media.durationMs)
        assertEquals("chain-track", media.title)
        assertEquals("com.chain.player", media.sourceApp)

        val wire = json.encodeToString(media)
        val obj = json.parseToJsonElement(wire).jsonObject
        assertEquals("playing", obj["playbackState"]!!.jsonPrimitive.content)
        assertEquals("4444", obj["positionMs"]!!.jsonPrimitive.content)
        assertEquals("55000", obj["durationMs"]!!.jsonPrimitive.content)
        assertTrue(wire.contains("\"playbackState\""))
        assertTrue(wire.contains("\"positionMs\""))
        assertTrue(wire.contains("\"durationMs\""))
    }

    @Test
    fun sample_defaultCtor_securityException_stillCalledGetSystemServiceAndGetActiveSessions() {
        val msm = mock<MediaSessionManager>()
        whenever(msm.getActiveSessions(any())).thenThrow(SecurityException("nls denied"))
        val context = spiedContextWithMsm(msm)

        val collector = AndroidCollector(context)
        val sample = collector.sample()

        verify(context, atLeastOnce()).getSystemService(Context.MEDIA_SESSION_SERVICE)
        verify(msm).getActiveSessions(any())
        assertNull(sample.media)
    }

    @Test
    fun defaultCtor_usesBridgePath_notInjectHook() {
        // Kotlin default params: AndroidCollector(context) leaves activeMediaSessions=null
        // so sampleMedia takes MediaSessionManagerBridge branch (getSystemService + getActiveSessions).
        val msm = mock<MediaSessionManager>()
        whenever(msm.getActiveSessions(any())).thenReturn(emptyList())
        val context = spiedContextWithMsm(msm)

        // Call site matches production: only Context, no media inject
        val collector = AndroidCollector(context = context)
        collector.sample()

        verify(context, atLeastOnce()).getSystemService(Context.MEDIA_SESSION_SERVICE)
        val captor = argumentCaptor<ComponentName>()
        verify(msm).getActiveSessions(captor.capture())
        assertEquals(MediaNotificationListener::class.java.name, captor.firstValue.className)

        // Source still has inject hook for other tests, but default sample uses Bridge
        val src = java.io.File(
            "src/main/java/app/nekolink/android/collector/AndroidCollector.kt",
        ).let { f ->
            if (f.isFile) f else java.io.File(
                "../app/src/main/java/app/nekolink/android/collector/AndroidCollector.kt",
            )
        }
        val text = if (src.isFile) {
            src.readText()
        } else {
            // resolve from cwd
            val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
            val candidates = listOf(
                java.io.File(cwd, "src/main/java/app/nekolink/android/collector/AndroidCollector.kt"),
                java.io.File(cwd, "app/src/main/java/app/nekolink/android/collector/AndroidCollector.kt"),
                java.io.File(cwd.parentFile, "app/src/main/java/app/nekolink/android/collector/AndroidCollector.kt"),
            )
            candidates.first { it.isFile }.readText()
        }
        assertTrue(text.contains("MediaSessionManagerBridge.sampleUsingManager"))
        assertTrue(text.contains("if (activeMediaSessions != null)"))
    }
}
