package app.nekolink.android.net

import app.nekolink.android.domain.normalizeBase
import app.nekolink.android.protocol.ApiErrorBody
import app.nekolink.android.protocol.HeartbeatRequest
import app.nekolink.android.protocol.IngestAck
import app.nekolink.android.protocol.PairRequest
import app.nekolink.android.protocol.PairResponse
import app.nekolink.android.protocol.SetPrivacyShieldRequest
import app.nekolink.android.protocol.SnapshotIngestRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class ClientError : Exception() {
    data class Network(override val message: String, override val cause: Throwable? = null) : ClientError()
    data object InvalidPairingCode : ClientError() {
        private fun readResolve(): Any = InvalidPairingCode
        override val message: String = "invalid pairing code"
    }
    data object Unauthorized : ClientError() {
        private fun readResolve(): Any = Unauthorized
        override val message: String = "unauthorized (token invalid or revoked)"
    }
    data class Server(val status: Int, override val message: String) : ClientError()
    data class Decode(override val message: String) : ClientError()
}

class ApiClient(
    serverBase: String,
    private val http: OkHttpClient = defaultClient(),
    private val json: Json = defaultJson(),
    private val userAgent: String = "neko-link-android/0.1.0",
) {
    private val base: String = normalizeBase(serverBase)
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun base(): String = base

    fun pair(req: PairRequest): PairResponse {
        val body = json.encodeToString(req)
        val request = Request.Builder()
            .url(url("/api/v1/device/pair"))
            .post(body.toRequestBody(jsonMedia))
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/json")
            .build()
        return mapJson(execute(request))
    }

    fun heartbeat(token: String, req: HeartbeatRequest): IngestAck {
        val body = json.encodeToString(req)
        val request = Request.Builder()
            .url(url("/api/v1/device/heartbeat"))
            .post(body.toRequestBody(jsonMedia))
            .header("Authorization", "Bearer $token")
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/json")
            .build()
        return mapJson(execute(request))
    }

    fun snapshot(token: String, req: SnapshotIngestRequest): IngestAck {
        val body = json.encodeToString(req)
        val request = Request.Builder()
            .url(url("/api/v1/device/snapshot"))
            .put(body.toRequestBody(jsonMedia))
            .header("Authorization", "Bearer $token")
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/json")
            .build()
        return mapJson(execute(request))
    }

    fun setPrivacyShield(token: String, enabled: Boolean) {
        val body = json.encodeToString(SetPrivacyShieldRequest(enabled = enabled))
        val request = Request.Builder()
            .url(url("/api/v1/device/privacy-shield"))
            .post(body.toRequestBody(jsonMedia))
            .header("Authorization", "Bearer $token")
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/json")
            .build()
        val (status, bytes) = execute(request)
        if (status == 401) throw ClientError.Unauthorized
        if (status == 204 || status in 200..299) return
        val message = String(bytes, Charsets.UTF_8)
        val err = runCatching { json.decodeFromString<ApiErrorBody>(message) }.getOrNull()
        throw ClientError.Server(status, err?.error ?: message)
    }

    fun getPrivacyShield(): Boolean {
        val request = Request.Builder()
            .url(url("/api/v1/board"))
            .get()
            .header("User-Agent", userAgent)
            .build()
        val (status, bytes) = execute(request)
        if (status !in 200..299) {
            val message = String(bytes, Charsets.UTF_8)
            throw ClientError.Server(status, message)
        }
        val text = String(bytes, Charsets.UTF_8)
        val obj = runCatching {
            json.parseToJsonElement(text).jsonObject
        }.getOrElse { throw ClientError.Decode(it.message ?: "decode board") }
        return obj["privacyShield"]?.jsonPrimitive?.booleanOrNull ?: false
    }

    /**
     * HEAD `/api/v1/device/assets/{hash}` — true when asset already exists (200), false on 404.
     * 401 → [ClientError.Unauthorized]; 403 privacy_shield → [ClientError.Server].
     */
    fun assetExists(token: String, hash: String): Boolean {
        val request = Request.Builder()
            .url(url("/api/v1/device/assets/$hash"))
            .method("HEAD", null)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", userAgent)
            .build()
        val (status, bytes) = execute(request)
        when (status) {
            200 -> return true
            404 -> return false
            401 -> throw ClientError.Unauthorized
            else -> throw mapAssetError(status, bytes)
        }
    }

    /**
     * PUT `/api/v1/device/assets/{hash}` with raw body; server verifies SHA-256 of body matches [hash].
     */
    fun uploadAsset(token: String, hash: String, bytes: ByteArray, contentType: String) {
        val mediaType = contentType.toMediaType()
        val request = Request.Builder()
            .url(url("/api/v1/device/assets/$hash"))
            .put(bytes.toRequestBody(mediaType))
            .header("Authorization", "Bearer $token")
            .header("User-Agent", userAgent)
            .header("Content-Type", contentType)
            .build()
        val (status, body) = execute(request)
        if (status == 401) throw ClientError.Unauthorized
        if (status in 200..299) return
        throw mapAssetError(status, body)
    }

    private fun mapAssetError(status: Int, bytes: ByteArray): ClientError {
        val text = String(bytes, Charsets.UTF_8)
        val err = runCatching { json.decodeFromString<ApiErrorBody>(text) }.getOrNull()
        if (status == 401 || err?.code == "revoked" || err?.code == "unauthorized") {
            return ClientError.Unauthorized
        }
        if (status == 403 || err?.code == "privacy_shield") {
            return ClientError.Server(403, err?.error ?: err?.code ?: "privacy_shield")
        }
        return ClientError.Server(status, err?.error ?: text.ifBlank { "asset error $status" })
    }

    private fun url(path: String): String = "$base$path"

    private fun execute(request: Request): Pair<Int, ByteArray> {
        return try {
            http.newCall(request).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                resp.code to bytes
            }
        } catch (e: IOException) {
            throw ClientError.Network(e.message ?: "network error", e)
        }
    }

    private inline fun <reified T> mapJson(result: Pair<Int, ByteArray>): T {
        val (status, bytes) = result
        val text = String(bytes, Charsets.UTF_8)
        if (status == 401) throw ClientError.Unauthorized
        if (status == 400) {
            val err = runCatching { json.decodeFromString<ApiErrorBody>(text) }.getOrNull()
            if (err?.code == "invalid_pairing_code" ||
                (err?.error?.contains("pairing", ignoreCase = true) == true)
            ) {
                throw ClientError.InvalidPairingCode
            }
        }
        if (status !in 200..299) {
            val err = runCatching { json.decodeFromString<ApiErrorBody>(text) }.getOrNull()
            if (err?.code == "revoked" || err?.code == "unauthorized") {
                throw ClientError.Unauthorized
            }
            throw ClientError.Server(status, err?.error ?: text)
        }
        return try {
            json.decodeFromString(text)
        } catch (e: Exception) {
            throw ClientError.Decode(e.message ?: "decode failed")
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
