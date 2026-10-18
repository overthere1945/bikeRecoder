package com.cowork.bikerecoder.post

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import com.cowork.bikerecoder.core.post.HeartRateSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class HealthConnectMappingTest {
    private val min = 60_000L

    private fun at(ms: Long): Instant = Instant.ofEpochMilli(ms)

    private fun hr(vararg samples: Pair<Long, Long>): HeartRateRecord = HeartRateRecord(
        startTime = at(samples.minOf { it.first }),
        startZoneOffset = null,
        endTime = at(samples.maxOf { it.first } + 1),
        endZoneOffset = null,
        samples = samples.map { HeartRateRecord.Sample(at(it.first), it.second) },
        metadata = Metadata.unknownRecordingMethod(),
    )

    private fun session(start: Long, end: Long, type: Int = ExerciseSessionRecord.EXERCISE_TYPE_BIKING) =
        ExerciseSessionRecord(
            startTime = at(start),
            startZoneOffset = null,
            endTime = at(end),
            endZoneOffset = null,
            metadata = Metadata.unknownRecordingMethod(),
            exerciseType = type,
        )

    private fun kcal(start: Long, end: Long, kcal: Double) = TotalCaloriesBurnedRecord(
        startTime = at(start),
        startZoneOffset = null,
        endTime = at(end),
        endZoneOffset = null,
        energy = Energy.kilocalories(kcal),
        metadata = Metadata.unknownRecordingMethod(),
    )

    @Test
    fun `samples from unsorted records come out in time order inside the window`() {
        val later = hr(5_000L to 140L, 4_000L to 135L, 9_000L to 99L)
        val earlier = hr(1_000L to 110L, 500L to 90L, 2_000L to 120L)

        val samples = HealthConnectMapping.samples(listOf(later, earlier), from = 1_000L, to = 5_000L)

        assertEquals(
            listOf(
                HeartRateSample(1_000L, 110),
                HeartRateSample(2_000L, 120),
                HeartRateSample(4_000L, 135),
                HeartRateSample(5_000L, 140),
            ),
            samples,
        )
    }

    @Test
    fun `calories are the overlapping share of each biking session`() {
        // Session A 0–60 min with 600 kcal, ride 30–90 min → half of A = 300.
        // Session B 80–100 min with 200 kcal → 10 of 20 min overlap = 100.
        val sessions = listOf(session(0, 60 * min), session(80 * min, 100 * min))
        val calories = listOf(kcal(0, 60 * min, 600.0), kcal(80 * min, 100 * min, 200.0))

        val total = HealthConnectMapping.calories(sessions, calories, from = 30 * min, to = 90 * min)

        assertEquals(400.0, total!!, 1e-9)
    }

    @Test
    fun `calorie records are counted only for the part inside the session`() {
        // 0–60 min record of 600 kcal, session 0–30 min → session holds 300; ride covers all of it.
        val total = HealthConnectMapping.calories(
            listOf(session(0, 30 * min)),
            listOf(kcal(0, 60 * min, 600.0)),
            from = 0,
            to = 30 * min,
        )

        assertEquals(300.0, total!!, 1e-9)
    }

    @Test
    fun `no overlapping biking session means no calories`() {
        val running = session(0, 60 * min, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING)
        val bikingBefore = session(0, 10 * min)
        val calories = listOf(kcal(0, 60 * min, 600.0))

        assertNull(HealthConnectMapping.calories(listOf(running, bikingBefore), calories, from = 20 * min, to = 40 * min))
    }

    @Test
    fun `biking session without calorie records means no calories`() {
        assertNull(HealthConnectMapping.calories(listOf(session(0, 60 * min)), emptyList(), from = 0, to = 60 * min))
    }

    @Test
    fun `heart-rate window widens to the overlapping sessions`() {
        val sessions = listOf(session(-10 * min, 5 * min), session(50 * min, 70 * min), session(200 * min, 210 * min))

        assertEquals(-10 * min to 70 * min, HealthConnectMapping.readWindow(0, 60 * min, sessions))
        assertEquals(0L to 60 * min, HealthConnectMapping.readWindow(0, 60 * min, emptyList()))
    }
}
