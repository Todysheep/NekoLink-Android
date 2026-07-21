package app.nekolink.android.domain

import app.nekolink.android.net.ApiClient
import app.nekolink.android.net.ClientError
import app.nekolink.android.protocol.CollectedSample
import app.nekolink.android.protocol.HeartbeatRequest
import app.nekolink.android.protocol.PairRequest
import app.nekolink.android.protocol.Platform
import java.time.Instant

/**
 * Pure orchestration used by tests and the Android agent service.
 * Does not depend on Android framework APIs.
 */
class AgentCore(
    var config: ClientConfig,
    var client: ApiClient,
    var credentials: StoredCredentials?,
) {
    fun pair(pairingCode: String, nowIso: String = Instant.now().toString()): StoredCredentials {
        val req = PairRequest(
            pairingCode = pairingCode.trim(),
            displayName = config.displayName,
            platform = Platform.ANDROID,
        )
        val resp = client.pair(req)
        val creds = StoredCredentials(
            deviceId = resp.deviceId,
            deviceToken = resp.deviceToken,
            displayName = resp.displayName,
            serverBase = config.serverBase,
            pairedAt = nowIso,
        )
        credentials = creds
        return creds
    }

    fun requireToken(): StoredCredentials =
        credentials ?: throw IllegalStateException("not paired")

    fun heartbeatOnce(token: String, atIso: String = Instant.now().toString()) {
        client.heartbeat(token, HeartbeatRequest(at = atIso))
    }

    fun setPrivacyShield(token: String, enabled: Boolean) {
        client.setPrivacyShield(token, enabled)
    }

    /**
     * One-shot sample push. Returns (diff, new lastSent sample or null if unchanged).
     */
    fun pushSample(
        token: String,
        sample: CollectedSample,
        prev: CollectedSample?,
        forceBackground: Boolean,
        forceMeaningful: Boolean,
    ): Pair<SampleDiff?, CollectedSample?> {
        var diff = classifyChange(prev, sample)
        if (forceMeaningful && (diff == SampleDiff.NONE || diff == SampleDiff.PROGRESS_ONLY)) {
            diff = SampleDiff.MEANINGFUL
        }
        return when (diff) {
            SampleDiff.NONE -> SampleDiff.NONE to null
            SampleDiff.PROGRESS_ONLY -> {
                val req = buildSnapshotRequest(
                    sample = sample,
                    displayName = null,
                    includeBackground = false,
                    progressOnly = true,
                    backgroundCap = config.backgroundAppCap,
                )
                client.snapshot(token, req)
                SampleDiff.PROGRESS_ONLY to sample
            }
            SampleDiff.MEANINGFUL -> {
                // Attach background only when forced, first sample, or id-set changed
                // (avoids re-uploading a 12-app list on every foreground switch).
                val includeBg =
                    forceBackground || prev == null || backgroundListChanged(prev, sample)
                val req = buildSnapshotRequest(
                    sample = sample,
                    displayName = config.displayName,
                    includeBackground = includeBg,
                    progressOnly = false,
                    backgroundCap = config.backgroundAppCap,
                )
                client.snapshot(token, req)
                SampleDiff.MEANINGFUL to sample
            }
        }
    }

    fun applyConfig(config: ClientConfig) {
        this.config = config
        this.client = ApiClient(config.serverBase)
    }
}

fun isUnauthorized(error: Throwable): Boolean =
    error is ClientError.Unauthorized ||
        (error.cause is ClientError.Unauthorized)
