package com.example.rainradar.data

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object DwdWmsClient {
    const val WMS_BASE_URL = "https://maps.dwd.de/geoserver/ows"
    const val WMS_LAYER = "dwd:Niederschlagsradar"
    
    private val isoFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC)

    fun formatIsoTime(instant: Instant): String {
        return isoFormatter.format(instant)
    }

    private fun getRoundedBaseTime(): Instant {
        val now = Instant.now()
        val epochSec = now.epochSecond
        val roundedSec = (epochSec / 300) * 300
        return Instant.ofEpochSecond(roundedSec)
    }

    /**
     * Generates a list of frame times (Instants) for past or forecast data.
     * DWD WMS updates every 5 minutes.
     */
    fun generateFrameTimes(showForecast: Boolean, maxFrames: Int = 24): List<Instant> {
        val base = getRoundedBaseTime()
        val list = ArrayList<Instant>(maxFrames)
        for (i in 0 until maxFrames) {
            val instant = if (showForecast) {
                base.plusSeconds(i * 5 * 60L)
            } else {
                base.minusSeconds((maxFrames - 1 - i) * 5 * 60L)
            }
            list.add(instant)
        }
        return list
    }

    /**
     * Helper to convert Osmdroid tile coordinates (x, y, zoom) to EPSG:3857 BBOX query.
     */
    fun tileToBBoxEPSG3857(x: Int, y: Int, zoom: Int): String {
        val totalSize = 20037508.342789244 * 2.0
        val tileSize = totalSize / (1 shl zoom)
        val originX = -20037508.342789244
        val originY = 20037508.342789244
        
        val minX = originX + x * tileSize
        val maxX = minX + tileSize
        
        // Y increases downwards in Slippy Map standard, so max Y is at the top of the tile
        val maxY = originY - y * tileSize
        val minY = maxY - tileSize
        
        return "$minX,$minY,$maxX,$maxY"
    }
}
