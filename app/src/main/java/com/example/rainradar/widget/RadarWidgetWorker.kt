package com.example.rainradar.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class RadarWidgetWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("RadarWidgetWorker", "Starting background widget update via WorkManager")
        val context = applicationContext
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, RadarWidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

        return try {
            for (appWidgetId in appWidgetIds) {
                RadarWidgetProvider.updateWidgetSuspended(context, appWidgetManager, appWidgetId)
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("RadarWidgetWorker", "Error in WorkManager widget update", e)
            Result.retry()
        }
    }
}
