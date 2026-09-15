package com.example.rainradar.server

import kotlinx.coroutines.sync.Mutex

/** Skip overlapping triggers; a failed/cancelled run releases the gate too. */
internal class PreloadGate {
    private val mutex = Mutex()
    suspend fun run(block: suspend () -> Unit) {
        if (!mutex.tryLock()) return
        try { block() } finally { mutex.unlock() }
    }
}
