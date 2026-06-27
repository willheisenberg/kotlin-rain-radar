package com.example.rainradar.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
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
