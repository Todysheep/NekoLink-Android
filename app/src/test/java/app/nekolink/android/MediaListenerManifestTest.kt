package app.nekolink.android

import app.nekolink.android.collector.MediaSessionSamplePath
import app.nekolink.android.service.MediaNotificationListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural checks: NLS is declared in the **shipped** manifest and the production
 * collector wires [MediaSessionSamplePath] with getActiveSessions(ComponentName).
 */
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
    fun collectorWiresSamplePath_andGetActiveSessionsWithComponentName() {
        val collector = File(projectRoot(), "app/src/main/java/app/nekolink/android/collector/AndroidCollector.kt")
        val text = collector.readText(Charsets.UTF_8)
        assertTrue(text.contains("MediaSessionSamplePath.sample"))
        assertTrue(text.contains("getActiveSessions(cn)"))
        assertTrue(text.contains("MediaSessionSamplePath.toComponentName") || text.contains("toComponentName("))
        assertTrue(!text.contains("getActiveSessions(null)"))
        assertTrue(text.contains("LISTENER_CLASS_NAME"))

        // Identity used when building ComponentName(pkg, className) in production
        val (pkg, cls) = MediaSessionSamplePath.listenerComponentName("app.nekolink.android")
        assertEquals("app.nekolink.android", pkg)
        assertEquals(MediaNotificationListener::class.java.name, cls)
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
