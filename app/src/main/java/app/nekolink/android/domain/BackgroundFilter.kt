package app.nekolink.android.domain

import app.nekolink.android.protocol.BackgroundApp

/**
 * Android background list policy (ADR 0010 approximate + ADR 0019 filter/cap).
 *
 * v2 (board-friendly):
 * - Recent window + min foreground time (not full-day usage dump)
 * - Tighter default cap (12 / system 24)
 * - Broader system / OEM noise denylist
 * - Pure helpers for recency selection (unit-testable without Android framework)
 */
object BackgroundFilter {
    /** Default max apps in snapshot (user-facing mode). */
    const val DEFAULT_CAP = 12

    /** Cap when "show system" is on. */
    const val SYSTEM_MODE_CAP = 24

    const val FILTER_PROFILE = "v2"

    /** Only apps used within this window enter the candidate list (2h). */
    const val RECENT_WINDOW_MS: Long = 2L * 60 * 60 * 1000

    /** Drop glance / spurious wake-ups below this total foreground time in the window. */
    const val MIN_FOREGROUND_MS: Long = 5_000L

    /** UsageStats query span (must cover [RECENT_WINDOW_MS]). */
    const val QUERY_SPAN_MS: Long = RECENT_WINDOW_MS

    private val exactDeny = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.mms",
        "com.android.providers.downloads",
        "com.android.providers.media",
        "com.android.providers.settings",
        "com.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.android.shell",
        "com.android.keychain",
        "com.android.inputmethod.latin",
        "com.google.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.ext.services",
        "com.google.android.tts",
        "com.google.android.as",
        "com.google.android.apps.restore",
        "com.android.vending",
        "com.android.printspooler",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.server.telecom",
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.sec.android.app.launcher",
        "com.bbk.launcher2",
        "com.android.documentsui",
        "com.android.externalstorage",
        "com.android.storagemanager",
        "com.android.wallpaper",
        "com.android.deskclock",
        "com.android.calendar",
        "com.android.contacts",
        "com.android.dialer",
        "com.android.camera2",
        "com.google.android.apps.wellbeing",
        "com.android.intentresolver",
        "com.android.managedprovisioning",
        "com.android.captiveportallogin",
        "com.android.traceur",
        "com.android.localtransport",
        "com.android.backupconfirm",
        "com.android.sharedstoragebackup",
        "com.android.proxyhandler",
        "com.android.wallpaperbackup",
        "com.android.providers.userdictionary",
        "com.android.vpndialogs",
        "com.android.htmlviewer",
        "com.android.bips",
        "com.android.smspush",
        "com.android.cellbroadcastreceiver",
        "com.android.emergency",
        "com.android.dynsystem",
        "com.android.companiondevicemanager",
        "com.android.ons",
        "com.android.se",
        "com.android.simappdialog",
        "com.qualcomm.qti.telephonyservice",
        "com.qualcomm.timeservice",
        "com.qti.diagservices",
    )

    private val prefixDeny = listOf(
        "com.android.providers.",
        "com.android.internal.",
        "com.google.android.overlay.",
        "com.google.android.ext.",
        "android.",
        "com.qualcomm.",
        "com.qti.",
        "com.samsung.android.provider.",
        "com.samsung.android.lool",
        "com.samsung.android.sm.",
        "com.miui.daemon",
        "com.miui.securitycenter",
        "com.miui.powerkeeper",
        "com.miui.systemAdSolution",
        "com.xiaomi.xmsf",
        "com.xiaomi.finddevice",
        "com.huawei.systemserver",
        "com.huawei.hwid",
        "com.huawei.android.hwouc",
        "com.huawei.powergenie",
        "com.coloros.",
        "com.oplus.",
        "com.heytap.",
        "com.oneplus.brickmode",
        "com.android.cts.",
        "com.vivo.",
        "com.bbk.",
        "com.iqoo.",
    )

    /** Raw UsageStats fields needed for pure recency selection. */
    data class UsageCandidate(
        val packageName: String,
        val lastTimeUsed: Long,
        val totalTimeInForeground: Long,
    )

    fun isSystemNoise(packageName: String): Boolean {
        val pkg = packageName.trim().lowercase()
        if (pkg.isEmpty()) return true
        if (pkg in exactDeny) return true
        return prefixDeny.any { pkg.startsWith(it) }
    }

    /**
     * Keep packages recently used with meaningful foreground time.
     * Sorted by [UsageCandidate.lastTimeUsed] descending; distinct by package.
     */
    fun selectRecentCandidates(
        raw: List<UsageCandidate>,
        nowMs: Long,
        windowMs: Long = RECENT_WINDOW_MS,
        minForegroundMs: Long = MIN_FOREGROUND_MS,
        excludePackages: Set<String> = emptySet(),
    ): List<UsageCandidate> {
        val windowStart = nowMs - windowMs.coerceAtLeast(0L)
        val exclude = excludePackages.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        return raw
            .asSequence()
            .mapNotNull { c ->
                val pkg = c.packageName.trim()
                if (pkg.isEmpty()) return@mapNotNull null
                val key = pkg.lowercase()
                if (key in exclude) return@mapNotNull null
                if (c.lastTimeUsed <= 0L || c.lastTimeUsed < windowStart) return@mapNotNull null
                if (c.totalTimeInForeground < minForegroundMs) return@mapNotNull null
                c.copy(packageName = pkg)
            }
            .sortedByDescending { it.lastTimeUsed }
            .distinctBy { it.packageName.lowercase() }
            .toList()
    }

    /**
     * Filter system-noise package ids + hard cap.
     * @return Triple(kept apps, hiddenCount including filtered noise + overflow, truncated)
     */
    fun filterAndCap(
        raw: List<BackgroundApp>,
        showSystem: Boolean,
        cap: Int = if (showSystem) SYSTEM_MODE_CAP else DEFAULT_CAP,
    ): Triple<List<BackgroundApp>, Int, Boolean> {
        var hidden = 0
        val filtered = if (showSystem) {
            raw
        } else {
            raw.filter { app ->
                val noise = isSystemNoise(app.id)
                if (noise) hidden++
                !noise
            }
        }
        val effectiveCap = effectiveCap(cap, showSystem)
        if (filtered.size <= effectiveCap) {
            return Triple(filtered, hidden, false)
        }
        val overflow = filtered.size - effectiveCap
        return Triple(filtered.take(effectiveCap), hidden + overflow, true)
    }

    fun effectiveCap(cap: Int, showSystem: Boolean): Int {
        if (cap > 0) return cap
        return if (showSystem) SYSTEM_MODE_CAP else DEFAULT_CAP
    }

    /** Compare background lists by package-id set only (ignore order / label churn). */
    fun sameIdSet(a: List<BackgroundApp>, b: List<BackgroundApp>): Boolean {
        if (a.size != b.size) return false
        return a.map { it.id.lowercase() }.toSet() == b.map { it.id.lowercase() }.toSet()
    }
}
