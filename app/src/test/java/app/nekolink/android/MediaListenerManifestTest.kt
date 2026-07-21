package app.nekolink.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural check: shipped NotificationListenerService is declared so the app
 * can appear in 通知使用权 and MediaSessionManager.getActiveSessions(ComponentName) works.
 * Reads the real AndroidManifest.xml from the project tree (not a re-implementation).
 */
class MediaListenerManifestTest {
    @Test
    fun manifestDeclaresMediaNotificationListenerService() {
        val manifest = locateManifest()
        val text = manifest.readText(Charsets.UTF_8)
        assertTrue(
            "manifest must declare MediaNotificationListener",
            text.contains(".service.MediaNotificationListener") ||
                text.contains("MediaNotificationListener"),
        )
        assertTrue(
            "must request BIND_NOTIFICATION_LISTENER_SERVICE permission on the service",
            text.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"),
        )
        assertTrue(
            "must filter NotificationListenerService action",
            text.contains("android.service.notification.NotificationListenerService"),
        )
    }

    @Test
    fun listenerSourceClassExists() {
        val root = projectRoot()
        val cls = File(
            root,
            "app/src/main/java/app/nekolink/android/service/MediaNotificationListener.kt",
        )
        assertTrue("MediaNotificationListener.kt must exist", cls.isFile)
        val text = cls.readText(Charsets.UTF_8)
        assertTrue(text.contains("NotificationListenerService"))
        assertTrue(text.contains("class MediaNotificationListener"))
    }

    @Test
    fun collectorBindsComponentNameNotNull() {
        val root = projectRoot()
        val collector = File(
            root,
            "app/src/main/java/app/nekolink/android/collector/AndroidCollector.kt",
        )
        val text = collector.readText(Charsets.UTF_8)
        assertTrue(
            "getActiveSessions must use notification listener ComponentName",
            text.contains("getActiveSessions(listener)") ||
                text.contains("getActiveSessions(notificationListenerComponent"),
        )
        assertTrue(
            "must not call getActiveSessions(null) as the production path",
            !text.contains("getActiveSessions(null)"),
        )
        assertTrue(text.contains("MediaNotificationListener"))
        assertTrue(text.contains("MediaMapper.toMediaSession"))
    }

    private fun locateManifest(): File {
        val root = projectRoot()
        val m = File(root, "app/src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml missing at ${m.absolutePath}", m.isFile)
        return m
    }

    private fun projectRoot(): File {
        // Working directory when running unit tests is typically module dir (:app) or project root.
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            cwd,
            cwd.parentFile,
            File(cwd, ".."),
            File("."),
            File(".."),
        )
        for (c in candidates) {
            if (c == null) continue
            val norm = c.canonicalFile
            if (File(norm, "app/src/main/AndroidManifest.xml").isFile) return norm
            if (File(norm, "src/main/AndroidManifest.xml").isFile) return norm.parentFile
        }
        // Fallback: walk up
        var cur: File? = cwd.canonicalFile
        repeat(6) {
            if (cur == null) return@repeat
            if (File(cur, "app/src/main/AndroidManifest.xml").isFile) return cur!!
            cur = cur!!.parentFile
        }
        error("cannot locate project root from ${cwd.absolutePath}")
    }
}
