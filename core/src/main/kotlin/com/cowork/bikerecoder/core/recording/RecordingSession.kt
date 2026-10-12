package com.cowork.bikerecoder.core.recording

import com.cowork.bikerecoder.core.model.LocationFix
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor

enum class PauseKind { AUTO, MANUAL }

sealed interface RecordingState {
    data object Recording : RecordingState
    data class Paused(val kind: PauseKind) : RecordingState
    data object Stopped : RecordingState
}

sealed interface RecordingEvent {
    data object Started : RecordingEvent
    data class Paused(val kind: PauseKind) : RecordingEvent
    data object Resumed : RecordingEvent

    /** 그날 기록 거리가 [km]번째 킬로미터를 넘었다. day*는 그날(이 여행, Asia/Seoul) 합계. */
    data class KmReached(val km: Int, val dayDistanceM: Double, val dayMovingTimeMs: Long) : RecordingEvent
}

data class DayTotals(val distanceM: Double, val movingTimeMs: Long) {
    /** 그날 평균 속도 = distanceM / (movingTimeMs / 1000.0), 이동 시간 0이면 0. 1km 안내 문장의 {x.x}는 이것 하나만 쓴다(Task 10·11). */
    val avgSpeedMps: Double
        get() = if (movingTimeMs > 0) distanceM / (movingTimeMs / 1000.0) else 0.0

    companion object {
        val ZERO = DayTotals(0.0, 0L)
    }
}

/** [point]: 이번 위치가 기록 중 상태에서 채택됐으면 저장할 점. */
data class RecordingUpdate(
    val state: RecordingState,
    val stats: RideStats,
    val events: List<RecordingEvent>,
    val point: TrackPoint?,
    val gpsWeak: Boolean,
)

/**
 * 기록 엔진. [TrackFilter] → [AltitudeFusion] → 상태별 처리([RideStatsCalculator], [AscentCounter], [AutoPauseDetector]).
 *
 * - 생성 직후부터 [RecordingState.Recording]이고 [start]는 Started 이벤트를 한 번 내보낸다.
 * - 이동 시간·자동 일시정지는 `fix.timeMillis` 차이, 경과 시간·날짜·고도 보정 창·GPS 감시는 호출자가 주는
 *   nowMillis(tick 시각)로 계산한다. 경과 시간 = 지금까지 본 가장 큰 `nowMillis − startMillis`.
 * - 자동 일시정지 중 위치가 재개 조건을 만족하면 Resumed를 내고 그 위치를 새 사슬의 첫 점으로 기록한다.
 *   수동 일시정지는 [resume]으로만 풀린다.
 * - 상태가 바뀔 때마다 [AutoPauseDetector.reset]을 부른다. 단 자동 일시정지 자체는 예외다:
 *   [AutoPauseDetector.shouldPause]가 느림/빠름 시작 시각을 이미 지웠고, reset은 "정지 지점에서 15m" 재개에
 *   필요한 정지 지점까지 지워 버리기 때문이다.
 * - 그날 합계 [day] = initialDay + 이 세션의 거리·이동 시간. nowMillis의 [zone] 날짜가 바뀌면 0부터 다시
 *   세고 km도 1부터 센다. 날짜가 바뀌는 위치의 구간은 새 날짜에 들어간다.
 * - GPS 감시: [onFix]는 필터가 채택한 위치일 때만 "봤음"을 표시하고(버린 위치는 신호로 치지 않는다), [onTick]이 마지막 활동 tick 이후 [gpsTimeoutMs]가 지나면 약함으로 본다.
 * - [stop] 이후 호출은 상태·통계를 바꾸지 않고 빈 이벤트를 돌려준다.
 *
 * 스레드: 단일 스레드(rideScope)에서만 호출한다.
 */
class RecordingSession(
    private val startMillis: Long,
    autoPause: Boolean,
    initialDay: DayTotals = DayTotals.ZERO,
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
    private val gpsTimeoutMs: Long = 10_000,
) {
    var state: RecordingState = RecordingState.Recording
        private set
    var autoPause: Boolean = autoPause
        private set

    private val filter = TrackFilter()
    private val calc = RideStatsCalculator()
    private val detector = AutoPauseDetector()
    private val fusion = AltitudeFusion()
    private val ascent = AscentCounter()

    private var started = false
    private var maxNowMillis = startMillis
    private var climbMode: AltitudeMode? = null
    private var minAltitudeM: Double? = null
    private var maxAltitudeM: Double? = null

    // 그날 합계: dayBase + (세션 누적 − 그날 시작 시점의 세션 누적)
    private var dayDate: LocalDate = dateOf(startMillis)
    private var dayBase: DayTotals = initialDay
    private var daySessionStartM = 0.0
    private var daySessionStartMs = 0L
    private var lastKm: Int = floor(initialDay.distanceM / 1000).toInt()

    // GPS 감시(tick 시각만)
    private var lastActivityTickMillis: Long? = null
    private var fixSinceLastTick = false
    private var gpsWeak = false

    val stats: RideStats
        get() = RideStats(
            distanceM = calc.distanceM,
            movingTimeMs = calc.movingTimeMs,
            elapsedMs = maxNowMillis - startMillis,
            currentSpeedMps = if (state == RecordingState.Recording) calc.currentSpeedMps else 0.0,
            avgSpeedMps = calc.avgSpeedMps,
            maxSpeedMps = calc.maxSpeedMps,
            altitudeM = fusion.altitudeM,
            ascentM = ascent.ascentM,
            descentM = ascent.descentM,
            minAltitudeM = minAltitudeM,
            maxAltitudeM = maxAltitudeM,
        )

    val day: DayTotals
        get() = DayTotals(
            distanceM = dayBase.distanceM + calc.distanceM - daySessionStartM,
            movingTimeMs = dayBase.movingTimeMs + calc.movingTimeMs - daySessionStartMs,
        )

    fun start(): RecordingUpdate {
        if (started || state == RecordingState.Stopped) return update()
        started = true
        return update(listOf(RecordingEvent.Started))
    }

    fun onFix(fix: LocationFix, nowMillis: Long): RecordingUpdate {
        if (state == RecordingState.Stopped) return update()
        observe(nowMillis)

        // Only a usable fix counts for the GPS watchdog: a stream of rejected ones (e.g. all > 30 m) is no signal.
        val accepted = filter.accept(fix) ?: return update()
        fixSinceLastTick = true
        gpsWeak = false
        fix.altitudeM?.let { fusion.onGps(it, fix.verticalAccuracyM, nowMillis) }

        val events = mutableListOf<RecordingEvent>()
        var point: TrackPoint? = null
        when (val s = state) {
            RecordingState.Recording -> point = record(accepted, events)
            is RecordingState.Paused -> if (s.kind == PauseKind.AUTO &&
                detector.shouldResume(fix.timeMillis, accepted.speedMps, fix.point)
            ) {
                resumeRecording()
                events += RecordingEvent.Resumed
                point = record(accepted, events)
            }
            RecordingState.Stopped -> Unit
        }
        return update(events, point)
    }

    fun onPressure(pressureHpa: Double, nowMillis: Long) {
        if (state == RecordingState.Stopped) return
        observe(nowMillis)
        fusion.onPressure(pressureHpa, nowMillis)
    }

    fun onTick(nowMillis: Long): RecordingUpdate {
        if (state == RecordingState.Stopped) return update()
        observe(nowMillis)
        val last = lastActivityTickMillis
        if (last == null || fixSinceLastTick) {
            lastActivityTickMillis = nowMillis
            fixSinceLastTick = false
        } else if (nowMillis - last >= gpsTimeoutMs) {
            gpsWeak = true
        }
        return update()
    }

    fun pause(nowMillis: Long): RecordingUpdate {
        if (state != RecordingState.Recording) return update()
        observe(nowMillis)
        pauseRecording(PauseKind.MANUAL)
        detector.reset()
        return update(listOf(RecordingEvent.Paused(PauseKind.MANUAL)))
    }

    fun resume(nowMillis: Long): RecordingUpdate {
        if (state !is RecordingState.Paused) return update()
        observe(nowMillis)
        resumeRecording()
        return update(listOf(RecordingEvent.Resumed))
    }

    fun setAutoPause(enabled: Boolean, nowMillis: Long): RecordingUpdate {
        if (state == RecordingState.Stopped || enabled == autoPause) return update()
        observe(nowMillis)
        autoPause = enabled
        detector.reset()
        if (!enabled && state == RecordingState.Paused(PauseKind.AUTO)) {
            resumeRecording()
            return update(listOf(RecordingEvent.Resumed))
        }
        return update()
    }

    fun stop(nowMillis: Long): RecordingUpdate {
        if (state == RecordingState.Stopped) return update()
        observe(nowMillis)
        calc.breakChain()
        detector.reset()
        state = RecordingState.Stopped
        return update()
    }

    /** 기록 중 채택 위치 하나: 거리·상승·고도 범위·km·자동 일시정지. 저장할 점을 돌려준다. */
    private fun record(accepted: AcceptedFix, events: MutableList<RecordingEvent>): TrackPoint {
        val fix = accepted.fix
        if (accepted.reanchored) calc.breakChain()
        calc.add(fix, accepted.speedMps)

        val mode = fusion.mode
        if (mode != climbMode) {
            ascent.rebase()
            climbMode = mode
        }
        fusion.climbAltitudeM?.let { ascent.onAltitude(it, fusion.climbThresholdM) }

        val altitude = fusion.altitudeM
        if (altitude != null) {
            minAltitudeM = minAltitudeM?.coerceAtMost(altitude) ?: altitude
            maxAltitudeM = maxAltitudeM?.coerceAtLeast(altitude) ?: altitude
        }

        val today = day
        val km = floor(today.distanceM / 1000).toInt()
        if (km > lastKm) {
            lastKm = km
            events += RecordingEvent.KmReached(km, today.distanceM, today.movingTimeMs)
        }

        val point = TrackPoint(fix.timeMillis, fix.point, altitude, fix.speedMps, fix.accuracyM)

        if (autoPause && detector.shouldPause(fix.timeMillis, accepted.speedMps, fix.point)) {
            // reset 하지 않는다: shouldPause가 시작 시각을 지웠고, 정지 지점은 15m 재개에 쓴다.
            pauseRecording(PauseKind.AUTO)
            events += RecordingEvent.Paused(PauseKind.AUTO)
        }
        return point
    }

    private fun pauseRecording(kind: PauseKind) {
        calc.breakChain()
        state = RecordingState.Paused(kind)
    }

    /** 일시정지 → 기록 중. 정지 지점→재개 위치 거리·상승을 잇지 않는다. */
    private fun resumeRecording() {
        calc.breakChain()
        ascent.rebase()
        detector.reset()
        state = RecordingState.Recording
    }

    /** nowMillis가 올 때마다: 경과 시간(최댓값)과 날짜 바뀜. */
    private fun observe(nowMillis: Long) {
        if (nowMillis > maxNowMillis) maxNowMillis = nowMillis
        val date = dateOf(nowMillis)
        if (date > dayDate) {
            dayDate = date
            dayBase = DayTotals.ZERO
            daySessionStartM = calc.distanceM
            daySessionStartMs = calc.movingTimeMs
            lastKm = 0
        }
    }

    private fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun update(events: List<RecordingEvent> = emptyList(), point: TrackPoint? = null) =
        RecordingUpdate(state, stats, events, point, gpsWeak)
}
