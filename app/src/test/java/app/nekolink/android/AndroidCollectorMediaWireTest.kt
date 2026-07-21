package app.nekolink.android

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState as AndroidPlaybackState
import app.nekolink.android.collector.AndroidCollector
import app.nekolink.android.protocol.PlaybackState
import app.nekolink.android.service.MediaNotificationListener
import app.nekolink.android.shadow.ShadowMediaSessionManagerRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Drives the **shipped** [AndroidCollector.sample] with a **real Application context**
 * (no ContextWrapper, no spy, no activeMediaSessions inject).
 *
 * Production chain under test:
 * ```
 * AndroidCollector(application)
 *   .sample()
 *     → getSystemService(MEDIA_SESSION_SERVICE)  // real Context path
 *     → MediaSessionManagerBridge.sampleUsingManager
 *       → msm.getActiveSessions(nls)             // intercepted by [ShadowMediaSessionManagerRecord]
 *       → MediaMapper G1 fields
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowMediaSessionManagerRecord::class])
class AndroidCollectorMediaWireTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Before
    fun resetShadow() {
        ShadowMediaSessionManagerRecord.reset()
    }

    @Test
    fun sample_realApplicationContext_callsGetActiveSessions_withNlsComponent() {
        ShadowMediaSessionManagerRecord.controllersToReturn = emptyList()

        // Pure production construction — real Application, no Context double
        val app = RuntimeEnvironment.getApplication()
        val sample = AndroidCollector(app).sample()

        assertEquals(
            "shipped sampleMedia must invoke MediaSessionManager.getActiveSessions",
            1,
            ShadowMediaSessionManagerRecord.callCount,
        )
        val nls = ShadowMediaSessionManagerRecord.lastNotificationListener
        assertNotNull("must not pass null ComponentName to getActiveSessions", nls)
        assertEquals(MediaNotificationListener::class.java.name, nls!!.className)
        assertEquals(app.packageName, nls.packageName)
        assertNull(sample.media)

        assertTrue(
            "AndroidCollector must not expose an inject alternate path",
            AndroidCollector::class.java.declaredFields.none {
                it.name.contains("activeMedia", ignoreCase = true)
            },
        )
    }

    @Test
    fun sample_realApplicationContext_mapsG1Fields_fromGetActiveSessionsControllers() {
        val controller = mock<MediaController>()
        val metadata = mock<MediaMetadata>()
        val playback = mock<AndroidPlaybackState>()

        whenever(metadata.getString(MediaMetadata.METADATA_KEY_TITLE)).thenReturn("shadow-wire-track")
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)).thenReturn(null)
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)).thenReturn("artist")
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)).thenReturn(null)
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)).thenReturn("album")
        whenever(metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)).thenReturn(150_000L)
        whenever(playback.state).thenReturn(AndroidPlaybackState.STATE_PLAYING)
        whenever(playback.position).thenReturn(9_999L)
        whenever(controller.packageName).thenReturn("com.shadow.player")
        whenever(controller.metadata).thenReturn(metadata)
        whenever(controller.playbackState).thenReturn(playback)

        ShadowMediaSessionManagerRecord.controllersToReturn = listOf(controller)

        val app = RuntimeEnvironment.getApplication()
        val sample = AndroidCollector(app).sample()

        assertEquals(1, ShadowMediaSessionManagerRecord.callCount)
        val nls = ShadowMediaSessionManagerRecord.lastNotificationListener
        assertNotNull(nls)
        assertEquals(MediaNotificationListener::class.java.name, nls!!.className)
        assertEquals(app.packageName, nls.packageName)

        assertNotNull(sample.media)
        val media = sample.media!!
        assertEquals(PlaybackState.PLAYING, media.playbackState)
        assertEquals(9_999L, media.positionMs)
        assertEquals(150_000L, media.durationMs)
        assertEquals("shadow-wire-track", media.title)
        assertEquals("com.shadow.player", media.sourceApp)

        val wire = json.encodeToString(media)
        val obj = json.parseToJsonElement(wire).jsonObject
        assertEquals("playing", obj["playbackState"]!!.jsonPrimitive.content)
        assertEquals("9999", obj["positionMs"]!!.jsonPrimitive.content)
        assertEquals("150000", obj["durationMs"]!!.jsonPrimitive.content)
        assertTrue(wire.contains("\"playbackState\""))
        assertTrue(wire.contains("\"positionMs\""))
        assertTrue(wire.contains("\"durationMs\""))
    }

    @Test
    fun sample_realApplicationContext_securityException_nullMedia() {
        // Shadow returns empty; SecurityException path is covered in MediaSessionManagerBridgeTest.
        // Here prove empty sessions on real Application → honest null media after getActiveSessions.
        ShadowMediaSessionManagerRecord.controllersToReturn = emptyList()
        val sample = AndroidCollector(RuntimeEnvironment.getApplication()).sample()
        assertEquals(1, ShadowMediaSessionManagerRecord.callCount)
        assertNotNull(ShadowMediaSessionManagerRecord.lastNotificationListener)
        assertEquals(
            MediaNotificationListener::class.java.name,
            ShadowMediaSessionManagerRecord.lastNotificationListener!!.className,
        )
        assertNull(sample.media)
    }
}
