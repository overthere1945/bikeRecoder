package com.cowork.bikerecoder.core.video

import kotlin.math.roundToInt

/** 영상 초당 장 수. */
const val FPS = 30

/** [seconds]초에 해당하는 장 수(반올림). 장면 길이는 초로 적고 장 수는 여기서 얻는다. */
fun framesOf(seconds: Double): Int = (seconds * FPS).roundToInt()

/** 화면 비율: 세로 9:16, 가로 16:9. */
enum class Aspect(val width: Int, val height: Int) { PORTRAIT(1080, 1920), LANDSCAPE(1920, 1080) }

/** 영상 길이(초)와 그 길이에 넣을 수 있는 사진 상한. */
enum class VideoLength(val seconds: Int, val photoCap: Int) { SHORT(30, 4), NORMAL(60, 8), LONG(120, 15) }

/** 경로 색: 기본(날마다 색) 또는 심박. */
enum class RouteColoring { DEFAULT, HEART_RATE }

/**
 * 앱이 직접 만들어 담은 배경음악 셋. [rawName]은 `res/raw` 안의 파일 이름(core에는 Android가 없어 R 번호 대신 이름으로 찾는다).
 */
enum class BuiltInMusic(val key: String, val title: String, val rawName: String) {
    SUNNY_PEDAL("sunny_pedal", "햇살 페달", "music_sunny_pedal"),
    WIND_ROAD("wind_road", "바람길", "music_wind_road"),
    OVER_THE_HILL("over_the_hill", "언덕 너머", "music_over_the_hill"),
}

/**
 * 영상 만들기 화면에서 고른 옵션. 배경음악은 고른 파일 [musicUri], 없으면 담긴 곡 [builtInMusic]; 둘 다 null이면 없음.
 * 파일을 고르면 [builtInMusic]은 null이 된다.
 */
data class VideoOptions(
    val aspect: Aspect = Aspect.PORTRAIT,
    val length: VideoLength = VideoLength.NORMAL,
    val photos: Boolean = true,
    val hud: Boolean = true,
    val coloring: RouteColoring = RouteColoring.DEFAULT,
    val musicUri: String? = null,
    val builtInMusic: BuiltInMusic? = BuiltInMusic.SUNNY_PEDAL,
)

/** 영상에 깔 음악의 URI: 고른 파일, 없으면 앱 안의 곡([packageName]의 `raw/`), 둘 다 없으면 null. */
fun VideoOptions.musicSource(packageName: String): String? =
    musicUri ?: builtInMusic?.let { "android.resource://$packageName/raw/${it.rawName}" }
