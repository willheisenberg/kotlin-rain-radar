package com.example.rainradar.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class GenerationScheduleTest {
    // App and server show generation 12:00 from 12:10:00 until 12:14:59.
    private val current = Instant.parse("2026-09-16T12:00:00Z")
    private val next = Instant.parse("2026-09-16T12:05:00Z")

    private fun at(time: String) = Instant.parse("2026-09-16T${time}Z")

    private fun published(
        run: String,
        timeEnd: String = "14:00:00",
    ) = DwdPublication(at(run), at(timeEnd))

    @Test fun missingCurrentGenerationIsLoadedFirst() =
        runBlocking {
            val target = GenerationSchedule.nextTarget(at("12:10:05"), emptySet()) { error("Not needed yet") }
            assertEquals(current, target)
        }

    @Test fun dwdIsNotAskedBeforeTheRunCanExist() =
        runBlocking {
            // G(12:05) matches today's switch-time content only with run 12:10,
            // which DWD publishes about three minutes after 12:10.
            val target =
                GenerationSchedule.nextTarget(at("12:12:59"), setOf(current)) {
                    error("Publication checked before run 12:10 can exist")
                }
            assertNull(target)
        }

    @Test fun nextGenerationIsPrewarmedOnceItsRunIsPublished() =
        runBlocking {
            val target = GenerationSchedule.nextTarget(at("12:13:40"), setOf(current)) { published("12:10:00") }
            assertEquals(next, target)
        }

    @Test fun olderRunWouldServeStalerForecastThanTodaySoItWaits() =
        runBlocking {
            val target =
                GenerationSchedule.nextTarget(at("12:13:40"), setOf(current)) {
                    published("12:05:00", timeEnd = "14:05:00")
                }
            assertNull(target)
        }

    @Test fun timeAxisMustReachTheLastForecastFrame() =
        runBlocking {
            // The reference time can advance before the time axis does.
            val target =
                GenerationSchedule.nextTarget(at("12:13:40"), setOf(current)) {
                    published("12:10:00", timeEnd = "13:55:00")
                }
            assertNull(target)
        }

    @Test fun unreachableDwdFallsBackToLoadingAtTheSwitch() =
        runBlocking {
            assertNull(GenerationSchedule.nextTarget(at("12:13:40"), setOf(current)) { null })
            assertEquals(next, GenerationSchedule.nextTarget(at("12:15:01"), setOf(current)) { null })
        }

    @Test fun nothingToDoWhenBothGenerationsAreReady() =
        runBlocking {
            val target =
                GenerationSchedule.nextTarget(at("12:14:00"), setOf(current, next)) {
                    error("Nothing left to prewarm")
                }
            assertNull(target)
        }

    @Test fun frameKeysMatchTheExistingCacheFiles() {
        val base = Instant.parse("2026-09-16T23:30:00Z")
        val times = GenerationSchedule.frameTimes(base)
        assertEquals(60, times.size)
        assertEquals("frame_2026-09-16T20:30:00Z_1920x2084", GenerationSchedule.frameKey(times.first(), base))
        assertEquals(
            "frame_2026-09-16T23:30:00Z_base_2026-09-16T23:30:00Z_1920x2084",
            GenerationSchedule.frameKey(times[36], base),
        )
        assertEquals(Instant.parse("2026-09-17T01:25:00Z"), times.last())
    }

    @Test fun capabilitiesYieldLatestRunAndTimeAxisEnd() {
        val xml =
            """
            <Layer queryable="1"><Name>Niederschlagsradar</Name>
              <Dimension name="time" default="current" units="ISO8601">2026-09-13T00:00:00.000Z/2026-09-17T01:35:00.000Z/PT5M</Dimension>
              <Dimension name="REFERENCE_TIME" default="2026-09-16T23:35:00.000Z" units="ISO8601">2026-09-16T23:30:00.000Z,2026-09-16T23:35:00.000Z</Dimension>
            </Layer>
            """.trimIndent()
        assertEquals(
            DwdPublication(Instant.parse("2026-09-16T23:35:00Z"), Instant.parse("2026-09-17T01:35:00Z")),
            DwdCapabilities.parse(xml),
        )
        assertNull(DwdCapabilities.parse("<ServiceExceptionReport/>"))
    }
}
