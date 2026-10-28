package com.cowork.bikerecoder.core.video

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
}
