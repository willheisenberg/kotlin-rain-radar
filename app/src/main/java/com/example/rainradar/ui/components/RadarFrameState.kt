package com.example.rainradar.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.example.rainradar.data.RadarFrameLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

@Composable
internal fun rememberRadarBitmap(time: Instant?, base: Instant?, revision: Long): State<Bitmap?> {
    val context = LocalContext.current
    val loader = remember(context) { RadarFrameLoader(context) }
    DisposableEffect(loader) {
        onDispose { loader.clear() }
    }
    return produceState<Bitmap?>(null, time, base, revision) {
        // withContext checks cancellation before returning to Main, so an old
        // decode cannot overwrite a newer slider selection.
        value = if (time == null || base == null) null else {
            withContext(Dispatchers.IO) { loader.load(time, base) }
        }
    }
}
