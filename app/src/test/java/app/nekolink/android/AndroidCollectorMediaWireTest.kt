package app.nekolink.android

import android.content.ComponentName
import android.media.session.PlaybackState as AndroidPlaybackState
import app.nekolink.android.collector.AndroidCollector
import app.nekolink.android.collector.MediaSessionSamplePath
import app.nekolink.android.protocol.PlaybackState
import app.nekolink.android.service.MediaNotificationListener
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Drives the **shipped** [AndroidCollector.sample] entry (production collector path).
 *
 * Injects only the MediaSessionManager side (`activeMediaSessions`) so we prove:
 * 1. [AndroidCollector] builds NLS ComponentName via [MediaSessionSamplePath.sampleForPackage]
 * 2. That ComponentName is passed to getActiveSessions (never null)
 * 3. Resulting media has G1 playbackState / positionMs / durationMs
 *
 * This is not a reimplementation of the collector — it constructs the real class
 * and calls [AndroidCollector.sample].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidCollectorMediaWireTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun sample_drivesProductionMediaPath_withNlsComponentNameAndG1Fields() {
        val context = RuntimeEnvironment.getApplication()
        var captured: ComponentName? = null
        var calls = 0

        val collector = AndroidCollector(
            context = context,
            activeMediaSessions = { cn ->
                calls++
                captured = cn
                assertNotNull(cn)
                assertEquals(
                    MediaNotificationListener::class.java.name,
                    cn.className,
                )
                assertEquals(context.packageName, cn.packageName)
                listOf(
                    MediaSessionSamplePath.ControllerFields(
                        packageName = "com.spotify.music",
                        title = "collector-track",
                        artist = "art",
                        album = "alb",
                        androidPlaybackState = AndroidPlaybackState.STATE_PLAYING,
                        positionMs = 9_001L,
                        durationMs = 240_000L,
                    ),
                )
            },
        )

        // Drive the real shipped entry point
        val sample = collector.sample()

        assertEquals("getActiveSessions must be invoked once via sampleForPackage", 1, calls)
        assertNotNull(captured)
        assertEquals(MediaNotificationListener::class.java.name, captured!!.className)
        assertNotNull("media must be populated through production path", sample.media)
        val media = sample.media!!
        assertEquals(PlaybackState.PLAYING, media.playbackState)
        assertEquals(9_001L, media.positionMs)
        assertEquals(240_000L, media.durationMs)
        assertEquals("collector-track", media.title)
        assertEquals("com.spotify.music", media.sourceApp)

        val wire = json.encodeToString(media)
        val obj = json.parseToJsonElement(wire).jsonObject
        assertEquals("playing", obj["playbackState"]!!.jsonPrimitive.content)
        assertEquals("9001", obj["positionMs"]!!.jsonPrimitive.content)
        assertEquals("240000", obj["durationMs"]!!.jsonPrimitive.content)
        assertTrue(wire.contains("\"playbackState\""))
        assertTrue(wire.contains("\"positionMs\""))
        assertTrue(wire.contains("\"durationMs\""))
    }

    @Test
    fun sample_securityException_yieldsNullMedia_honestDegrade() {
        val context = RuntimeEnvironment.getApplication()
        val collector = AndroidCollector(
            context = context,
            activeMediaSessions = { throw SecurityException("nls not granted") },
        )
        val sample = collector.sample()
        assertEquals(null, sample.media)
    }

    @Test
    fun sample_emptySessions_yieldsNullMedia() {
        val context = RuntimeEnvironment.getApplication()
        val collector = AndroidCollector(
            context = context,
            activeMediaSessions = { emptyList() },
        )
        val sample = collector.sample()
        assertEquals(null, sample.media)
    }
}
