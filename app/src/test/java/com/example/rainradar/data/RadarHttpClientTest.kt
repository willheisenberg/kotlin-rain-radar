package com.example.rainradar.data

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

class RadarHttpClientTest {
    @Test fun proxyFailureStillLimitsDirectDwdAndReportsTheFallback() = runBlocking {
        val proxy = MockWebServer()
        val dwd = MockWebServer()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        proxy.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
        }
        dwd.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                try { Thread.sleep(100) } finally { active.decrementAndGet() }
                return MockResponse().setBody("radar")
            }
        }
        try {
            val reported = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val downloader = RadarHttpClient(dwdConcurrency = 2, retryDelayMillis = 1, onProxyFailure = { reported.add(it) })
            val sources = java.util.concurrent.ConcurrentLinkedQueue<FrameSource>()
            val frames = (1..12).map {
                async { downloader.download(proxy.url("/radar").toString(), dwd.url("/wms").toString(), { sources.add(it) }) }
            }.awaitAll()
            assertTrue(frames.all { String(it) == "radar" })
            assertTrue("DWD concurrency was ${peak.get()}", peak.get() <= 2)
            assertTrue(sources.contains(FrameSource.PROXY))
            assertTrue(sources.contains(FrameSource.DWD))
            assertTrue("Failed proxy retried for the entire batch", proxy.requestCount < 12)
            assertTrue("Proxy failure was not reported", reported.any { it.contains("503") })
        } finally { proxy.close(); dwd.close() }
    }

    @Test fun cancellingBodyReadCancelsSocketAndReleasesDownloadSlot() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("stalled body").setBodyDelay(1_500, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody("next"))
            val cancelled = CompletableDeferred<Unit>()
            val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS)
                .eventListener(object : okhttp3.EventListener() {
                    override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) {
                        if (call.isCanceled()) cancelled.complete(Unit)
                    }
                }).build()
            val downloader = RadarHttpClient(client, dwdConcurrency = 1)
            val pending = launch { downloader.download(null, server.url("/first").toString()) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            val elapsed = measureTimeMillis { pending.cancelAndJoin() }
            assertTrue("Cancellation waited $elapsed ms", elapsed < 1_000)
            withTimeout(1_000) { cancelled.await() }
            val next = withTimeout(2_000) { downloader.download(null, server.url("/next").toString()) }
            assertEquals("next", String(next))
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun recoveringProxyIsUsedAgainWithoutWaitingOutTheBlackout() = runBlocking {
        val proxy = MockWebServer()
        val dwd = MockWebServer()
        val proxyBroken = java.util.concurrent.atomic.AtomicBoolean(true)
        proxy.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (proxyBroken.get()) MockResponse().setResponseCode(503) else MockResponse().setBody("proxy")
        }
        dwd.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("dwd")
        }
        try {
            val downloader = RadarHttpClient(retryDelayMillis = 1, proxyProbeIntervalMillis = 50)
            val sources = mutableListOf<FrameSource>()
            val bodies = mutableListOf<String>()
            fun frame() {
                runBlocking {
                    bodies.add(String(downloader.download(proxy.url("/radar").toString(), dwd.url("/wms").toString(), { sources.add(it) })))
                }
            }
            frame()
            proxyBroken.set(false)
            Thread.sleep(80)
            frame()
            frame()
            assertEquals(listOf("dwd", "proxy", "proxy"), bodies)
            assertEquals(1, sources.count { it == FrameSource.DWD })
        } finally { proxy.close(); dwd.close() }
    }

    @Test fun deadProxyIsNotProbedByEveryFrame() = runBlocking {
        val proxy = MockWebServer()
        val dwd = MockWebServer()
        proxy.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                Thread.sleep(200)
                return MockResponse().setResponseCode(503)
            }
        }
        dwd.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                Thread.sleep(150)
                return MockResponse().setBody("dwd")
            }
        }
        try {
            val downloader = RadarHttpClient(dwdConcurrency = 6, retryDelayMillis = 1, proxyProbeIntervalMillis = 100)
            val frames = (1..24).map {
                async { String(downloader.download(proxy.url("/radar").toString(), dwd.url("/wms").toString())) }
            }.awaitAll()
            assertTrue(frames.all { it == "dwd" })
            assertTrue("Proxy was asked ${proxy.requestCount} times for 24 frames", proxy.requestCount < 14)
        } finally { proxy.close(); dwd.close() }
    }
}
