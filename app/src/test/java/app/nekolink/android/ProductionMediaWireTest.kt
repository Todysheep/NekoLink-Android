package app.nekolink.android

import android.content.ComponentName
import android.media.session.PlaybackState as AndroidPlaybackState
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
import org.robolectric.annotation.Config
import java.io.File

/**
 * Proves the **production** media wire that [app.nekolink.android.collector.AndroidCollector]
 * calls:
 *
 * `MediaSessionSamplePath.sampleForPackage(packageName) { cn -> msm.getActiveSessions(cn) ... }`
 *
 * 1. Builds non-null NLS [ComponentName] from package name
 * 2. Feeds that ComponentName into getActiveSessions (never null)
 * 3. Maps framework playback state → G1 playbackState / positionMs / durationMs
 * 4. AndroidCollector source actually calls [MediaSessionSamplePath.sampleForPackage]
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ProductionMediaWireTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val appPackage = "app.nekolink.android"

    @Test
    fun productionEntry_sampleForPackage_buildsNlsComponentAndCallsGetActiveSessions() {
        var captured: ComponentName? = null
        var invocations = 0

        val media = MediaSessionSamplePath.sampleForPackage(
            packageName = appPackage,
            getActiveSessions = { cn ->
                // This lambda is what AndroidCollector binds to msm.getActiveSessions(cn)
                invocations++
                captured = cn
                assertNotNull("production must pass non-null ComponentName", cn)
                assertEquals(
                    "must be MediaNotificationListener (NLS), not null / other class",
                    MediaNotificationListener::class.java.name,
                    cn.className,
                )
                assertEquals(appPackage, cn.packageName)
                listOf(
                    MediaSessionSamplePath.ControllerFields(
                        packageName = "com.spotify.music",
                        title = "G1 Track",
                        artist = "Artist",
                        album = "Album",
                        androidPlaybackState = AndroidPlaybackState.STATE_PLAYING,
                        positionMs = 42_000L,
                        durationMs = 180_000L,
                    ),
                )
            },
            updatedAt = "2026-07-21T15:00:00Z",
        )

        assertEquals(1, invocations)
        assertNotNull(captured)
        assertEquals(MediaNotificationListener::class.java.name, captured!!.className)
        assertNotNull(media)
        assertEquals(PlaybackState.PLAYING, media!!.playbackState)
        assertEquals(42_000L, media.positionMs)
        assertEquals(180_000L, media.durationMs)
        assertEquals("G1 Track", media.title)

        val wire = json.encodeToString(media)
        val obj = json.parseToJsonElement(wire).jsonObject
        assertEquals("playing", obj["playbackState"]!!.jsonPrimitive.content)
        assertEquals("42000", obj["positionMs"]!!.jsonPrimitive.content)
        assertEquals("180000", obj["durationMs"]!!.jsonPrimitive.content)
    }

    @Test
    fun androidCollector_callsSampleForPackage_andDefaultUsesGetActiveSessions() {
        val root = projectRoot()
        val collector = File(
            root,
            "app/src/main/java/app/nekolink/android/collector/AndroidCollector.kt",
        )
        assertTrue(collector.isFile)
        val text = collector.readText(Charsets.UTF_8)
        assertTrue(
            "AndroidCollector.sampleMedia must call production entry sampleForPackage",
            text.contains("MediaSessionSamplePath.sampleForPackage"),
        )
        assertTrue(
            "default loader must call msm.getActiveSessions(cn) with ComponentName",
            text.contains("getActiveSessions(cn)"),
        )
        assertTrue(
            "must never use getActiveSessions(null)",
            !text.contains("getActiveSessions(null)"),
        )
        assertTrue(
            "activeMediaSessions injection hook must exist for tests of sample()",
            text.contains("activeMediaSessions"),
        )
        assertTrue(text.contains("sampleForPackage"))
    }

    @Test
    fun manifestAndListenerIdentity_matchComponentNameBuiltByProductionEntry() {
        val manifest = File(projectRoot(), "app/src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
        assertTrue(manifest.contains("MediaNotificationListener"))
        assertTrue(manifest.contains("BIND_NOTIFICATION_LISTENER_SERVICE"))

        val built = MediaSessionSamplePath.toComponentName(appPackage)
        assertEquals(MediaNotificationListener::class.java.name, built.className)
        assertEquals(
            MediaSessionSamplePath.LISTENER_CLASS_NAME,
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
