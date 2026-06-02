package com.example.rainradar.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

object DwdWmsClient {
    const val WMS_BASE_URL = "https://maps.dwd.de/geoserver/ows"
    const val WMS_LAYER = "dwd:Niederschlagsradar"
    
    private val isoFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun formatIsoTime(instant: Instant): String {
        return isoFormatter.format(instant)
    }

    private fun getRoundedBaseTime(): Instant {
        val now = Instant.now()
        val epochSec = now.epochSecond
        // Deduct 10 minutes (600 seconds) safety offset before rounding to 5-minute (300 seconds) interval
        val roundedSec = ((epochSec - 600) / 300) * 300
        return Instant.ofEpochSecond(roundedSec)
    }

    /**
     * Generates a unified list of 60 frame times (Instants):
     * - 36 past frames (indices 0 to 35, where 35 is the base current time)
     * - 24 forecast frames (indices 36 to 59, starting at base + 5 min)
     */
    fun generateCombinedFrameTimes(): List<Instant> {
        val base = getRoundedBaseTime()
        val list = ArrayList<Instant>(60)
        
        // 36 past frames (from base - 35*5m up to base)
        for (i in 0 until 36) {
            val instant = base.minusSeconds((35 - i) * 5 * 60L)
            list.add(instant)
        }
        
        // 24 forecast frames (from base + 1*5m up to base + 24*5m)
        for (i in 1..24) {
            val instant = base.plusSeconds(i * 5 * 60L)
            list.add(instant)
        }
        
        return list
    }

    /**
     * Generates a WMS query URL for the full DWD bounding box.
     */
    fun getBBoxWmsUrl(time: Instant, width: Int = 1000, height: Int = 1085): String {
        val timeStr = formatIsoTime(time)
        val bbox = "222638.98,5621521.49,2115070.32,7673967.65"
        return "$WMS_BASE_URL?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap" +
                "&LAYERS=$WMS_LAYER" +
                "&STYLES=" +
                "&CRS=EPSG:3857" +
                "&BBOX=$bbox" +
                "&WIDTH=$width&HEIGHT=$height" +
                "&FORMAT=image/png" +
                "&TRANSPARENT=TRUE" +
                "&TIME=$timeStr"
    }

    /**
     * Resolves the local cache file path for a frame.
     */
    fun getCachedFrameFile(context: Context, time: Instant): File {
        val dir = File(context.cacheDir, "radar_cache")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val timeStr = formatIsoTime(time)
        return File(dir, "frame_$timeStr.png")
    }

    /**
     * Returns true if the frame image file is successfully downloaded and cached.
     */
    fun isFrameReady(context: Context, time: Instant): Boolean {
        val file = getCachedFrameFile(context, time)
        return file.exists() && file.length() > 0
    }

    /**
     * Downloads the WMS image for a given timestamp and caches it.
     * Uses a temp file during download to avoid saving incomplete/corrupt files.
     */
    fun downloadFrame(context: Context, time: Instant): Boolean {
        val file = getCachedFrameFile(context, time)
        if (file.exists() && file.length() > 0) {
            return true
        }

        val url = getBBoxWmsUrl(time)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "DwdRainRadarApp")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return false
                }
                val body = response.body ?: return false
                val tempFile = File.createTempFile("radar_temp_", ".tmp", context.cacheDir)
                try {
                    tempFile.outputStream().use { output ->
                        body.byteStream().copyTo(output)
                    }
                    if (tempFile.exists() && tempFile.length() > 0) {
                        if (tempFile.renameTo(file)) {
                            return true
                        } else {
                            tempFile.copyTo(file, overwrite = true)
                            tempFile.delete()
                            return true
                        }
                    }
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    /**
     * Helper to convert Osmdroid tile coordinates (x, y, zoom) to EPSG:3857 BBOX query.
     * (Retained for backwards compatibility if needed elsewhere).
     */
    fun tileToBBoxEPSG3857(x: Int, y: Int, zoom: Int): String {
        val totalSize = 20037508.342789244 * 2.0
        val tileSize = totalSize / (1 shl zoom)
        val originX = -20037508.342789244
        val originY = 20037508.342789244
        
        val minX = originX + x * tileSize
        val maxX = minX + tileSize
        
        // Y increases downwards in Slippy Map standard, so max Y is at the top of the tile
        val maxY = originY - y * tileSize
        val minY = maxY - tileSize
        
        return "$minX,$minY,$maxX,$maxY"
    }
}
