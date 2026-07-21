package app.nekolink.android

import app.nekolink.android.domain.AgentCore
import app.nekolink.android.domain.ClientConfig
import app.nekolink.android.domain.SampleDiff
import app.nekolink.android.net.ApiClient
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
}
