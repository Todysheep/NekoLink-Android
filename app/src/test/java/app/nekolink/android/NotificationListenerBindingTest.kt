package app.nekolink.android

import android.os.Build
import android.provider.Settings
import app.nekolink.android.collector.AndroidCollector
import app.nekolink.android.service.MediaNotificationListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * UI / settings binding for 通知使用权:
 * [AndroidCollector.notificationListenerSettingsIntent] must target
 * [MediaNotificationListener] so the app appears in the system list
 * and [android.media.session.MediaSessionManager.getActiveSessions] can be authorized.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NotificationListenerBindingTest {

    @Test
    fun notificationListenerComponent_isMediaNotificationListener() {
        val app = RuntimeEnvironment.getApplication()
        val cn = AndroidCollector.notificationListenerComponent(app)
        assertEquals(app.packageName, cn.packageName)
        assertEquals(MediaNotificationListener::class.java.name, cn.className)
    }

    @Test
    fun notificationListenerSettingsIntent_deepLinksToNlsComponentOnApi30() {
        val app = RuntimeEnvironment.getApplication()
        val intent = AndroidCollector.notificationListenerSettingsIntent(app)
        assertEquals(
            Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS,
            intent.action,
        )
        val extra = intent.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME)
        assertNotNull(extra)
        val expected = AndroidCollector.notificationListenerComponent(app).flattenToString()
        assertEquals(expected, extra)
        assertTrue(extra!!.contains("MediaNotificationListener"))
    }

    @Test
    @Config(sdk = [28])
    fun notificationListenerSettingsIntent_opensListenerSettingsOnApi28() {
        val app = RuntimeEnvironment.getApplication()
        val intent = AndroidCollector.notificationListenerSettingsIntent(app)
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, intent.action)
    }

    @Test
    fun mainActivity_opensNotificationAccessViaCollectorIntent() {
        val src = loadMainActivitySource()
        assertTrue(src.contains("notificationListenerSettingsIntent"))
        assertTrue(src.contains("onOpenNotificationAccess"))
    }

    private fun loadMainActivitySource(): String {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            java.io.File(cwd, "src/main/java/app/nekolink/android/MainActivity.kt"),
            java.io.File(cwd, "app/src/main/java/app/nekolink/android/MainActivity.kt"),
            java.io.File(cwd.parentFile, "app/src/main/java/app/nekolink/android/MainActivity.kt"),
        )
        return candidates.first { it.isFile }.readText(Charsets.UTF_8)
    }
}
