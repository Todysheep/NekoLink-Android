package app.nekolink.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.nekolink.android.service.UploadForegroundService
import app.nekolink.android.store.PrefsStore

/** Optional autostart when paired and autostart flag is on (ADR 0004 Windows parity). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val store = PrefsStore(context)
        val config = store.loadConfig()
        if (!config.autostart) return
        if (store.loadCredentials() == null) return
        if (store.isPaused()) return
        UploadForegroundService.start(context)
    }
}
