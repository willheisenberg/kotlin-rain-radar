package com.example.rainradar.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.util.Log
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.rainradar.MainActivity
import com.example.rainradar.R
import com.example.rainradar.data.DwdWmsClient
import com.example.rainradar.data.RadarBitmapUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.maplibre.android.MapLibre
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class RadarWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        schedulePeriodicUpdate(context)
        triggerImmediateUpdate(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, RadarWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            
            // Show loading text on the forecast view immediately
            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.radar_widget)
                views.setTextViewText(R.id.widget_forecast_text, "Aktualisiere...")
                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
            }
            
            triggerImmediateUpdate(context)
        } else if (intent.action == ACTION_TOGGLE_ZOOM) {
            val prefs = context.getSharedPreferences("rain_radar_prefs", Context.MODE_PRIVATE)
            val currentMode = prefs.getString(KEY_WIDGET_ZOOM_MODE, "germany") ?: "germany"
            val newMode = if (currentMode == "germany") "location" else "germany"
            prefs.edit().putString(KEY_WIDGET_ZOOM_MODE, newMode).apply()
            
            triggerImmediateUpdate(context)
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelPeriodicUpdate(context)
    }

    companion object {
        private const val ACTION_REFRESH = "com.example.rainradar.widget.ACTION_REFRESH"
        private const val ACTION_TOGGLE_ZOOM = "com.example.rainradar.widget.ACTION_TOGGLE_ZOOM"
        private const val KEY_WIDGET_ZOOM_MODE = "widget_zoom_mode"
        private const val TAG = "RadarWidgetProvider"
        private const val PERIODIC_WORK_NAME = "OpenRainWidgetPeriodicUpdate"
        private const val UNIQUE_ONE_TIME_WORK_NAME = "OpenRainWidgetOneTimeUpdate"
 
        fun schedulePeriodicUpdate(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<RadarWidgetWorker>(
                15, TimeUnit.MINUTES
            ).build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
 
        fun triggerImmediateUpdate(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<RadarWidgetWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }
 
        fun scheduleSingleImmediateUpdate(context: Context) {
            triggerImmediateUpdate(context)
        }
 
        fun cancelPeriodicUpdate(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }

    private suspend fun getMapSnapshot(
        context: Context,
        lat: Double,
        lon: Double,
        zoom: Float,
        radarBitmap: Bitmap?,
        hasLocation: Boolean
    ): Bitmap? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                try {
                    MapLibre.getInstance(context)
                    val options = MapSnapshotter.Options(480, 521)
                        .withCameraPosition(CameraPosition.Builder()
                            .target(LatLng(lat, lon))
                            .zoom(zoom.toDouble())
                            .build()
                        )
                        .withStyle("https://tiles.openfreemap.org/styles/liberty")
                    
                    val snapshotter = MapSnapshotter(context, options)
                    snapshotter.start({ snapshot ->
                        val bmp = snapshot.bitmap
                        if (bmp != null) {
                            try {
                                val mutableBmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
                                val canvas = Canvas(mutableBmp)
                                
                                // 1. Overlay radar bitmap in correct coordinates
                                if (radarBitmap != null) {
                                    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                                        alpha = 204 // 80% opacity
                                    }
                                    val tl = snapshot.pixelForLatLng(LatLng(DwdWmsClient.LAT_NORTH, DwdWmsClient.LON_WEST))
                                    val tr = snapshot.pixelForLatLng(LatLng(DwdWmsClient.LAT_NORTH, DwdWmsClient.LON_EAST))
                                    val br = snapshot.pixelForLatLng(LatLng(DwdWmsClient.LAT_SOUTH, DwdWmsClient.LON_EAST))
                                    val bl = snapshot.pixelForLatLng(LatLng(DwdWmsClient.LAT_SOUTH, DwdWmsClient.LON_WEST))
                                    
                                    val destRect = RectF(
                                        minOf(tl.x, bl.x),
                                        minOf(tl.y, tr.y),
                                        maxOf(tr.x, br.x),
                                        maxOf(bl.y, br.y)
                                    )
                                    canvas.drawBitmap(radarBitmap, null, destRect, paint)
                                }
                                
                                // 2. Draw user location blue dot in center of viewport
                                if (hasLocation) {
                                    val cpx = mutableBmp.width / 2f
                                    val cpy = mutableBmp.height / 2f
                                    
                                    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        color = Color.parseColor("#803B82F6")
                                        style = Paint.Style.FILL
                                    }
                                    canvas.drawCircle(cpx, cpy, 18f, glowPaint)
                                    
                                    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        color = Color.parseColor("#3B82F6")
                                        style = Paint.Style.FILL
                                    }
                                    canvas.drawCircle(cpx, cpy, 7f, pinPaint)
                                    
                                    val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        color = Color.WHITE
                                        style = Paint.Style.FILL
                                    }
                                    canvas.drawCircle(cpx, cpy, 2.5f, centerPaint)
                                }
                                
                                continuation.resume(mutableBmp)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error copying or drawing snapshot", e)
                                continuation.resume(null)
                            }
                        } else {
                            continuation.resume(null)
                        }
                    }, { error ->
                        Log.e(TAG, "MapSnapshotter error callback: $error")
                        continuation.resume(null)
                    })
                    
                    continuation.invokeOnCancellation {
                        snapshotter.cancel()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed in MapSnapshotter setup", e)
                    continuation.resume(null)
                }
            }
        }
    }

    internal suspend fun updateWidgetSuspended(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.radar_widget)
        
        val prefs = context.getSharedPreferences("rain_radar_prefs", Context.MODE_PRIVATE)
        val hasLocation = prefs.contains("last_lat") && prefs.contains("last_lon")
        val lat = prefs.getFloat("last_lat", 51.1657f).toDouble()
        val lon = prefs.getFloat("last_lon", 10.4515f).toDouble()
        
        val isPremium = com.example.rainradar.billing.BillingManager.isPremiumUser(context)

        var radarBitmap: Bitmap? = null
        val zoomMode = prefs.getString(KEY_WIDGET_ZOOM_MODE, "germany") ?: "germany"
        var forecastText = "Regen-Übersicht"
        var locationName = "Deutschland"
        var activeCoords: Pair<Int, Int>? = null

        val fullWidth = DwdWmsClient.WMS_DEFAULT_WIDTH
        val fullHeight = DwdWmsClient.WMS_DEFAULT_HEIGHT

        if (isPremium) {
            val base = DwdWmsClient.getRoundedBaseTime()
            val times = DwdWmsClient.generateCombinedFrameTimes(base)
            val currentFrameTime = times.getOrNull(DwdWmsClient.PAST_FRAME_COUNT) ?: base

            // Download current frame if not available
            val currentFileReady = DwdWmsClient.isFrameReady(context, currentFrameTime, base) || 
                                   withContext(Dispatchers.IO) { DwdWmsClient.downloadFrame(context, currentFrameTime, base) }

            if (currentFileReady) {
                val currentFile = DwdWmsClient.getCachedFrameFile(context, currentFrameTime, base)
                if (currentFile.exists() && currentFile.length() > 0) {
                    try {
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = 2
                            inMutable = true
                        }
                        val rawBmp = BitmapFactory.decodeFile(currentFile.absolutePath, opts)
                        if (rawBmp != null) {
                            RadarBitmapUtils.cleanRadarBitmap(rawBmp)
                            radarBitmap = rawBmp
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode current radar frame", e)
                    }
                }
            }

            locationName = if (zoomMode == "location" && hasLocation) {
                "Lokale Ansicht"
            } else {
                "Deutschland"
            }

            val coords = DwdWmsClient.getPixelCoords(lat, lon, fullWidth, fullHeight)
            activeCoords = if (hasLocation && coords != null) coords else null

            if (activeCoords != null) {
                locationName = "Mein Standort"
                
                // Forecast logic: scan forecast frames
                var firstRainTimeOffset: Int? = null
                var firstRainIntensity: String? = null
                var firstRainNearbyOffset: Int? = null
                
                // We download a subset of forecast frames to optimize data consumption
                val p = DwdWmsClient.PAST_FRAME_COUNT
                val forecastIndices = listOf(p, p + 3, p + 6, p + 9, p + 12, p + 15, p + 18, p + 21, p + 23)
                val timeOffsets = listOf(0, 15, 30, 45, 60, 75, 90, 105, 120)
                
                for (i in forecastIndices.indices) {
                    val idx = forecastIndices[i]
                    val offsetMin = timeOffsets[i]
                    val time = times.getOrNull(idx) ?: continue
                    
                    val isReady = DwdWmsClient.isFrameReady(context, time, base) || 
                                  withContext(Dispatchers.IO) { DwdWmsClient.downloadFrame(context, time, base) }
                    
                    if (isReady) {
                        val file = DwdWmsClient.getCachedFrameFile(context, time, base)
                        if (file.exists() && file.length() > 0) {
                            try {
                                val opts = BitmapFactory.Options().apply {
                                    inSampleSize = 2
                                }
                                val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                                if (bmp != null) {
                                    val scaleX = bmp.width.toFloat() / fullWidth.toFloat()
                                    val scaleY = bmp.height.toFloat() / fullHeight.toFloat()
                                    val cpx = (activeCoords.first * scaleX).toInt()
                                    val cpy = (activeCoords.second * scaleY).toInt()
                                    
                                    // Check exact pixel for rain
                                    if (cpx in 0 until bmp.width && cpy in 0 until bmp.height) {
                                        val color = bmp.getPixel(cpx, cpy)
                                        if (RadarBitmapUtils.isRainColor(color)) {
                                            if (firstRainTimeOffset == null) {
                                                firstRainTimeOffset = offsetMin
                                                firstRainIntensity = RadarBitmapUtils.getRainIntensityName(color)
                                            }
                                        }
                                    }
                                    
                                    // Check nearby area within ~5 km radius (3 pixels)
                                    if (firstRainNearbyOffset == null) {
                                        var foundNearby = false
                                        val radius = 3
                                        for (dx in -radius..radius) {
                                            for (dy in -radius..radius) {
                                                val nx = cpx + dx
                                                val ny = cpy + dy
                                                if (nx in 0 until bmp.width && ny in 0 until bmp.height) {
                                                    val color = bmp.getPixel(nx, ny)
                                                    if (RadarBitmapUtils.isRainColor(color)) {
                                                        foundNearby = true
                                                        break
                                                    }
                                                }
                                            }
                                            if (foundNearby) break
                                        }
                                        if (foundNearby) {
                                            firstRainNearbyOffset = offsetMin
                                        }
                                    }
                                    
                                    bmp.recycle()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed decoding forecast frame index $idx", e)
                            }
                        }
                    }
                    
                    // Break early if we've determined it's raining at this exact minute
                    if (firstRainTimeOffset == 0) {
                        break
                    }
                }
                
                forecastText = when {
                    firstRainTimeOffset == 0 -> {
                        "${firstRainIntensity ?: "Regen"} jetzt"
                    }
                    firstRainTimeOffset != null -> {
                        "${firstRainIntensity ?: "Regen"} in $firstRainTimeOffset Min."
                    }
                    firstRainNearbyOffset != null -> {
                        if (firstRainNearbyOffset == 0) {
                            "Regen in der Nähe"
                        } else {
                            "Regen in der Nähe in $firstRainNearbyOffset Min."
                        }
                    }
                    else -> {
                        "Trocken für 2 Std."
                    }
                }
            }
        } else {
            forecastText = "Premium-Feature"
            locationName = "In-App freischalten"
        }
        
        var combinedBmp: Bitmap? = null
        
        if (isPremium && zoomMode == "location" && hasLocation) {
            val zoomLevel = prefs.getFloat("target_zoom_level", 9.5f)
            combinedBmp = getMapSnapshot(context, lat, lon, zoomLevel, radarBitmap, hasLocation)
        }
        
        if (combinedBmp == null) {
            // Render combined map of Germany with translucent radar overlay or locked screen
            try {
                val baseMapBmp = BitmapFactory.decodeResource(context.resources, R.drawable.germany_map)
                if (baseMapBmp != null) {
                    combinedBmp = Bitmap.createBitmap(baseMapBmp.width, baseMapBmp.height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(combinedBmp)
                    canvas.drawBitmap(baseMapBmp, 0f, 0f, null)
                    baseMapBmp.recycle()
                    
                    if (isPremium) {
                        // Overlay the radar frame on top with 80% opacity
                        if (radarBitmap != null) {
                            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                                alpha = 204 // 80% opacity
                            }
                            val srcRect = Rect(0, 0, radarBitmap.width, radarBitmap.height)
                            val destRect = Rect(0, 0, combinedBmp.width, combinedBmp.height)
                            canvas.drawBitmap(radarBitmap, srcRect, destRect, paint)
                        }
                        
                        // Overlay user position marker
                        if (activeCoords != null) {
                            val scaleX = combinedBmp.width.toFloat() / fullWidth.toFloat()
                            val scaleY = combinedBmp.height.toFloat() / fullHeight.toFloat()
                            val cpx = activeCoords.first * scaleX
                            val cpy = activeCoords.second * scaleY
                            
                            // Translucent blue glow circle
                            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.parseColor("#803B82F6")
                                style = Paint.Style.FILL
                            }
                            canvas.drawCircle(cpx, cpy, 18f, glowPaint)
                            
                            // Solid blue pin circle
                            val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.parseColor("#3B82F6")
                                style = Paint.Style.FILL
                            }
                            canvas.drawCircle(cpx, cpy, 7f, pinPaint)
                            
                            // White center point
                            val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.WHITE
                                style = Paint.Style.FILL
                            }
                            canvas.drawCircle(cpx, cpy, 2.5f, centerPaint)
                        }
                    } else {
                        // Draw semi-translucent dark overlay
                        val overlayPaint = Paint().apply {
                            color = Color.parseColor("#C80F141C") // ~78% dark overlay
                            style = Paint.Style.FILL
                        }
                        canvas.drawRect(0f, 0f, combinedBmp.width.toFloat(), combinedBmp.height.toFloat(), overlayPaint)
                        
                        val centerX = combinedBmp.width / 2f
                        val centerY = combinedBmp.height / 2f - 40f
                        
                        // Padlock shackle (U-shape on top)
                        val shacklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.parseColor("#94A3B8")
                            style = Paint.Style.STROKE
                            strokeWidth = 14f
                            strokeCap = Paint.Cap.ROUND
                        }
                        val shacklePath = Path().apply {
                            moveTo(centerX - 40f, centerY)
                            lineTo(centerX - 40f, centerY - 45f)
                            arcTo(centerX - 40f, centerY - 85f, centerX + 40f, centerY - 5f, 180f, 180f, false)
                            lineTo(centerX + 40f, centerY)
                        }
                        canvas.drawPath(shacklePath, shacklePaint)
                        
                        // Padlock body (rounded rectangle)
                        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.parseColor("#94A3B8")
                            style = Paint.Style.FILL
                        }
                        val bodyRect = RectF(centerX - 60f, centerY, centerX + 60f, centerY + 90f)
                        canvas.drawRoundRect(bodyRect, 18f, 18f, bodyPaint)
                        
                        // Keyhole circle & keyhole bar
                        val keyholePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.parseColor("#0F141C")
                            style = Paint.Style.FILL
                        }
                        canvas.drawCircle(centerX, centerY + 35f, 12f, keyholePaint)
                        val keyholeBar = RectF(centerX - 6f, centerY + 35f, centerX + 6f, centerY + 65f)
                        canvas.drawRect(keyholeBar, keyholePaint)
                        
                        // "OpenRain Premium" Label text
                        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.parseColor("#E2E8F0")
                            textSize = 38f
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            textAlign = Paint.Align.CENTER
                        }
                        canvas.drawText("OpenRain Premium", centerX, centerY + 160f, textPaint)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build composite base map + radar image", e)
            }
        }
        
        var finalBmp: Bitmap? = null
        if (combinedBmp != null) {
            try {
                finalBmp = Bitmap.createScaledBitmap(combinedBmp, 480, 521, true)
                combinedBmp.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to scale down composite bitmap", e)
                finalBmp = combinedBmp
            }
        }

        // Final fallback output bitmap if rendering failed
        val outputBmp = finalBmp ?: Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.parseColor("#121620"))
            if (radarBitmap != null) {
                canvas.drawBitmap(radarBitmap, null, Rect(0, 0, 300, 300), null)
            }
        }
        
        // Clean up memory
        radarBitmap?.recycle()
        
        // Update views
        views.setImageViewBitmap(R.id.widget_radar_image, outputBmp)
        views.setTextViewText(R.id.widget_forecast_text, forecastText)
        views.setTextViewText(R.id.widget_subtext, "Standort: $locationName")
        
        val timeFormatter = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault())
        val updateTimeStr = timeFormatter.format(Instant.now())
        views.setTextViewText(R.id.widget_update_time, updateTimeStr)
        
        // PendingIntent for clicking the whole widget to launch MainActivity
        val configIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (!isPremium) {
                putExtra("show_billing", true)
            }
        }
        val configPendingIntent = PendingIntent.getActivity(
            context,
            0,
            configIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, configPendingIntent)
        
        // PendingIntent for clicking the refresh button (direct to App if locked, otherwise normal refresh broadcast)
        val refreshPendingIntent = if (isPremium) {
            val refreshIntent = Intent(context, RadarWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            PendingIntent.getBroadcast(
                context,
                0,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            configPendingIntent
        }
        views.setOnClickPendingIntent(R.id.widget_btn_refresh, refreshPendingIntent)
        
        // PendingIntent for clicking the zoom toggle button
        val toggleZoomIntent = Intent(context, RadarWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE_ZOOM
        }
        val toggleZoomPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            toggleZoomIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_btn_zoom_toggle, toggleZoomPendingIntent)
        
        // Dynamically set zoom toggle icon based on mode
        val toggleIconRes = if (zoomMode == "location") {
            R.drawable.ic_location_pin
        } else {
            R.drawable.ic_map
        }
        views.setImageViewResource(R.id.widget_btn_zoom_toggle, toggleIconRes)
        
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
}
