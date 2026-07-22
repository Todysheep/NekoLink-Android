package app.nekolink.android.domain

import app.nekolink.android.net.ApiClient
import app.nekolink.android.net.ArtworkAsset
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
    /** Session-local set of hashes already confirmed on the server (HEAD 200 or PUT ok). */
    private val knownHashes: MutableSet<String> = mutableSetOf()

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
     *
     * Artwork: collector leaves [media.artworkHash] null; we attach prev hash for identity,
     * then on MEANINGFUL ensure (HEAD/PUT) before snapshot so progress ticks keep the cover.
     */
    fun pushSample(
        token: String,
        sample: CollectedSample,
        prev: CollectedSample?,
        forceBackground: Boolean,
        forceMeaningful: Boolean,
    ): Pair<SampleDiff?, CollectedSample?> {
        // Carry hash from lastSent so mediaIdentityEq does not thrash MEANINGFUL every poll.
        val withHash = attachPrevArtworkHash(sample, prev)
        var diff = classifyChange(prev, withHash)
        if (forceMeaningful && (diff == SampleDiff.NONE || diff == SampleDiff.PROGRESS_ONLY)) {
            diff = SampleDiff.MEANINGFUL
        }
        return when (diff) {
            SampleDiff.NONE -> SampleDiff.NONE to null
            SampleDiff.PROGRESS_ONLY -> {
                // No network for assets; hash already copied when core identity matched.
                val ensured = ensureArtworkOnSample(withHash, prev, token, doUpload = false)
                val req = buildSnapshotRequest(
                    sample = ensured,
                    displayName = null,
                    includeBackground = false,
                    progressOnly = true,
                    backgroundCap = config.backgroundAppCap,
                )
                client.snapshot(token, req)
                SampleDiff.PROGRESS_ONLY to ensured
            }
            SampleDiff.MEANINGFUL -> {
                val ensured = ensureArtworkOnSample(withHash, prev, token, doUpload = true)
                // Attach background only when forced, first sample, or id-set changed
                // (avoids re-uploading a 12-app list on every foreground switch).
                val includeBg =
                    forceBackground || prev == null || backgroundListChanged(prev, ensured)
                val req = buildSnapshotRequest(
                    sample = ensured,
                    displayName = config.displayName,
                    includeBackground = includeBg,
                    progressOnly = false,
                    backgroundCap = config.backgroundAppCap,
                )
                client.snapshot(token, req)
                SampleDiff.MEANINGFUL to ensured
            }
        }
    }

    /**
     * Copy [MediaSession.artworkHash] from [prev] when the track/playback core identity matches
     * and the new sample has no hash yet (collector always leaves hash null).
     */
    fun attachPrevArtworkHash(sample: CollectedSample, prev: CollectedSample?): CollectedSample {
        val media = sample.media ?: return sample
        if (media.artworkHash != null) return sample
        val prevMedia = prev?.media ?: return sample
        val prevHash = prevMedia.artworkHash ?: return sample
        if (!mediaCoreIdentityEq(prevMedia, media)) return sample
        return sample.copy(media = media.copy(artworkHash = prevHash))
    }

    /**
     * Ensure artwork asset is on the server (when [doUpload]) and set [media.artworkHash].
     * Progress-only: no HEAD/PUT; keep hash from [attachPrevArtworkHash] if present.
     * On non-auth ensure failure: snapshot without hash (no retry storm).
     * [ClientError.Unauthorized] still propagates.
     */
    fun ensureArtworkOnSample(
        sample: CollectedSample,
        @Suppress("UNUSED_PARAMETER") prev: CollectedSample?,
        token: String,
        doUpload: Boolean,
    ): CollectedSample {
        val media = sample.media
            ?: return sample.copy(artworkBytes = null)

        if (!doUpload) {
            // Progress path: no asset network; drop raw bytes from lastSent to save memory.
            return sample.copy(artworkBytes = null)
        }

        val bytes = sample.artworkBytes
        if (bytes == null || bytes.isEmpty()) {
            // No art → honest null hash (keep existing hash only if same track already had one).
            return sample.copy(artworkBytes = null)
        }

        val prepared = ArtworkAsset.prepareArtwork(bytes)
        if (prepared == null) {
            // Oversize / empty after prepare → skip artwork
            return sample.copy(
                media = media.copy(artworkHash = null),
                artworkBytes = null,
            )
        }

        if (prepared.hash in knownHashes || media.artworkHash == prepared.hash) {
            knownHashes.add(prepared.hash)
            return sample.copy(
                media = media.copy(artworkHash = prepared.hash),
                artworkBytes = null,
            )
        }

        return try {
            if (!client.assetExists(token, prepared.hash)) {
                client.uploadAsset(token, prepared.hash, prepared.bytes, prepared.contentType)
            }
            knownHashes.add(prepared.hash)
            sample.copy(
                media = media.copy(artworkHash = prepared.hash),
                artworkBytes = null,
            )
        } catch (e: ClientError.Unauthorized) {
            throw e
        } catch (_: ClientError) {
            // privacy_shield 403, network, server — send text media without hash
            sample.copy(
                media = media.copy(artworkHash = null),
                artworkBytes = null,
            )
        } catch (_: Exception) {
            sample.copy(
                media = media.copy(artworkHash = null),
                artworkBytes = null,
            )
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
