package com.example.rainradar.ui.components

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.rainradar.data.DwdWmsClient
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.MapTileApproximater
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.MapTileFilesystemProvider
import org.osmdroid.tileprovider.modules.TileWriter
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import java.time.Instant
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class DwdWmsTileSource(timeString: String) : OnlineTileSourceBase(
    "DWD_Radar_$timeString",
    0, 18, 256, ".png",
    arrayOf(DwdWmsClient.WMS_BASE_URL)
) {
    private val wmsTime = timeString

    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        
        val bbox = DwdWmsClient.tileToBBoxEPSG3857(x, y, zoom)
        
        return "$baseUrl?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap" +
                "&LAYERS=${DwdWmsClient.WMS_LAYER}" +
                "&STYLES=" +
                "&CRS=EPSG:3857" +
                "&BBOX=${bbox}" +
                "&WIDTH=256&HEIGHT=256" +
                "&FORMAT=image/png" +
                "&TRANSPARENT=TRUE" +
                "&TIME=${wmsTime}"
    }
}

@Composable
fun RadarMapView(
    frameTimes: List<Instant>,
    activeFrameIndex: Int,
    userLocation: GeoPoint?,
    isPreloading: Boolean,
    modifier: Modifier = Modifier,
    onMapReady: (MapView) -> Unit = {}
) {
    // Configure user agent and internal cache directory for Osmdroid to prevent Scoped Storage crashes on Android 10+ / 16
    val context = androidx.compose.ui.platform.LocalContext.current
    remember {
        val osmConfig = Configuration.getInstance()
        osmConfig.userAgentValue = "DwdRainRadarApp"
        
        // Use app-specific internal cache directory to bypass external storage write restrictions
        val basePath = java.io.File(context.cacheDir, "osmdroid")
        val tileCache = java.io.File(basePath, "tiles")
        osmConfig.osmdroidBasePath = basePath
        osmConfig.osmdroidTileCache = tileCache
        
        // Cache configuration: aggressive caching to prevent reloading of tiles when zooming
        // Override the default WMS expiration (e.g., no-cache) with 24 hours.
        // Since we use dynamic frame-time specific cache paths, there's no risk of stale data.
        osmConfig.expirationOverrideDuration = 24 * 60 * 60 * 1000L // 24 hours
        osmConfig.tileFileSystemCacheMaxBytes = 1024L * 1024 * 1024 // 1GB
        osmConfig.tileFileSystemCacheTrimBytes = 800L * 1024 * 1024 // 800MB
        
        osmConfig.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        osmConfig
    }

    val mapView = remember {
        MapView(context).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true // Smoother zoom: scales tiles during pinch-zoom transitions
            setHorizontalMapRepetitionEnabled(false)
            setVerticalMapRepetitionEnabled(false)
            controller.setZoom(6.0)
            // Center of Germany
            controller.setCenter(GeoPoint(51.1657, 10.4515))
        }
    }

    // Keep track of active overlays and their providers in a cache
    val overlaysCache = remember { HashMap<String, Pair<TilesOverlay, MapTileProviderArray>>() }
    
    // Clean up cached overlays and providers when frameTimes changes or on dispose
    // to prevent memory leaks, thread exhaustion, and SQLite connection locks.
    DisposableEffect(frameTimes) {
        onDispose {
            overlaysCache.values.forEach { (overlay, provider) ->
                overlay.onDetach(mapView)
                provider.detach()
            }
            overlaysCache.clear()
        }
    }
    
    // Create a 100% transparent ColorFilter to load tiles invisibly
    val transparentColorFilter = remember {
        val matrix = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f // alpha multiplier set to 0.0f
        ))
        ColorMatrixColorFilter(matrix)
    }

    val locationDrawable = remember { LocationDotDrawable(context) }

    val userLocationMarker = remember {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = locationDrawable
            title = "Dein Standort"
        }
    }

    DisposableEffect(mapView) {
        onMapReady(mapView)
        onDispose {
            locationDrawable.stopAnimation()
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            // Sync dynamic overlay frames
            if (frameTimes.isNotEmpty() && activeFrameIndex in frameTimes.indices) {

                // 1. Pre-create or retrieve overlays for all loaded frameTimes to maintain chronological order
                val currentFrameOverlays = frameTimes.map { time ->
                    val timeStr = DwdWmsClient.formatIsoTime(time)
                    val pair = overlaysCache.getOrPut(timeStr) {
                        val tileSource = DwdWmsTileSource(timeStr)
                        // Osmdroid's 3-stage tile pipeline for flicker-free zooming:
                        // 1. FilesystemProvider: reads cached tiles from disk (instant, no network)
                        // 2. Approximater: scales cached tiles from nearby zoom levels during zoom transitions
                        // 3. Downloader: fetches new tiles from DWD WMS server, saves via TileWriter (no SQLite)
                        val receiver = SimpleRegisterReceiver(context)
                        val tileWriter = TileWriter()
                        val filesystemProvider = MapTileFilesystemProvider(receiver, tileSource)
                        val approximater = MapTileApproximater().apply {
                            addProvider(filesystemProvider)
                        }
                        val downloader = MapTileDownloader(tileSource, tileWriter)
                        val provider = MapTileProviderArray(tileSource, receiver, arrayOf(filesystemProvider, approximater, downloader))
                        
                        val overlay = TilesOverlay(provider, context).apply {
                            loadingBackgroundColor = Color.TRANSPARENT
                            loadingLineColor = Color.TRANSPARENT
                        }
                        Pair(overlay, provider)
                    }
                    pair.first
                }

                // 2. Build the stable, desired overlays list: radar layers first (chronological order), then user location marker
                val desiredOverlays = ArrayList<org.osmdroid.views.overlay.Overlay>()
                desiredOverlays.addAll(currentFrameOverlays)
                if (userLocation != null) {
                    userLocationMarker.position = userLocation
                    desiredOverlays.add(userLocationMarker)
                }

                // Apply desired overlays list to MapView only if there is a structure or order mismatch.
                // This prevents resetting Osmdroid's internals or tile loaders during interactions.
                var isMatch = view.overlays.size == desiredOverlays.size
                if (isMatch) {
                    for (i in desiredOverlays.indices) {
                        if (view.overlays[i] != desiredOverlays[i]) {
                            isMatch = false
                            break
                        }
                    }
                }

                if (!isMatch) {
                    view.overlays.clear()
                    view.overlays.addAll(desiredOverlays)
                }

                // 3. Define the active and previous indices for zero-flicker transitions
                val visibleIndex = activeFrameIndex
                val previousIndex = if (activeFrameIndex > 0) activeFrameIndex - 1 else -1

                // 4. Update state properties (isEnabled, colorFilter) on all overlays based on preloading state
                currentFrameOverlays.forEachIndexed { idx, overlay ->
                    if (isPreloading) {
                        // During preloading: enable all overlays so they actively download WMS tiles in the background
                        overlay.isEnabled = true
                        overlay.setColorFilter(transparentColorFilter) // Keep tiles invisible
                    } else {
                        // During playback/normal operation: only enable the current and previous overlays.
                        // This gives 100% fluid GPU/CPU performance, completely removes orange block glitches,
                        // and prevents grey shadow color build-up from stacked transparent layers.
                        if (idx == visibleIndex || idx == previousIndex) {
                            overlay.isEnabled = true
                            overlay.setColorFilter(null) // Fully visible
                        } else {
                            overlay.isEnabled = false
                            overlay.setColorFilter(null)
                        }
                    }
                }

                // Force view repaint
                view.postInvalidate()
            }
        }
    )
}

class LocationDotDrawable(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    
    private val coreRadius = 8f * density
    private val borderRadius = 10f * density
    private val maxHaloRadius = 22f * density
    private val minHaloRadius = 10f * density
    
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var haloAlpha = 0.2f
    private var haloRadius = minHaloRadius
    
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener { animation ->
            val fraction = animation.animatedValue as Float
            // Halo expands from minHaloRadius to maxHaloRadius
            haloRadius = minHaloRadius + (maxHaloRadius - minHaloRadius) * fraction
            // Halo fades out as it expands
            haloAlpha = 0.25f * (1f - fraction)
            invalidateSelf()
        }
    }
    
    init {
        animator.start()
    }
    
    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        
        // 1. Draw pulsing outer halo (semi-transparent blue)
        paint.color = 0xFF3B82F6.toInt()
        paint.alpha = (haloAlpha * 255).toInt()
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, haloRadius, paint)
        
        // 2. Draw subtle shadow under the white border
        paint.color = 0x44000000 // 27% black
        paint.alpha = 255
        canvas.drawCircle(cx, cy + 1.5f * density, borderRadius + 0.5f * density, paint)
        
        // 3. Draw white border
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, borderRadius, paint)
        
        // 4. Draw blue core
        paint.color = 0xFF3B82F6.toInt()
        canvas.drawCircle(cx, cy, coreRadius, paint)
    }
    
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    
    override fun getIntrinsicWidth(): Int = (maxHaloRadius * 2).toInt()
    override fun getIntrinsicHeight(): Int = (maxHaloRadius * 2).toInt()
    
    fun stopAnimation() {
        animator.cancel()
    }
}
