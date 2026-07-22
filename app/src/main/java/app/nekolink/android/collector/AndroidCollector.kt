package app.nekolink.android.collector

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import app.nekolink.android.domain.BackgroundFilter
import app.nekolink.android.net.ArtworkAsset
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
 * **Only** media path (no alternate inject):
 * [sample] → [sampleMedia]
 *   → [MediaSessionManagerBridge.sampleUsingManager]
 *     → `context.getSystemService(MEDIA_SESSION_SERVICE)`
 *     → NLS [ComponentName] ([MediaNotificationListener])
 *     → `msm.getActiveSessions(nlsComponent)`  // never null
 *     → [MediaMapper] G1 fields
 *
 * Unit tests drive this path via Context spy + mock [MediaSessionManager].
 */
class AndroidCollector(
    private val context: Context,
    private val showSystemBackground: Boolean = false,
    private val backgroundCap: Int = BackgroundFilter.DEFAULT_CAP,
) : Collector {

    override fun sample(): CollectedSample {
        val usageGranted = hasUsageAccess()
        val (foreground, foregroundPackage) = if (usageGranted) {
            sampleForegroundWithPackage()
        } else {
            null to null
        }
        val (backgroundApps, hidden) = if (usageGranted) {
            sampleBackground(excludePackage = foregroundPackage)
        } else {
            emptyList<BackgroundApp>() to 0
        }
        val (media, artworkBytes) = sampleMedia()
        val device = DeviceSummary(
            batteryPercent = batteryPercent(),
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            partialPermissions = !usageGranted,
        )
        return CollectedSample(
            foreground = foreground,
            media = media,
            artworkBytes = artworkBytes,
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

    private fun sampleForegroundWithPackage(): Pair<ForegroundApp?, String?> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null to null
        val end = System.currentTimeMillis()
        val begin = end - 60_000L
        val events = usm.queryEvents(begin, end)
        val event = UsageEvents.Event()
        var lastPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                lastPackage = event.packageName
            }
        }
        val pkg = lastPackage ?: return ForegroundApp(
            kind = ForegroundKind.DESKTOP,
            appName = "主屏幕",
            title = null,
        ) to null
        val fg = ForegroundApp(
            kind = ForegroundKind.APP,
            appName = labelFor(pkg),
            // Window title is generally unavailable on Android without accessibility.
            title = null,
        )
        return fg to pkg
    }

    /**
     * Recent-user apps for the secondary board card (not a process dump).
     *
     * Policy (filterProfile v2):
     * - UsageStats window: [BackgroundFilter.RECENT_WINDOW_MS] (2h)
     * - min totalTimeInForeground: [BackgroundFilter.MIN_FOREGROUND_MS]
     * - exclude self + current foreground package
     * - denylist + (default) hide FLAG_SYSTEM without LAUNCHER
     * - hard cap [backgroundCap] (default 12)
     */
    private fun sampleBackground(excludePackage: String?): Pair<List<BackgroundApp>, Int> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList<BackgroundApp>() to 0
        val end = System.currentTimeMillis()
        val begin = end - BackgroundFilter.QUERY_SPAN_MS
        // INTERVAL_BEST (API 28+) picks the most granular bucket that covers the span;
        // older APIs fall back to DAILY (still filtered by our 2h recency window).
        val interval = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            UsageStatsManager.INTERVAL_BEST
        } else {
            UsageStatsManager.INTERVAL_DAILY
        }
        val stats = usm.queryUsageStats(interval, begin, end) ?: emptyList()

        val exclude = buildSet {
            add(context.packageName)
            excludePackage?.takeIf { it.isNotBlank() }?.let { add(it) }
        }

        val recent = BackgroundFilter.selectRecentCandidates(
            raw = stats.mapNotNull { st ->
                val pkg = st.packageName ?: return@mapNotNull null
                BackgroundFilter.UsageCandidate(
                    packageName = pkg,
                    lastTimeUsed = st.lastTimeUsed,
                    totalTimeInForeground = st.totalTimeInForeground,
                )
            },
            nowMs = end,
            windowMs = BackgroundFilter.RECENT_WINDOW_MS,
            minForegroundMs = BackgroundFilter.MIN_FOREGROUND_MS,
            excludePackages = exclude,
        )

        var hiddenByUserFacing = 0
        val raw = recent.mapNotNull { c ->
            val pkg = c.packageName
            if (!showSystemBackground && !isUserFacingApp(pkg)) {
                hiddenByUserFacing++
                return@mapNotNull null
            }
            BackgroundApp(
                id = pkg,
                name = labelFor(pkg),
                title = null,
            )
        }

        val (kept, hiddenByFilter, _) = BackgroundFilter.filterAndCap(
            raw = raw,
            showSystem = showSystemBackground,
            cap = backgroundCap,
        )
        return kept to (hiddenByUserFacing + hiddenByFilter)
    }

    /**
     * Prefer apps with a launcher icon; hide pure system services when showSystem is off.
     * Fail-open to true when PackageManager cannot resolve (still denylisted later).
     */
    private fun isUserFacingApp(packageName: String): Boolean {
        val pm = context.packageManager
        return try {
            val ai = pm.getApplicationInfo(packageName, 0)
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!isSystem) return true
            // System apps with a home-screen entry (Phone, Settings, etc.) stay candidates;
            // denylist still removes pure noise packages.
            hasLauncherActivity(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            true
        }
    }

    private fun hasLauncherActivity(packageName: String): Boolean {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        return try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PackageManager.MATCH_ALL
            } else {
                0
            }
            pm.queryIntentActivities(intent, flags).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Sole production media path:
     * [MediaSessionManagerBridge.sampleUsingManager]
     * → NLS ComponentName → `msm.getActiveSessions(cn)` → G1 map + optional artwork bytes
     */
    private fun sampleMedia(): Pair<MediaSession?, ByteArray?> {
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return null to null
            val sampled = MediaSessionManagerBridge.sampleUsingManager(
                packageName = context.packageName,
                msm = msm,
                extract = ::extractControllerFields,
                updatedAt = Instant.now().toString(),
            )
            // Media session path still carries packageName; resolve to display label for the board.
            val media = sampled.media?.let { resolveMediaSourceApp(it) }
            media to sampled.artworkBytes
        } catch (_: Exception) {
            null to null
        }
    }

    private fun resolveMediaSourceApp(media: MediaSession): MediaSession {
        val pkg = media.sourceApp?.takeIf { it.isNotBlank() } ?: return media
        return media.copy(sourceApp = labelFor(pkg))
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
        val artworkBytes = extractArtworkBytes(meta)
        return MediaSessionSamplePath.ControllerFields(
            packageName = c.packageName,
            title = title,
            artist = artist,
            album = album,
            androidPlaybackState = state?.state,
            positionMs = state?.position,
            durationMs = duration,
            artworkBytes = artworkBytes,
        )
    }

    /**
     * Bitmap album art from metadata (ART then ALBUM_ART). URI-based art skipped for MVP.
     * Compresses before leaving the framework object; oversize → null.
     */
    private fun extractArtworkBytes(meta: MediaMetadata?): ByteArray? {
        if (meta == null) return null
        val bitmap: Bitmap = meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: return null
        return try {
            ArtworkAsset.compressBitmap(bitmap)
        } catch (_: Exception) {
            null
        }
    }

    private fun batteryPercent(): Int? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return ((level * 100f) / scale).toInt().coerceIn(0, 100)
    }

    /**
     * Resolve package id → user-visible application label.
     * Requires [android.permission.QUERY_ALL_PACKAGES] (or equivalent package visibility)
     * on targetSdk 30+; otherwise [PackageManager.getApplicationInfo] often fails and we
     * fall back to the raw package name (what the public board was showing).
     */
    private fun labelFor(packageName: String): String {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return packageName
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(ai)?.toString()?.trim()
            if (!label.isNullOrEmpty()) label else pkg
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        } catch (_: Exception) {
            pkg
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
