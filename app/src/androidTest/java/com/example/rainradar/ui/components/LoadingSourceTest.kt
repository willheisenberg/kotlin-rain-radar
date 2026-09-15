package com.example.rainradar.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.rainradar.ui.PreloadingOverlay
import com.example.rainradar.ui.RadarLoadStatus
import org.junit.Rule
import org.junit.Test

class LoadingSourceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadingCardShowsActualRoutesAndLocalProcessing() {
        var status by mutableStateOf(RadarLoadStatus(serverActive = 3))
        compose.setContent {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PreloadingOverlay(0f, 60, status)
            }
        }
        compose.onNodeWithText("Vom Server: 3 Downloads").assertExists()
        compose.runOnIdle { status = RadarLoadStatus(dwdActive = 5) }
        compose.onNodeWithText("Direkt von DWD: 5 Downloads\nAufbereitung auf dem Gerät").assertExists()
        compose.runOnIdle { status = RadarLoadStatus(serverActive = 2, dwdActive = 3, cached = 12) }
        compose.onNodeWithText("Vom Server: 2 Downloads", substring = true).assertExists()
        compose.onNodeWithText("Direkt von DWD: 3 Downloads", substring = true).assertExists()
        compose.onNodeWithText("12 Bilder aus lokalem Cache", substring = true).assertExists()
    }
}
