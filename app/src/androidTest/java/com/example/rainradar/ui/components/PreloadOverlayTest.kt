package com.example.rainradar.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.rainradar.data.DwdWmsClient
import com.example.rainradar.data.FrameSource
import com.example.rainradar.ui.RadarViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

class PreloadOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Test fun overlayEndsWithTheCurrentFrameNotWithTheWholeGeneration() {
        val realContext = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(realContext.cacheDir, "overlay-test-${System.nanoTime()}").apply { mkdirs() }
        val context =
            object : ContextWrapper(realContext) {
                override fun getCacheDir(): File = dir

                override fun getApplicationContext(): Context = this
            }
        val base = Instant.parse("2041-01-01T12:00:00Z")
        val currentFrame = CompletableDeferred<Unit>()
        val remainingFrames = CompletableDeferred<Unit>()
        val store = ViewModelStore()
        lateinit var model: RadarViewModel
        val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            compose.runOnIdle {
                model =
                    RadarViewModel(baseTime = { base }, download = { _, time, frameBase, _, report ->
                        report(FrameSource.PROXY)
                        // Both gates keep the order observable: the current frame
                        // first, everything else afterwards.
                        if (time == frameBase) currentFrame.await() else remainingFrames.await()
                        DwdWmsClient.getCachedFrameFile(context, time, frameBase).outputStream().use {
                            image.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        true
                    })
                store.put("radar", model)
                model.refreshData(context)
            }
            compose.waitUntil(5_000) { model.isPreloading.value }
            compose.runOnIdle {
                assertEquals(0, model.loadStatus.value.ready)
                currentFrame.complete(Unit)
            }
            compose.waitUntil(5_000) { !model.isPreloading.value }
            compose.runOnIdle {
                assertTrue(
                    "Overlay waited for the whole generation",
                    model.loadStatus.value.ready < DwdWmsClient.TOTAL_FRAME_COUNT,
                )
                assertTrue(DwdWmsClient.isFrameReady(context, base, base))
                remainingFrames.complete(Unit)
            }
            compose.waitUntil(10_000) { model.loadStatus.value.ready == DwdWmsClient.TOTAL_FRAME_COUNT }
            compose.runOnIdle { assertEquals(1f, model.preloadProgress.value, 0.001f) }
        } finally {
            compose.runOnIdle { store.clear() }
            image.recycle()
            dir.deleteRecursively()
        }
    }
}
