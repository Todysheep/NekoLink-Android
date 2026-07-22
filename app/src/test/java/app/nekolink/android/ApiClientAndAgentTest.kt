package app.nekolink.android

import app.nekolink.android.domain.AgentCore
import app.nekolink.android.domain.ClientConfig
import app.nekolink.android.domain.SampleDiff
import app.nekolink.android.net.ApiClient
import app.nekolink.android.net.ArtworkAsset
import app.nekolink.android.net.ClientError
import app.nekolink.android.protocol.CollectedSample
import app.nekolink.android.protocol.ForegroundApp
import app.nekolink.android.protocol.ForegroundKind
import app.nekolink.android.protocol.MediaSession
import app.nekolink.android.protocol.PairRequest
import app.nekolink.android.protocol.Platform
import app.nekolink.android.protocol.PlaybackState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiClientAndAgentTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun pair_postsCamelCase_platformAndroid() {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(
                    """
                    {
                      "deviceId":"dev_x",
                      "deviceToken":"tok_x",
                      "displayName":"Pixel",
                      "platform":"android"
                    }
                    """.trimIndent(),
                ),
        )
        val client = ApiClient(server.url("/").toString().trimEnd('/'))
        val resp = client.pair(
            PairRequest(
                pairingCode = "CODE99",
                displayName = "Pixel",
                platform = Platform.ANDROID,
            ),
        )
        assertEquals("dev_x", resp.deviceId)
        assertEquals("tok_x", resp.deviceToken)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.endsWith("/api/v1/device/pair"))
        val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("android", body["platform"]!!.jsonPrimitive.content)
        assertEquals("CODE99", body["pairingCode"]!!.jsonPrimitive.content)
        assertEquals("Pixel", body["displayName"]!!.jsonPrimitive.content)
    }

    @Test
    fun snapshot_sendsBearer_andProgressOnly() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {"ok":true,"lastSyncedAt":"2026-07-21T12:00:00Z","eventsRecorded":0}
                    """.trimIndent(),
                ),
        )
        val client = ApiClient(server.url("/").toString().trimEnd('/'))
        val ack = client.snapshot(
            "secret-token",
            app.nekolink.android.protocol.SnapshotIngestRequest(progressOnly = true),
        )
        assertTrue(ack.ok)
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("Bearer secret-token", recorded.getHeader("Authorization"))
        assertTrue(recorded.body.readUtf8().contains("\"progressOnly\":true"))
    }

    @Test
    fun privacyShield_postsEnabled() {
        server.enqueue(MockResponse().setResponseCode(204))
        val client = ApiClient(server.url("/").toString().trimEnd('/'))
        client.setPrivacyShield("tok", true)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.contains("/api/v1/device/privacy-shield"))
        assertEquals("Bearer tok", recorded.getHeader("Authorization"))
        assertTrue(recorded.body.readUtf8().contains("\"enabled\":true"))
    }

    @Test
    fun unauthorized_mapsToError() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":"unauthorized","code":"revoked"}"""),
        )
        val client = ApiClient(server.url("/").toString().trimEnd('/'))
        try {
            client.heartbeat("bad", app.nekolink.android.protocol.HeartbeatRequest())
            throw AssertionError("expected Unauthorized")
        } catch (_: ClientError.Unauthorized) {
            // ok
        }
    }

    @Test
    fun agentPair_persistsPlatformAndroidInRequest() {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(
                    """
                    {
                      "deviceId":"dev_agent",
                      "deviceToken":"tok_agent",
                      "displayName":"Phone",
                      "platform":"android"
                    }
                    """.trimIndent(),
                ),
        )
        val base = server.url("/").toString().trimEnd('/')
        val core = AgentCore(
            config = ClientConfig(serverBase = base, displayName = "Phone"),
            client = ApiClient(base),
            credentials = null,
        )
        val creds = core.pair("PAIRCODE", nowIso = "2026-07-21T12:00:00Z")
        assertEquals("dev_agent", creds.deviceId)
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("android", body["platform"]!!.jsonPrimitive.content)
    }

    @Test
    fun pushSample_usesProgressOnly_onMediaTick() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"ok":true,"lastSyncedAt":"2026-07-21T12:00:00Z","eventsRecorded":0}""",
                ),
        )
        val base = server.url("/").toString().trimEnd('/')
        val core = AgentCore(
            config = ClientConfig(serverBase = base),
            client = ApiClient(base),
            credentials = null,
        )
        val prev = CollectedSample(
            foreground = ForegroundApp(
                kind = ForegroundKind.APP,
                appName = "A",
                title = null,
            ),
            media = MediaSession(
                title = "S",
                artist = null,
                album = null,
                sourceApp = null,
                artworkUrl = null,
                artworkHash = null,
                playbackState = PlaybackState.PLAYING,
                positionMs = 1,
                durationMs = 100,
                updatedAt = "2026-07-21T12:00:00Z",
            ),
        )
        val next = prev.copy(
            media = prev.media!!.copy(positionMs = 50),
        )
        val (diff, _) = core.pushSample("t", next, prev, forceBackground = false, forceMeaningful = false)
        assertEquals(SampleDiff.PROGRESS_ONLY, diff)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"progressOnly\":true"))
    }

    @Test
    fun pushSample_unchanged_doesNotSend() {
        val base = server.url("/").toString().trimEnd('/')
        val core = AgentCore(
            config = ClientConfig(serverBase = base),
            client = ApiClient(base),
            credentials = null,
        )
        val s = CollectedSample(
            foreground = ForegroundApp(kind = ForegroundKind.APP, appName = "A"),
        )
        val (diff, sent) = core.pushSample("t", s, s, forceBackground = false, forceMeaningful = false)
        assertEquals(SampleDiff.NONE, diff)
        assertEquals(null, sent)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun assetExists_head200_true_head404_false() {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(404))
        val client = ApiClient(server.url("/").toString().trimEnd('/'))
        assertTrue(client.assetExists("tok", "abc"))
        assertFalse(client.assetExists("tok", "missing"))

        val r1 = server.takeRequest()
        assertEquals("HEAD", r1.method)
        assertTrue(r1.path!!.endsWith("/api/v1/device/assets/abc"))
        assertEquals("Bearer tok", r1.getHeader("Authorization"))

        val r2 = server.takeRequest()
        assertEquals("HEAD", r2.method)
        assertTrue(r2.path!!.endsWith("/api/v1/device/assets/missing"))
    }

    @Test
    fun uploadAsset_putRawBytes() {
        server.enqueue(MockResponse().setResponseCode(201))
        val client = ApiClient(server.url("/").toString().trimEnd('/'))
        val body = byteArrayOf(1, 2, 3, 4)
        val hash = ArtworkAsset.sha256Hex(body)
        client.uploadAsset("tok", hash, body, "image/png")
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertTrue(recorded.path!!.endsWith("/api/v1/device/assets/$hash"))
        assertEquals("Bearer tok", recorded.getHeader("Authorization"))
        assertEquals("image/png", recorded.getHeader("Content-Type"))
        assertTrue(recorded.body.readByteArray().contentEquals(body))
    }

    @Test
    fun assetExists_privacyShield403_mapsServer() {
        // HEAD responses should not include a body (real servers / OkHttp).
        server.enqueue(MockResponse().setResponseCode(403))
        val client = ApiClient(server.url("/").toString().trimEnd('/'))
        try {
            client.assetExists("tok", "h")
            throw AssertionError("expected Server")
        } catch (e: ClientError.Server) {
            assertEquals(403, e.status)
        }
    }

    @Test
    fun pushSample_meaningful_ensuresArtworkBeforeSnapshot() {
        val art = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x10, 0x20)
        val hash = ArtworkAsset.sha256Hex(art)
        // HEAD 404 → PUT 201 → snapshot 200
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true,"lastSyncedAt":"2026-07-21T12:00:00Z","eventsRecorded":1}"""),
        )
        val base = server.url("/").toString().trimEnd('/')
        val core = AgentCore(
            config = ClientConfig(serverBase = base, displayName = "Phone"),
            client = ApiClient(base),
            credentials = null,
        )
        val sample = CollectedSample(
            foreground = ForegroundApp(kind = ForegroundKind.APP, appName = "Player"),
            media = MediaSession(
                title = "Track",
                artist = "A",
                album = null,
                sourceApp = "Spotify",
                artworkUrl = null,
                artworkHash = null,
                playbackState = PlaybackState.PLAYING,
                positionMs = 10,
                durationMs = 1000,
                updatedAt = "2026-07-21T12:00:00Z",
            ),
            artworkBytes = art,
        )
        val (diff, sent) = core.pushSample(
            "t",
            sample,
            null,
            forceBackground = true,
            forceMeaningful = true,
        )
        assertEquals(SampleDiff.MEANINGFUL, diff)
        assertEquals(hash, sent?.media?.artworkHash)
        assertNull(sent?.artworkBytes)

        val head = server.takeRequest()
        assertEquals("HEAD", head.method)
        assertTrue(head.path!!.contains("/api/v1/device/assets/$hash"))

        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertTrue(put.body.readByteArray().contentEquals(art))

        val snap = server.takeRequest()
        assertEquals("PUT", snap.method)
        assertTrue(snap.path!!.endsWith("/api/v1/device/snapshot"))
        val snapBody = snap.body.readUtf8()
        assertTrue(snapBody.contains("\"artworkHash\":\"$hash\""))
        assertFalse(snapBody.contains("artworkUrl"))
    }

    @Test
    fun pushSample_progressOnly_keepsPrevHash_noAssetNetwork() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true,"lastSyncedAt":"2026-07-21T12:00:00Z","eventsRecorded":0}"""),
        )
        val base = server.url("/").toString().trimEnd('/')
        val core = AgentCore(
            config = ClientConfig(serverBase = base),
            client = ApiClient(base),
            credentials = null,
        )
        val prev = CollectedSample(
            foreground = ForegroundApp(kind = ForegroundKind.APP, appName = "A"),
            media = MediaSession(
                title = "S",
                artist = null,
                album = null,
                sourceApp = null,
                artworkUrl = null,
                artworkHash = "abc123",
                playbackState = PlaybackState.PLAYING,
                positionMs = 1,
                durationMs = 100,
                updatedAt = "2026-07-21T12:00:00Z",
            ),
        )
        // Collector leaves hash null but may still have bytes — progress must not re-upload
        val next = prev.copy(
            media = prev.media!!.copy(positionMs = 50, artworkHash = null),
            artworkBytes = byteArrayOf(9, 9, 9),
        )
        val (diff, sent) = core.pushSample("t", next, prev, forceBackground = false, forceMeaningful = false)
        assertEquals(SampleDiff.PROGRESS_ONLY, diff)
        assertEquals("abc123", sent?.media?.artworkHash)
        assertEquals(1, server.requestCount)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"progressOnly\":true"))
        assertTrue(body.contains("\"artworkHash\":\"abc123\""))
    }

    @Test
    fun pushSample_privacyShield_skipsArtwork_stillSnapshotsText() {
        val art = byteArrayOf(1, 2, 3, 4, 5)
        // HEAD must not carry a body (OkHttp ProtocolException); status alone maps to privacy skip.
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true,"lastSyncedAt":"2026-07-21T12:00:00Z","eventsRecorded":1}"""),
        )
        val base = server.url("/").toString().trimEnd('/')
        val core = AgentCore(
            config = ClientConfig(serverBase = base, displayName = "Phone"),
            client = ApiClient(base),
            credentials = null,
        )
        val sample = CollectedSample(
            media = MediaSession(
                title = "Track",
                artist = null,
                album = null,
                sourceApp = null,
                artworkUrl = null,
                artworkHash = null,
                playbackState = PlaybackState.PLAYING,
                positionMs = 1,
                durationMs = 10,
                updatedAt = "t",
            ),
            artworkBytes = art,
        )
        val (diff, sent) = core.pushSample("t", sample, null, forceBackground = false, forceMeaningful = true)
        assertEquals(SampleDiff.MEANINGFUL, diff)
        assertNull(sent?.media?.artworkHash)
        assertNotNull(sent?.media)
        assertEquals("Track", sent?.media?.title)
        assertEquals(2, server.requestCount) // HEAD fail + snapshot
    }

    @Test
    fun pushSample_headExists_skipsPut() {
        val art = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D)
        val hash = ArtworkAsset.sha256Hex(art)
        server.enqueue(MockResponse().setResponseCode(200)) // HEAD exists
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true,"lastSyncedAt":"2026-07-21T12:00:00Z","eventsRecorded":1}"""),
        )
        val base = server.url("/").toString().trimEnd('/')
        val core = AgentCore(
            config = ClientConfig(serverBase = base),
            client = ApiClient(base),
            credentials = null,
        )
        val sample = CollectedSample(
            media = MediaSession(
                title = "T",
                artist = null,
                album = null,
                sourceApp = null,
                artworkUrl = null,
                artworkHash = null,
                playbackState = PlaybackState.PLAYING,
                positionMs = 0,
                durationMs = 1,
                updatedAt = "t",
            ),
            artworkBytes = art,
        )
        val (_, sent) = core.pushSample("t", sample, null, forceBackground = false, forceMeaningful = true)
        assertEquals(hash, sent?.media?.artworkHash)
        assertEquals(2, server.requestCount)
        assertEquals("HEAD", server.takeRequest().method)
        assertEquals("PUT", server.takeRequest().method) // snapshot only
    }
}
