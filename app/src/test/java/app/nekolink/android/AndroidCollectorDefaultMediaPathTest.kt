package app.nekolink.android

import app.nekolink.android.collector.AndroidCollector
import app.nekolink.android.service.MediaNotificationListener
import app.nekolink.android.shadow.ShadowMediaSessionManagerRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Sole production path structural + real-Application smoke:
 * [AndroidCollector] → [app.nekolink.android.collector.MediaSessionManagerBridge]
 * → shadowed [android.media.session.MediaSessionManager.getActiveSessions] with NLS.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowMediaSessionManagerRecord::class])
class AndroidCollectorDefaultMediaPathTest {

    @Before
    fun resetShadow() {
        ShadowMediaSessionManagerRecord.reset()
    }

    @Test
    fun sample_realApplication_recordsNlsOnGetActiveSessions() {
        ShadowMediaSessionManagerRecord.controllersToReturn = emptyList()
        val app = RuntimeEnvironment.getApplication()
        val sample = AndroidCollector(app).sample()

        assertEquals(1, ShadowMediaSessionManagerRecord.callCount)
        val nls = ShadowMediaSessionManagerRecord.lastNotificationListener
        assertNotNull(nls)
        assertEquals(MediaNotificationListener::class.java.name, nls!!.className)
        assertEquals(app.packageName, nls.packageName)
        assertNull(sample.media)
    }

    @Test
    fun sample_sourceOnlyUsesBridge_noInject_noNullListener() {
        ShadowMediaSessionManagerRecord.controllersToReturn = emptyList()
        AndroidCollector(RuntimeEnvironment.getApplication()).sample()
        assertTrue(ShadowMediaSessionManagerRecord.callCount >= 1)

        val text = loadCollectorSource()
        assertTrue(text.contains("MediaSessionManagerBridge.sampleUsingManager"))
        assertTrue(!text.contains("activeMediaSessions"))
        assertTrue(text.contains("MEDIA_SESSION_SERVICE"))
        val nonComment = text.lineSequence().filter { !it.trimStart().startsWith("//") }
        assertTrue(nonComment.none { it.contains("getActiveSessions(null)") })
        assertTrue(
            AndroidCollector::class.java.declaredFields.none {
                it.name.contains("activeMedia", ignoreCase = true)
            },
        )
    }

    @Test
    fun sample_noAlternateInjectPathOnClass() {
        val names = AndroidCollector::class.java.declaredFields.map { it.name }
        assertTrue(names.none { it.contains("activeMedia", ignoreCase = true) })
        assertTrue(names.none { it.contains("inject", ignoreCase = true) })
    }

    private fun loadCollectorSource(): String {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            java.io.File(cwd, "src/main/java/app/nekolink/android/collector/AndroidCollector.kt"),
            java.io.File(cwd, "app/src/main/java/app/nekolink/android/collector/AndroidCollector.kt"),
            java.io.File(cwd.parentFile, "app/src/main/java/app/nekolink/android/collector/AndroidCollector.kt"),
        )
        return candidates.first { it.isFile }.readText(Charsets.UTF_8)
    }
}
