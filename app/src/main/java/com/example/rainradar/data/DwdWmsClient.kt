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
    
    // Set to empty string "" to bypass proxy and fetch directly from DWD
    val PROXY_URL = com.example.rainradar.BuildConfig.PROXY_URL

    // Frame layout constants
    const val PAST_FRAME_COUNT = 36
    const val TOTAL_FRAME_COUNT = 60
    const val FRAME_INTERVAL_SECONDS = 300L   // 5 minutes
    const val SAFETY_OFFSET_SECONDS = 600L    // 10 minutes

    // WMS image dimensions
    const val WMS_DEFAULT_WIDTH = 1920
    const val WMS_DEFAULT_HEIGHT = 2084

    // Radar geographic bounds (WGS84)
    const val LAT_SOUTH = 45.0
    const val LAT_NORTH = 56.576107
    const val LON_WEST = 2.0
    const val LON_EAST = 19.0

    // EPSG:3857 bounding box
    const val BBOX_X_MIN = 222638.98
    const val BBOX_Y_MIN = 5621521.49
    const val BBOX_X_MAX = 2115070.32
    const val BBOX_Y_MAX = 7673967.65

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
        // 10-Minuten Safety-Offset – identisch mit der KDE Plasma Extension
        // Verhindert, dass Frames angefragt werden, bevor der DWD sie bereitstellt
        val roundedSec = ((epochSec - SAFETY_OFFSET_SECONDS) / FRAME_INTERVAL_SECONDS) * FRAME_INTERVAL_SECONDS
        return Instant.ofEpochSecond(roundedSec)
    }

    /**
     * Generates a unified list of 60 frame times (Instants):
     * - 36 past frames (indices 0 to 35): base - (36-i)*5min  → Frame 35 = base - 5min
     * - 24 future frames (indices 36 to 59): base + (i-36)*5min → Frame 36 = base (="Jetzt")
     * Identisch mit der KDE Plasma Extension Logik
     */
    fun generateCombinedFrameTimes(base: Instant = getRoundedBaseTime()): List<Instant> {
        val list = ArrayList<Instant>(TOTAL_FRAME_COUNT)
        
        for (i in 0 until TOTAL_FRAME_COUNT) {
            val instant = if (i < PAST_FRAME_COUNT) {
                // Vergangene Frames: base - (PAST_FRAME_COUNT - i) * 5min
                base.minusSeconds((PAST_FRAME_COUNT - i) * FRAME_INTERVAL_SECONDS)
            } else {
                // Vorhersage-Frames: base + (i - PAST_FRAME_COUNT) * 5min
                // Frame PAST_FRAME_COUNT = base + 0 = "Jetzt"
                base.plusSeconds((i - PAST_FRAME_COUNT) * FRAME_INTERVAL_SECONDS)
            }
            list.add(instant)
        }
        
        return list
    }

    /**
     * Generates a WMS query URL for the full DWD bounding box.
     */
    fun getBBoxWmsUrl(time: Instant, base: Instant = getRoundedBaseTime(), width: Int = WMS_DEFAULT_WIDTH, height: Int = WMS_DEFAULT_HEIGHT): String {
        val timeStr = formatIsoTime(time)
        val bbox = "$BBOX_X_MIN,$BBOX_Y_MIN,$BBOX_X_MAX,$BBOX_Y_MAX"
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
            // Past/history frames use a simple canonical filename without base.
            // Do NOT search for or rename old forecast files here — old forecasts
            // are predictions, not actual observations, and must be re-downloaded.
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
     * Checks if the file has a WebP image signature (starts with "RIFF" and "WEBP").
     */
    fun isWebpFile(file: File): Boolean {
        if (!file.exists() || file.length() < 12) return false
        return try {
            file.inputStream().use { stream ->
                val bytes = ByteArray(12)
                val read = stream.read(bytes)
                if (read < 12) return false
                bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Cleans up old cache files:
     * - Forecast files whose base time is different from the current base time
     * - History files that are older than oldestAllowed
     */
    fun cleanOldCache(context: Context, currentBase: Instant, oldestAllowed: Instant, activeTimes: List<Instant> = emptyList()) {
        val dir = File(context.cacheDir, "radar_cache")
        if (dir.exists() && dir.isDirectory) {
            val currentBaseStr = formatIsoTime(currentBase)
            val files = dir.listFiles() ?: return
            
            val activeTimesSet = if (activeTimes.isEmpty()) {
                generateCombinedFrameTimes(currentBase).map { formatIsoTime(it) }.toSet()
            } else {
                activeTimes.map { formatIsoTime(it) }.toSet()
            }
            
            for (file in files) {
                val name = file.name
                if (!name.startsWith("frame_") || !name.endsWith(".png")) {
                    continue
                }
                
                if (name.contains("_base_")) {
                    try {
                        val firstBaseIndex = name.indexOf("_base_")
                        if (firstBaseIndex != -1) {
                            val timeStr = name.substring(6, firstBaseIndex)
                            val baseStr = name.substring(firstBaseIndex + 6, name.length - 4)
                            
                            if (!activeTimesSet.contains(timeStr)) {
                                // Time is no longer in the active frame list at all
                                file.delete()
                            } else {
                                val fileTime = Instant.parse(timeStr)
                                if (fileTime.isBefore(currentBase)) {
                                    // This was a forecast file, but the time is now in the past.
                                    // Delete it — forecast predictions must not be reused as
                                    // historical observations. The actual observation will be
                                    // re-downloaded on the next preload cycle.
                                    file.delete()
                                } else if (baseStr != currentBaseStr) {
                                    // Forecast for current time window but with an outdated base → stale
                                    file.delete()
                                }
                            }
                        } else {
                            file.delete()
                        }
                    } catch (e: Exception) {
                        file.delete()
                    }
                } else {
                    val timeStr = name.substring(6, name.length - 4)
                    if (!activeTimesSet.contains(timeStr)) {
                        file.delete()
                    } else {
                        try {
                            val fileTime = Instant.parse(timeStr)
                            if (fileTime.isBefore(oldestAllowed)) {
                                file.delete()
                            }
                        } catch (e: Exception) {
                            file.delete()
                        }
                    }
                }
            }
        }
    }

    /**
     * Downloads the WMS image for a given timestamp and caches it.
     * Uses a temp file during download to avoid saving incomplete/corrupt files.
     */
    fun downloadFrame(context: Context, time: Instant, base: Instant = getRoundedBaseTime(), force: Boolean = false): Boolean {
        val file = getCachedFrameFile(context, time, base)
        if (!force && file.exists() && file.length() > 0) {
            return true
        }

        val timeStr = formatIsoTime(time)
        val baseStr = formatIsoTime(base)
        val proxyUrl = if (PROXY_URL.isNotEmpty()) {
            "$PROXY_URL?time=$timeStr&base=$baseStr&width=$WMS_DEFAULT_WIDTH&height=$WMS_DEFAULT_HEIGHT"
        } else ""

        var success = false

        // 1. Try downloading optimized WebP from our proxy
        if (proxyUrl.isNotEmpty()) {
            try {
                val request = Request.Builder()
                    .url(proxyUrl)
                    .header("User-Agent", "DwdRainRadarApp")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body ?: throw IOException("Empty proxy response body")
                        val tempFile = File.createTempFile("radar_temp_proxy_", ".tmp", context.cacheDir)
                        try {
                            tempFile.outputStream().use { output ->
                                body.byteStream().copyTo(output)
                            }
                            if (tempFile.exists() && tempFile.length() > 0) {
                                success = if (tempFile.renameTo(file)) {
                                    true
                                } else {
                                    tempFile.copyTo(file, overwrite = true)
                                    tempFile.delete()
                                    true
                                }
                            }
                        } finally {
                            if (tempFile.exists()) {
                                tempFile.delete()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("DwdWmsClient", "Proxy download failed for $time: ${e.message}. Falling back to DWD directly...")
            }
        }

        if (success) {
            return true
        }

        // 2. Fallback: Download PNG directly from DWD WMS
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
                            val dwdSuccess = if (tempFile.renameTo(file)) {
                                true
                            } else {
                                tempFile.copyTo(file, overwrite = true)
                                tempFile.delete()
                                true
                            }
                            if (dwdSuccess) return true
                        }
                    } finally {
                        if (tempFile.exists()) {
                            tempFile.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("DwdWmsClient", "DWD fallback download failed for $time (Attempt $attempt/$maxAttempts): ${e.message}")
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

    /**
     * Converts a Lat/Lon coordinate to pixel coordinates on the WMS PNG image.
     * Bounds: lat (45.0 to 56.576107), lon (2.0 to 19.0)
     */
    fun getPixelCoords(lat: Double, lon: Double, width: Int, height: Int): Pair<Int, Int>? {
        val r = 6378137.0 // Earth radius in meters (EPSG:3857)
        val x = r * (lon * Math.PI / 180.0)
        val y = r * Math.log(Math.tan(Math.PI / 4.0 + (lat * Math.PI / 360.0)))
        
        val xMin = BBOX_X_MIN
        val yMin = BBOX_Y_MIN
        val xMax = BBOX_X_MAX
        val yMax = BBOX_Y_MAX
        
        if (x !in xMin..xMax || y !in yMin..yMax) {
            return null // Outside DWD radar bounds
        }
        
        val px = ((x - xMin) / (xMax - xMin) * width).toInt()
        val py = ((yMax - y) / (yMax - yMin) * height).toInt()
        return Pair(px, py)
    }
}
