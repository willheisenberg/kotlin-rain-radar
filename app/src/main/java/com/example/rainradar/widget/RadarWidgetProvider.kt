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
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelPeriodicUpdate(context)
    }

    companion object {
        private const val ACTION_REFRESH = "com.example.rainradar.widget.ACTION_REFRESH"
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

        fun cancelPeriodicUpdate(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }

    internal suspend fun updateWidgetSuspended(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.radar_widget)
        
        val prefs = context.getSharedPreferences("rain_radar_prefs", Context.MODE_PRIVATE)
        val hasLocation = prefs.contains("last_lat") && prefs.contains("last_lon")
        val lat = prefs.getFloat("last_lat", 51.1657f).toDouble()
        val lon = prefs.getFloat("last_lon", 10.4515f).toDouble()
        
        val base = DwdWmsClient.getRoundedBaseTime()
        val times = DwdWmsClient.generateCombinedFrameTimes(base)
        
        val currentFrameTime = times.getOrNull(DwdWmsClient.PAST_FRAME_COUNT) ?: base
        
        // Download current frame if not available
        val currentFileReady = DwdWmsClient.isFrameReady(context, currentFrameTime, base) || 
                               withContext(Dispatchers.IO) { DwdWmsClient.downloadFrame(context, currentFrameTime, base) }
        
        var radarBitmap: Bitmap? = null
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
        
        var locationName = "Deutschland"
        var forecastText = "Regen-Übersicht"
        
        val fullWidth = DwdWmsClient.WMS_DEFAULT_WIDTH
        val fullHeight = DwdWmsClient.WMS_DEFAULT_HEIGHT
        val coords = DwdWmsClient.getPixelCoords(lat, lon, fullWidth, fullHeight)
        
        val activeCoords = if (hasLocation && coords != null) coords else null
        
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
        
        // Render combined map of Germany with translucent radar overlay
        var combinedBmp: Bitmap? = null
        try {
            val baseMapBmp = BitmapFactory.decodeResource(context.resources, R.drawable.germany_map)
            if (baseMapBmp != null) {
                combinedBmp = Bitmap.createBitmap(baseMapBmp.width, baseMapBmp.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(combinedBmp)
                canvas.drawBitmap(baseMapBmp, 0f, 0f, null)
                baseMapBmp.recycle()
                
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build composite base map + radar image", e)
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
        
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        val updateTimeStr = timeFormatter.format(Instant.now())
        views.setTextViewText(R.id.widget_update_time, updateTimeStr)
        
        // PendingIntent for clicking the whole widget to launch MainActivity
        val configIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val configPendingIntent = PendingIntent.getActivity(
            context,
            0,
            configIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, configPendingIntent)
        
        // PendingIntent for clicking the refresh button
        val refreshIntent = Intent(context, RadarWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_btn_refresh, refreshPendingIntent)
        
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    }
}
