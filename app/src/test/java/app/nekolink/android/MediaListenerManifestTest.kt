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
 * Structural + ComponentName identity: NLS in shipped manifest; production
 * collector wires [MediaSessionSamplePath.sample] with getActiveSessions(ComponentName).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MediaListenerManifestTest {
    @Test
    fun manifestDeclaresMediaNotificationListenerService() {
        val text = locateManifest().readText(Charsets.UTF_8)
        assertTrue(text.contains("MediaNotificationListener"))
        assertTrue(text.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"))
        assertTrue(text.contains("android.service.notification.NotificationListenerService"))
    }

    @Test
    fun listenerSourceExtendsNotificationListenerService() {
        val cls = File(projectRoot(), "app/src/main/java/app/nekolink/android/service/MediaNotificationListener.kt")
        assertTrue(cls.isFile)
        val text = cls.readText(Charsets.UTF_8)
        assertTrue(text.contains("NotificationListenerService"))
        assertTrue(text.contains("class MediaNotificationListener"))
        assertEquals(
            "app.nekolink.android.service.MediaNotificationListener",
            MediaNotificationListener::class.java.name,
        )
        assertEquals(MediaNotificationListener::class.java.name, MediaSessionSamplePath.LISTENER_CLASS_NAME)
    }

    @Test
    fun collectorWiresSamplePath_getActiveSessionsWithComponentName() {
        val collector = File(projectRoot(), "app/src/main/java/app/nekolink/android/collector/AndroidCollector.kt")
        val text = collector.readText(Charsets.UTF_8)
        assertTrue(
            "default path uses MediaSessionManagerBridge",
            text.contains("MediaSessionManagerBridge.sampleUsingManager"),
        )
        assertTrue(
            "inject path uses sampleForPackage",
            text.contains("MediaSessionSamplePath.sampleForPackage"),
        )
        // No live call site should pass a null listener (allow comments/docs only via bridge)
        val liveCallNull = Regex("""getActiveSessions\s*\(\s*null\s*\)""")
        assertTrue(
            "no live getActiveSessions with null listener",
            !liveCallNull.containsMatchIn(text) ||
                text.lineSequence().none {
                    liveCallNull.containsMatchIn(it) && !it.trimStart().startsWith("//")
                },
        )

        val cn = MediaSessionSamplePath.toComponentName("app.nekolink.android")
        assertEquals(MediaNotificationListener::class.java.name, cn.className)
        assertEquals("app.nekolink.android", cn.packageName)
    }

    private fun locateManifest(): File {
        val m = File(projectRoot(), "app/src/main/AndroidManifest.xml")
        assertTrue(m.isFile)
        return m
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
