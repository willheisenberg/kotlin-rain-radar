package com.example.rainradar.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.rainradar.data.DwdWmsClient
import com.example.rainradar.ui.RadarViewModel
import java.io.File
import java.time.Instant
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RadarFrameStateTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = mutableListOf<File>()
    private val base = Instant.parse("2040-01-01T12:00:00Z")

    private fun writeFrame(time: Instant, generation: Instant, color: Int) {
        val file = DwdWmsClient.getCachedFrameFile(context, time, generation)
        files.add(file)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @After fun cleanUp() { files.forEach { it.delete() } }

    @Test fun missingForecastAppearsAfterBackgroundDownloadWithoutMovingSlider() {
        val selected = base.plusSeconds(300)
        writeFrame(base, base, Color.RED)
        val revision = mutableStateOf(0L)
        compose.setContent {
            val bitmap by rememberRadarBitmap(selected, base, revision.value)
            Text(bitmap?.getPixel(0, 0)?.toString() ?: "missing")
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(Color.RED.toString()))
                .fetchSemanticsNodes().isNotEmpty()
        }
        writeFrame(selected, base, Color.GREEN)
        compose.runOnIdle { revision.value++ }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(Color.GREEN.toString()))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun seekingAndChangingForecastGenerationNeverReuseWrongFrame() {
        val selected = mutableStateOf(base)
        val generation = mutableStateOf(base)
        writeFrame(base, base, Color.RED)
        writeFrame(base.plusSeconds(300), base, Color.GREEN)
        writeFrame(base.plusSeconds(300), base.plusSeconds(300), Color.BLUE)
        compose.setContent {
            val bitmap by rememberRadarBitmap(selected.value, generation.value, 0)
            Text(bitmap?.getPixel(0, 0)?.toString() ?: "missing")
        }
        fun awaitColor(color: Int) = compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(color.toString()))
                .fetchSemanticsNodes().isNotEmpty()
        }
        awaitColor(Color.RED)
        compose.runOnIdle { selected.value = base.plusSeconds(300) }
        awaitColor(Color.GREEN)
        compose.runOnIdle { generation.value = base.plusSeconds(300) }
        awaitColor(Color.BLUE)
    }

    @Test fun manualSelectionStopsPlayback() {
        compose.runOnIdle {
            val viewModel = RadarViewModel()
            val store = androidx.lifecycle.ViewModelStore()
            store.put("radar", viewModel)
            try {
                viewModel.togglePlayback()
                assertTrue(viewModel.isPlaying.value)
                viewModel.setActiveFrameIndex(59)
                assertFalse(viewModel.isPlaying.value)
                assertEquals(59, viewModel.activeFrameIndex.value)
            } finally {
                store.clear()
            }
        }
    }
}
