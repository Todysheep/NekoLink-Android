package app.nekolink.android

import app.nekolink.android.collector.MediaSessionSamplePath
import app.nekolink.android.service.MediaNotificationListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Structural + identity checks for the sole production media wire:
 * AndroidCollector → MediaSessionManagerBridge → getActiveSessions(NLS).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ProductionMediaWireTest {
    private val appPackage = "app.nekolink.android"

    @Test
    fun androidCollector_solePath_usesMediaSessionManagerBridge() {
        val root = projectRoot()
        val collector = File(
            root,
            "app/src/main/java/app/nekolink/android/collector/AndroidCollector.kt",
        )
        assertTrue(collector.isFile)
        val text = collector.readText(Charsets.UTF_8)
        assertTrue(
            "collector must use MediaSessionManagerBridge.sampleUsingManager",
            text.contains("MediaSessionManagerBridge.sampleUsingManager"),
        )
        assertTrue(
            "collector must not keep an activeMediaSessions inject alternate path",
            !text.contains("activeMediaSessions"),
        )
        val nonComment = text.lineSequence().filter { !it.trimStart().startsWith("//") }
        assertTrue(nonComment.none { it.contains("getActiveSessions(null)") })

        val bridge = File(
            root,
            "app/src/main/java/app/nekolink/android/collector/MediaSessionManagerBridge.kt",
        )
        assertTrue(bridge.isFile)
        val bridgeText = bridge.readText(Charsets.UTF_8)
        assertTrue(bridgeText.contains("msm.getActiveSessions(listenerComponent)"))
        assertTrue(bridgeText.contains("sampleUsingManager"))
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

    @Test
    fun productionEntry_sampleForPackage_buildsNlsComponentAndCallsGetActiveSessions() {
        // Bridge uses sampleForPackage under the hood — keep identity check
        var capturedClass: String? = null
        MediaSessionSamplePath.sampleForPackage(
            packageName = appPackage,
            getActiveSessions = { cn ->
                capturedClass = cn.className
                emptyList()
            },
            updatedAt = "t",
        )
        assertEquals(MediaNotificationListener::class.java.name, capturedClass)
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
