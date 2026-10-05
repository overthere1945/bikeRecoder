package com.cowork.bikerecoder.core.voice

import com.cowork.bikerecoder.core.model.TurnType.LEFT
import com.cowork.bikerecoder.core.model.TurnType.RIGHT
import com.cowork.bikerecoder.core.model.TurnType.ROUNDABOUT
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals

class KoreanPhrasesTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val p = KoreanPhrases(zone)

    /** 2026-10-06 Asia/Seoul 기준, 시:분을 epochMillis로 변환한다. */
    private fun at(h: Int, m: Int): Long =
        ZonedDateTime.of(2026, 10, 6, h, m, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun distances() {
        assertEquals("800미터", p.distance(796.0))
        assertEquals("200미터", p.distance(204.0))
        assertEquals("1.0킬로미터", p.distance(1_000.0))
        assertEquals("42.1킬로미터", p.distance(42_080.0))
        assertEquals("1.2킬로미터", p.distance(1_150.0))
    }

    @Test
    fun `clock noon and midnight`() {
        assertEquals("오후 12시 0분", p.clockTime(at(12, 0)))
        assertEquals("오전 12시 5분", p.clockTime(at(0, 5)))
        assertEquals("오후 3시 40분", p.clockTime(at(15, 40)))
        assertEquals("오전 9시 7분", p.clockTime(at(9, 7)))
    }

    @Test
    fun turns() {
        assertEquals("200미터 앞에서 좌회전입니다", p.turnAhead(200.0, LEFT, 0, null))
        assertEquals("좌회전 후 바로 우회전입니다", p.turnNow(LEFT, 0, RIGHT))
        assertEquals("300미터 앞에서 회전교차로에서 2번째 출구입니다", p.turnAhead(300.0, ROUNDABOUT, 2, null))
    }

    @Test
    fun `km report`() = assertEquals(
        "5킬로미터 이동. 남은 거리 42.1킬로미터, 도착 예정 오후 3시 40분입니다.",
        p.kmReport(5, 42_080.0, at(15, 40)),
    )

    @Test
    fun `fixed phrases`() {
        assertEquals("목적지에 도착했습니다", p.arrivedDestination)
        assertEquals("경유지에 도착했습니다", p.arrivedWaypoint)
        assertEquals("경로를 벗어났습니다. 다시 탐색합니다", p.offRoute)
        assertEquals("경로를 다시 찾지 못했습니다", p.rerouteFailed)
        assertEquals("GPS 신호가 약합니다", p.gpsWeak)
    }
}
