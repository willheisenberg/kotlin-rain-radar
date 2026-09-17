package com.example.rainradar.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

private val logger = LoggerFactory.getLogger("RadarProxy")

// In-Memory cache for WebP byte arrays
private val memoryCache = ConcurrentHashMap<String, ByteArray>()
private val cacheDir = File("cache").apply { if (!exists()) mkdirs() }

// Limits concurrent DWD fetches from both on-demand requests and pre-cache loop
private val dwdSemaphore = kotlinx.coroutines.sync.Semaphore(5)

private val upstream = RadarUpstream()
private val preloadGate = PreloadGate()
private val radarCache = RadarCache(memoryCache)

private suspend fun cachedRadar(cacheKey: String, time: String, base: String, width: Int, height: Int): ByteArray? =
    radarCache.getOrLoad(cacheKey) {
        withContext(Dispatchers.IO) {
            val file = File(cacheDir, "$cacheKey.webp")
            if (file.isFile && file.length() > 0) {
                file.readBytes()
            } else {
                dwdSemaphore.withPermit {
                    logger.info("Cache miss for key: $cacheKey. Fetching from DWD...")
                    fetchAndProcessRadar(time, base, width, height)?.also { bytes ->
                        val temporary = File.createTempFile("radar-", ".tmp", cacheDir)
                        try {
                            temporary.writeBytes(bytes)
                            Files.move(temporary.toPath(), file.toPath(),
                                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        } finally {
                            temporary.delete()
                        }
                    }
                }
            }
        }
    }

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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                logger.error("Error in background pre-caching loop: ${e.message}", e)
            }
            // The next generation can be prewarmed only about 90 seconds before
            // the switch, so a minute-long pause could miss most of that window.
            delay(20_000)
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
            val timeInstant =
                try {
                    Instant.parse(time)
                } catch (e: Exception) {
                    call.respondText(
                        "Invalid time format (must be ISO-8601 UTC, e.g. 2026-06-27T10:00:00Z)",
                        status = HttpStatusCode.BadRequest,
                    )
                    return@get
                }

            val baseInstant =
                if (!base.isNullOrBlank() && base != "0") {
                    try {
                        Instant.parse(base)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }

            // Forecasts depend on base time, history frames do not
            val isForecast = baseInstant != null && timeInstant.epochSecond >= baseInstant.epochSecond
            val cacheKey =
                if (isForecast) {
                    "frame_${time}_base_${base}_${width}x$height"
                } else {
                    "frame_${time}_${width}x$height"
                }

            // A client ahead of the server (a missed prewarm, clock skew) starts
            // loading right away instead of waiting for the background loop.
            if (baseInstant != null && completedBases.none { it >= baseInstant }) {
                launch(Dispatchers.IO) { preCacheRadarFrames() }
            }

            val cachedAtArrival = memoryCache.containsKey(cacheKey)
            val started = System.nanoTime()
            val webpBytes = cachedRadar(cacheKey, time, base ?: "0", width, height)
            call.response.headers.append("X-Radar-Cache", if (cachedAtArrival) "memory" else "not-in-memory")
            call.response.headers.append("Server-Timing", "radar;dur=${(System.nanoTime() - started) / 1_000_000}")
            if (webpBytes != null) {
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
private suspend fun fetchAndProcessRadar(
    time: String,
    base: String,
    width: Int,
    height: Int,
): ByteArray? {
    val bbox = "222638.98,5621521.49,2115070.32,7673967.65"
    val dwdUrl =
        "https://maps.dwd.de/geoserver/ows?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap" +
            "&LAYERS=dwd:Niederschlagsradar" +
            "&STYLES=" +
            "&CRS=EPSG:3857" +
            "&BBOX=$bbox" +
            "&WIDTH=$width&HEIGHT=$height" +
            "&FORMAT=image/png" +
            "&TRANSPARENT=TRUE" +
            "&TIME=$time" +
            "&_cb=$base"

    repeat(2) { attempt ->
        try {
            val bytes = upstream.read(dwdUrl)
            // ImageIO only sees a complete local buffer. A stalled network body
            // can no longer hold a download slot indefinitely inside its decoder.
            val image = ImageIO.read(ByteArrayInputStream(bytes))
                ?: throw java.io.IOException("DWD returned non-image data")
            cleanRadarImage(image)
            return compressToWebP(image)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.warn("Error fetching radar frame for $time (attempt ${attempt + 1}): ${error.message}")
            if (attempt == 0) delay(500)
        }
    }
    logger.error("All attempts failed for radar frame at $time")
    return null
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
        val isGray = (
            Math.abs(rf - gf) <= 0.03f &&
                Math.abs(rf - bf) <= 0.03f &&
                Math.abs(gf - bf) <= 0.03f
        )

        // 2. Pink/magenta border detection
        val minRB = minOf(rf, bf)
        val isPink =
            (Math.abs(rf - bf) <= 0.19f) &&
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
        throw IllegalStateException(
            "WebP ImageWriter is not registered on this system. Make sure webp-imageio dependency is on the classpath.",
        )
    }

    val writer = writers.next()
    val writeParam = writer.defaultWriteParam
    try {
        writeParam.compressionMode = ImageWriteParam.MODE_EXPLICIT
        // Attempt to set WebP lossy compression with 80% quality
        writeParam.compressionType =
            writeParam.compressionTypes.firstOrNull { it.contains("lossy", ignoreCase = true) } ?: writeParam.compressionTypes[0]
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

// Generations whose 60 frames are all cached: at most the current and the next one.
private val completedBases: MutableSet<Instant> = ConcurrentHashMap.newKeySet()

private suspend fun dwdPublication(): DwdPublication? =
    try {
        DwdCapabilities.parse(String(upstream.read(DwdCapabilities.URL)))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        logger.warn("Could not read DWD capabilities: ${e.message}")
        null
    }

private suspend fun preCacheRadarFrames() = preloadGate.run {
    val now = Instant.now()
    val base = GenerationSchedule.nextTarget(now, completedBases) { dwdPublication() } ?: return@run
    val current = GenerationSchedule.currentBase(now)
    val generation = if (base == current) "current" else "next"

    logger.info("Pre-caching all 60 frames of the $generation generation $base...")
    val times = GenerationSchedule.frameTimes(base)
    val failed = fetchFrameBatch(times, base)

    if (failed.isNotEmpty()) {
        logger.info("${failed.size} frames failed. Retrying in 15 seconds...")
        delay(15_000)
        val stillFailed = fetchFrameBatch(failed, base)
        if (stillFailed.isNotEmpty()) {
            logger.warn("${stillFailed.size} frames still failed after retry: $stillFailed")
        }
    }

    if (times.all { time ->
            val key = GenerationSchedule.frameKey(time, base)
            memoryCache.containsKey(key) || File(cacheDir, "$key.webp").isFile
        }) {
        completedBases.add(base)
        completedBases.removeIf { it < current }
        // Clients keep requesting the current generation until the switch.
        val removed = RadarDiskCache.retain(cacheDir, memoryCache.keys, setOf(current, base))
        logger.info("Pre-caching completed for the $generation generation $base; removed $removed stale files")
    } else {
        logger.warn("Pre-caching incomplete for base time: $base; retaining previous cache and retrying next cycle")
    }
}

private suspend fun fetchFrameBatch(
    times: List<Instant>,
    base: Instant,
): List<Instant> {
    val failed = Collections.synchronizedList(mutableListOf<Instant>())
    coroutineScope {
        times.forEach { timeInstant ->
            launch(Dispatchers.IO) {
                val timeStr = timeInstant.toString()
                val baseStr = base.toString()
                val isForecast = timeInstant >= base
                val key = GenerationSchedule.frameKey(timeInstant, base)
                if (cachedRadar(key, timeStr, if (isForecast) baseStr else "0", 1920, 2084) == null) {
                    failed.add(timeInstant)
                }
            }
        }
    }
    return failed
}
