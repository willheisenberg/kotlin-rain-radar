package com.example.rainradar.data

import android.graphics.Color

/**
 * Shared utility functions for processing DWD radar bitmap images.
 * Used by both the main app (RadarMapView) and the homescreen widget (RadarWidgetProvider).
 */
object RadarBitmapUtils {

    /**
     * Removes gray background, pink/magenta borders, and blended boundary pixels
     * from a DWD radar bitmap by setting them to transparent.
     */
    fun cleanRadarBitmap(bitmap: android.graphics.Bitmap) {
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

    /**
     * Determines if a pixel color represents rain (i.e., is NOT a background/border color).
     */
    fun isRainColor(color: Int): Boolean {
        val a = (color shr 24) and 0xFF
        if (a < 10) return false
        
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        
        val rf = r / 255.0f
        val gf = g / 255.0f
        val bf = b / 255.0f
        
        val isGray = (Math.abs(rf - gf) <= 0.03f && 
                      Math.abs(rf - bf) <= 0.03f && 
                      Math.abs(gf - bf) <= 0.03f)
                      
        val minRB = minOf(rf, bf)
        val isPink = (Math.abs(rf - bf) <= 0.19f) && 
                     (minRB > 0.01f) && 
                     (gf < minRB - 0.02f)
                     
        val isBlend = (minRB > 0.3f) && (gf > 0.05f)
        
        return !(isGray || isPink || isBlend)
    }

    /**
     * Returns a human-readable German rain intensity name based on pixel color hue.
     */
    fun getRainIntensityName(color: Int): String {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val hue = hsv[0]
        return when {
            hue in 260.0f..330.0f -> "Starkregen/Gewitter"
            hue >= 330.0f || hue < 25.0f -> "Starker Regen"
            hue in 25.0f..70.0f -> "Mäßiger Regen"
            else -> "Leichter Regen"
        }
    }
}
