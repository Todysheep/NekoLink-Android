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
            BackgroundApp(id = "com.miui.securitycenter", name = "Security"),
            BackgroundApp(id = "com.coloros.safecenter", name = "ColorOS"),
        )
        val (kept, hidden, trunc) = BackgroundFilter.filterAndCap(raw, showSystem = false, cap = 12)
        assertFalse(trunc)
        assertEquals(2, kept.size)
        assertTrue(kept.any { it.id == "com.spotify.music" })
        assertTrue(kept.any { it.id == "com.example.chat" })
        assertEquals(4, hidden)
    }

    @Test
    fun cap12_defaultMode() {
        val raw = (0 until 20).map { i ->
            BackgroundApp(id = "com.user.app$i", name = "App$i")
        }
        val (kept, hidden, trunc) = BackgroundFilter.filterAndCap(raw, showSystem = false, cap = 12)
        assertEquals(12, kept.size)
        assertTrue(trunc)
        assertEquals(8, hidden)
    }

    @Test
    fun showSystem_keepsNoise() {
        val raw = listOf(
            BackgroundApp(id = "com.android.systemui", name = "System UI"),
            BackgroundApp(id = "com.example.chat", name = "Chat"),
        )
        val (kept, hidden, _) = BackgroundFilter.filterAndCap(raw, showSystem = true, cap = 24)
        assertEquals(2, kept.size)
        assertEquals(0, hidden)
    }

    @Test
    fun selectRecentCandidates_windowAndMinForeground() {
        val now = 1_000_000_000L
        val window = BackgroundFilter.RECENT_WINDOW_MS
        val raw = listOf(
            // too old
            BackgroundFilter.UsageCandidate("com.old.app", now - window - 1, 60_000),
            // recent but glance
            BackgroundFilter.UsageCandidate("com.glance.app", now - 1_000, 1_000),
            // recent + meaningful
            BackgroundFilter.UsageCandidate("com.good.app", now - 10_000, 30_000),
            // excluded
            BackgroundFilter.UsageCandidate("com.fg.app", now - 5_000, 40_000),
            // self
            BackgroundFilter.UsageCandidate("app.nekolink.android", now - 2_000, 99_000),
        )
        val out = BackgroundFilter.selectRecentCandidates(
            raw = raw,
            nowMs = now,
            excludePackages = setOf("com.fg.app", "app.nekolink.android"),
        )
        assertEquals(listOf("com.good.app"), out.map { it.packageName })
    }

    @Test
    fun sameIdSet_ignoresOrderAndLabels() {
        val a = listOf(
            BackgroundApp(id = "com.a", name = "A"),
            BackgroundApp(id = "com.b", name = "B"),
        )
        val b = listOf(
            BackgroundApp(id = "com.b", name = "Bee"),
            BackgroundApp(id = "com.a", name = "Aye"),
        )
        assertTrue(BackgroundFilter.sameIdSet(a, b))
        assertFalse(
            BackgroundFilter.sameIdSet(
                a,
                listOf(BackgroundApp(id = "com.a", name = "A")),
            ),
        )
    }

    @Test
    fun filterProfile_isV2() {
        assertEquals("v2", BackgroundFilter.FILTER_PROFILE)
        assertEquals(12, BackgroundFilter.DEFAULT_CAP)
        assertEquals(24, BackgroundFilter.SYSTEM_MODE_CAP)
    }
}
