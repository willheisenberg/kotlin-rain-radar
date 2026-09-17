package com.example.rainradar.server

import java.io.File
import java.time.Instant

internal object RadarDiskCache {
    /**
     * Keeps exactly the frames of [activeBases] in memory and on disk. While the
     * next generation is prewarmed, the current one is still requested, so both
     * must survive; anything else is stale. Files that are not frames stay.
     */
    fun retain(
        dir: File,
        memoryKeys: MutableSet<String>,
        activeBases: Collection<Instant>,
    ): Int {
        val keep =
            activeBases.flatMapTo(HashSet()) { base ->
                GenerationSchedule.frameTimes(base).map { GenerationSchedule.frameKey(it, base) }
            }
        memoryKeys.retainAll(keep)
        val stale =
            dir.listFiles { file ->
                file.name.startsWith("frame_") && file.name.endsWith(".webp") && file.name.removeSuffix(".webp") !in keep
            } ?: return 0
        return stale.count { it.delete() }
    }
}
