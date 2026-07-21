package app.nekolink.android

import app.nekolink.android.domain.SampleDiff
import app.nekolink.android.domain.buildSnapshotRequest
import app.nekolink.android.domain.capBackground
import app.nekolink.android.domain.classifyChange
import app.nekolink.android.protocol.BackgroundApp
import app.nekolink.android.protocol.CollectedSample
import app.nekolink.android.protocol.ForegroundApp
import app.nekolink.android.protocol.ForegroundKind
import app.nekolink.android.protocol.MediaSession
import app.nekolink.android.protocol.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeTest {
    private fun fg(name: String, title: String) = ForegroundApp(
        kind = ForegroundKind.APP,
        appName = name,
        title = title,
    )

    private fun media(title: String, pos: Long) = MediaSession(
        title = title,
        artist = "a",
        album = null,
        sourceApp = "Spotify",
        artworkUrl = null,
        artworkHash = null,
        playbackState = PlaybackState.PLAYING,
        positionMs = pos,
        durationMs = 200_000,
        updatedAt = "2026-07-21T12:00:00Z",
    )

    @Test
    fun firstSample_isMeaningful() {
        val s = CollectedSample(foreground = fg("Code", "main.rs"))
        assertEquals(SampleDiff.MEANINGFUL, classifyChange(null, s))
    }

    @Test
    fun progressTickOnly_notForcedMeaningful() {
        val prev = CollectedSample(
            foreground = fg("Code", "main.rs"),
            media = media("Song", 1000),
        )
        val next = CollectedSample(
            foreground = fg("Code", "main.rs"),
            media = media("Song", 5000),
        )
        assertEquals(SampleDiff.PROGRESS_ONLY, classifyChange(prev, next))
        val req = buildSnapshotRequest(next, null, includeBackground = false, progressOnly = true, backgroundCap = 50)
        assertTrue(req.progressOnly)
        assertNull(req.foreground)
        assertEquals(5000L, req.media?.positionMs)
    }

    @Test
    fun noChange_isNone_doesNotForceMeaningful() {
        val s = CollectedSample(
            foreground = fg("A", "1"),
            media = media("S", 1),
        )
        assertEquals(SampleDiff.NONE, classifyChange(s, s))
    }

    @Test
    fun foregroundSwitch_meaningful() {
        val prev = CollectedSample(foreground = fg("A", "1"))
        val next = CollectedSample(foreground = fg("B", "2"))
        assertEquals(SampleDiff.MEANINGFUL, classifyChange(prev, next))
        val req = buildSnapshotRequest(next, "Phone", includeBackground = true, progressOnly = false, backgroundCap = 50)
        assertFalse(req.progressOnly)
        assertEquals("B", req.foreground?.appName)
    }

    @Test
    fun backgroundCap_default50() {
        val apps = (0 until 60).map { i ->
            BackgroundApp(id = "p$i", name = "App$i")
        }
        val (out, trunc, hidden) = capBackground(apps, hiddenCount = 2, cap = 50)
        assertEquals(50, out.size)
        assertTrue(trunc)
        assertEquals(12, hidden)
    }
}
