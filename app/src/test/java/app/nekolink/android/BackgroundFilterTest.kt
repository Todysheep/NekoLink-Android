package app.nekolink.android

import app.nekolink.android.domain.BackgroundFilter
import app.nekolink.android.protocol.BackgroundApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundFilterTest {
    @Test
    fun filtersSystemNoise_byDefault() {
        val raw = listOf(
            BackgroundApp(id = "com.android.systemui", name = "System UI"),
            BackgroundApp(id = "com.spotify.music", name = "Spotify"),
            BackgroundApp(id = "com.google.android.gms", name = "GMS"),
            BackgroundApp(id = "com.example.chat", name = "Chat"),
        )
        val (kept, hidden, trunc) = BackgroundFilter.filterAndCap(raw, showSystem = false, cap = 50)
        assertFalse(trunc)
        assertEquals(2, kept.size)
        assertTrue(kept.any { it.id == "com.spotify.music" })
        assertTrue(kept.any { it.id == "com.example.chat" })
        assertEquals(2, hidden)
    }

    @Test
    fun cap50_defaultMode() {
        val raw = (0 until 60).map { i ->
            BackgroundApp(id = "com.user.app$i", name = "App$i")
        }
        val (kept, hidden, trunc) = BackgroundFilter.filterAndCap(raw, showSystem = false, cap = 50)
        assertEquals(50, kept.size)
        assertTrue(trunc)
        assertEquals(10, hidden)
    }

    @Test
    fun showSystem_keepsNoise() {
        val raw = listOf(
            BackgroundApp(id = "com.android.systemui", name = "System UI"),
            BackgroundApp(id = "com.example.chat", name = "Chat"),
        )
        val (kept, hidden, _) = BackgroundFilter.filterAndCap(raw, showSystem = true, cap = 50)
        assertEquals(2, kept.size)
        assertEquals(0, hidden)
    }
}
