package com.example.rainradar.server

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class RadarDiskCacheTest {
    private val previous = Instant.parse("2026-09-16T23:25:00Z")
    private val current = Instant.parse("2026-09-16T23:30:00Z")
    private val next = Instant.parse("2026-09-16T23:35:00Z")

    private fun keys(base: Instant) = GenerationSchedule.frameTimes(base).map { GenerationSchedule.frameKey(it, base) }

    @Test fun prewarmingTheNextGenerationKeepsTheOneStillInUse() {
        val dir = Files.createTempDirectory("radar-cache").toFile()
        try {
            (keys(previous) + keys(current) + keys(next)).distinct().forEach { File(dir, "$it.webp").writeBytes(byteArrayOf(1)) }
            File(dir, "notes.txt").writeText("not a frame")

            val memory = (keys(previous) + keys(current) + keys(next)).associateWith { byteArrayOf(1) }.toMutableMap()
            RadarDiskCache.retain(dir, memory.keys, listOf(current, next))

            val remaining = dir.list()!!.toSet()
            (keys(current) + keys(next)).forEach {
                assertTrue("Deleted $it although a generation in use needs it", "$it.webp" in remaining)
            }
            val stale = keys(previous) - (keys(current) + keys(next)).toSet()
            assertTrue(stale.isNotEmpty())
            stale.forEach { assertFalse("Kept stale $it", "$it.webp" in remaining) }
            assertTrue("notes.txt" in remaining)
            assertEquals((keys(current) + keys(next)).toSet(), memory.keys)
        } finally {
            dir.deleteRecursively()
        }
    }
}
