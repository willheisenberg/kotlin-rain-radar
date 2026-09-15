package com.example.rainradar.ui

import com.example.rainradar.data.FrameSource

/** Successful images and currently used routes for one refresh. */
data class RadarLoadStatus(
    val cached: Int = 0,
    val server: Int = 0,
    val dwd: Int = 0,
    val failed: Int = 0,
    val serverActive: Int = 0,
    val dwdActive: Int = 0,
) {
    val ready: Int get() = cached + server + dwd

    fun active(source: FrameSource?, delta: Int): RadarLoadStatus = when (source) {
        FrameSource.PROXY -> copy(serverActive = serverActive + delta)
        FrameSource.DWD -> copy(dwdActive = dwdActive + delta)
        else -> this
    }

    fun finished(source: FrameSource?, success: Boolean): RadarLoadStatus {
        val idle = active(source, -1)
        if (!success) return idle.copy(failed = idle.failed + 1)
        return when (source) {
            FrameSource.CACHE -> idle.copy(cached = idle.cached + 1)
            FrameSource.PROXY -> idle.copy(server = idle.server + 1)
            FrameSource.DWD -> idle.copy(dwd = idle.dwd + 1)
            null -> idle
        }
    }

    val description: String get() = buildList {
        if (serverActive > 0) add("Vom Server: $serverActive Downloads")
        if (dwdActive > 0) add("Direkt von DWD: $dwdActive Downloads\nAufbereitung auf dem Gerät")
        if (serverActive == 0 && dwdActive == 0) {
            when {
                dwd > 0 -> add("DWD direkt · Aufbereitung auf dem Gerät")
                server > 0 -> add("Radardaten vom Server")
                else -> add("Prüfe lokalen Cache …")
            }
        }
        if (cached > 0) add("$cached Bilder aus lokalem Cache")
        if (failed > 0) add("$failed Bilder konnten nicht geladen werden")
    }.joinToString("\n")
}
