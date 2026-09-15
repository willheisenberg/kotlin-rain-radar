package com.example.rainradar.server

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.Assert.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

class RadarUpstreamTest {
    @Test fun bodyTimeoutExpiresEvenAfterSuccessfulHeaders() = runBlocking {
        MockWebServer().use { server ->
            // Headers and one byte arrive; the rest of the PNG would stall ImageIO.
            server.enqueue(MockResponse().setBody("unfinished image").throttleBody(1, 600, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody("recovered"))
            val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(300, TimeUnit.MILLISECONDS).build()
            val upstream = RadarUpstream(client)
            val elapsed = measureTimeMillis {
                try {
                    upstream.read(server.url("/stalled").toString())
                    fail("Body read should have timed out")
                } catch (expected: IOException) { /* end-to-end deadline */ }
            }
            assertTrue("Body waited $elapsed ms", elapsed < 2_000)
            assertEquals("recovered", String(upstream.read(server.url("/next").toString())))
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun overlappingPreloadsAreSkippedAndGateReopensAfterCancellation() = runBlocking {
        val gate = PreloadGate()
        val entered = CompletableDeferred<Unit>()
        var runs = 0
        val first = launch { gate.run { runs++; entered.complete(Unit); awaitCancellation() } }
        entered.await()
        coroutineScope { repeat(30) { launch { gate.run { runs++ } } } }
        assertEquals(1, runs)
        first.cancelAndJoin()
        gate.run { runs++ }
        assertEquals(2, runs)
    }
}
