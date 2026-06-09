package com.example.rainradar.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.rainradar.data.DwdWmsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource
import java.time.Instant

private fun calculateMinZoom(context: Context, mapWidth: Int, mapHeight: Int): Double {
    if (mapWidth <= 0 || mapHeight <= 0) return 6.0

    val density = context.resources.displayMetrics.density
    val wDp = mapWidth.toDouble() / density
    val hDp = mapHeight.toDouble() / density

    // Lon constraint:
    // Z >= log2( (W * 360.0) / (256.0 * 17.0) )
    val minZoomLon = Math.log((wDp * 360.0) / (256.0 * 17.0)) / Math.log(2.0)

    // Lat constraint:
    // delta_y = ln(tan(pi/4 + lat_N/2)) - ln(tan(pi/4 + lat_S/2))
    // Z >= log2( H * 2 * pi / (256.0 * delta_y) )
    val latN = Math.toRadians(56.576107)
    val latS = Math.toRadians(45.0)
    val yN = Math.log(Math.tan(Math.PI / 4.0 + latN / 2.0))
    val yS = Math.log(Math.tan(Math.PI / 4.0 + latS / 2.0))
    val deltaY = yN - yS
    val minZoomLat = Math.log((hDp * 2.0 * Math.PI) / (256.0 * deltaY)) / Math.log(2.0)

    // Using minOf and subtracting 1.0 ensures that the entire radar frame fits in the viewport with a comfortable margin
    val minZoom = minOf(minZoomLon, minZoomLat) - 1.0
    return maxOf(3.0, minZoom)
}

private fun cleanRadarBitmap(bitmap: android.graphics.Bitmap) {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    
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
            pixels[i] = 0 // Transparent
            modified = true
        }
    }
    
    if (modified && bitmap.isMutable) {
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }
}

@Composable
fun RadarMapView(
    frameTimes: List<Instant>,
    activeFrameIndex: Int,
    userLocation: LatLng?,
    isPreloading: Boolean,
    modifier: Modifier = Modifier,
    onMapClick: () -> Unit = {},
    onMapReady: (MapLibreMap) -> Unit = {}
) {
    val context = LocalContext.current
    val bitmapCache = remember { android.util.LruCache<String, android.graphics.Bitmap>(15) }
    var activeBitmapState by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(activeFrameIndex, frameTimes, isPreloading) {
        if (isPreloading || frameTimes.isEmpty()) {
            activeBitmapState = null
            return@LaunchedEffect
        }
        
        val base = frameTimes.getOrNull(36) ?: DwdWmsClient.getRoundedBaseTime()

        withContext(Dispatchers.IO) {
            var targetIndex = -1
            if (activeFrameIndex in frameTimes.indices) {
                val file = DwdWmsClient.getCachedFrameFile(context, frameTimes[activeFrameIndex], base)
                if (file.exists() && file.length() > 0) {
                    targetIndex = activeFrameIndex
                } else {
                    for (j in activeFrameIndex - 1 downTo 0) {
                        val f = DwdWmsClient.getCachedFrameFile(context, frameTimes[j], base)
                        if (f.exists() && f.length() > 0) {
                            targetIndex = j
                            break
                        }
                    }
                    if (targetIndex == -1) {
                        for (j in activeFrameIndex + 1 until frameTimes.size) {
                            val f = DwdWmsClient.getCachedFrameFile(context, frameTimes[j], base)
                            if (f.exists() && f.length() > 0) {
                                targetIndex = j
                                break
                            }
                        }
                    }
                }
            }

            if (targetIndex == -1) {
                if (isActive) {
                    activeBitmapState = null
                }
                return@withContext
            }

            val targetTime = frameTimes[targetIndex]
            val timeStr = DwdWmsClient.formatIsoTime(targetTime)
            
            val cached = bitmapCache.get(timeStr)
            if (cached != null) {
                if (isActive) {
                    activeBitmapState = cached
                }
                return@withContext
            }

            val file = DwdWmsClient.getCachedFrameFile(context, targetTime, base)
            val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = 2
                inMutable = true
            }

            try {
                if (!isActive) return@withContext
                val decoded = android.graphics.BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                if (decoded != null) {
                    if (!isActive) return@withContext
                    cleanRadarBitmap(decoded)
                    
                    if (!isActive) return@withContext
                    bitmapCache.put(timeStr, decoded)
                    activeBitmapState = decoded
                } else {
                    if (file.exists()) {
                        file.delete()
                    }
                    if (isActive) {
                        activeBitmapState = null
                    }
                }
            } catch (e: OutOfMemoryError) {
                bitmapCache.evictAll()
                System.gc()
                
                try {
                    if (!isActive) return@withContext
                    val decoded = android.graphics.BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                    if (decoded != null) {
                        if (!isActive) return@withContext
                        cleanRadarBitmap(decoded)
                        
                        if (!isActive) return@withContext
                        bitmapCache.put(timeStr, decoded)
                        activeBitmapState = decoded
                    } else {
                        if (file.exists()) {
                            file.delete()
                        }
                        if (isActive) {
                            activeBitmapState = null
                        }
                    }
                } catch (e2: Throwable) {
                    if (isActive) {
                        activeBitmapState = null
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    activeBitmapState = null
                }
            }
        }
    }

    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            bitmapCache.evictAll()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.uiSettings.isRotateGesturesEnabled = false
            // Use the Liberty vector style from OpenFreeMap
            map.setStyle("https://tiles.openfreemap.org/styles/liberty") { style ->
                // Add the radar source and layer
                val quad = LatLngQuad(
                    LatLng(56.576107, 2.0),
                    LatLng(56.576107, 19.0),
                    LatLng(45.0, 19.0),
                    LatLng(45.0, 2.0)
                )
                val emptyBmp = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                val imageSource = ImageSource("radar-source", quad, emptyBmp)
                style.addSource(imageSource)

                val rasterLayer = RasterLayer("radar-layer", "radar-source")
                rasterLayer.setProperties(
                    PropertyFactory.rasterFadeDuration(0f)
                )
                style.addLayer(rasterLayer)

                // Initialize native LocationComponent
                val locationComponent = map.locationComponent
                val options = LocationComponentOptions.builder(context)
                    .pulseEnabled(true)
                    .pulseColor(android.graphics.Color.parseColor("#3B82F6"))
                    .pulseAlpha(0.3f)
                    .build()
                val activationOptions = LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(false)
                    .locationComponentOptions(options)
                    .build()
                locationComponent.activateLocationComponent(activationOptions)

                mapInstance = map
                map.addOnMapClickListener {
                    onMapClick()
                    false
                }
                onMapReady(map)
            }

            // Set initial state
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(51.1657, 10.4515), 6.0))

            // Keep min zoom and pan boundaries restricted dynamically depending on viewport/screen size
            var isClamping = false
            fun clampMap() {
                if (isClamping) return
                val w = mapView.width
                val h = mapView.height
                if (w > 0 && h > 0) {
                    val minZoom = calculateMinZoom(context, w, h)
                    map.setMinZoomPreference(minZoom)
                    if (map.cameraPosition.zoom < minZoom) {
                        isClamping = true
                        map.moveCamera(CameraUpdateFactory.zoomTo(minZoom))
                        isClamping = false
                    }
                    
                    val bbox = map.projection.visibleRegion.latLngBounds
                    val center = map.cameraPosition.target ?: return
                    var newLat = center.latitude
                        var newLon = center.longitude

                        val latNorth = bbox.getLatNorth()
                        val latSouth = bbox.getLatSouth()
                        val lonWest = bbox.getLonWest()
                        val lonEast = bbox.getLonEast()

                        val lonSpan = Math.abs(lonEast - lonWest)
                        val latSpan = Math.abs(latNorth - latSouth)

                        var changed = false
                        // Longitude clamping / centering
                        if (lonSpan >= 17.0) {
                            if (Math.abs(newLon - 10.5) > 1e-5) {
                                newLon = 10.5
                                changed = true
                            }
                        } else {
                            if (lonWest < 2.0) {
                                newLon += (2.0 - lonWest)
                                changed = true
                            } else if (lonEast > 19.0) {
                                newLon -= (lonEast - 19.0)
                                changed = true
                            }
                        }

                        // Latitude clamping / centering
                        val radarLatSpan = 56.576107 - 45.0
                        if (latSpan >= radarLatSpan) {
                            if (Math.abs(newLat - 50.7880535) > 1e-5) {
                                newLat = 50.7880535
                                changed = true
                            }
                        } else {
                            if (latNorth > 56.576107) {
                                newLat -= (latNorth - 56.576107)
                                changed = true
                            } else if (latSouth < 45.0) {
                                newLat += (45.0 - latSouth)
                                changed = true
                            }
                        }

                        if (changed) {
                            isClamping = true
                            map.moveCamera(CameraUpdateFactory.newLatLng(LatLng(newLat, newLon)))
                            isClamping = false
                        }
                }
            }

            map.addOnCameraMoveListener {
                clampMap()
            }

            mapView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                clampMap()
            }
            
            // Perform initial clamp
            clampMap()
        }
    }

    LaunchedEffect(activeBitmapState, mapInstance) {
        val map = mapInstance ?: return@LaunchedEffect
        val bitmap = activeBitmapState
        val style = map.style ?: return@LaunchedEffect
        val source = style.getSource("radar-source") as? ImageSource ?: return@LaunchedEffect
        
        if (bitmap != null) {
            source.setImage(bitmap)
            style.getLayer("radar-layer")?.setProperties(PropertyFactory.visibility(Property.VISIBLE))
        } else {
            style.getLayer("radar-layer")?.setProperties(PropertyFactory.visibility(Property.NONE))
        }
    }

    LaunchedEffect(userLocation, mapInstance) {
        val map = mapInstance ?: return@LaunchedEffect
        if (map.style == null) return@LaunchedEffect
        val locationComponent = map.locationComponent
        
        if (userLocation != null) {
            locationComponent.isLocationComponentEnabled = true
            val loc = android.location.Location("manual").apply {
                latitude = userLocation.latitude
                longitude = userLocation.longitude
                accuracy = 10f
                time = System.currentTimeMillis()
            }
            locationComponent.forceLocationUpdate(loc)
        } else {
            locationComponent.isLocationComponentEnabled = false
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
    )
}
