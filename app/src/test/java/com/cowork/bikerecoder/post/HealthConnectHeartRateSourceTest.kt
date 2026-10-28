package com.cowork.bikerecoder.post

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HrRead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant
import kotlin.reflect.KClass

class HealthConnectHeartRateSourceTest {
    private val min = 60_000L
    private val heartRate = "android.permission.health.READ_HEART_RATE"
    private val exercise = "android.permission.health.READ_EXERCISE"
    private val calories = "android.permission.health.READ_TOTAL_CALORIES_BURNED"

    private fun at(ms: Long): Instant = Instant.ofEpochMilli(ms)

    private val hrRecord = HeartRateRecord(
        startTime = at(10 * min),
        startZoneOffset = null,
        endTime = at(20 * min),
        endZoneOffset = null,
        samples = listOf(HeartRateRecord.Sample(at(15 * min), 130), HeartRateRecord.Sample(at(11 * min), 110)),
        metadata = Metadata.unknownRecordingMethod(),
    )
    private val biking = ExerciseSessionRecord(
        startTime = at(0),
        startZoneOffset = null,
        endTime = at(60 * min),
        endZoneOffset = null,
        metadata = Metadata.unknownRecordingMethod(),
        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
    )
    private val kcal = TotalCaloriesBurnedRecord(
        startTime = at(0),
        startZoneOffset = null,
        endTime = at(60 * min),
        endZoneOffset = null,
        energy = Energy.kilocalories(600.0),
        metadata = Metadata.unknownRecordingMethod(),
    )

    /** Returns the given records per type and records every read as (type, from, to). */
    private class FakeReader(
        val granted: Set<String>,
        val records: Map<KClass<out Record>, List<Record>>,
        val failure: Exception? = null,
    ) : HealthRecordReader {
        val reads = mutableListOf<Triple<KClass<*>, Long, Long>>()

        override suspend fun grantedPermissions(): Set<String> = granted

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Record> readAll(type: KClass<T>, from: Long, to: Long): List<T> {
            reads += Triple(type, from, to)
            failure?.let { throw it }
            return (records[type] ?: emptyList()) as List<T>
        }
    }

    private fun reader(vararg granted: String, failure: Exception? = null) = FakeReader(
        granted.toSet(),
        mapOf(
            HeartRateRecord::class to listOf(hrRecord),
            ExerciseSessionRecord::class to listOf(biking),
            TotalCaloriesBurnedRecord::class to listOf(kcal),
        ),
        failure,
    )

    private fun source(reader: FakeReader, sdk: Int = HealthConnectClient.SDK_AVAILABLE) =
        HealthConnectHeartRateSource(sdkStatus = { sdk }, reader = { reader }, warnLog = WarnLog { _, _ -> })

    private val expectedSamples = listOf(HeartRateSample(11 * min, 110), HeartRateSample(15 * min, 130))

    @Test
    fun `all three permissions give samples and calories`() = runTest {
        val r = reader(heartRate, exercise, calories)

        val read = source(r).read(0, 30 * min)

        assertEquals(HrRead.Samples(expectedSamples, calories = 300.0), read)
    }

    /** Spec §6.2: the watch workout was "걷기", not biking — its calories count all the same. */
    @Test
    fun walkingWorkoutCaloriesCount() = runTest {
        // walking 15–45 min with 200 kcal, ride 0–30 min: half of the walk overlaps → 100 kcal
        val walking = ExerciseSessionRecord(
            startTime = at(15 * min),
            startZoneOffset = null,
            endTime = at(45 * min),
            endZoneOffset = null,
            metadata = Metadata.unknownRecordingMethod(),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        )
        val walkKcal = TotalCaloriesBurnedRecord(
            startTime = at(15 * min),
            startZoneOffset = null,
            endTime = at(45 * min),
            endZoneOffset = null,
            energy = Energy.kilocalories(200.0),
            metadata = Metadata.unknownRecordingMethod(),
        )
        val r = FakeReader(
            setOf(heartRate, exercise, calories),
            mapOf(
                HeartRateRecord::class to listOf(hrRecord),
                ExerciseSessionRecord::class to listOf(walking),
                TotalCaloriesBurnedRecord::class to listOf(walkKcal),
            ),
        )

        val read = source(r).read(0, 30 * min)

        assertEquals(HrRead.Samples(expectedSamples, calories = 100.0), read)
        assertTrue(r.reads.contains(Triple<KClass<*>, Long, Long>(TotalCaloriesBurnedRecord::class, 15 * min, 45 * min)))
    }

    @Test
    fun `heart rate alone is enough - calories are null and never read`() = runTest {
        val r = reader(heartRate)
        val s = source(r)

        assertTrue(s.hasPermissions())
        assertEquals(HrRead.Samples(expectedSamples, calories = null), s.read(0, 30 * min))
        assertEquals(listOf<KClass<*>>(HeartRateRecord::class), r.reads.map { it.first })
    }

    @Test
    fun `exercise without total calories still gives null calories`() = runTest {
        val r = reader(heartRate, exercise)

        assertEquals(HrRead.Samples(expectedSamples, calories = null), source(r).read(0, 30 * min))
        assertFalse(r.reads.any { it.first == TotalCaloriesBurnedRecord::class })
    }

    @Test
    fun `without heart-rate permission the read fails without touching records`() = runTest {
        val r = reader(exercise, calories)
        val s = source(r)

        assertFalse(s.hasPermissions())
        assertEquals(HrRead.Failed, s.read(0, 30 * min))
        assertTrue(r.reads.isEmpty())
    }

    @Test
    fun `unavailable Health Connect fails`() = runTest {
        val r = reader(heartRate, exercise, calories)
        val s = source(r, sdk = HealthConnectClient.SDK_UNAVAILABLE)

        assertEquals(HealthAvailability.NOT_INSTALLED, s.availability())
        assertFalse(s.hasPermissions())
        assertEquals(HrRead.Failed, s.read(0, 30 * min))
    }

    @Test
    fun `zero or negative length window is Empty without reading`() = runTest {
        val r = reader(heartRate, exercise, calories)
        val s = source(r)

        assertEquals(HrRead.Empty, s.read(5 * min, 5 * min))
        assertEquals(HrRead.Empty, s.read(6 * min, 5 * min))
        assertTrue(r.reads.isEmpty())
    }

    @Test
    fun `heart-rate query reaches 60 s past both ends`() = runTest {
        val r = reader(heartRate)

        source(r).read(30 * min, 40 * min)

        assertEquals(listOf(Triple<KClass<*>, Long, Long>(HeartRateRecord::class, 29 * min, 41 * min)), r.reads)
    }

    @Test
    fun `read error is Failed but cancellation propagates`() = runTest {
        assertEquals(HrRead.Failed, source(reader(heartRate, failure = IOException("binder died"))).read(0, 30 * min))
        val thrown = runCatching {
            source(reader(heartRate, failure = CancellationException("stop"))).read(0, 30 * min)
        }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `permissions to request stay all three`() {
        assertEquals(setOf(heartRate, exercise, calories), source(reader()).permissions)
    }
}
