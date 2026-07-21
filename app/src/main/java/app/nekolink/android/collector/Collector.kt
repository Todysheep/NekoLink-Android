package app.nekolink.android.collector

import app.nekolink.android.protocol.CollectedSample

/** Collect current device state for ingest. */
interface Collector {
    fun sample(): CollectedSample
}
