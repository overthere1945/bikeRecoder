package com.cowork.bikerecoder.core.post

/** 누적 지도 주행 선 색. 0번이 최신 주행이고, 10번째 이후는 처음부터 반복한다. */
object RideColors {
    val PALETTE: List<String> = listOf(
        "#E53935", "#1E88E5", "#43A047", "#8E24AA", "#FB8C00",
        "#00ACC1", "#D81B60", "#3949AB", "#6D4C41", "#7CB342",
    )

    fun colorAt(index: Int): String = PALETTE[Math.floorMod(index, PALETTE.size)]
}
