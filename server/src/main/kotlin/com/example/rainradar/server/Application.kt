package com.example.rainradar.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

private val logger = LoggerFactory.getLogger("RadarProxy")

// In-Memory cache for WebP byte arrays
private val memoryCache = ConcurrentHashMap<String, ByteArray>()
private val cacheDir = File("cache").apply { if (!exists()) mkdirs() }

// HTTP client for fetching from DWD WMS
private val httpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Internal Server Error: ${cause.message}", cause)
            call.respondText(text = "500 Internal Server Error: ${cause.message}", status = HttpStatusCode.InternalServerError)
        }
    }

    launch(Dispatchers.IO) {
        logger.info("Starting background radar pre-caching loop...")
        while (isActive) {
            try {
                preCacheRadarFrames()
            } catch (e: Exception) {
                logger.error("Error in background pre-caching loop: ${e.message}", e)
            }
            delay(60000) // check every minute
        }
    }

    routing {
        get("/") {
            call.respondText("DWD Radar WebP Proxy is running!")
        }

        get("/radar") {
            val time = call.request.queryParameters["time"]
            val base = call.request.queryParameters["base"]
            val widthStr = call.request.queryParameters["width"] ?: "1920"
            val heightStr = call.request.queryParameters["height"] ?: "2084"

            if (time.isNullOrBlank()) {
                call.respondText("Missing required query parameter: time", status = HttpStatusCode.BadRequest)
                return@get
            }

            val width = widthStr.toIntOrNull() ?: 1920
            val height = heightStr.toIntOrNull() ?: 2084

            // Generate unique cache key
            val timeInstant = try {
                Instant.parse(time)
            } catch (e: Exception) {
                call.respondText("Invalid time format (must be ISO-8601 UTC, e.g. 2026-06-27T10:00:00Z)", status = HttpStatusCode.BadRequest)
                return@get
            }

            val baseInstant = if (!base.isNullOrBlank() && base != "0") {
                try { Instant.parse(base) } catch (e: Exception) { null }
            } else null

            // Forecasts depend on base time, history frames do not
            val isForecast = baseInstant != null && timeInstant.epochSecond >= baseInstant.epochSecond
            val cacheKey = if (isForecast) {
                "frame_${time}_base_${base}_${width}x${height}"
            } else {
                "frame_${time}_${width}x${height}"
            }

            // 1. Try In-Memory Cache
            val cachedBytes = memoryCache[cacheKey]
            if (cachedBytes != null) {
                call.respondBytes(cachedBytes, ContentType.parse("image/webp"))
                return@get
            }

            // 2. Try Disk Cache
            val diskFile = File(cacheDir, "$cacheKey.webp")
            if (diskFile.exists() && diskFile.length() > 0) {
                val bytes = diskFile.readBytes()
                memoryCache[cacheKey] = bytes
                call.respondBytes(bytes, ContentType.parse("image/webp"))
                return@get
            }

            // 3. Cache Miss - Fetch from DWD, process, and compress
            logger.info("Cache miss for key: $cacheKey. Fetching from DWD...")
            val webpBytes = fetchAndProcessRadar(time, base ?: "0", width, height)

            if (webpBytes != null) {
                // Save to caches
                memoryCache[cacheKey] = webpBytes
                try {
                    diskFile.writeBytes(webpBytes)
                } catch (e: Exception) {
                    logger.warn("Failed to write cache file to disk: ${e.message}")
                }
                call.respondBytes(webpBytes, ContentType.parse("image/webp"))
            } else {
                call.respondText("Failed to retrieve or process radar frame from DWD", status = HttpStatusCode.BadGateway)
            }
        }
    }
}

/**
 * Fetches the PNG from maps.dwd.de, removes gray/pink/blend background pixels,
 * and encodes the result into WebP.
 */
private fun fetchAndProcessRadar(time: String, base: String, width: Int, height: Int): ByteArray? {
    val bbox = "222638.98,5621521.49,2115070.32,7673967.65"
    val dwdUrl = "https://maps.dwd.de/geoserver/ows?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap" +
            "&LAYERS=dwd:Niederschlagsradar" +
            "&STYLES=" +
            "&CRS=EPSG:3857" +
            "&BBOX=$bbox" +
            "&WIDTH=$width&HEIGHT=$height" +
            "&FORMAT=image/png" +
            "&TRANSPARENT=TRUE" +
            "&TIME=$time" +
            "&_cb=$base"

    try {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(dwdUrl))
            .header("User-Agent", "DwdRainRadarProxyServer")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            logger.error("DWD WMS server returned HTTP status code ${response.statusCode()} for time $time")
            return null
        }

        response.body().use { inputStream ->
            val image = ImageIO.read(inputStream) ?: return null
            
            // Clean the image pixels (set gray/pink background and blend boundary pixels to transparent)
            cleanRadarImage(image)

            // Convert to WebP
            return compressToWebP(image)
        }
    } catch (e: Exception) {
        logger.error("Error processing radar frame for time $time: ${e.message}", e)
        return null
    }
}

/**
 * Removes gray backgrounds, pink/magenta borders, and blended boundary pixels
 * from a BufferedImage by setting them to transparent.
 * Direct translation of the Android Kotlin client-side cleanup logic.
 */
private fun cleanRadarImage(image: BufferedImage) {
    val width = image.width
    val height = image.height
    
    // Bulk read pixels for high performance
    val pixels = IntArray(width * height)
    image.getRGB(0, 0, width, height, pixels, 0, width)

    var modified = false
    for (i in pixels.indices) {
        val color = pixels[i]
        val a = (color shr 24) and 0xFF
        if (a == 0) continue

        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        val rf = r / 255.0f
        val gf = g / 255.0f
        val bf = b / 255.0f

        // 1. Gray background: R ≈ G ≈ B (within 0.03 of each other)
        val isGray = (Math.abs(rf - gf) <= 0.03f &&
                Math.abs(rf - bf) <= 0.03f &&
                Math.abs(gf - bf) <= 0.03f)

        // 2. Pink/magenta border detection
        val minRB = minOf(rf, bf)
        val isPink = (Math.abs(rf - bf) <= 0.19f) &&
                (minRB > 0.01f) &&
                (gf < minRB - 0.02f)

        // 3. Blended boundary check
        val isBlend = (minRB > 0.3f) && (gf > 0.05f)

        if (isGray || isPink || isBlend) {
            pixels[i] = 0x00000000 // transparent ARGB
            modified = true
        }
    }

    if (modified) {
        image.setRGB(0, 0, width, height, pixels, 0, width)
    }
}

/**
 * Encodes a BufferedImage into WebP byte array using webp-imageio.
 */
private fun compressToWebP(image: BufferedImage): ByteArray {
    val outputStream = ByteArrayOutputStream()
    val writers = ImageIO.getImageWritersByMIMEType("image/webp")
    if (!writers.hasNext()) {
        throw IllegalStateException("WebP ImageWriter is not registered on this system. Make sure webp-imageio dependency is on the classpath.")
    }
    
    val writer = writers.next()
    val writeParam = writer.defaultWriteParam
    try {
        writeParam.compressionMode = ImageWriteParam.MODE_EXPLICIT
        // Attempt to set WebP lossy compression with 80% quality
        writeParam.compressionType = writeParam.compressionTypes.firstOrNull { it.contains("lossy", ignoreCase = true) } ?: writeParam.compressionTypes[0]
        writeParam.compressionQuality = 0.80f
    } catch (e: Exception) {
        logger.warn("Could not configure WebP compression parameters: ${e.message}. Using default WebP settings.")
    }

    ImageIO.createImageOutputStream(outputStream).use { ios ->
        writer.output = ios
        writer.write(null, IIOImage(image, null, null), writeParam)
        ios.flush()
    }
    writer.dispose()
    
    return outputStream.toByteArray()
}

private const val PAST_FRAME_COUNT = 36
private const val TOTAL_FRAME_COUNT = 60
private const val FRAME_INTERVAL_SECONDS = 300L   // 5 minutes
private const val SAFETY_OFFSET_SECONDS = 600L    // 10 minutes

private const val PRE_CACHE_WIDTH = 3840
private const val PRE_CACHE_HEIGHT = 4168

private fun getRoundedBaseTime(): Instant {
    val now = Instant.now()
    val epochSec = now.epochSecond
    val roundedSec = ((epochSec - SAFETY_OFFSET_SECONDS) / FRAME_INTERVAL_SECONDS) * FRAME_INTERVAL_SECONDS
    return Instant.ofEpochSecond(roundedSec)
}

private fun generateCombinedFrameTimes(base: Instant): List<Instant> {
    val list = ArrayList<Instant>(TOTAL_FRAME_COUNT)
    for (i in 0 until TOTAL_FRAME_COUNT) {
        val instant = if (i < PAST_FRAME_COUNT) {
            base.minusSeconds((PAST_FRAME_COUNT - i) * FRAME_INTERVAL_SECONDS)
        } else {
            base.plusSeconds((i - PAST_FRAME_COUNT) * FRAME_INTERVAL_SECONDS)
        }
        list.add(instant)
    }
    return list
}

private fun cleanOldDiskCache(oldestAllowed: Instant, activeTimes: List<Instant>, currentBase: Instant) {
    if (!cacheDir.exists() || !cacheDir.isDirectory) return
    val files = cacheDir.listFiles() ?: return
    
    val activeTimesSet = activeTimes.map { it.toString() }.toSet()
    val currentBaseStr = currentBase.toString()
    val suffixLength = "_${PRE_CACHE_WIDTH}x${PRE_CACHE_HEIGHT}.webp".length
    
    for (file in files) {
        val name = file.name
        if (!name.startsWith("frame_") || !name.endsWith(".webp")) {
            continue
        }
        
        try {
            if (name.contains("_base_")) {
                val firstBaseIndex = name.indexOf("_base_")
                if (firstBaseIndex != -1) {
                    val timeStr = name.substring(6, firstBaseIndex)
                    val baseStr = name.substring(firstBaseIndex + 6, name.length - suffixLength)
                    
                    if (!activeTimesSet.contains(timeStr)) {
                        file.delete()
                    } else {
                        val fileTime = Instant.parse(timeStr)
                        if (fileTime.isBefore(currentBase)) {
                            file.delete()
                        } else if (baseStr != currentBaseStr) {
                            file.delete()
                        }
                    }
                } else {
                    file.delete()
                }
            } else {
                val timeStr = name.substring(6, name.length - suffixLength)
                if (!activeTimesSet.contains(timeStr)) {
                    file.delete()
                } else {
                    val fileTime = Instant.parse(timeStr)
                    if (fileTime.isBefore(oldestAllowed)) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            file.delete()
        }
    }
}

private var lastCachedBaseTime: Instant? = null

private suspend fun preCacheRadarFrames() {
    val base = getRoundedBaseTime()
    if (base == lastCachedBaseTime) {
        return
    }
    
    logger.info("New base time detected: $base. Pre-caching all 60 frames...")
    val times = generateCombinedFrameTimes(base)
    
    val oldestAllowed = base.minus(Duration.ofHours(6))
    cleanOldDiskCache(oldestAllowed, times, base)
    
    val semaphore = kotlinx.coroutines.sync.Semaphore(5)
    coroutineScope {
        times.forEach { timeInstant ->
            launch {
                semaphore.acquire()
                try {
                    val timeStr = timeInstant.toString()
                    val baseStr = base.toString()
                    
                    val isForecast = timeInstant.epochSecond >= base.epochSecond
                    val cacheKey = if (isForecast) {
                        "frame_${timeStr}_base_${baseStr}_${PRE_CACHE_WIDTH}x${PRE_CACHE_HEIGHT}"
                    } else {
                        "frame_${timeStr}_${PRE_CACHE_WIDTH}x${PRE_CACHE_HEIGHT}"
                    }
                    
                    val diskFile = File(cacheDir, "$cacheKey.webp")
                    if (!diskFile.exists() || diskFile.length() == 0L) {
                        logger.info("Pre-caching miss for key: $cacheKey. Fetching...")
                        val webpBytes = fetchAndProcessRadar(timeStr, if (isForecast) baseStr else "0", PRE_CACHE_WIDTH, PRE_CACHE_HEIGHT)
                        if (webpBytes != null) {
                            memoryCache[cacheKey] = webpBytes
                            try {
                                diskFile.writeBytes(webpBytes)
                            } catch (e: Exception) {
                                logger.warn("Failed to write pre-cached file: ${e.message}")
                            }
                        }
                    }
                } finally {
                    semaphore.release()
                }
            }
        }
    }
    
    lastCachedBaseTime = base
    logger.info("Pre-caching completed for base time: $base")
}
