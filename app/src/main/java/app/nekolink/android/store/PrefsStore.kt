package app.nekolink.android.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.nekolink.android.domain.ClientConfig
import app.nekolink.android.domain.StoredCredentials
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Local config + credential storage.
 * Token is stored in private SharedPreferences (MODE_PRIVATE); not shown in UI by default.
 */
class PrefsStore(
    context: Context,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadConfig(): ClientConfig {
        val text = prefs.getString(KEY_CONFIG, null) ?: return ClientConfig()
        return runCatching { json.decodeFromString<ClientConfig>(text) }.getOrDefault(ClientConfig())
    }

    fun saveConfig(config: ClientConfig) {
        prefs.edit { putString(KEY_CONFIG, json.encodeToString(config)) }
    }

    fun loadCredentials(): StoredCredentials? {
        val text = prefs.getString(KEY_CREDS, null) ?: return null
        return runCatching { json.decodeFromString<StoredCredentials>(text) }.getOrNull()
    }

    fun saveCredentials(creds: StoredCredentials) {
        prefs.edit { putString(KEY_CREDS, json.encodeToString(creds)) }
    }

    fun clearCredentials() {
        prefs.edit { remove(KEY_CREDS) }
    }

    fun isPaused(): Boolean = prefs.getBoolean(KEY_PAUSED, false)

    fun setPaused(paused: Boolean) {
        prefs.edit { putBoolean(KEY_PAUSED, paused) }
    }

    companion object {
        private const val PREFS_NAME = "nekolink_android"
        private const val KEY_CONFIG = "config_json"
        private const val KEY_CREDS = "credentials_json"
        private const val KEY_PAUSED = "paused"
    }
}
