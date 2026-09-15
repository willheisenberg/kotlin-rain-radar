package com.example.rainradar.server

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Read the entire HTTP body under a deadline, before handing it to ImageIO. */
internal class RadarUpstream(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun read(url: String): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "DwdRainRadarProxyServer").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (!it.isSuccessful) throw IOException("DWD HTTP ${it.code}")
                        it.body?.bytes()?.takeIf { body -> body.isNotEmpty() }
                            ?: throw IOException("Empty DWD response")
                    }
                    continuation.resume(bytes)
                } catch (failure: IOException) {
                    continuation.resumeWithException(failure)
                }
            }
        })
    }
}
