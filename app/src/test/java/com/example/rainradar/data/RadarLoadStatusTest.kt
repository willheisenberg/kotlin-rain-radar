package com.example.rainradar.data

import com.example.rainradar.ui.RadarLoadStatus
import org.junit.Assert.*
import org.junit.Test

class RadarLoadStatusTest {
    @Test fun mixedRoutesAndCacheAreReportedWithoutCountingFailuresAsLoaded() {
        var state = RadarLoadStatus().active(FrameSource.PROXY, 1).active(FrameSource.DWD, 1)
        assertTrue(state.description.contains("Vom Server"))
        assertTrue(state.description.contains("Direkt von DWD"))
        state = state.finished(FrameSource.PROXY, false)
        state = state.finished(FrameSource.DWD, true)
        state = state.finished(FrameSource.CACHE, true)
        assertEquals(2, state.ready)
        assertEquals(1, state.failed)
        assertEquals(0, state.serverActive)
        assertEquals(0, state.dwdActive)
        assertTrue(state.description.contains("lokalem Cache"))
    }
}
