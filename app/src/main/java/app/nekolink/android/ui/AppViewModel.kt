package app.nekolink.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.nekolink.android.collector.AndroidCollector
import app.nekolink.android.domain.AgentCore
import app.nekolink.android.domain.AgentPhase
import app.nekolink.android.domain.ClientConfig
import app.nekolink.android.domain.ConfigStateMachine
import app.nekolink.android.domain.UiStatus
import app.nekolink.android.domain.normalizeBase
import app.nekolink.android.net.ApiClient
import app.nekolink.android.net.ClientError
import app.nekolink.android.service.RuntimeStatus
import app.nekolink.android.service.UploadForegroundService
import app.nekolink.android.store.PrefsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val status: UiStatus = UiStatus(),
    val busy: Boolean = false,
    val message: String? = null,
    val usageAccessGranted: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PrefsStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            while (isActive) {
                refreshFromRuntime()
                delay(1500)
            }
        }
    }

    fun refresh() {
        val config = store.loadConfig()
        val creds = store.loadCredentials()
        val paused = store.isPaused()
        val usage = AndroidCollector(getApplication()).hasUsageAccess()
        val machine = ConfigStateMachine.State(
            config = config,
            credentials = creds,
            paused = paused,
            lastError = RuntimeStatus.lastError.get(),
        )
        val status = ConfigStateMachine.toUiStatus(
            state = machine,
            lastSyncAt = RuntimeStatus.lastSyncAt.get(),
            sample = RuntimeStatus.lastSample.get(),
            privacyShield = RuntimeStatus.privacyShield.get(),
        )
        _state.update {
            it.copy(
                status = status,
                usageAccessGranted = usage,
                message = null,
            )
        }
        if (creds != null && !paused) {
            UploadForegroundService.start(getApplication())
        }
    }

    private fun refreshFromRuntime() {
        val config = store.loadConfig()
        val creds = store.loadCredentials()
        val paused = store.isPaused() || RuntimeStatus.paused.get()
        val usage = AndroidCollector(getApplication()).hasUsageAccess()
        val machine = ConfigStateMachine.State(
            config = config,
            credentials = creds,
            paused = paused,
            lastError = RuntimeStatus.lastError.get(),
        )
        val phaseOverride = RuntimeStatus.phase.get()
        var status = ConfigStateMachine.toUiStatus(
            state = machine,
            lastSyncAt = RuntimeStatus.lastSyncAt.get(),
            sample = RuntimeStatus.lastSample.get(),
            privacyShield = RuntimeStatus.privacyShield.get(),
        )
        if (creds != null && phaseOverride != AgentPhase.SETUP) {
            status = status.copy(phase = if (paused) AgentPhase.PAUSED else phaseOverride)
        }
        _state.update {
            it.copy(status = status, usageAccessGranted = usage)
        }
    }

    fun pair(serverBase: String, displayName: String, pairingCode: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                val base = normalizeBase(serverBase)
                val name = displayName.trim().ifBlank { ClientConfig.DEFAULT_DISPLAY_NAME }
                val config = store.loadConfig().copy(serverBase = base, displayName = name)
                store.saveConfig(config)
                val core = AgentCore(
                    config = config,
                    client = ApiClient(base),
                    credentials = null,
                )
                val creds = withContext(Dispatchers.IO) {
                    core.pair(pairingCode)
                }
                store.saveCredentials(creds)
                store.setPaused(false)
                RuntimeStatus.privacyShield.set(false)
                RuntimeStatus.lastError.set(null)
                UploadForegroundService.start(getApplication())
                refresh()
                _state.update { it.copy(busy = false, message = "配对成功") }
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, message = mapError(e))
                }
            }
        }
    }

    fun unpair() {
        viewModelScope.launch {
            UploadForegroundService.stop(getApplication())
            store.clearCredentials()
            store.setPaused(false)
            RuntimeStatus.phase.set(AgentPhase.SETUP)
            RuntimeStatus.lastSample.set(null)
            RuntimeStatus.lastSyncAt.set(null)
            RuntimeStatus.deviceId.set(null)
            refresh()
        }
    }

    fun setPaused(paused: Boolean) {
        store.setPaused(paused)
        RuntimeStatus.paused.set(paused)
        if (paused) {
            RuntimeStatus.phase.set(AgentPhase.PAUSED)
        } else {
            RuntimeStatus.phase.set(AgentPhase.RUNNING)
            if (store.loadCredentials() != null) {
                UploadForegroundService.start(getApplication())
            }
        }
        refresh()
    }

    fun saveDisplayName(name: String) {
        val config = store.loadConfig().copy(displayName = name.trim())
        store.saveConfig(config)
        store.loadCredentials()?.let { c ->
            store.saveCredentials(c.copy(displayName = name.trim()))
        }
        RuntimeStatus.displayName.set(name.trim())
        refresh()
    }

    fun setShowSystemBackground(show: Boolean) {
        val config = store.loadConfig().copy(showSystemBackground = show)
        store.saveConfig(config)
        refresh()
    }

    fun setAutostart(enabled: Boolean) {
        val config = store.loadConfig().copy(autostart = enabled)
        store.saveConfig(config)
        // Actual BOOT_COMPLETED is handled by receiver when flag is true.
        refresh()
    }

    /**
     * Confirmed server URL change: clear credentials and return to setup.
     */
    fun changeServerUrlConfirmed(newServerBase: String) {
        viewModelScope.launch {
            UploadForegroundService.stop(getApplication())
            val base = normalizeBase(newServerBase)
            val config = store.loadConfig().copy(serverBase = base)
            store.saveConfig(config)
            store.clearCredentials()
            store.setPaused(false)
            RuntimeStatus.phase.set(AgentPhase.SETUP)
            RuntimeStatus.deviceId.set(null)
            RuntimeStatus.lastSample.set(null)
            refresh()
        }
    }

    fun setPrivacyShield(enabled: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                val creds = store.loadCredentials()
                    ?: throw IllegalStateException("未配对")
                val config = store.loadConfig()
                withContext(Dispatchers.IO) {
                    ApiClient(config.serverBase).setPrivacyShield(creds.deviceToken, enabled)
                }
                RuntimeStatus.privacyShield.set(enabled)
                _state.update {
                    it.copy(
                        busy = false,
                        message = if (enabled) "已开启禁止视奸" else "已关闭禁止视奸",
                        status = it.status.copy(privacyShield = enabled),
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = mapError(e)) }
            }
        }
    }

    fun refreshPrivacyShieldFromBoard() {
        viewModelScope.launch {
            try {
                val config = store.loadConfig()
                val flag = withContext(Dispatchers.IO) {
                    ApiClient(config.serverBase).getPrivacyShield()
                }
                RuntimeStatus.privacyShield.set(flag)
                refreshFromRuntime()
            } catch (_: Exception) {
                // ignore probe failures
            }
        }
    }

    fun stopServiceAndFinish() {
        UploadForegroundService.stop(getApplication())
        RuntimeStatus.phase.set(AgentPhase.STOPPED)
    }

    private fun mapError(e: Exception): String = when (e) {
        is ClientError.InvalidPairingCode -> "注册码无效或已过期"
        is ClientError.Unauthorized -> "凭证无效或已吊销，请重新配对"
        is ClientError.Network -> "网络错误：${e.message}"
        is ClientError.Server -> "服务器错误 (${e.status})：${e.message}"
        is ClientError.Decode -> "响应解析失败：${e.message}"
        else -> e.message ?: e.toString()
    }
}
