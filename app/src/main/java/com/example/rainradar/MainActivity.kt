package com.example.rainradar

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.rainradar.ui.RadarScreen
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        MapLibre.getInstance(this)

        val showBilling = intent?.getBooleanExtra("show_billing", false) ?: false

        setContent {
            RadarScreen(initialShowBilling = showBilling)
        }
    }
}
