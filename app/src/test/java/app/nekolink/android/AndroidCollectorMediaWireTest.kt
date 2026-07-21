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
import org.junit.Assert.assertFalse
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
import java.io.File

/**
 * **Skeptic gate:** drive shipped [AndroidCollector.sample] and prove production media path:
 *
 * 1. `AndroidCollector(application).sample()` is the entry under test
 * 2. Production calls `MediaSessionManager.getActiveSessions(non-null NLS ComponentName)`
 *    where NLS class is [MediaNotificationListener] (declared in AndroidManifest)
 * 3. Controllers map to G1 fields: playbackState / positionMs / durationMs
 *
 * No ContextWrapper / no inject alternate path — real Application +
 * [ShadowMediaSessionManagerRecord] intercepts the framework call.
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
    fun androidCollector_sample_calls_getActiveSessions_with_nonNull_MediaNotificationListener_ComponentName() {
        ShadowMediaSessionManagerRecord.controllersToReturn = emptyList()

        val application = RuntimeEnvironment.getApplication()
        // Drive the SHIPPED collector entry — production path only
        val collected = AndroidCollector(application).sample()

        assertEquals(
            "AndroidCollector.sample() must reach MediaSessionManager.getActiveSessions",
            1,
            ShadowMediaSessionManagerRecord.callCount,
        )
        val nlsComponent = ShadowMediaSessionManagerRecord.lastNotificationListener
        assertNotNull(
            "production must call getActiveSessions with non-null ComponentName (never null)",
            nlsComponent,
        )
        assertEquals(
            "ComponentName must be MediaNotificationListener (NLS for 通知使用权)",
            MediaNotificationListener::class.java.name,
            nlsComponent!!.className,
        )
        assertEquals(application.packageName, nlsComponent.packageName)
        assertNull("empty controllers → honest null media", collected.media)
    }

    @Test
    fun androidCollector_sample_maps_G1_playbackState_positionMs_durationMs_after_getActiveSessions() {
        val controller = mock<MediaController>()
        val metadata = mock<MediaMetadata>()
        val playback = mock<AndroidPlaybackState>()

        whenever(metadata.getString(MediaMetadata.METADATA_KEY_TITLE)).thenReturn("g1-now-playing")
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)).thenReturn(null)
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)).thenReturn("g1-artist")
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)).thenReturn(null)
        whenever(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)).thenReturn("g1-album")
        whenever(metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)).thenReturn(210_000L)
        whenever(playback.state).thenReturn(AndroidPlaybackState.STATE_PLAYING)
        whenever(playback.position).thenReturn(12_345L)
        whenever(controller.packageName).thenReturn("com.g1.player")
        whenever(controller.metadata).thenReturn(metadata)
        whenever(controller.playbackState).thenReturn(playback)

        ShadowMediaSessionManagerRecord.controllersToReturn = listOf(controller)

        val collected = AndroidCollector(RuntimeEnvironment.getApplication()).sample()

        // getActiveSessions was invoked with NLS
        assertEquals(1, ShadowMediaSessionManagerRecord.callCount)
        assertNotNull(ShadowMediaSessionManagerRecord.lastNotificationListener)
        assertEquals(
            MediaNotificationListener::class.java.name,
            ShadowMediaSessionManagerRecord.lastNotificationListener!!.className,
        )

        // G1 media fields from shipped MediaMapper path
        assertNotNull(collected.media)
        val media = collected.media!!
        assertEquals(PlaybackState.PLAYING, media.playbackState)
        assertEquals(12_345L, media.positionMs)
        assertEquals(210_000L, media.durationMs)
        assertEquals("g1-now-playing", media.title)
        assertEquals("com.g1.player", media.sourceApp)

        val wire = json.encodeToString(media)
        val obj = json.parseToJsonElement(wire).jsonObject
        assertEquals("playing", obj["playbackState"]!!.jsonPrimitive.content)
        assertEquals("12345", obj["positionMs"]!!.jsonPrimitive.content)
        assertEquals("210000", obj["durationMs"]!!.jsonPrimitive.content)
        assertTrue(wire.contains("\"playbackState\""))
        assertTrue(wire.contains("\"positionMs\""))
        assertTrue(wire.contains("\"durationMs\""))
    }

    @Test
    fun productionSources_declareNls_andWireBridge_notNullGetActiveSessions() {
        val root = projectRoot()

        // 1) MediaNotificationListener must be declared in shipped manifest
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
        assertTrue(manifest.contains("MediaNotificationListener"))
        assertTrue(manifest.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"))
        assertTrue(manifest.contains("android.service.notification.NotificationListenerService"))
        // Package visibility: resolve package ids → display labels for the board
        assertTrue(manifest.contains("android.permission.QUERY_ALL_PACKAGES"))

        // 2) Listener class exists and extends NotificationListenerService
        val listener = File(
            root,
            "app/src/main/java/app/nekolink/android/service/MediaNotificationListener.kt",
        ).readText(Charsets.UTF_8)
        assertTrue(listener.contains("class MediaNotificationListener"))
        assertTrue(listener.contains("NotificationListenerService"))

        // 3) AndroidCollector sole path uses MediaSessionManagerBridge (no inject)
        val collector = File(
            root,
            "app/src/main/java/app/nekolink/android/collector/AndroidCollector.kt",
        ).readText(Charsets.UTF_8)
        assertTrue(collector.contains("MediaSessionManagerBridge.sampleUsingManager"))
        assertTrue(collector.contains("MEDIA_SESSION_SERVICE"))
        assertTrue(collector.contains("resolveMediaSourceApp"))
        assertTrue(collector.contains("getApplicationLabel"))
        assertFalse(collector.contains("activeMediaSessions"))
        val nonComment = collector.lineSequence().filter { !it.trimStart().startsWith("//") }
        assertTrue(nonComment.none { it.contains("getActiveSessions(null)") })

        // 4) Bridge actually calls msm.getActiveSessions(listenerComponent)
        val bridge = File(
            root,
            "app/src/main/java/app/nekolink/android/collector/MediaSessionManagerBridge.kt",
        ).readText(Charsets.UTF_8)
        assertTrue(bridge.contains("msm.getActiveSessions(listenerComponent)"))
        assertTrue(bridge.contains("sampleUsingManager"))

        // Runtime identity matches manifest NLS
        assertEquals(
            "app.nekolink.android.service.MediaNotificationListener",
            MediaNotificationListener::class.java.name,
        )
    }

    private fun projectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(cwd, cwd.parentFile, File(cwd, ".."), File("."), File(".."))
        for (c in candidates) {
            if (c == null) continue
            val norm = c.canonicalFile
            if (File(norm, "app/src/main/AndroidManifest.xml").isFile) return norm
            if (File(norm, "src/main/AndroidManifest.xml").isFile) return norm.parentFile
        }
        var cur: File? = cwd.canonicalFile
        repeat(6) {
            if (cur == null) return@repeat
            if (File(cur, "app/src/main/AndroidManifest.xml").isFile) return cur!!
            cur = cur!!.parentFile
        }
        error("cannot locate project root from ${cwd.absolutePath}")
    }
}
