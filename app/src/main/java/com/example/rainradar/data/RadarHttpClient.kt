package com.example.rainradar.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class FrameSource { CACHE, PROXY, DWD }

/** Limits the actual upstream, including proxy fallbacks and concurrent widget loads. */
internal class RadarHttpClient(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build(),
    dwdConcurrency: Int = 5,
    proxyConcurrency: Int = 8,
    private val retryDelayMillis: Long = 1_500,
    private val proxyProbeIntervalMillis: Long = 2_000,
    private val onProxyFailure: (String) -> Unit = {},
) {
    private val dwdSlots = Semaphore(dwdConcurrency)
    private val proxySlots = Semaphore(proxyConcurrency)
    private val proxyRetryAfter = AtomicLong(0)
    private val proxyProbeAfter = AtomicLong(0)
    private val probing = AtomicBoolean(false)

    suspend fun download(
        proxyUrl: String?,
        dwdUrl: String,
        onSource: (FrameSource) -> Unit = {},
        validate: (ByteArray) -> Unit = {},
    ): ByteArray {
        if (!proxyUrl.isNullOrBlank()) {
            val result = proxySlots.withPermit { readViaProxy(proxyUrl, onSource, validate) }
            if (result != null) return result
        }
        var failure: IOException? = null
        repeat(3) { attempt ->
            try {
                return dwdSlots.withPermit {
                    onSource(FrameSource.DWD)
                    read(dwdUrl).also(validate)
                }
            } catch (error: IOException) {
                failure = error
                if (attempt < 2) delay(retryDelayMillis * (attempt + 1))
            }
        }
        throw failure ?: IOException("Radar download failed")
    }

    /**
     * One failed proxy must not cost every queued frame another connection timeout,
     * so a failure parks the proxy. A single frame at a time probes it again, which
     * brings a proxy that was only cold back within seconds instead of after the
     * full blackout. Returns null whenever the caller has to use DWD directly.
     */
    private suspend fun readViaProxy(
        proxyUrl: String,
        onSource: (FrameSource) -> Unit,
        validate: (ByteArray) -> Unit,
    ): ByteArray? {
        val now = System.nanoTime()
        val parked = now < proxyRetryAfter.get()
        val probe = parked && now >= proxyProbeAfter.get() && probing.compareAndSet(false, true)
        if (parked && !probe) return null
        try {
            onSource(FrameSource.PROXY)
            return read(proxyUrl).also(validate).also { proxyRetryAfter.set(0) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            val failedAt = System.nanoTime()
            proxyRetryAfter.set(failedAt + TimeUnit.SECONDS.toNanos(30))
            proxyProbeAfter.set(failedAt + TimeUnit.MILLISECONDS.toNanos(proxyProbeIntervalMillis))
            onProxyFailure("Proxy frame failed, falling back to DWD: ${failure.message}")
            return null
        } finally {
            if (probe) probing.set(false)
        }
    }

    private suspend fun read(url: String): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val call =
                client.newCall(
                    Request
                        .Builder()
                        .url(url)
                        .header("User-Agent", "DwdRainRadarApp")
                        .build(),
                )
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        try {
                            val bytes =
                                response.use {
                                    if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                                    it.body?.bytes()?.takeIf { body -> body.isNotEmpty() }
                                        ?: throw IOException("Empty radar response")
                                }
                            continuation.resume(bytes)
                        } catch (error: IOException) {
                            continuation.resumeWithException(error)
                        }
                    }
                },
            )
        }
}
