package com.cowork.bikerecoder.core.video

import com.cowork.bikerecoder.core.trip.TripLabel
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RideTitleTest {

    private fun millis(hour: Int, minute: Int) =
        LocalDateTime.of(2026, 10, 17, hour, minute).atZone(SEOUL).toInstant().toEpochMilli()

    private fun titleAt(hour: Int, minute: Int) = RideTitle.base(millis(hour, minute), 1, SEOUL)

    @Test
    fun timeOfDayBoundaries() {
        assertEquals("밤 라이딩", titleAt(3, 59))
        assertEquals("새벽 라이딩", titleAt(4, 0))
        assertEquals("새벽 라이딩", titleAt(6, 59))
        assertEquals("아침 라이딩", titleAt(7, 0))
        assertEquals("아침 라이딩", titleAt(10, 59))
        assertEquals("점심 라이딩", titleAt(11, 0))
        assertEquals("점심 라이딩", titleAt(13, 59))
        assertEquals("오후 라이딩", titleAt(14, 0))
        assertEquals("오후 라이딩", titleAt(17, 59))
        assertEquals("저녁 라이딩", titleAt(18, 0))
        assertEquals("저녁 라이딩", titleAt(20, 59))
        assertEquals("밤 라이딩", titleAt(21, 0))
        assertEquals("밤 라이딩", titleAt(0, 0))
    }

    @Test
    fun multiDay() {
        assertEquals("2일 여행", RideTitle.base(millis(9, 0), 2, SEOUL))
        assertEquals("5일 여행", RideTitle.base(millis(9, 0), 5, SEOUL))
    }

    @Test
    fun regionShortening() {
        assertEquals("서울 강서구", RideTitle.shortRegion("서울특별시", "강서구"))
        assertEquals("부산 해운대구", RideTitle.shortRegion("부산광역시", "해운대구"))
        assertEquals("대구 수성구", RideTitle.shortRegion("대구광역시", "수성구"))
        assertEquals("인천 연수구", RideTitle.shortRegion("인천광역시", "연수구"))
        assertEquals("광주 북구", RideTitle.shortRegion("광주광역시", "북구"))
        assertEquals("대전 유성구", RideTitle.shortRegion("대전광역시", "유성구"))
        assertEquals("울산 남구", RideTitle.shortRegion("울산광역시", "남구"))
        assertEquals("세종", RideTitle.shortRegion("세종특별자치시", ""))
        assertEquals("경기 성남시 분당구", RideTitle.shortRegion("경기도", "성남시 분당구"))
        assertEquals("강원 춘천시", RideTitle.shortRegion("강원도", "춘천시"))
        assertEquals("강원 춘천시", RideTitle.shortRegion("강원특별자치도", "춘천시"))
        assertEquals("충북 청주시 상당구", RideTitle.shortRegion("충청북도", "청주시 상당구"))
        assertEquals("충남 천안시 동남구", RideTitle.shortRegion("충청남도", "천안시 동남구"))
        assertEquals("전북 전주시 완산구", RideTitle.shortRegion("전라북도", "전주시 완산구"))
        assertEquals("전북 전주시 완산구", RideTitle.shortRegion("전북특별자치도", "전주시 완산구"))
        assertEquals("전남 목포시", RideTitle.shortRegion("전라남도", "목포시"))
        assertEquals("경북 경주시", RideTitle.shortRegion("경상북도", "경주시"))
        assertEquals("경남 창원시 성산구", RideTitle.shortRegion("경상남도", "창원시 성산구"))
        assertEquals("제주 제주시", RideTitle.shortRegion("제주특별자치도", "제주시"))
        assertEquals("어딘가", RideTitle.shortRegion("어딘가", ""))
        assertEquals("어딘가 무슨구", RideTitle.shortRegion("어딘가", "무슨구"))
        assertNull(RideTitle.shortRegion("", ""))
        assertNull(RideTitle.shortRegion("  ", " "))
    }

    @Test
    fun withRegion() {
        assertEquals("저녁 라이딩", RideTitle.withRegion("저녁 라이딩", null))
        assertEquals("저녁 라이딩 · 서울 강서구", RideTitle.withRegion("저녁 라이딩", "서울 강서구"))
    }

    @Test
    fun shownPrefersCustomThenRegion() {
        val evening = LocalDateTime.of(2026, 10, 17, 19, 0).atZone(SEOUL).toInstant().toEpochMilli()
        assertEquals("한강 한 바퀴", RideTitle.shown(TripLabel("한강 한 바퀴", "서울 강서구"), evening, 1, SEOUL))
        assertEquals("한강 한 바퀴", RideTitle.shown(TripLabel("한강 한 바퀴", null), evening, 1, SEOUL))
        assertEquals("저녁 라이딩 · 서울 강서구", RideTitle.shown(TripLabel(null, "서울 강서구"), evening, 1, SEOUL))
        assertEquals("저녁 라이딩", RideTitle.shown(TripLabel(), evening, 1, SEOUL))
        assertEquals("3일 여행 · 강원 춘천시", RideTitle.shown(TripLabel(null, "강원 춘천시"), evening, 3, SEOUL))
    }

    /** D6: 30 code points, an emoji counts one. */
    @Test
    fun thirtyCodePointsIncludingEmoji() {
        assertEquals("한강", RideTitle.cleanCustom("  한강 "))
        assertNull(RideTitle.cleanCustom("   "))
        assertNull(RideTitle.cleanCustom(""))
        val bike = "\uD83D\uDEB4"
        assertEquals(bike.repeat(30), RideTitle.cleanCustom(bike.repeat(30)))
        assertEquals(bike.repeat(30), RideTitle.cleanCustom(bike.repeat(31)))
        assertEquals(30, RideTitle.cleanCustom(bike.repeat(31))!!.let { it.codePointCount(0, it.length) })
        assertEquals("가".repeat(30), RideTitle.cleanCustom("가".repeat(40)))
        assertEquals(30, RideTitle.MAX_CUSTOM_CODE_POINTS)
    }
}
