package com.cowork.bikerecoder.core.video

/** 영상 초당 장 수. */
const val FPS = 24

/** 화면 비율: 세로 9:16, 가로 16:9. */
enum class Aspect(val width: Int, val height: Int) { PORTRAIT(1080, 1920), LANDSCAPE(1920, 1080) }

/** 영상 길이(초)와 그 길이에 넣을 수 있는 사진 상한. */
enum class VideoLength(val seconds: Int, val photoCap: Int) { SHORT(30, 4), NORMAL(60, 8), LONG(120, 15) }

/** 경로 색: 기본(날마다 색) 또는 심박. */
enum class RouteColoring { DEFAULT, HEART_RATE }

/** 영상 만들기 화면에서 고른 옵션. [musicUri]가 null이면 배경음악 없음. */
data class VideoOptions(
    val aspect: Aspect = Aspect.PORTRAIT,
    val length: VideoLength = VideoLength.NORMAL,
    val photos: Boolean = true,
    val hud: Boolean = true,
    val coloring: RouteColoring = RouteColoring.DEFAULT,
    val musicUri: String? = null,
)
