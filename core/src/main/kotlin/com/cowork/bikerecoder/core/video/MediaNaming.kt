package com.cowork.bikerecoder.core.video

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 갤러리 파일 이름(스펙 §3.5): `bikeRecoder_yyyyMMdd_HHmm`, 같은 이름이 있으면 `_2`, `_3`…. */
object MediaNaming {

    private const val PREFIX = "bikeRecoder_"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")

    /** 여행 첫 대상 구간 시작 시각 [firstStartMillis]를 [zone] 시간으로 적은 이름(확장자 없음). */
    fun baseName(firstStartMillis: Long, zone: ZoneId): String =
        PREFIX + Instant.ofEpochMilli(firstStartMillis).atZone(zone).format(STAMP)

    /** [existing](확장자 포함 이름들)에 없는 첫 이름: `base.ext`, `base_2.ext`, `base_3.ext`…. [ext]는 점이 있어도 없어도 된다. */
    fun unique(base: String, ext: String, existing: Set<String>): String {
        val suffix = "." + ext.removePrefix(".")
        if (base + suffix !in existing) return base + suffix
        var n = 2
        while ("${base}_$n$suffix" in existing) n++
        return "${base}_$n$suffix"
    }
}
