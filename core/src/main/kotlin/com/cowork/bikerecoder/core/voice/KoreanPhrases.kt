package com.cowork.bikerecoder.core.voice

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.activity.SunsetStage
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.model.TurnType
import com.cowork.bikerecoder.core.model.TurnType.KEEP_LEFT
import com.cowork.bikerecoder.core.model.TurnType.KEEP_RIGHT
import com.cowork.bikerecoder.core.model.TurnType.LEFT
import com.cowork.bikerecoder.core.model.TurnType.RIGHT
import com.cowork.bikerecoder.core.model.TurnType.ROUNDABOUT
import com.cowork.bikerecoder.core.model.TurnType.SHARP_LEFT
import com.cowork.bikerecoder.core.model.TurnType.SHARP_RIGHT
import com.cowork.bikerecoder.core.model.TurnType.SLIGHT_LEFT
import com.cowork.bikerecoder.core.model.TurnType.SLIGHT_RIGHT
import com.cowork.bikerecoder.core.model.TurnType.STRAIGHT
import com.cowork.bikerecoder.core.model.TurnType.U_TURN
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** 음성 안내에 쓰이는 모든 한국어 문구를 생성한다. */
class KoreanPhrases(private val zone: ZoneId = ZoneId.of("Asia/Seoul")) {

    val arrivedDestination: String = "목적지에 도착했습니다"
    val arrivedWaypoint: String = "경유지에 도착했습니다"
    val offRoute: String = "경로를 벗어났습니다. 다시 탐색합니다"
    val rerouteFailed: String = "경로를 다시 찾지 못했습니다"
    val gpsWeak: String = "GPS 신호가 약합니다"
    val recordingStarted: String = "기록을 시작합니다"
    val recordingPaused: String = "일시정지되었습니다"
    val recordingResumed: String = "기록을 다시 시작합니다"
    val guidanceEndedRecordingGoesOn: String = "안내를 마칩니다. 기록은 계속됩니다"
    val newSegmentStarted: String = "새 구간으로 기록을 시작합니다"

    /** [m]을 10m 단위로 반올림한다. 결과가 1,000m 이상이면 소수 첫째 자리 킬로미터로 표기한다. */
    fun distance(m: Double): String {
        val roundedM = Math.round(m / 10.0) * 10
        if (roundedM < 1_000L) return "${roundedM}미터"

        val km = BigDecimal(m).divide(BigDecimal(1_000), 1, RoundingMode.HALF_UP)
        return "${km.toPlainString()}킬로미터"
    }

    /** 오르막 음성: "{거리} 앞 오르막, {길이}, 평균 {n}퍼센트입니다". 평균 경사는 반올림 정수. */
    fun climb(distanceM: Double, lengthM: Double, avgGradePct: Double): String =
        "${distance(distanceM)} 앞 오르막, ${distance(lengthM)}, 평균 ${Math.round(avgGradePct)}퍼센트입니다"

    /** [epochMillis]를 [zone] 기준 "오전/오후 h시 m분"으로 표기한다(시는 12시간제, 분은 0패딩 없음). */
    fun clockTime(epochMillis: Long): String {
        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
        val amPm = if (zdt.hour < 12) "오전" else "오후"
        val hour12 = zdt.hour % 12
        val displayHour = if (hour12 == 0) 12 else hour12
        return "$amPm ${displayHour}시 ${zdt.minute}분"
    }

    /** 회전 종류를 한국어 문구로 변환한다. [roundaboutExit]은 ROUNDABOUT에서만 쓰인다. */
    fun turn(type: TurnType, roundaboutExit: Int): String = when (type) {
        STRAIGHT -> "직진"
        LEFT -> "좌회전"
        SLIGHT_LEFT -> "왼쪽 방향"
        SHARP_LEFT -> "왼쪽으로 급회전"
        RIGHT -> "우회전"
        SLIGHT_RIGHT -> "오른쪽 방향"
        SHARP_RIGHT -> "오른쪽으로 급회전"
        KEEP_LEFT -> "왼쪽 길로 계속"
        KEEP_RIGHT -> "오른쪽 길로 계속"
        U_TURN -> "유턴"
        ROUNDABOUT -> "회전교차로에서 ${roundaboutExit}번째 출구"
    }

    /** "{거리} 앞에서 {turn}입니다" 또는 [then]이 있으면 "{거리} 앞에서 {turn} 후 바로 {then}입니다". */
    fun turnAhead(distanceM: Double, type: TurnType, exit: Int, then: TurnType?): String {
        val mainTurn = turn(type, exit)
        return if (then == null) {
            "${distance(distanceM)} 앞에서 ${mainTurn}입니다"
        } else {
            "${distance(distanceM)} 앞에서 $mainTurn 후 바로 ${turn(then, 0)}입니다"
        }
    }

    /** "{turn}입니다" 또는 [then]이 있으면 "{turn} 후 바로 {then}입니다". */
    fun turnNow(type: TurnType, exit: Int, then: TurnType?): String {
        val mainTurn = turn(type, exit)
        return if (then == null) {
            "${mainTurn}입니다"
        } else {
            "$mainTurn 후 바로 ${turn(then, 0)}입니다"
        }
    }

    /** "{km}킬로미터 이동. 남은 거리 {distance}, 도착 예정 {clockTime}입니다." */
    fun kmReport(km: Int, remainingM: Double, etaMillis: Long): String =
        "${km}킬로미터 이동. 남은 거리 ${distance(remainingM)}, 도착 예정 ${clockTime(etaMillis)}입니다."

    /** "{km}킬로미터 이동. 평균 시속 {x.x}킬로미터, 이동 시간 {시간}입니다." */
    fun rideKmReport(km: Int, avgSpeedMps: Double, movingTimeMs: Long): String =
        "${km}킬로미터 이동. 평균 시속 ${RideFormat.speedKmh(avgSpeedMps)}킬로미터, " +
            "이동 시간 ${RideFormat.duration(movingTimeMs)}입니다."

    /** 기록과 안내를 함께 할 때: "...이동 시간 {시간}. 남은 거리 {거리}, 도착 예정 {시각}입니다." */
    fun rideKmReportWithNav(
        km: Int,
        avgSpeedMps: Double,
        movingTimeMs: Long,
        remainingM: Double,
        etaMillis: Long,
    ): String =
        "${km}킬로미터 이동. 평균 시속 ${RideFormat.speedKmh(avgSpeedMps)}킬로미터, " +
            "이동 시간 ${RideFormat.duration(movingTimeMs)}. " +
            "남은 거리 ${distance(remainingM)}, 도착 예정 ${clockTime(etaMillis)}입니다."

    /** 활동 종류의 말하는 이름. */
    fun activityName(type: ActivityType): String = when (type) {
        ActivityType.BIKE -> "자전거"
        ActivityType.WALK -> "걷기"
        ActivityType.RUN -> "달리기"
        ActivityType.HIKE -> "등산"
    }

    /**
     * 시작 안내. 모든 종류가 "{이름} 기록을 시작합니다"다(자전거도 "자전거 기록을 시작합니다", V1.9.1).
     * 등산이고 일몰까지 [sunsetInMs]가 0 초과 2시간 미만이면 " 일몰까지 {시간} 남았습니다."를 붙인다.
     */
    fun recordingStarted(type: ActivityType, sunsetInMs: Long?): String {
        val base = "${activityName(type)} $recordingStarted"
        return if (type == ActivityType.HIKE && sunsetInMs != null && sunsetInMs > 0 && sunsetInMs < 7_200_000) {
            "$base ${sunsetLeft(sunsetInMs)}"
        } else {
            base
        }
    }

    /** 등산 음성의 시간(P4): 분 내림, "{h}시간", "{h}시간 {m}분", "{m}분". 분이 0인 부분은 뺀다(1분 미만은 "0분"). */
    fun spokenDuration(ms: Long): String {
        val totalMinutes = ms / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0L -> "${minutes}분"
            minutes == 0L -> "${hours}시간"
            else -> "${hours}시간 ${minutes}분"
        }
    }

    /** 페이스 음성(P3): "{m}분 {s}초", s가 0이면 "{m}분", 1분 미만이면 "{s}초". */
    fun pace(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return when {
            m == 0 -> "${s}초"
            s == 0 -> "${m}분"
            else -> "${m}분 ${s}초"
        }
    }

    /**
     * 달리기·걷기 1 km 안내: "{n}킬로미터. 이번 1킬로미터 {페이스}, 평균 페이스 {페이스}입니다."
     * 값이 null인 부분은 뺀다. 둘 다 없으면 "{n}킬로미터입니다."
     */
    fun paceKmReport(km: Int, lapSeconds: Int?, avgSeconds: Int?): String {
        val parts = listOfNotNull(
            lapSeconds?.let { "이번 1킬로미터 ${pace(it)}" },
            avgSeconds?.let { "평균 페이스 ${pace(it)}" },
        )
        return if (parts.isEmpty()) "${km}킬로미터입니다." else "${km}킬로미터. ${parts.joinToString(", ")}입니다."
    }

    /**
     * 등산 30분 안내(스펙 §3.3, P5): "산행 {시간}. 현재 고도 {h}미터, 누적 상승 {u}미터, 거리 {d.d}킬로미터입니다."
     * 고도를 모르면 "현재 고도" 부분을 뺀다. 일몰까지 [sunsetInMs]가 0 초과 3시간 미만이면 " 일몰까지 {시간} 남았습니다."를 붙인다.
     */
    fun hikeReport(
        elapsedMs: Long,
        altitudeM: Double?,
        ascentM: Double,
        distanceM: Double,
        sunsetInMs: Long?,
    ): String {
        val km = BigDecimal(distanceM).divide(BigDecimal(1_000), 1, RoundingMode.HALF_UP).toPlainString()
        val altitude = altitudeM?.let { "현재 고도 ${wholeMeters(it)}미터, " } ?: ""
        val tail = if (sunsetInMs != null && sunsetInMs > 0 && sunsetInMs < 10_800_000) " ${sunsetLeft(sunsetInMs)}" else ""
        return "산행 ${spokenDuration(elapsedMs)}. ${altitude}누적 상승 ${wholeMeters(ascentM)}미터, 거리 ${km}킬로미터입니다.$tail"
    }

    /** 일몰 안내 문장(스펙 §3.4). */
    fun sunsetCue(stage: SunsetStage): String = when (stage) {
        SunsetStage.H2 -> "일몰까지 2시간 남았습니다. 하산 시간을 확인하세요."
        SunsetStage.H1 -> "일몰까지 1시간 남았습니다. 하산을 시작하세요."
        SunsetStage.M30 -> "일몰까지 30분 남았습니다. 서둘러 하산하세요."
        SunsetStage.NOW -> "해가 졌습니다. 곧 어두워지니 랜턴을 준비하세요."
    }

    private fun sunsetLeft(ms: Long): String = "일몰까지 ${spokenDuration(ms)} 남았습니다."

    private fun wholeMeters(m: Double): String = BigDecimal(m).setScale(0, RoundingMode.HALF_UP).toPlainString()
}
