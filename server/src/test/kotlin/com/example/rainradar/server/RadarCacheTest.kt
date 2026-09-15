package com.example.rainradar.server

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RadarCacheTest {
    @Test fun cachedImageDoesNotWaitForUnrelatedStalledDownload() = runBlocking {
        val values = ConcurrentHashMap<String, ByteArray>()
        val image = byteArrayOf(1, 2, 3)
        values["ready"] = image
        // A single stripe deliberately forces a collision between both keys.
        val cache = RadarCache(values, stripes = 1)
        val entered = CompletableDeferred<Unit>()
        val stalled = launch {
            cache.getOrLoad("missing") { entered.complete(Unit); awaitCancellation() }
        }
        try {
            entered.await()
            val result = withTimeout(1_000) {
                cache.getOrLoad("ready") { error("Cached image must not reload") }
            }
            assertSame(image, result)
            assertTrue(stalled.isActive)
        } finally { stalled.cancelAndJoin() }
    }

    @Test fun concurrentRequestsStillShareOneDownload() = runBlocking {
        val cache = RadarCache(ConcurrentHashMap())
        val release = CompletableDeferred<Unit>()
        var downloads = 0
        val requests = List(50) {
            async(start = CoroutineStart.UNDISPATCHED) {
                cache.getOrLoad("same-image") {
                    downloads++
                    release.await()
                    byteArrayOf(42)
                }
            }
        }
        assertEquals(1, downloads)
        release.complete(Unit)
        requests.awaitAll().forEach { assertArrayEquals(byteArrayOf(42), it) }
        assertEquals(1, downloads)
    }
}
