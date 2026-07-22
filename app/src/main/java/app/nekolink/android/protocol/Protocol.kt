package app.nekolink.android.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire types aligned with monorepo `packages/protocol` camelCase JSON. */

@Serializable
enum class Platform {
    @SerialName("windows")
    WINDOWS,

    @SerialName("android")
    ANDROID,

    @SerialName("unknown")
    UNKNOWN,
}

@Serializable
enum class PlaybackState {
    @SerialName("playing")
    PLAYING,

    @SerialName("paused")
    PAUSED,

    @SerialName("stopped")
    STOPPED,

    @SerialName("unknown")
    UNKNOWN,
}

@Serializable
enum class ForegroundKind {
    @SerialName("app")
    APP,

    @SerialName("desktop")
    DESKTOP,

    @SerialName("lock_screen")
    LOCK_SCREEN,

    @SerialName("unknown")
    UNKNOWN,
}

@Serializable
data class MediaSession(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val sourceApp: String? = null,
    val artworkUrl: String? = null,
    val artworkHash: String? = null,
    val playbackState: PlaybackState,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val updatedAt: String,
)

@Serializable
data class ForegroundApp(
    val kind: ForegroundKind? = null,
    val appName: String? = null,
    val title: String? = null,
    val iconUrl: String? = null,
    val iconHash: String? = null,
)

@Serializable
data class BackgroundApp(
    val id: String,
    val name: String,
    val title: String? = null,
    val iconUrl: String? = null,
    val iconHash: String? = null,
)

@Serializable
data class DeviceSummary(
    val batteryPercent: Int? = null,
    val osVersion: String? = null,
    val partialPermissions: Boolean = false,
)

@Serializable
data class PairRequest(
    val pairingCode: String,
    val displayName: String,
    val platform: Platform,
)

@Serializable
data class PairResponse(
    val deviceId: String,
    val deviceToken: String,
    val displayName: String,
    val platform: Platform,
)

@Serializable
data class HeartbeatRequest(
    val at: String? = null,
)

@Serializable
data class SnapshotIngestRequest(
    val displayName: String? = null,
    val foreground: ForegroundApp? = null,
    val media: MediaSession? = null,
    val mediaClear: Boolean = false,
    val backgroundApps: List<BackgroundApp>? = null,
    val backgroundHiddenCount: Int? = null,
    val device: DeviceSummary? = null,
    val progressOnly: Boolean = false,
)

@Serializable
data class IngestAck(
    val ok: Boolean,
    val lastSyncedAt: String,
    val eventsRecorded: Int = 0,
)

@Serializable
data class ApiErrorBody(
    val error: String,
    val code: String? = null,
)

@Serializable
data class SetPrivacyShieldRequest(
    val enabled: Boolean,
)

/** Local sample before building ingest body. */
data class CollectedSample(
    val foreground: ForegroundApp? = null,
    val media: MediaSession? = null,
    /**
     * Compressed album-art bytes from MediaSession metadata (not on the wire).
     * [MediaSession.artworkHash] is filled only after AgentCore ensure (HEAD/PUT) succeeds.
     */
    val artworkBytes: ByteArray? = null,
    val backgroundApps: List<BackgroundApp> = emptyList(),
    val backgroundHiddenCount: Int = 0,
    val device: DeviceSummary? = null,
)
