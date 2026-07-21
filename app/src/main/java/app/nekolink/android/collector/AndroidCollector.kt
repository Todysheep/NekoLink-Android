package app.nekolink.android.collector

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import app.nekolink.android.domain.BackgroundFilter
import app.nekolink.android.protocol.BackgroundApp
import app.nekolink.android.protocol.CollectedSample
import app.nekolink.android.protocol.DeviceSummary
import app.nekolink.android.protocol.ForegroundApp
import app.nekolink.android.protocol.ForegroundKind
import app.nekolink.android.protocol.MediaSession
import app.nekolink.android.service.MediaNotificationListener
import java.time.Instant

/**
 * Android UsageStats / MediaSession / battery collector (ADR 0010).
 * Degrades honestly when permissions are missing (partialPermissions=true).
 *
 * Media path (production):
 * [sample] → [sampleMedia] → [MediaSessionSamplePath.sampleForPackage]
 * → builds NLS [ComponentName] → [activeMediaSessions] (`msm.getActiveSessions(cn)`)
 * → [MediaMapper] G1 fields.
 *
 * [activeMediaSessions] is injectable so unit tests drive **[sample]** (the real
 * collector entry) with a real NLS ComponentName without a device.
 */
class AndroidCollector(
    private val context: Context,
    private val showSystemBackground: Boolean = false,
    private val backgroundCap: Int = BackgroundFilter.DEFAULT_CAP,
    /**
     * Production default: `MediaSessionManager.getActiveSessions(listenerComponent)`.
     * Tests inject a fake that still receives the NLS ComponentName built by
     * [MediaSessionSamplePath.sampleForPackage].
     */
    private val activeMediaSessions: ((ComponentName) -> List<MediaSessionSamplePath.ControllerFields>)? = null,
) : Collector {

    override fun sample(): CollectedSample {
        val usageGranted = hasUsageAccess()
        val foreground = if (usageGranted) sampleForeground() else null
        val (backgroundApps, hidden) = if (usageGranted) {
            sampleBackground()
        } else {
            emptyList<BackgroundApp>() to 0
        }
        val media = sampleMedia()
        val device = DeviceSummary(
            batteryPercent = batteryPercent(),
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            partialPermissions = !usageGranted,
        )
        return CollectedSample(
            foreground = foreground,
            media = media,
            backgroundApps = backgroundApps,
            backgroundHiddenCount = hidden,
            device = device,
        )
    }

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                "android:get_usage_stats",
                android.os.Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                "android:get_usage_stats",
                android.os.Process.myUid(),
                context.packageName,
            )
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun sampleForeground(): ForegroundApp? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val end = System.currentTimeMillis()
        val begin = end - 60_000L
        val events = usm.queryEvents(begin, end)
        val event = UsageEvents.Event()
        var lastPackage: String? = null
        var lastClass: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                lastPackage = event.packageName
                lastClass = event.className
            }
        }
        val pkg = lastPackage ?: return ForegroundApp(
            kind = ForegroundKind.DESKTOP,
            appName = "主屏幕",
            title = null,
        )
        if (pkg == context.packageName) {
            return ForegroundApp(
                kind = ForegroundKind.APP,
                appName = labelFor(pkg),
                title = null,
            )
        }
        return ForegroundApp(
            kind = ForegroundKind.APP,
            appName = labelFor(pkg),
            // Window title is generally unavailable on Android without accessibility.
            title = null,
        ).also { _ -> lastClass }
    }

    private fun sampleBackground(): Pair<List<BackgroundApp>, Int> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList<BackgroundApp>() to 0
        val end = System.currentTimeMillis()
        val begin = end - 24L * 60 * 60 * 1000
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
            ?: emptyList()
        val sorted = stats
            .filter { it.lastTimeUsed > 0 }
            .sortedByDescending { it.lastTimeUsed }
        val raw = sorted.mapNotNull { st ->
            val pkg = st.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            BackgroundApp(
                id = pkg,
                name = labelFor(pkg),
                title = null,
            )
        }.distinctBy { it.id }
        val (kept, hidden, _) = BackgroundFilter.filterAndCap(
            raw = raw,
            showSystem = showSystemBackground,
            cap = backgroundCap,
        )
        return kept to hidden
    }

    /**
     * Production media sample:
     * - default: [MediaSessionManagerBridge.sampleUsingManager]
     *   → NLS ComponentName → `msm.getActiveSessions(cn)` → G1 map
     * - tests may inject [activeMediaSessions] to stub only the MSM side
     */
    private fun sampleMedia(): MediaSession? {
        return try {
            if (activeMediaSessions != null) {
                MediaSessionSamplePath.sampleForPackage(
                    packageName = context.packageName,
                    getActiveSessions = activeMediaSessions,
                    updatedAt = Instant.now().toString(),
                )
            } else {
                val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                    ?: return null
                // Default production path — NLS ComponentName only (never a null listener)
                MediaSessionManagerBridge.sampleUsingManager(
                    packageName = context.packageName,
                    msm = msm,
                    extract = ::extractControllerFields,
                    updatedAt = Instant.now().toString(),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractControllerFields(c: MediaController): MediaSessionSamplePath.ControllerFields {
        val meta = c.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val state = c.playbackState
        return MediaSessionSamplePath.ControllerFields(
            packageName = c.packageName,
            title = title,
            artist = artist,
            album = album,
            androidPlaybackState = state?.state,
            positionMs = state?.position,
            durationMs = duration,
        )
    }

    private fun batteryPercent(): Int? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return ((level * 100f) / scale).toInt().coerceIn(0, 100)
    }

    private fun labelFor(packageName: String): String {
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    fun hasNotificationListenerAccess(): Boolean =
        Companion.hasNotificationListenerAccess(context)

    companion object {
        fun notificationListenerComponent(context: Context): ComponentName =
            ComponentName(context, MediaNotificationListener::class.java)

        fun usageAccessSettingsIntent(): Intent =
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

        /**
         * Open system UI to grant notification listener access for our NLS component.
         * On API 30+ deep-links to this app's entry when possible.
         */
        fun notificationListenerSettingsIntent(context: Context): Intent {
            val component = notificationListenerComponent(context)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                    putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        component.flattenToString(),
                    )
                }
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
        }

        fun hasNotificationListenerAccess(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            val flat = notificationListenerComponent(context).flattenToString()
            return enabled.split(':').any { it.equals(flat, ignoreCase = true) }
        }
    }
}
