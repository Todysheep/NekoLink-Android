package app.nekolink.android

import app.nekolink.android.protocol.ForegroundApp
import app.nekolink.android.protocol.ForegroundKind
import app.nekolink.android.protocol.MediaSession
import app.nekolink.android.protocol.PairRequest
import app.nekolink.android.protocol.Platform
import app.nekolink.android.protocol.PlaybackState
import app.nekolink.android.protocol.SnapshotIngestRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun pairRequest_containsPlatformAndroid_andCamelCase() {
        val req = PairRequest(
            pairingCode = "ABCD1234",
            displayName = "Pixel",
            platform = Platform.ANDROID,
        )
        val text = json.encodeToString(req)
        val v = json.parseToJsonElement(text).jsonObject
        assertEquals("ABCD1234", v["pairingCode"]!!.jsonPrimitive.content)
        assertEquals("Pixel", v["displayName"]!!.jsonPrimitive.content)
        assertEquals("android", v["platform"]!!.jsonPrimitive.content)
        assertTrue(text.contains("\"pairingCode\""))
        assertTrue(text.contains("\"displayName\""))
        assertTrue(!text.contains("pairing_code"))
    }

    @Test
    fun snapshot_media_g1_fields_camelCase() {
        val media = MediaSession(
            title = "song",
            artist = "a",
            album = null,
            sourceApp = "Spotify",
            artworkUrl = null,
            artworkHash = null,
            playbackState = PlaybackState.PLAYING,
            positionMs = 1000,
            durationMs = 200_000,
            updatedAt = "2026-07-21T12:00:00Z",
        )
        val req = SnapshotIngestRequest(
            displayName = "Phone",
            foreground = ForegroundApp(
                kind = ForegroundKind.APP,
                appName = "Chrome",
                title = null,
            ),
            media = media,
            progressOnly = false,
        )
        val text = json.encodeToString(req)
        val v = json.parseToJsonElement(text).jsonObject
        val m = v["media"]!!.jsonObject
        assertEquals("playing", m["playbackState"]!!.jsonPrimitive.content)
        assertEquals("1000", m["positionMs"]!!.jsonPrimitive.content)
        assertEquals("200000", m["durationMs"]!!.jsonPrimitive.content)
        assertEquals("Spotify", m["sourceApp"]!!.jsonPrimitive.content)
        assertTrue(text.contains("\"progressOnly\""))
        assertTrue(text.contains("\"displayName\""))
    }
}
