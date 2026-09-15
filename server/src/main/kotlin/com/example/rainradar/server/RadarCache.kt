package com.example.rainradar.server

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Cached images never queue behind unrelated downloads sharing a lock stripe. */
internal class RadarCache(
    private val values: ConcurrentHashMap<String, ByteArray>,
    stripes: Int = 128,
) {
    private val locks = Array(stripes) { Mutex() }

    suspend fun getOrLoad(key: String, load: suspend () -> ByteArray?): ByteArray? {
        values[key]?.let { return it }
        return locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            // Another request may have completed this image while we waited.
            values[key] ?: load()?.also { values[key] = it }
        }
    }
}
