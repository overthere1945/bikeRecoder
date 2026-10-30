package com.cowork.bikerecoder.core.video

import com.cowork.bikerecoder.core.trip.TripLabel
import java.time.Instant
import java.time.ZoneId

/** 영상·요약 이미지의 자동 제목(스펙 §3.8). */
object RideTitle {

    private val PROVINCE_SHORT = mapOf(
        "서울특별시" to "서울",
        "부산광역시" to "부산",
        "대구광역시" to "대구",
        "인천광역시" to "인천",
        "광주광역시" to "광주",
        "대전광역시" to "대전",
        "울산광역시" to "울산",
        "세종특별자치시" to "세종",
        "경기도" to "경기",
        "강원도" to "강원",
        "강원특별자치도" to "강원",
        "충청북도" to "충북",
        "충청남도" to "충남",
        "전라북도" to "전북",
        "전북특별자치도" to "전북",
        "전라남도" to "전남",
        "경상북도" to "경북",
        "경상남도" to "경남",
        "제주특별자치도" to "제주",
    )

    /** 첫 출발 시각([zone] 시각)의 시간대 + "라이딩", 둘 이상의 날을 탔으면 "N일 여행". */
    fun base(firstStartedAt: Long, rideDays: Int, zone: ZoneId): String {
        if (rideDays >= 2) return "${rideDays}일 여행"
        val name = when (Instant.ofEpochMilli(firstStartedAt).atZone(zone).hour) {
            in 4..6 -> "새벽"
            in 7..10 -> "아침"
            in 11..13 -> "점심"
            in 14..17 -> "오후"
            in 18..20 -> "저녁"
            else -> "밤"
        }
        return "$name 라이딩"
    }

    /** 시·도 이름을 줄여 2단계 이름을 붙인다. 2단계가 비면 시·도만, 둘 다 비면 null. */
    fun shortRegion(depth1: String, depth2: String): String? {
        val d1 = depth1.trim()
        val d2 = depth2.trim()
        val head = PROVINCE_SHORT[d1] ?: d1
        return listOf(head, d2).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
    }

    /** "저녁 라이딩 · 서울 강서구". 지역이 없으면 [base]만. */
    fun withRegion(base: String, region: String?): String = if (region.isNullOrBlank()) base else "$base · $region"

    /** 직접 붙인 제목의 길이: 코드 포인트 수(이모지 하나가 1자, 규칙 D6). */
    const val MAX_CUSTOM_CODE_POINTS = 30

    /** 입력의 앞뒤 빈칸을 지우고 [MAX_CUSTOM_CODE_POINTS]자까지만 남긴다. 비면 null(= 직접 제목을 지운다). */
    fun cleanCustom(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        return truncateCodePoints(trimmed, MAX_CUSTOM_CODE_POINTS).trimEnd().ifEmpty { null }
    }

    /** [text]의 앞 [max] 코드 포인트. 서로게이트 쌍을 가운데서 자르지 않는다. */
    fun truncateCodePoints(text: String, max: Int): String =
        if (text.codePointCount(0, text.length) <= max) text else text.substring(0, text.offsetByCodePoints(0, max))

    /** 보이는 제목: 직접 붙인 제목, 없으면 자동 제목에 지역을 붙인 것(스펙 §2.5). */
    fun shown(label: TripLabel, firstStartedAt: Long, rideDays: Int, zone: ZoneId): String =
        label.customTitle?.takeIf { it.isNotBlank() } ?: withRegion(base(firstStartedAt, rideDays, zone), label.regionName)
}
