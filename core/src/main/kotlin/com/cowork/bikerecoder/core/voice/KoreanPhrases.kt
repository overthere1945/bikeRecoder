package com.cowork.bikerecoder.core.voice

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

    /** [m]을 10m 단위로 반올림한다. 결과가 1,000m 이상이면 소수 첫째 자리 킬로미터로 표기한다. */
    fun distance(m: Double): String {
        val roundedM = Math.round(m / 10.0) * 10
        if (roundedM < 1_000L) return "${roundedM}미터"

        val km = BigDecimal(m).divide(BigDecimal(1_000), 1, RoundingMode.HALF_UP)
        return "${km.toPlainString()}킬로미터"
    }

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
}
