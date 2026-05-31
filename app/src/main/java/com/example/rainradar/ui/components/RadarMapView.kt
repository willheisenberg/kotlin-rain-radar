package com.example.rainradar.ui.components

import android.content.Context
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.rainradar.data.DwdWmsClient
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import java.time.Instant

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
    modifier: Modifier = Modifier,
    onMapReady: (MapView) -> Unit = {}
) {
    // Configure user agent for Osmdroid
    val context = androidx.compose.ui.platform.LocalContext.current
    remember {
        Configuration.getInstance().userAgentValue = "DwdRainRadarApp"
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    }

    val mapView = remember {
        MapView(context).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(6.0)
            // Center of Germany
            controller.setCenter(GeoPoint(51.1657, 10.4515))
        }
    }

    // Keep track of active overlays in a cache
    val overlaysCache = remember { HashMap<String, TilesOverlay>() }
    val userLocationMarker = remember {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            // Custom high-quality marker using built-in shape or resource later
            title = "Dein Standort"
        }
    }

    DisposableEffect(mapView) {
        onMapReady(mapView)
        onDispose {
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            // Update User Location Marker
            if (userLocation != null) {
                userLocationMarker.position = userLocation
                if (!view.overlays.contains(userLocationMarker)) {
                    view.overlays.add(userLocationMarker)
                }
            } else {
                view.overlays.remove(userLocationMarker)
            }

            // Sync dynamic overlay frames
            if (frameTimes.isNotEmpty() && activeFrameIndex in frameTimes.indices) {
                val activeTimeStr = DwdWmsClient.formatIsoTime(frameTimes[activeFrameIndex])

                // Pre-create overlays for all loaded frameTimes to allow background caching
                frameTimes.forEach { time ->
                    val timeStr = DwdWmsClient.formatIsoTime(time)
                    if (!overlaysCache.containsKey(timeStr)) {
                        val tileSource = DwdWmsTileSource(timeStr)
                        val provider = MapTileProviderBasic(context, tileSource)
                        val overlay = TilesOverlay(provider, context).apply {
                            loadingBackgroundColor = Color.TRANSPARENT
                            loadingLineColor = Color.TRANSPARENT
                        }
                        overlaysCache[timeStr] = overlay
                    }
                }

                // Add missing overlays to map view and update visibility
                overlaysCache.forEach { (timeStr, overlay) ->
                    val isCurrent = (timeStr == activeTimeStr)
                    overlay.isEnabled = isCurrent
                    
                    if (isCurrent && !view.overlays.contains(overlay)) {
                        // Insert at index 0 or before marker to ensure overlay renders under location markers
                        view.overlays.add(0, overlay)
                    } else if (!isCurrent && view.overlays.contains(overlay)) {
                        // Keep it on the map for pre-fetching/smooth rendering, but set isEnabled = false
                        overlay.isEnabled = false
                    }
                }

                // Force view repaint
                view.postInvalidate()
            }
        }
    )
}
