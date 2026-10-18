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

/** 삼성 헬스가 Health Connect에 쓴 심박·자전거 운동·총 칼로리를 읽는다. */
class HealthConnectHeartRateSource(
    context: Context,
    private val warnLog: WarnLog = androidWarnLog("HealthConnectHR"),
) : HeartRateSource {
    private val context = context.applicationContext

    override val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    )

    override fun availability(): HealthAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.UPDATE_REQUIRED
        else -> HealthAvailability.NOT_INSTALLED
    }

    override suspend fun hasPermissions(): Boolean {
        if (availability() != HealthAvailability.AVAILABLE) return false
        return try {
            client().permissionController.getGrantedPermissions().containsAll(permissions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnLog.warn("Health Connect permission check failed", e)
            false
        }
    }

    override suspend fun read(from: Long, to: Long): HrRead {
        if (!hasPermissions()) return HrRead.Failed
        return try {
            val client = client()
            val sessions = client.readAll(
                ExerciseSessionRecord::class,
                from - HealthConnectMapping.SESSION_MARGIN_MS,
                to + HealthConnectMapping.SESSION_MARGIN_MS,
            )
            val (hrFrom, hrTo) = HealthConnectMapping.readWindow(from, to, sessions)
            val samples = HealthConnectMapping.samples(client.readAll(HeartRateRecord::class, hrFrom, hrTo), from, to)
            val calories = HealthConnectMapping.bikingOverlapping(sessions, from, to)
                .takeIf { it.isNotEmpty() }
                ?.let { biking ->
                    val records = client.readAll(
                        TotalCaloriesBurnedRecord::class,
                        biking.minOf { it.startTime.toEpochMilli() },
                        biking.maxOf { it.endTime.toEpochMilli() },
                    )
                    HealthConnectMapping.calories(biking, records, from, to)
                }
            hrReadOf(samples, calories)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnLog.warn("Health Connect read failed", e)
            HrRead.Failed
        }
    }

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    /** 삼성 헬스가 쓴 [type] 기록을 [from, to]에서 모든 페이지까지 읽는다. */
    private suspend fun <T : Record> HealthConnectClient.readAll(type: KClass<T>, from: Long, to: Long): List<T> {
        val out = ArrayList<T>()
        var pageToken: String? = null
        do {
            val response = readRecords(
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

    /** [records]의 샘플 중 [from, to] 안의 것, 시간순(기록 순서와 무관). */
    fun samples(records: List<HeartRateRecord>, from: Long, to: Long): List<HeartRateSample> =
        records.asSequence()
            .flatMap { it.samples.asSequence() }
            .map { HeartRateSample(it.time.toEpochMilli(), it.beatsPerMinute.toInt()) }
            .filter { it.timeMillis in from..to }
            .sortedBy { it.timeMillis }
            .toList()

    /**
     * 심박을 읽을 범위: [from, to]에 겹치는 운동들을 더한 범위.
     * 운동 내내 이어진 심박 기록이 구간보다 길어도 읽기 범위에 온전히 들어오게 한다.
     */
    fun readWindow(from: Long, to: Long, sessions: List<ExerciseSessionRecord>): Pair<Long, Long> {
        val overlapping = sessions.filter { overlap(it.startMs, it.endMs, from, to) > 0 }
        val start = (overlapping.map { it.startMs } + from).min()
        val end = (overlapping.map { it.endMs } + to).max()
        return start to end
    }

    /** [from, to]와 겹치는 자전거(BIKING) 운동. */
    fun bikingOverlapping(sessions: List<ExerciseSessionRecord>, from: Long, to: Long): List<ExerciseSessionRecord> =
        sessions.filter {
            it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_BIKING && overlap(it.startMs, it.endMs, from, to) > 0
        }

    /**
     * 겹치는 자전거 운동마다 (운동 안의 총 칼로리) × (구간과 겹친 시간 / 운동 시간)을 더한다.
     * 칼로리 기록이 운동 밖으로 걸치면 운동 안에 든 비율만 센다.
     * 겹치는 자전거 운동이 없거나 그 운동들에 칼로리 기록이 없으면 null.
     */
    fun calories(
        sessions: List<ExerciseSessionRecord>,
        calories: List<TotalCaloriesBurnedRecord>,
        from: Long,
        to: Long,
    ): Double? {
        var total = 0.0
        var any = false
        for (s in bikingOverlapping(sessions, from, to)) {
            val sessionMs = s.endMs - s.startMs
            if (sessionMs <= 0) continue
            var sessionKcal = 0.0
            for (c in calories) {
                val inside = overlap(c.startMs, c.endMs, s.startMs, s.endMs)
                if (inside <= 0) continue
                any = true
                sessionKcal += c.energy.inKilocalories * inside / (c.endMs - c.startMs)
            }
            total += sessionKcal * overlap(s.startMs, s.endMs, from, to) / sessionMs
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
