package com.example.rainradar.server

import java.time.Instant

/** Latest nowcast run on the DWD WMS and how far its time axis reaches. */
internal data class DwdPublication(
    val latestRun: Instant,
    val timeEnd: Instant,
)

/**
 * Which radar generation the server should load next.
 *
 * App and server switch to generation `b` at `b + 10 min`. Fetching it at that
 * moment returns run `b + 5 min`, the newest one on the WMS by then. That run is
 * published about three minutes after its reference time (measured 3:09-3:35),
 * so the same content is available roughly 90 seconds before the switch. Loading
 * it then removes the cold cache that clients opening the app right after a
 * switch used to wait for, without serving an older forecast than before.
 */
internal object GenerationSchedule {
    const val PAST_FRAME_COUNT = 36
    const val TOTAL_FRAME_COUNT = 60
    const val FRAME_INTERVAL_SECONDS = 300L
    const val SAFETY_OFFSET_SECONDS = 600L
    private const val EARLIEST_PUBLICATION_SECONDS = 180L

    fun currentBase(now: Instant): Instant =
        Instant.ofEpochSecond((now.epochSecond - SAFETY_OFFSET_SECONDS) / FRAME_INTERVAL_SECONDS * FRAME_INTERVAL_SECONDS)

    fun frameTimes(base: Instant): List<Instant> =
        (0 until TOTAL_FRAME_COUNT).map { i -> base.plusSeconds((i - PAST_FRAME_COUNT) * FRAME_INTERVAL_SECONDS) }

    // Forecasts depend on their base, observations do not.
    fun frameKey(
        time: Instant,
        base: Instant,
    ): String = if (time >= base) "frame_${time}_base_${base}_1920x2084" else "frame_${time}_1920x2084"

    /**
     * The generation to load now, or null if there is nothing to do. [publication]
     * is only consulted while the next generation's run could already exist.
     */
    suspend fun nextTarget(
        now: Instant,
        completed: Set<Instant>,
        publication: suspend () -> DwdPublication?,
    ): Instant? {
        val current = currentBase(now)
        if (current !in completed) return current
        val next = current.plusSeconds(FRAME_INTERVAL_SECONDS)
        if (next in completed) return null
        val run = next.plusSeconds(FRAME_INTERVAL_SECONDS)
        if (now < run.plusSeconds(EARLIEST_PUBLICATION_SECONDS)) return null
        val published = publication() ?: return null
        return next.takeIf { published.latestRun >= run && published.timeEnd >= frameTimes(next).last() }
    }
}

internal object DwdCapabilities {
    const val URL =
        "https://maps.dwd.de/geoserver/dwd/Niederschlagsradar/ows?service=WMS&version=1.3.0&request=GetCapabilities"

    private val referenceTime = Regex("""name="REFERENCE_TIME"[^>]*default="([^"]+)"""")
    private val timeAxis = Regex("""<Dimension name="time"[^>]*>([^<]+)</Dimension>""")

    fun parse(xml: String): DwdPublication? {
        val run = referenceTime.find(xml)?.groupValues?.get(1) ?: return null
        // A comma-separated list of "start/end/period" ranges or single times.
        val lastRange =
            timeAxis
                .find(xml)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.split(',')
                ?.last() ?: return null
        val end = lastRange.split('/').let { if (it.size >= 2) it[1] else it[0] }
        return runCatching { DwdPublication(Instant.parse(run), Instant.parse(end)) }.getOrNull()
    }
}
