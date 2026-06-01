package com.example.rainradar.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rainradar.data.DwdWmsClient
import com.example.rainradar.ui.components.RadarMapView
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

@SuppressLint("MissingPermission")
@Composable
fun RadarScreen(viewModel: RadarViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val context = LocalContext.current
    val showForecast by viewModel.showForecast.collectAsState()
    val frameTimes by viewModel.frameTimes.collectAsState()
    val activeFrameIndex by viewModel.activeFrameIndex.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isPreloading by viewModel.isPreloading.collectAsState()

    var preloadProgress by remember { mutableStateOf(0f) }
    LaunchedEffect(isPreloading) {
        if (isPreloading) {
            preloadProgress = 0f
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 5000) {
                val elapsed = System.currentTimeMillis() - startTime
                preloadProgress = (elapsed / 5000f).coerceIn(0f, 1f)
                kotlinx.coroutines.delay(30)
            }
            preloadProgress = 1f
        } else {
            preloadProgress = 1f
        }
    }

    var userLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }

    // Setup GPS Location Updates
    val locationManager = remember { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    val locationListener = remember {
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                userLocation = GeoPoint(location.latitude, location.longitude)
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            try {
                val lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (lastKnown != null) {
                    userLocation = GeoPoint(lastKnown.latitude, lastKnown.longitude)
                }
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 10000L, 10f, locationListener
                )
            } catch (e: SecurityException) {
                // Handle exception
            }
        }
    }

    LaunchedEffect(Unit) {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
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
                onMapReady = { mapView ->
                    mapViewInstance = mapView
                }
            )

            // ── Header Control overlay ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(12.dp)
                    .background(SurfaceBg.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                    .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "DWD Regenradar",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "© OpenStreetMap | DWD (CC BY 4.0)",
                        color = TextSecondary,
                        fontSize = 10.sp
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                
                Button(
                    onClick = { viewModel.toggleForecast() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (showForecast) AccentBlue else AccentGreen
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        text = if (showForecast) "🔮 Vorhersage" else "⏪ Verlauf",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = { viewModel.refreshData() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Aktualisieren",
                        tint = TextPrimary
                    )
                }
            }

            // ── Active Frame Info (Top-Left under header) ──
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 90.dp, start = 12.dp)
                    .background(DarkBg.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                    .border(1.dp, BorderColor, RoundedCornerShape(6.dp))
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

            // ── Mode Badge Indicator (Top-Right under header) ──
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 90.dp, end = 12.dp)
                    .background(
                        color = if (showForecast) AccentBlue.copy(alpha = 0.9f) else AccentGreen.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (showForecast) "Vorhersage" else "Verlauf",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // ── Styled Rain Intensity Legend (Bottom-Right above controller) ──
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 150.dp, end = 12.dp)
                    .background(SurfaceBg.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                    .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
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

            // ── Floating Zoom / Location buttons (Bottom-Left above controller) ──
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 150.dp, start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Zoom In
                SmallFloatingActionButton(
                    onClick = {
                        mapViewInstance?.let {
                            it.controller.zoomIn()
                        }
                    },
                    containerColor = SurfaceBg,
                    contentColor = TextPrimary,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(38.dp)
                        .border(1.dp, BorderColor, CircleShape)
                ) {
                    Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                // Zoom Out
                SmallFloatingActionButton(
                    onClick = {
                        mapViewInstance?.let {
                            it.controller.zoomOut()
                        }
                    },
                    containerColor = SurfaceBg,
                    contentColor = TextPrimary,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(38.dp)
                        .border(1.dp, BorderColor, CircleShape)
                ) {
                    Text("−", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                // Location Jump
                AnimatedVisibility(
                    visible = userLocation != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            userLocation?.let { loc ->
                                mapViewInstance?.let { map ->
                                    map.controller.animateTo(loc, 9.5, 1000L)
                                }
                            }
                        },
                        containerColor = AccentBlue,
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "Zu Standort springen",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // ── Preloading Card Overlay (Glassmorphic) ──
            AnimatedVisibility(
                visible = isPreloading,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Column(
                    modifier = Modifier
                        .background(SurfaceBg.copy(alpha = 0.95f), RoundedCornerShape(16.dp))
                        .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
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
                        text = "${(preloadProgress * 100).toInt()}% geladen",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // ── Bottom Playback & Slider Controller ──
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(12.dp)
                    .background(SurfaceBg.copy(alpha = 0.95f), RoundedCornerShape(14.dp))
                    .border(1.dp, BorderColor, RoundedCornerShape(14.dp))
                    .padding(12.dp)
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
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Text(
                            text = if (isPreloading) "⏳" else if (isPlaying) "⏸" else "▶",
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Dynamic Frame slider
                    Slider(
                        value = activeFrameIndex.toFloat(),
                        onValueChange = { viewModel.setActiveFrameIndex(it.toInt()) },
                        enabled = !isPreloading,
                        valueRange = 0f..maxOf(0f, (frameTimes.size - 1).toFloat()),
                        steps = maxOf(0, frameTimes.size - 2),
                        colors = SliderDefaults.colors(
                            activeTrackColor = AccentBlue,
                            inactiveTrackColor = BorderColor,
                            thumbColor = AccentBlue,
                            disabledActiveTrackColor = BorderColor,
                            disabledThumbColor = BorderColor
                        ),
                        modifier = Modifier.weight(1f)
                    )

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
    }
}
