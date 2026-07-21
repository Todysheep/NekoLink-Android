package app.nekolink.android.domain

import app.nekolink.android.protocol.BackgroundApp

/**
 * Android system / noise package prefixes and names (ADR 0019 heuristic).
 * Pure logic — unit-testable without Android framework.
 */
object BackgroundFilter {
    const val DEFAULT_CAP = 50
    const val SYSTEM_MODE_CAP = 100
    const val FILTER_PROFILE = "v1"

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
        "com.miui.daemon",
        "com.huawei.systemserver",
        "com.android.cts.",
    )

    fun isSystemNoise(packageName: String): Boolean {
        val pkg = packageName.trim().lowercase()
        if (pkg.isEmpty()) return true
        if (pkg in exactDeny) return true
        return prefixDeny.any { pkg.startsWith(it) }
    }

    /**
     * Filter + cap background apps.
     * @return Triple(kept apps, hiddenCount including filtered system noise + overflow, truncated)
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
        val effectiveCap = if (cap <= 0) DEFAULT_CAP else cap
        if (filtered.size <= effectiveCap) {
            return Triple(filtered, hidden, false)
        }
        val overflow = filtered.size - effectiveCap
        return Triple(filtered.take(effectiveCap), hidden + overflow, true)
    }
}
