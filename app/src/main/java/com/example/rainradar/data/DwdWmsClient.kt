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

    fun getRoundedBaseTime(): Instant {
        val now = Instant.now()
        val epochSec = now.epochSecond
        // 10-Minuten Safety-Offset (600s) – identisch mit der KDE Plasma Extension
        // Verhindert, dass Frames angefragt werden, bevor der DWD sie bereitstellt
        val roundedSec = ((epochSec - 600) / 300) * 300
        return Instant.ofEpochSecond(roundedSec)
    }

    /**
     * Generates a unified list of 60 frame times (Instants):
     * - 36 past frames (indices 0 to 35): base - (36-i)*5min  → Frame 35 = base - 5min
     * - 24 future frames (indices 36 to 59): base + (i-36)*5min → Frame 36 = base (="Jetzt")
     * Identisch mit der KDE Plasma Extension Logik
     */
    fun generateCombinedFrameTimes(base: Instant = getRoundedBaseTime()): List<Instant> {
        val list = ArrayList<Instant>(60)
        
        val pastFrames = 36
        val totalFrames = 60
        
        for (i in 0 until totalFrames) {
            val instant = if (i < pastFrames) {
                // Vergangene Frames: base - (pastFrames - i) * 5min
                base.minusSeconds((pastFrames - i) * 5 * 60L)
            } else {
                // Vorhersage-Frames: base + (i - pastFrames) * 5min
                // Frame 36 = base + 0 = "Jetzt"
                base.plusSeconds((i - pastFrames) * 5 * 60L)
            }
            list.add(instant)
        }
        
        return list
    }

    /**
     * Generates a WMS query URL for the full DWD bounding box.
     */
    fun getBBoxWmsUrl(time: Instant, base: Instant = getRoundedBaseTime(), width: Int = 1920, height: Int = 2084): String {
        val timeStr = formatIsoTime(time)
        val bbox = "222638.98,5621521.49,2115070.32,7673967.65"
        val cb = if (time.epochSecond >= base.epochSecond) base.epochSecond else 0L
        return "$WMS_BASE_URL?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap" +
                "&LAYERS=$WMS_LAYER" +
                "&STYLES=" +
                "&CRS=EPSG:3857" +
                "&BBOX=$bbox" +
                "&WIDTH=$width&HEIGHT=$height" +
                "&FORMAT=image/png" +
                "&TRANSPARENT=TRUE" +
                "&TIME=$timeStr" +
                "&_cb=$cb"
    }

    /**
     * Resolves the local cache file path for a frame.
     */
    fun getCachedFrameFile(context: Context, time: Instant, base: Instant = getRoundedBaseTime()): File {
        val dir = File(context.cacheDir, "radar_cache")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val timeStr = formatIsoTime(time)
        return if (time.epochSecond >= base.epochSecond) {
            val baseStr = formatIsoTime(base)
            File(dir, "frame_${timeStr}_base_${baseStr}.png")
        } else {
            File(dir, "frame_$timeStr.png")
        }
    }

    /**
     * Returns true if the frame image file is successfully downloaded and cached.
     */
    fun isFrameReady(context: Context, time: Instant, base: Instant = getRoundedBaseTime()): Boolean {
        val file = getCachedFrameFile(context, time, base)
        return file.exists() && file.length() > 0
    }

    /**
     * Cleans up old forecast cache files whose base time is different from the current base time.
     */
    fun cleanOldForecastCache(context: Context, currentBase: Instant) {
        val dir = File(context.cacheDir, "radar_cache")
        if (dir.exists() && dir.isDirectory) {
            val currentBaseStr = formatIsoTime(currentBase)
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (file.name.contains("_base_") && !file.name.contains("_base_$currentBaseStr")) {
                    file.delete()
                }
            }
        }
    }

    /**
     * Downloads the WMS image for a given timestamp and caches it.
     * Uses a temp file during download to avoid saving incomplete/corrupt files.
     */
    fun downloadFrame(context: Context, time: Instant, base: Instant = getRoundedBaseTime()): Boolean {
        val file = getCachedFrameFile(context, time, base)
        if (file.exists() && file.length() > 0) {
            return true
        }

        val url = getBBoxWmsUrl(time, base)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "DwdRainRadarApp")
            .build()

        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Unexpected HTTP code $response")
                    }
                    val body = response.body ?: throw IOException("Empty response body")
                    val tempFile = File.createTempFile("radar_temp_", ".tmp", context.cacheDir)
                    try {
                        tempFile.outputStream().use { output ->
                            body.byteStream().copyTo(output)
                        }
                        if (tempFile.exists() && tempFile.length() > 0) {
                            val success = if (tempFile.renameTo(file)) {
                                true
                            } else {
                                tempFile.copyTo(file, overwrite = true)
                                tempFile.delete()
                                true
                            }
                            if (success) return true
                        }
                    } finally {
                        if (tempFile.exists()) {
                            tempFile.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("DwdWmsClient", "Download failed for $time (Attempt $attempt/$maxAttempts): ${e.message}")
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(1500L * attempt) // Exponential backoff
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
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
