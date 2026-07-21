package app.nekolink.android.domain

import app.nekolink.android.protocol.CollectedSample
import kotlinx.serialization.Serializable

@Serializable
data class ClientConfig(
    val serverBase: String = DEFAULT_SERVER,
    val displayName: String = DEFAULT_DISPLAY_NAME,
    val heartbeatIntervalSecs: Long = 20,
    val pollIntervalSecs: Long = 2,
    /** Force full background list refresh interval (seconds). */
    val backgroundIntervalSecs: Long = 90,
    /** Max background apps in snapshot (Android v2 default 12). */
    val backgroundAppCap: Int = BackgroundFilter.DEFAULT_CAP,
    val showSystemBackground: Boolean = false,
    val autostart: Boolean = false,
) {
    companion object {
        const val DEFAULT_SERVER = "http://127.0.0.1:8080"
        const val DEFAULT_DISPLAY_NAME = "Android"
    }
}

@Serializable
data class StoredCredentials(
    val deviceId: String,
    val deviceToken: String,
    val displayName: String,
    val serverBase: String,
    val pairedAt: String,
)

enum class AgentPhase {
    SETUP,
    RUNNING,
    PAUSED,
    ERROR,
    STOPPED,
}

data class UiStatus(
    val paired: Boolean = false,
    val phase: AgentPhase = AgentPhase.SETUP,
    val paused: Boolean = false,
    val privacyShield: Boolean = false,
    val serverBase: String = ClientConfig.DEFAULT_SERVER,
    val displayName: String = ClientConfig.DEFAULT_DISPLAY_NAME,
    val deviceId: String? = null,
    val lastSyncAt: String? = null,
    val lastError: String? = null,
    val foregroundSummary: String? = null,
    val mediaSummary: String? = null,
    val partialPermissions: Boolean = false,
    val showSystemBackground: Boolean = false,
    val autostart: Boolean = false,
)

fun normalizeBase(url: String): String =
    url.trim().trimEnd('/')

/**
 * Config / credential state machine (pure).
 * Changing server URL after pairing clears credentials (ADR 0012 Windows parity).
 */
object ConfigStateMachine {
    data class State(
        val config: ClientConfig = ClientConfig(),
        val credentials: StoredCredentials? = null,
        val paused: Boolean = false,
        val lastError: String? = null,
    ) {
        val isPaired: Boolean get() = credentials != null
    }

    fun applyPair(
        state: State,
        creds: StoredCredentials,
        displayName: String? = null,
    ): State {
        val name = displayName?.takeIf { it.isNotBlank() } ?: creds.displayName
        return state.copy(
            config = state.config.copy(
                serverBase = normalizeBase(creds.serverBase),
                displayName = name,
            ),
            credentials = creds.copy(
                serverBase = normalizeBase(creds.serverBase),
                displayName = name,
            ),
            lastError = null,
            paused = false,
        )
    }

    fun unpair(state: State): State =
        state.copy(credentials = null, paused = false, lastError = null)

    /**
     * Confirm change of server URL: clear credentials, keep display name, write new base.
     * Caller must have already confirmed with the user.
     */
    fun changeServerUrlConfirmed(state: State, newServerBase: String): State {
        val base = normalizeBase(newServerBase)
        return state.copy(
            config = state.config.copy(serverBase = base),
            credentials = null,
            paused = false,
            lastError = null,
        )
    }

    fun updateDisplayName(state: State, displayName: String): State {
        val name = displayName.trim()
        val creds = state.credentials?.copy(displayName = name)
        return state.copy(
            config = state.config.copy(displayName = name),
            credentials = creds,
        )
    }

    fun setPaused(state: State, paused: Boolean): State =
        state.copy(paused = paused)

    fun setShowSystemBackground(state: State, show: Boolean): State =
        state.copy(config = state.config.copy(showSystemBackground = show))

    fun setAutostart(state: State, enabled: Boolean): State =
        state.copy(config = state.config.copy(autostart = enabled))

    fun setLastError(state: State, error: String?): State =
        state.copy(lastError = error)

    fun toUiStatus(
        state: State,
        lastSyncAt: String? = null,
        sample: CollectedSample? = null,
        privacyShield: Boolean = false,
    ): UiStatus {
        val phase = when {
            !state.isPaired -> AgentPhase.SETUP
            state.paused -> AgentPhase.PAUSED
            state.lastError != null -> AgentPhase.ERROR
            else -> AgentPhase.RUNNING
        }
        val fg = sample?.foreground?.let { f ->
            val app = f.appName ?: "未知应用"
            val title = f.title?.let { " · $it" } ?: ""
            "$app$title"
        }
        val media = sample?.media?.let { m ->
            val artist = m.artist?.let { " — $it" } ?: ""
            "${m.title}$artist"
        }
        return UiStatus(
            paired = state.isPaired,
            phase = phase,
            paused = state.paused,
            privacyShield = privacyShield,
            serverBase = state.config.serverBase,
            displayName = state.config.displayName,
            deviceId = state.credentials?.deviceId,
            lastSyncAt = lastSyncAt,
            lastError = state.lastError,
            foregroundSummary = fg,
            mediaSummary = media,
            partialPermissions = sample?.device?.partialPermissions == true,
            showSystemBackground = state.config.showSystemBackground,
            autostart = state.config.autostart,
        )
    }
}
