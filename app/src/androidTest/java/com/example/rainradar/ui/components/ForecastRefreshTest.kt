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
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ForecastRefreshTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oldForecastStaysUsableUntilReplacementCompletes() {
        val realContext = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(realContext.cacheDir, "refresh-test-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(realContext) {
            override fun getCacheDir(): File = dir
            override fun getApplicationContext(): Context = this
        }
        val oldBase = Instant.parse("2041-01-01T12:00:00Z")
        var now = oldBase
        var failLastForecast = true
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val store = ViewModelStore()
        lateinit var model: RadarViewModel
        val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        fun save(time: Instant, base: Instant) {
            DwdWmsClient.getCachedFrameFile(context, time, base).outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        DwdWmsClient.generateCombinedFrameTimes(oldBase).forEach { save(it, oldBase) }
        try {
            compose.runOnIdle {
                model = RadarViewModel(baseTime = { now }, download = { _, time, base, _, report ->
                    if (base != oldBase) { entered.complete(Unit); gate.await() }
                    report(FrameSource.CACHE)
                    if (base != oldBase && failLastForecast && time == base.plusSeconds(23 * 300)) {
                        false
                    } else {
                        save(time, base)
                        true
                    }
                })
                store.put("radar", model)
                model.refreshData(context, silent = true)
            }
            compose.waitUntil(5_000) { model.loadStatus.value.ready == 60 }
            compose.runOnIdle {
                now = oldBase.plusSeconds(300)
                model.refreshData(context, silent = true)
            }
            compose.waitUntil(5_000) { entered.isCompleted }
            compose.runOnIdle {
                assertEquals(oldBase, model.frameTimes.value[36])
                assertFalse(model.isPreloading.value)
                model.setActiveFrameIndex(59)
                assertTrue(DwdWmsClient.isFrameReady(context, model.frameTimes.value[59], oldBase))
                // Poll/resume must not cancel the in-progress replacement.
                model.refreshData(context, silent = true)
                gate.complete(Unit)
            }
            compose.waitUntil(5_000) { model.loadStatus.value.ready == 59 && model.loadStatus.value.failed == 1 }
            compose.runOnIdle {
                assertEquals("Incomplete generation replaced working forecast", oldBase, model.frameTimes.value[36])
                assertTrue(DwdWmsClient.isFrameReady(context, oldBase.plusSeconds(23 * 300), oldBase))
                failLastForecast = false
                model.refreshData(context, silent = true)
            }
            compose.waitUntil(5_000) { model.frameTimes.value[36] == now }
            compose.runOnIdle {
                assertEquals(oldBase.plusSeconds(23 * 300), model.frameTimes.value[model.activeFrameIndex.value])
            }
        } finally {
            compose.runOnIdle { store.clear() }
            image.recycle()
            dir.deleteRecursively()
        }
    }
}
