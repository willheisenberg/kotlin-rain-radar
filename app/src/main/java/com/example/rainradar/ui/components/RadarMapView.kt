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
import com.example.rainradar.data.RadarBitmapUtils
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
    val minZoomLon = Math.log((wDp * 360.0) / (256.0 * (DwdWmsClient.LON_EAST - DwdWmsClient.LON_WEST))) / Math.log(2.0)

    // Lat constraint:
    // delta_y = ln(tan(pi/4 + lat_N/2)) - ln(tan(pi/4 + lat_S/2))
    // Z >= log2( H * 2 * pi / (256.0 * delta_y) )
    val latN = Math.toRadians(DwdWmsClient.LAT_NORTH)
    val latS = Math.toRadians(DwdWmsClient.LAT_SOUTH)
    val yN = Math.log(Math.tan(Math.PI / 4.0 + latN / 2.0))
    val yS = Math.log(Math.tan(Math.PI / 4.0 + latS / 2.0))
    val deltaY = yN - yS
    val minZoomLat = Math.log((hDp * 2.0 * Math.PI) / (256.0 * deltaY)) / Math.log(2.0)

    // Using minOf and subtracting 1.0 ensures that the entire radar frame fits in the viewport with a comfortable margin
    val minZoom = minOf(minZoomLon, minZoomLat) - 1.0
    return maxOf(3.0, minZoom)
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

    // Track the base time derived from frameTimes. When it changes (e.g. after
    // the app resumes from background), evict the in-memory bitmap cache so
    // stale decoded images are not served.
    val currentBase = frameTimes.getOrNull(DwdWmsClient.PAST_FRAME_COUNT)
    var lastSeenBase by remember { mutableStateOf<Instant?>(null) }
    if (currentBase != null && currentBase != lastSeenBase) {
        if (lastSeenBase != null) {
            bitmapCache.evictAll()
        }
        lastSeenBase = currentBase
    }

    LaunchedEffect(activeFrameIndex, frameTimes, isPreloading) {
        if (isPreloading || frameTimes.isEmpty()) {
            activeBitmapState = null
            return@LaunchedEffect
        }
        
        val base = frameTimes.getOrNull(DwdWmsClient.PAST_FRAME_COUNT) ?: DwdWmsClient.getRoundedBaseTime()

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
                    RadarBitmapUtils.cleanRadarBitmap(decoded)
                    
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
                        RadarBitmapUtils.cleanRadarBitmap(decoded)
                        
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
                    LatLng(DwdWmsClient.LAT_NORTH, DwdWmsClient.LON_WEST),
                    LatLng(DwdWmsClient.LAT_NORTH, DwdWmsClient.LON_EAST),
                    LatLng(DwdWmsClient.LAT_SOUTH, DwdWmsClient.LON_EAST),
                    LatLng(DwdWmsClient.LAT_SOUTH, DwdWmsClient.LON_WEST)
                )
                val emptyBmp = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                val imageSource = ImageSource("radar-source", quad, emptyBmp)
                style.addSource(imageSource)

                val rasterLayer = RasterLayer("radar-layer", "radar-source")
                rasterLayer.setProperties(
                    PropertyFactory.rasterFadeDuration(0f),
                    PropertyFactory.rasterOpacity(0.75f)
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

                        val radarLonSpan = DwdWmsClient.LON_EAST - DwdWmsClient.LON_WEST
                        val radarLonCenter = (DwdWmsClient.LON_WEST + DwdWmsClient.LON_EAST) / 2.0

                        var changed = false
                        // Longitude clamping / centering
                        if (lonSpan >= radarLonSpan) {
                            if (Math.abs(newLon - radarLonCenter) > 1e-5) {
                                newLon = radarLonCenter
                                changed = true
                            }
                        } else {
                            if (lonWest < DwdWmsClient.LON_WEST) {
                                newLon += (DwdWmsClient.LON_WEST - lonWest)
                                changed = true
                            } else if (lonEast > DwdWmsClient.LON_EAST) {
                                newLon -= (lonEast - DwdWmsClient.LON_EAST)
                                changed = true
                            }
                        }

                        // Latitude clamping / centering
                        val radarLatSpan = DwdWmsClient.LAT_NORTH - DwdWmsClient.LAT_SOUTH
                        val radarLatCenter = (DwdWmsClient.LAT_SOUTH + DwdWmsClient.LAT_NORTH) / 2.0
                        if (latSpan >= radarLatSpan) {
                            if (Math.abs(newLat - radarLatCenter) > 1e-5) {
                                newLat = radarLatCenter
                                changed = true
                            }
                        } else {
                            if (latNorth > DwdWmsClient.LAT_NORTH) {
                                newLat -= (latNorth - DwdWmsClient.LAT_NORTH)
                                changed = true
                            } else if (latSouth < DwdWmsClient.LAT_SOUTH) {
                                newLat += (DwdWmsClient.LAT_SOUTH - latSouth)
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
