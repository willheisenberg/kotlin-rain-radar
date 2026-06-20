package com.example.rainradar.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rainradar.data.DwdWmsClient
import com.example.rainradar.ui.components.RadarMapView
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import com.example.rainradar.billing.BillingManager


// Sleek dark color palette
val DarkBg = Color(0xFF0F141C)
val SurfaceBg = Color(0xFF17202C)
val BorderColor = Color(0xFF26354A)
val TextPrimary = Color(0xFFE2E8F0)
val TextSecondary = Color(0xFF94A3B8)
val AccentBlue = Color(0xFF3B82F6)
val AccentGreen = Color(0xFF22C55E)

fun formatLocalTimeStr(instant: Instant?): String {
    if (instant == null) return "–"
    val formatter = DateTimeFormatter
        .ofPattern("EEE, dd.MM. HH:mm", Locale.GERMAN)
        .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}

@kotlin.OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun RadarScreen(
    viewModel: RadarViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    initialShowBilling: Boolean = false
) {
    val context = LocalContext.current
    val view = LocalView.current
    val frameTimes by viewModel.frameTimes.collectAsState()
    val activeFrameIndex by viewModel.activeFrameIndex.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isPreloading by viewModel.isPreloading.collectAsState()

    val preloadProgress by viewModel.preloadProgress.collectAsState()

    val billingManager = remember { BillingManager.getInstance(context) }
    val isPremium by billingManager.isPremium.collectAsState()
    val isPremiumDebug by billingManager.isPremiumDebug.collectAsState()
    val productPrice by billingManager.productPrice.collectAsState()
    var showPremiumDialog by remember { mutableStateOf(initialShowBilling) }

    // Observe App Lifecycle: reset to Now on ON_RESUME and stop playback on ON_PAUSE
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.refreshData(context, silent = true)
                    viewModel.setActiveFrameIndex(DwdWmsClient.PAST_FRAME_COUNT)
                    billingManager.queryPurchases()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.stopPlayback()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var userLocation by remember { mutableStateOf<LatLng?>(null) }
    var mapViewInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var hasCenteredOnUser by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }

    val prefs = remember { context.getSharedPreferences("rain_radar_prefs", Context.MODE_PRIVATE) }
    var targetZoomLevel by remember { mutableStateOf(prefs.getFloat("target_zoom_level", 9.5f)) }
    var showSettingsSlider by remember { mutableStateOf(false) }

    // Control status bar and navigation bar (system bars / Gestensteuerung) visibility
    LaunchedEffect(controlsVisible) {
        val activity = context as? Activity
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (controlsVisible) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    // Helper to validate if GPS location is within the radar's bounding box coverage
    fun isLocationInRadarBounds(lat: Double, lon: Double): Boolean {
        return lat in DwdWmsClient.LAT_SOUTH..DwdWmsClient.LAT_NORTH && lon in DwdWmsClient.LON_WEST..DwdWmsClient.LON_EAST
    }

    // Setup GPS & Network Location Updates
    val locationManager = remember { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    val locationListener = remember {
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val lat = location.latitude
                val lon = location.longitude
                val inBounds = isLocationInRadarBounds(lat, lon)
                userLocation = if (inBounds) LatLng(lat, lon) else null
                if (inBounds) {
                    context.getSharedPreferences("rain_radar_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putFloat("last_lat", lat.toFloat())
                        .putFloat("last_lon", lon.toFloat())
                        .apply()
                }
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
    }

    var permissionGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // Animate map to user's location once it is first resolved
    LaunchedEffect(userLocation, mapViewInstance) {
        val loc = userLocation
        val map = mapViewInstance
        if (loc != null && map != null && !hasCenteredOnUser) {
            map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(loc, targetZoomLevel.toDouble()), 1000)
            hasCenteredOnUser = true
        }
    }

    DisposableEffect(permissionGranted) {
        if (permissionGranted) {
            try {
                // 1. Fetch best possible last known location instantly (GPS or Network)
                val lastKnownGps = try {
                    if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    } else null
                } catch (e: Exception) { null }

                val lastKnownNetwork = try {
                    if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    } else null
                } catch (e: Exception) { null }

                val bestLastKnown = when {
                    lastKnownGps != null && lastKnownNetwork != null -> {
                        if (lastKnownGps.time >= lastKnownNetwork.time) lastKnownGps else lastKnownNetwork
                    }
                    lastKnownGps != null -> lastKnownGps
                    else -> lastKnownNetwork
                }

                if (bestLastKnown != null) {
                    val lat = bestLastKnown.latitude
                    val lon = bestLastKnown.longitude
                    val inBounds = isLocationInRadarBounds(lat, lon)
                    userLocation = if (inBounds) LatLng(lat, lon) else null
                    if (inBounds) {
                        context.getSharedPreferences("rain_radar_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putFloat("last_lat", lat.toFloat())
                            .putFloat("last_lon", lon.toFloat())
                            .apply()
                    }
                }

                // 2. Request updates from BOTH GPS (high accuracy) and Network (indoor fallback)
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        5000L,
                        10f,
                        locationListener
                    )
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        5000L,
                        10f,
                        locationListener
                    )
                }
            } catch (e: SecurityException) {
                // Permission revoked or not granted
            } catch (e: IllegalArgumentException) {
                // Provider does not exist on this device
            } catch (e: Exception) {
                // Unexpected location manager error
            }
        }
        onDispose {
            try {
                locationManager.removeUpdates(locationListener)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DarkBg
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            
            // ── The Interactive Map View ──
            RadarMapView(
                frameTimes = frameTimes,
                activeFrameIndex = activeFrameIndex,
                userLocation = userLocation,
                isPreloading = isPreloading,
                modifier = Modifier.fillMaxSize(),
                onMapClick = {
                    controlsVisible = !controlsVisible
                },
                onMapReady = { mapView ->
                    mapViewInstance = mapView
                }
            )

            // ── Status Bar Scrim Overlay ──
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.5f),
                                    Color.Transparent
                                )
                            )
                        )
                ) {
                    Spacer(
                        modifier = Modifier
                            .statusBarsPadding()
                            .height(16.dp)
                    )
                }
            }

            // ── Top UI Control Panel (Header & Badges) ──
            AnimatedVisibility(
                visible = controlsVisible,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(12.dp)
                ) {
                    // Header Card Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceBg.copy(alpha = 0.9f), RoundedCornerShape(7.dp))
                            .border(1.dp, BorderColor, RoundedCornerShape(7.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { showPremiumDialog = true }
                                .padding(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "OpenRain",
                                    color = TextPrimary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "© OpenStreetMap | DWD (CC BY 4.0)",
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))

                        if (!isPremium) {
                            Box(
                                modifier = Modifier
                                    .height(26.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(AccentBlue)
                                    .clickable { showPremiumDialog = true }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Premium",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        // Now button
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (isPreloading) SurfaceBg.copy(alpha = 0.5f) else SurfaceBg)
                                .border(1.dp, BorderColor, RoundedCornerShape(7.dp))
                                .clickable(enabled = !isPreloading) {
                                    viewModel.setActiveFrameIndex(DwdWmsClient.PAST_FRAME_COUNT)
                                }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Now",
                                color = if (isPreloading) TextSecondary else TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (showSettingsSlider) AccentBlue.copy(alpha = 0.2f) else SurfaceBg)
                                .border(1.dp, if (showSettingsSlider) AccentBlue else BorderColor, RoundedCornerShape(7.dp))
                                .clickable {
                                    showSettingsSlider = !showSettingsSlider
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Einstellungen",
                                tint = if (showSettingsSlider) AccentBlue else TextPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Badges Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Active Frame Info (Top-Left under header)
                        Box(
                            modifier = Modifier
                                .background(SurfaceBg.copy(alpha = 0.9f), RoundedCornerShape(7.dp))
                                .border(1.dp, BorderColor, RoundedCornerShape(7.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = if (frameTimes.isNotEmpty() && activeFrameIndex in frameTimes.indices) {
                                    formatLocalTimeStr(frameTimes[activeFrameIndex])
                                } else "–",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Mode Badge (Top-Right under header)
                        val isActiveForecast = activeFrameIndex >= DwdWmsClient.PAST_FRAME_COUNT
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (isActiveForecast) AccentBlue.copy(alpha = 0.9f) else AccentGreen.copy(alpha = 0.9f),
                                    shape = RoundedCornerShape(7.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isActiveForecast) "Vorhersage" else "Verlauf",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // ── Styled Rain Intensity Legend (Bottom-Right above controller) ──
            AnimatedVisibility(
                visible = controlsVisible,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                RainIntensityLegend()
            }

            // ── Zoom Settings Slider Panel (Left side) ──
            AnimatedVisibility(
                visible = controlsVisible && showSettingsSlider,
                enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .width(64.dp)
                        .background(SurfaceBg.copy(alpha = 0.9f), RoundedCornerShape(7.dp))
                        .border(1.dp, BorderColor, RoundedCornerShape(7.dp))
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Ansicht",
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )

                    val widthKm = 106000 / Math.pow(2.0, targetZoomLevel.toDouble())
                    val displayKm = if (widthKm >= 1000) {
                        "${Math.round(widthKm / 100.0) * 100} km"
                    } else if (widthKm >= 100) {
                        "${Math.round(widthKm / 10.0) * 10} km"
                    } else {
                        "${Math.round(widthKm)} km"
                    }

                    Text(
                        text = displayKm,
                        color = AccentBlue,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Box(
                        modifier = Modifier
                            .height(320.dp)
                            .width(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Slider(
                            value = targetZoomLevel,
                            onValueChange = {
                                targetZoomLevel = it
                            },
                            onValueChangeFinished = {
                                prefs.edit().putFloat("target_zoom_level", targetZoomLevel).apply()
                            },
                            valueRange = 5.0f..13.0f,
                            colors = SliderDefaults.colors(
                                activeTrackColor = AccentBlue,
                                inactiveTrackColor = BorderColor,
                                thumbColor = AccentBlue
                            ),
                            thumb = {
                                val interactionSource = remember { MutableInteractionSource() }
                                SliderDefaults.Thumb(
                                    interactionSource = interactionSource,
                                    colors = SliderDefaults.colors(
                                        thumbColor = AccentBlue
                                    ),
                                    thumbSize = DpSize(36.dp, 36.dp)
                                )
                            },
                            modifier = Modifier
                                .graphicsLayer {
                                    rotationZ = -90f
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                                }
                                .requiredWidth(320.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (isPreloading) SurfaceBg.copy(alpha = 0.5f) else SurfaceBg)
                            .border(1.dp, BorderColor, RoundedCornerShape(7.dp))
                            .clickable(enabled = !isPreloading) {
                                viewModel.refreshData(context, force = true)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Cache leeren & aktualisieren",
                            tint = if (isPreloading) TextSecondary else TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // ── Floating Location button (Bottom-Left above controller) ──
            AnimatedVisibility(
                visible = controlsVisible && userLocation != null,
                enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 150.dp, start = 12.dp)
            ) {
                FloatingActionButton(
                    onClick = {
                        userLocation?.let { loc ->
                            mapViewInstance?.let { map ->
                                map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(loc, targetZoomLevel.toDouble()), 1000)
                            }
                        }
                    },
                    containerColor = AccentBlue,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(53.dp)
                        .border(1.dp, BorderColor.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Zu Standort springen",
                        modifier = Modifier.size(25.dp)
                    )
                }
            }

            // ── Preloading Card Overlay (Glassmorphic) ──
            AnimatedVisibility(
                visible = isPreloading,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                PreloadingOverlay(
                    preloadProgress = preloadProgress,
                    frameCount = frameTimes.size
                )
            }

            // ── Bottom Playback & Slider Controller ──
            AnimatedVisibility(
                visible = controlsVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(12.dp)
                        .background(SurfaceBg.copy(alpha = 0.9f), RoundedCornerShape(7.dp))
                        .border(1.dp, BorderColor, RoundedCornerShape(7.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Play/Pause button
                    Button(
                        onClick = { viewModel.togglePlayback() },
                        enabled = !isPreloading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPlaying) Color(0xFFEF4444) else AccentBlue,
                            disabledContainerColor = SurfaceBg.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(7.dp),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(46.dp)
                    ) {
                        if (isPreloading) {
                            Text(
                                text = "⏳",
                                color = Color.White,
                                fontSize = 20.sp
                            )
                        } else if (isPlaying) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(width = 4.dp, height = 16.dp).background(Color.White, RoundedCornerShape(1.dp)))
                                Box(modifier = Modifier.size(width = 4.dp, height = 16.dp).background(Color.White, RoundedCornerShape(1.dp)))
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Combined Slider with dual-colored background track
                    Box(
                        modifier = Modifier
                            .weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        // Background track representing past/history (60% width) and forecast (40% width)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .padding(horizontal = 8.dp) // Align with slider thumb padding
                                .clip(RoundedCornerShape(2.dp))
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(36f)
                                    .fillMaxHeight()
                                    .background(AccentGreen.copy(alpha = 0.8f))
                            )
                            Box(
                                modifier = Modifier
                                    .weight(24f)
                                    .fillMaxHeight()
                                    .background(AccentBlue.copy(alpha = 0.8f))
                            )
                        }

                        val sliderMax = maxOf(0f, (frameTimes.size - 1).toFloat())
                        val interactionSource = remember { MutableInteractionSource() }
                        Slider(
                            value = activeFrameIndex.toFloat().coerceIn(0f, maxOf(0.1f, sliderMax)),
                            onValueChange = { viewModel.setActiveFrameIndex(it.toInt()) },
                            enabled = !isPreloading,
                            valueRange = 0f..maxOf(0.1f, sliderMax),
                            steps = maxOf(0, frameTimes.size - 2),
                            colors = SliderDefaults.colors(
                                activeTrackColor = Color.Transparent,
                                inactiveTrackColor = Color.Transparent,
                                thumbColor = if (activeFrameIndex < DwdWmsClient.PAST_FRAME_COUNT) AccentGreen else AccentBlue,
                                disabledActiveTrackColor = Color.Transparent,
                                disabledInactiveTrackColor = Color.Transparent
                            ),
                            interactionSource = interactionSource,
                            thumb = {
                                SliderDefaults.Thumb(
                                    interactionSource = interactionSource,
                                    colors = SliderDefaults.colors(
                                        thumbColor = if (activeFrameIndex < DwdWmsClient.PAST_FRAME_COUNT) AccentGreen else AccentBlue,
                                        disabledThumbColor = (if (activeFrameIndex < DwdWmsClient.PAST_FRAME_COUNT) AccentGreen else AccentBlue).copy(alpha = 0.5f)
                                    ),
                                    enabled = !isPreloading,
                                    thumbSize = DpSize(30.dp, 30.dp)
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Local time readout
                    Text(
                        text = if (frameTimes.isNotEmpty() && activeFrameIndex in frameTimes.indices) {
                            formatLocalTimeStr(frameTimes[activeFrameIndex]).replace(".*, ".toRegex(), "")
                        } else "–",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(50.dp)
                    )
                }
            }
            }

            if (showPremiumDialog) {
                val isPurchased = remember(isPremium) {
                    context.getSharedPreferences("rain_radar_prefs", Context.MODE_PRIVATE)
                        .getBoolean("is_premium", false) || isPremiumDebug
                }

                AlertDialog(
                    onDismissRequest = { showPremiumDialog = false },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "OpenRain Premium",
                                color = TextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .weight(1f)
                                    .combinedClickable(
                                        onLongClick = {
                                            billingManager.toggleDeveloperBypass()
                                        },
                                        onClick = {}
                                    )
                            )
                        }
                    },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = "Vorteile der Premium-Version:",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            
                            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(text = "📱 ", color = AccentBlue)
                                Text(
                                    text = "Exklusives Deutschland-Radar Homescreen-Widget",
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(text = "⚡ ", color = AccentBlue)
                                Text(
                                    text = "Aktualisierung direkt vom Widget aus",
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(text = "🔍 ", color = AccentBlue)
                                Text(
                                    text = "Präzise 2-Stunden-Regenvorhersage am Standort",
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))

                            val statusText = when {
                                isPurchased -> "Status: Premium freigeschaltet! Vielen Dank für deine Unterstützung. ❤️"
                                isPremium -> {
                                    val installTime = try {
                                        context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
                                    } catch (e: Exception) { 0L }
                                    val timeLeftMs = (24 * 60 * 60 * 1000L) - (System.currentTimeMillis() - installTime)
                                    val hoursLeft = maxOf(0L, timeLeftMs / (1000 * 60 * 60))
                                    "Status: Kostenlose Testphase aktiv (noch $hoursLeft Std. verbleibend)"
                                }
                                else -> "Status: Free-Version (Widget gesperrt)"
                            }

                            Text(
                                text = statusText,
                                color = if (isPremium) AccentGreen else TextSecondary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )

                            if (isPremiumDebug) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "⚠️ Entwickler-Bypass ist AKTIV!",
                                    color = Color.Yellow,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    },
                    confirmButton = {
                        if (!isPurchased) {
                            Button(
                                onClick = {
                                    val activity = context as? Activity
                                    if (activity != null) {
                                        billingManager.launchPurchaseFlow(activity)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AccentBlue
                                ),
                                shape = RoundedCornerShape(7.dp)
                            ) {
                                Text(
                                    text = "Freischalten (${productPrice ?: "0,99 €"})",
                                    color = Color.White
                                )
                            }
                        } else {
                            Button(
                                onClick = { showPremiumDialog = false },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SurfaceBg
                                ),
                                shape = RoundedCornerShape(7.dp)
                            ) {
                                Text(text = "Schließen", color = TextPrimary)
                            }
                        }
                    },
                    dismissButton = {
                        if (!isPurchased) {
                            TextButton(onClick = { showPremiumDialog = false }) {
                                Text(text = "Später", color = TextSecondary)
                            }
                        } else if (isPremiumDebug) {
                            TextButton(onClick = { 
                                billingManager.toggleDeveloperBypass() 
                            }) {
                                Text(text = "Bypass deaktivieren", color = Color.Red)
                            }
                        }
                    },
                    containerColor = SurfaceBg,
                    textContentColor = TextSecondary,
                    titleContentColor = TextPrimary,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
    }
}

@Composable
private fun RainIntensityLegend() {
    Column(
        modifier = Modifier
            .padding(bottom = 150.dp, end = 12.dp)
            .background(SurfaceBg.copy(alpha = 0.9f), RoundedCornerShape(7.dp))
            .border(1.dp, BorderColor, RoundedCornerShape(7.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "mm/h",
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        // Clean gradient representing radar intensity
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(110.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF8B008B), // Violet/Extreme
                            Color.Red,         // Heavy
                            Color.Yellow,      // Moderate
                            Color(0xFF22C55E), // Light
                            Color.Transparent  // None
                        )
                    )
                )
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "stark",
            color = TextSecondary,
            fontSize = 9.sp
        )
        Text(
            text = "leicht",
            color = TextSecondary,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun PreloadingOverlay(preloadProgress: Float, frameCount: Int) {
    Column(
        modifier = Modifier
            .background(SurfaceBg.copy(alpha = 0.9f), RoundedCornerShape(7.dp))
            .border(1.dp, BorderColor, RoundedCornerShape(7.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            progress = preloadProgress,
            color = AccentBlue,
            trackColor = BorderColor,
            strokeWidth = 4.dp,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Lade Radar-Daten...",
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "${(preloadProgress * 100).toInt()}% geladen (${(preloadProgress * frameCount).toInt()} / $frameCount Frames)",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

