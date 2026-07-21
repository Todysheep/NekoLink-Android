package app.nekolink.android.domain

import app.nekolink.android.protocol.BackgroundApp
import app.nekolink.android.protocol.CollectedSample
import app.nekolink.android.protocol.DeviceSummary
import app.nekolink.android.protocol.ForegroundApp
import app.nekolink.android.protocol.MediaSession
import app.nekolink.android.protocol.SnapshotIngestRequest

/** Cap background list (Android v2 default 12). */
fun capBackground(
    apps: List<BackgroundApp>,
    hiddenCount: Int,
    cap: Int,
): Triple<List<BackgroundApp>, Boolean, Int> {
    val effectiveCap = if (cap <= 0) BackgroundFilter.DEFAULT_CAP else cap
    if (apps.size <= effectiveCap) {
        return Triple(apps, false, hiddenCount)
    }
    val overflow = apps.size - effectiveCap
    return Triple(apps.take(effectiveCap), true, hiddenCount + overflow)
}

private fun foregroundIdentityEq(a: ForegroundApp?, b: ForegroundApp?): Boolean {
    if (a == null && b == null) return true
    if (a == null || b == null) return false
    return a.kind == b.kind && a.appName == b.appName && a.title == b.title
}

/** Media identity + playback state (not position). */
private fun mediaIdentityEq(a: MediaSession?, b: MediaSession?): Boolean {
    if (a == null && b == null) return true
    if (a == null || b == null) return false
    return a.title == b.title &&
        a.artist == b.artist &&
        a.album == b.album &&
        a.sourceApp == b.sourceApp &&
        a.playbackState == b.playbackState
}

private fun mediaPositionChanged(a: MediaSession?, b: MediaSession?): Boolean {
    if (a == null || b == null) return false
    return a.positionMs != b.positionMs || a.durationMs != b.durationMs
}

/**
 * Background equality for change detection: package-id **set** only.
 * Reordering or label renames alone should not force a full snapshot.
 */
private fun backgroundEq(a: List<BackgroundApp>, b: List<BackgroundApp>): Boolean =
    BackgroundFilter.sameIdSet(a, b)

private fun deviceEq(a: DeviceSummary?, b: DeviceSummary?): Boolean = a == b

enum class SampleDiff {
    /** Nothing worth sending. */
    NONE,

    /** Only media position/duration advanced; set progressOnly. */
    PROGRESS_ONLY,

    /** Meaningful or structural change; full snapshot fields. */
    MEANINGFUL,
}

/** Classify change between last sent sample and current. */
fun classifyChange(prev: CollectedSample?, next: CollectedSample): SampleDiff {
    if (prev == null) return SampleDiff.MEANINGFUL

    val fgSame = foregroundIdentityEq(prev.foreground, next.foreground)
    val mediaIdSame = mediaIdentityEq(prev.media, next.media)
    val bgSame = backgroundEq(prev.backgroundApps, next.backgroundApps) &&
        prev.backgroundHiddenCount == next.backgroundHiddenCount
    val devSame = deviceEq(prev.device, next.device)

    if (!fgSame || !mediaIdSame || !bgSame || !devSame) {
        return SampleDiff.MEANINGFUL
    }

    if (mediaPositionChanged(prev.media, next.media) && next.media != null) {
        return SampleDiff.PROGRESS_ONLY
    }

    return SampleDiff.NONE
}

/** Whether background id-set or hidden count changed (for selective ingest). */
fun backgroundListChanged(prev: CollectedSample?, next: CollectedSample): Boolean {
    if (prev == null) return next.backgroundApps.isNotEmpty() || next.backgroundHiddenCount > 0
    return !backgroundEq(prev.backgroundApps, next.backgroundApps) ||
        prev.backgroundHiddenCount != next.backgroundHiddenCount
}

/** Build snapshot body from sample + diff policy. */
fun buildSnapshotRequest(
    sample: CollectedSample,
    displayName: String?,
    includeBackground: Boolean,
    progressOnly: Boolean,
    backgroundCap: Int,
): SnapshotIngestRequest {
    val (bg, truncated, hidden) = if (includeBackground) {
        val (apps, trunc, h) = capBackground(
            sample.backgroundApps,
            sample.backgroundHiddenCount,
            backgroundCap,
        )
        Triple(apps, trunc, h)
    } else {
        Triple(null, false, null)
    }

    val mediaClear = sample.media == null && !progressOnly
    val media = sample.media

    val backgroundHiddenCount: Int? = when {
        progressOnly -> null
        truncated -> hidden
        sample.backgroundHiddenCount > 0 -> sample.backgroundHiddenCount
        else -> hidden?.takeIf { it > 0 }
    }

    return SnapshotIngestRequest(
        displayName = displayName,
        foreground = if (progressOnly) null else sample.foreground,
        media = if (mediaClear) null else media,
        mediaClear = mediaClear && !progressOnly,
        backgroundApps = if (progressOnly) null else bg,
        backgroundHiddenCount = backgroundHiddenCount,
        device = if (progressOnly) null else sample.device,
        progressOnly = progressOnly,
    )
}
