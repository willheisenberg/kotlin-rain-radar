package com.example.rainradar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.maplibre.android.MapLibre
import com.example.rainradar.ui.RadarScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MapLibre.getInstance(this)
        
        val showBilling = intent?.getBooleanExtra("show_billing", false) ?: false
        
        setContent {
            RadarScreen(initialShowBilling = showBilling)
        }
    }
}
