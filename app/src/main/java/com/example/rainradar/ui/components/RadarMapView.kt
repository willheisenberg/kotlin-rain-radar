package com.example.rainradar.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.rainradar.data.DwdWmsClient
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.time.Instant
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class RadarBboxOverlay : org.osmdroid.views.overlay.Overlay() {
    var bitmap: android.graphics.Bitmap? = null

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val bmp = bitmap ?: return

        val projection = mapView.projection
        val nwPoint = android.graphics.Point()
        val sePoint = android.graphics.Point()

        // NW corner: Lat 56.576107, Lon 2.0
        projection.toPixels(GeoPoint(56.576107, 2.0), nwPoint)
        // SE corner: Lat 45.0, Lon 19.0
        projection.toPixels(GeoPoint(45.0, 19.0), sePoint)

        val left = nwPoint.x
        val top = nwPoint.y
        val right = sePoint.x
        val bottom = sePoint.y

        val destRect = android.graphics.Rect(left, top, right, bottom)
        canvas.drawBitmap(bmp, null, destRect, null)
    }
}

private fun calculateMinZoom(mapWidth: Int, mapHeight: Int): Double {
    if (mapWidth <= 0 || mapHeight <= 0) return 6.0

    // Lon constraint:
    // Z >= log2( (W * 360.0) / (256.0 * 17.0) )
    val minZoomLon = Math.log((mapWidth.toDouble() * 360.0) / (256.0 * 17.0)) / Math.log(2.0)

    // Lat constraint:
    // delta_y = ln(tan(pi/4 + lat_N/2)) - ln(tan(pi/4 + lat_S/2))
    // Z >= log2( H * 2 * pi / (256.0 * delta_y) )
    val latN = Math.toRadians(56.576107)
    val latS = Math.toRadians(45.0)
    val yN = Math.log(Math.tan(Math.PI / 4.0 + latN / 2.0))
    val yS = Math.log(Math.tan(Math.PI / 4.0 + latS / 2.0))
    val deltaY = yN - yS
    val minZoomLat = Math.log((mapHeight.toDouble() * 2.0 * Math.PI) / (256.0 * deltaY)) / Math.log(2.0)

    // Using minOf and subtracting 1.0 ensures that the entire radar frame fits in the viewport with a comfortable margin
    val minZoom = minOf(minZoomLon, minZoomLat) - 1.0
    return maxOf(3.0, minZoom)
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
    val context = androidx.compose.ui.platform.LocalContext.current
    remember {
        val osmConfig = Configuration.getInstance()
        osmConfig.userAgentValue = "DwdRainRadarApp"
        
        val basePath = java.io.File(context.cacheDir, "osmdroid")
        val tileCache = java.io.File(basePath, "tiles")
        osmConfig.osmdroidBasePath = basePath
        osmConfig.osmdroidTileCache = tileCache
        
        osmConfig.expirationOverrideDuration = 24 * 60 * 60 * 1000L // 24 hours
        osmConfig.tileFileSystemCacheMaxBytes = 1024L * 1024 * 1024 // 1GB
        osmConfig.tileFileSystemCacheTrimBytes = 800L * 1024 * 1024 // 800MB
        
        osmConfig.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        osmConfig
    }

    // Keep memory cache of bitmaps to avoid decoding them repeatedly on draw
    // Verwende nullable Bitmap um null-Rückgaben von decodeFile sicher zu handhaben
    val bitmapCache = remember { HashMap<String, android.graphics.Bitmap?>() }

    val mapView = remember {
        MapView(context).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            setHorizontalMapRepetitionEnabled(false)
            setVerticalMapRepetitionEnabled(false)
            
            // Set initial state
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(51.1657, 10.4515))

            // Add map clamping listener
            val clampMap = {
                val w = width
                val h = height
                if (w > 0 && h > 0) {
                    val minZoom = calculateMinZoom(w, h)
                    if (zoomLevelDouble < minZoom) {
                        controller.setZoom(minZoom)
                    }
                    val bbox = boundingBox
                    if (bbox != null && 
                        !bbox.latNorth.isNaN() && !bbox.latSouth.isNaN() && 
                        !bbox.lonWest.isNaN() && !bbox.lonEast.isNaN()) {
                        
                        val center = mapCenter
                        var newLat = center.latitude
                        var newLon = center.longitude

                        // Longitude clamping / centering
                        if (bbox.longitudeSpan >= 17.0) {
                            newLon = 10.5 // Center of [2.0, 19.0]
                        } else {
                            if (bbox.lonWest < 2.0) {
                                newLon += (2.0 - bbox.lonWest)
                            } else if (bbox.lonEast > 19.0) {
                                newLon -= (bbox.lonEast - 19.0)
                            }
                        }

                        // Latitude clamping / centering
                        val radarLatSpan = 56.576107 - 45.0
                        if (bbox.latitudeSpan >= radarLatSpan) {
                            newLat = 50.7880535 // Center of [45.0, 56.576107]
                        } else {
                            if (bbox.latNorth > 56.576107) {
                                newLat -= (bbox.latNorth - 56.576107)
                            } else if (bbox.latSouth < 45.0) {
                                newLat += (45.0 - bbox.latSouth)
                            }
                        }

                        if (Math.abs(newLat - center.latitude) > 1e-6 || Math.abs(newLon - center.longitude) > 1e-6) {
                            controller.setCenter(GeoPoint(newLat, newLon))
                        }
                    }
                }
            }

            addMapListener(object : org.osmdroid.events.MapListener {
                override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                    clampMap()
                    return true
                }
                override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                    clampMap()
                    return true
                }
            })

            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                clampMap()
            }
        }
    }

    val locationDrawable = remember { LocationDotDrawable(context) }

    val userLocationMarker = remember {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = locationDrawable
            title = "Dein Standort"
        }
    }

    val radarOverlay = remember { RadarBboxOverlay() }

    DisposableEffect(mapView) {
        onMapReady(mapView)
        onDispose {
            locationDrawable.stopAnimation()
            // Alle Bitmaps sicher recyclen beim Aufräumen
            bitmapCache.values.forEach { it?.recycle() }
            bitmapCache.clear()
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            if (frameTimes.isNotEmpty()) {
                // BitmapFactory-Options mit inSampleSize=2 um Speicher zu halbieren
                val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = 2
                }

                // 1. Aktuelle Zeitstempel-Keys sammeln
                val currentTimeKeys = HashSet<String>(frameTimes.size)

                // 2. Decode/fetch bitmaps from cache/disk (null-safe)
                val currentFrameBitmaps = frameTimes.map { time ->
                    val timeStr = DwdWmsClient.formatIsoTime(time)
                    currentTimeKeys.add(timeStr)
                    val file = DwdWmsClient.getCachedFrameFile(context, time)
                    if (file.exists() && file.length() > 0) {
                        // Prüfe ob bereits im Cache (auch null-Einträge beachten)
                        if (bitmapCache.containsKey(timeStr)) {
                            val cached = bitmapCache[timeStr]
                            // Recyclete Bitmaps erneut laden
                            if (cached != null && !cached.isRecycled) {
                                cached
                            } else {
                                val decoded = android.graphics.BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                                bitmapCache[timeStr] = decoded
                                decoded
                            }
                        } else {
                            val decoded = android.graphics.BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                            bitmapCache[timeStr] = decoded
                            decoded
                        }
                    } else {
                        null
                    }
                }

                // 3. Alte Bitmaps sicher aufräumen (nur die, die nicht mehr gebraucht werden)
                val keysToRemove = bitmapCache.keys.filter { it !in currentTimeKeys }
                for (key in keysToRemove) {
                    bitmapCache.remove(key)?.let { bmp ->
                        if (!bmp.isRecycled) bmp.recycle()
                    }
                }

                // 4. Perform fallback logic: if current index is not ready, search backward then forward
                var activeBitmap: android.graphics.Bitmap? = null
                if (!isPreloading && activeFrameIndex in frameTimes.indices) {
                    activeBitmap = currentFrameBitmaps[activeFrameIndex]
                    // Sicherstellen, dass das Bitmap nicht recycled ist
                    if (activeBitmap != null && activeBitmap.isRecycled) activeBitmap = null

                    if (activeBitmap == null) {
                        // Search backward
                        for (j in activeFrameIndex - 1 downTo 0) {
                            val bmp = currentFrameBitmaps[j]
                            if (bmp != null && !bmp.isRecycled) {
                                activeBitmap = bmp
                                break
                            }
                        }
                        // Search forward
                        if (activeBitmap == null) {
                            for (j in activeFrameIndex + 1 until frameTimes.size) {
                                val bmp = currentFrameBitmaps[j]
                                if (bmp != null && !bmp.isRecycled) {
                                    activeBitmap = bmp
                                    break
                                }
                            }
                        }
                    }
                }

                // 5. Update overlay with the resolved bitmap
                radarOverlay.bitmap = activeBitmap

                // 6. Construct desired overlay list
                val desiredOverlays = ArrayList<org.osmdroid.views.overlay.Overlay>()
                desiredOverlays.add(radarOverlay)
                if (userLocation != null) {
                    userLocationMarker.position = userLocation
                    desiredOverlays.add(userLocationMarker)
                }

                // Sync view overlays list
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
            haloRadius = minHaloRadius + (maxHaloRadius - minHaloRadius) * fraction
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
        
        paint.color = 0xFF3B82F6.toInt()
        paint.alpha = (haloAlpha * 255).toInt()
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, haloRadius, paint)
        
        paint.color = 0x44000000
        paint.alpha = 255
        canvas.drawCircle(cx, cy + 1.5f * density, borderRadius + 0.5f * density, paint)
        
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, borderRadius, paint)
        
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
