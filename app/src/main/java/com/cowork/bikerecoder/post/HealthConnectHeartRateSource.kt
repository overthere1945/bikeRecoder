package com.cowork.bikerecoder.post

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HrRead
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

/**
 * 삼성 헬스가 Health Connect에 쓴 심박·운동(종류와 관계없이)·총 칼로리를 읽는다.
 * 심박은 `READ_HEART_RATE`만 있으면 읽고, 칼로리는 `READ_EXERCISE`·`READ_TOTAL_CALORIES_BURNED`가 둘 다 있을 때만 읽는다(판정 R6).
 */
class HealthConnectHeartRateSource internal constructor(
    private val sdkStatus: () -> Int,
    private val reader: () -> HealthRecordReader,
    private val warnLog: WarnLog,
) : HeartRateSource {
    constructor(context: Context, warnLog: WarnLog = androidWarnLog("HealthConnectHR")) : this(
        sdkStatus = { HealthConnectClient.getSdkStatus(context.applicationContext) },
        reader = { ClientRecordReader(HealthConnectClient.getOrCreate(context.applicationContext)) },
        warnLog = warnLog,
    )

    private val heartRatePermission = HealthPermission.getReadPermission(HeartRateRecord::class)
    private val exercisePermission = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    private val caloriesPermission = HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)

    override val permissions: Set<String> = setOf(heartRatePermission, exercisePermission, caloriesPermission)

    override fun availability(): HealthAvailability = when (sdkStatus()) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.UPDATE_REQUIRED
        else -> HealthAvailability.NOT_INSTALLED
    }

    override suspend fun hasPermissions(): Boolean = heartRatePermission in granted()

    override suspend fun read(from: Long, to: Long): HrRead {
        val granted = granted()
        if (heartRatePermission !in granted) return HrRead.Failed
        // 길이 없는 구간: 읽을 것이 없다(TimeRangeFilter.between은 시작 < 끝이어야 한다).
        if (to <= from) return HrRead.Empty
        return try {
            val reader = reader()
            val sessions = if (exercisePermission in granted) {
                reader.readAll(
                    ExerciseSessionRecord::class,
                    from - HealthConnectMapping.SESSION_MARGIN_MS,
                    to + HealthConnectMapping.SESSION_MARGIN_MS,
                )
            } else {
                emptyList()
            }
            val (hrFrom, hrTo) = HealthConnectMapping.readWindow(from, to, sessions)
            val samples = HealthConnectMapping.samples(reader.readAll(HeartRateRecord::class, hrFrom, hrTo), from, to)
            val calories = if (caloriesPermission in granted) {
                HealthConnectMapping.workoutsOverlapping(sessions, from, to)
                    .takeIf { it.isNotEmpty() }
                    ?.let { workouts ->
                        val records = reader.readAll(
                            TotalCaloriesBurnedRecord::class,
                            workouts.minOf { it.startTime.toEpochMilli() },
                            workouts.maxOf { it.endTime.toEpochMilli() },
                        )
                        HealthConnectMapping.calories(workouts, records, from, to)
                    }
            } else {
                null
            }
            hrReadOf(samples, calories)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnLog.warn("Health Connect read failed", e)
            HrRead.Failed
        }
    }

    /** 허용된 권한. Health Connect를 쓸 수 없거나 확인이 실패하면 빈 집합. */
    private suspend fun granted(): Set<String> {
        if (availability() != HealthAvailability.AVAILABLE) return emptySet()
        return try {
            reader().grantedPermissions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnLog.warn("Health Connect permission check failed", e)
            emptySet()
        }
    }
}

/** Health Connect에서 이 앱이 쓰는 두 가지: 허용된 권한, 삼성 헬스 기록 읽기. */
internal interface HealthRecordReader {
    suspend fun grantedPermissions(): Set<String>

    /** 삼성 헬스가 쓴 [type] 기록을 [from, to]에서 모든 페이지까지 읽는다. */
    suspend fun <T : Record> readAll(type: KClass<T>, from: Long, to: Long): List<T>
}

private class ClientRecordReader(private val client: HealthConnectClient) : HealthRecordReader {
    override suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    override suspend fun <T : Record> readAll(type: KClass<T>, from: Long, to: Long): List<T> {
        val out = ArrayList<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to)),
                    dataOriginFilter = setOf(DataOrigin(HealthConnectMapping.SOURCE_PACKAGE)),
                    pageToken = pageToken,
                ),
            )
            out += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return out
    }
}

/** Health Connect 기록 → 앱 값. 순수 계산(기기 없이 테스트). */
internal object HealthConnectMapping {
    const val SOURCE_PACKAGE = "com.sec.android.app.shealth"

    /** 구간과 겹치는 운동을 놓치지 않으려고 운동 기록은 구간 앞뒤로 이만큼 더 넓게 읽는다. */
    const val SESSION_MARGIN_MS = 12 * 60 * 60_000L

    /**
     * 심박 기록도 구간 앞뒤로 이만큼 넓게 읽고 샘플을 [from, to]로 자른다. Health Connect가 기록을 시작 시각으로
     * 거르든 겹침으로 거르든, 구간 시작 직전에 시작한 짧은 심박 기록을 놓치지 않는다.
     */
    const val HR_MARGIN_MS = 60_000L

    /** [records]의 샘플 중 [from, to] 안의 것, 시간순(기록 순서와 무관). */
    fun samples(records: List<HeartRateRecord>, from: Long, to: Long): List<HeartRateSample> =
        records.asSequence()
            .flatMap { it.samples.asSequence() }
            .map { HeartRateSample(it.time.toEpochMilli(), it.beatsPerMinute.toInt()) }
            .filter { it.timeMillis in from..to }
            .sortedBy { it.timeMillis }
            .toList()

    /**
     * 심박을 읽을 범위: [from − HR_MARGIN_MS, to + HR_MARGIN_MS]에 [from, to]와 겹치는 운동들을 더한 범위.
     * 운동 내내 이어진 심박 기록이 구간보다 길어도 읽기 범위에 온전히 들어오게 한다.
     */
    fun readWindow(from: Long, to: Long, sessions: List<ExerciseSessionRecord>): Pair<Long, Long> {
        val overlapping = sessions.filter { overlap(it.startMs, it.endMs, from, to) > 0 }
        val start = (overlapping.map { it.startMs } + (from - HR_MARGIN_MS)).min()
        val end = (overlapping.map { it.endMs } + (to + HR_MARGIN_MS)).max()
        return start to end
    }

    /**
     * [from, to]와 겹치는 운동, 종류와 관계없이(스펙 §6.2: 워치에서 "걷기"로 시작한 운동도 주행의 칼로리를 담는다).
     */
    fun workoutsOverlapping(sessions: List<ExerciseSessionRecord>, from: Long, to: Long): List<ExerciseSessionRecord> =
        sessions.filter { overlap(it.startMs, it.endMs, from, to) > 0 }

    /**
     * 겹치는 운동마다 (운동 안의 총 칼로리) × (구간과 겹친 시간 / 운동 시간)을 더한다 — 운동이 주행보다 늦게 끝나도
     * 겹친 부분만큼만. 칼로리 기록이 운동 밖으로 걸치면 운동 안에 든 비율만 센다.
     * 같은 시간에 기록된 운동들은 두 번 세지 않는다(판정 R18): 먼저 시작한 운동부터, 각 운동은 구간 중 앞선 운동들이
     * 아직 차지하지 않은 시간만 센다.
     * 겹치는 운동이 없거나 그 운동들에 칼로리 기록이 없으면 null.
     */
    fun calories(
        sessions: List<ExerciseSessionRecord>,
        calories: List<TotalCaloriesBurnedRecord>,
        from: Long,
        to: Long,
    ): Double? {
        var total = 0.0
        var any = false
        // Taken in start order, the ride time that earlier workouts cover from a later workout's start on is one
        // stretch ending at coveredUntil, so that workout counts [max(its start, coveredUntil), min(its end, to)].
        var coveredUntil = from
        val ordered = workoutsOverlapping(sessions, from, to).sortedWith(compareBy({ it.startMs }, { -it.endMs }))
        for (s in ordered) {
            val sessionMs = s.endMs - s.startMs
            if (sessionMs <= 0) continue
            val countedEnd = minOf(s.endMs, to)
            val counted = (countedEnd - maxOf(s.startMs, coveredUntil)).coerceAtLeast(0L)
            coveredUntil = maxOf(coveredUntil, countedEnd)
            var sessionKcal = 0.0
            for (c in calories) {
                val inside = overlap(c.startMs, c.endMs, s.startMs, s.endMs)
                if (inside <= 0) continue
                any = true
                sessionKcal += c.energy.inKilocalories * inside / (c.endMs - c.startMs)
            }
            total += sessionKcal * counted / sessionMs
        }
        return if (any) total else null
    }

    private fun overlap(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Double =
        (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0L).toDouble()

    private val ExerciseSessionRecord.startMs get() = startTime.toEpochMilli()
    private val ExerciseSessionRecord.endMs get() = endTime.toEpochMilli()
    private val TotalCaloriesBurnedRecord.startMs get() = startTime.toEpochMilli()
    private val TotalCaloriesBurnedRecord.endMs get() = endTime.toEpochMilli()
}
