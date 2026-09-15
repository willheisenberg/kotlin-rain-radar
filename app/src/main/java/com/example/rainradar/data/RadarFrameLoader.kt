package com.example.rainradar.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.time.Instant

/** Prefer the requested frame; bridge gaps with a cached neighbour. */
internal class RadarFrameLoader(private val context: Context) {
    private val cache = LruCache<String, Bitmap>(15)

    fun load(time: Instant, base: Instant): Bitmap? {
        decode(time, base)?.let { return it }
        val times = DwdWmsClient.generateCombinedFrameTimes(base)
        for (candidate in times.filter { it < time }.asReversed() + times.filter { it > time }) {
            decode(candidate, base)?.let { return it }
        }
        return null
    }

    private fun decode(time: Instant, base: Instant): Bitmap? {
        val file = DwdWmsClient.getCachedFrameFile(context, time, base)
        if (!file.isFile || file.length() == 0L) return null
        val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        cache.get(key)?.let { return it }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 2
            inMutable = true
        }
        return try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            if (!DwdWmsClient.isWebpFile(file)) RadarBitmapUtils.cleanRadarBitmap(bitmap)
            cache.put(key, bitmap)
            bitmap
        } catch (error: OutOfMemoryError) {
            cache.evictAll()
            null
        }
    }

    fun clear() = cache.evictAll()
}
