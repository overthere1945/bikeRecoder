package com.cowork.bikerecoder.core.activity

/** 1 km 안내를 세는 기준. */
enum class KmBasis {
    /** 그날(이 여행) 합계. 자전거. */
    DAY,

    /** 이 구간의 거리. 시작은 0이고 날짜가 바뀌어도 다시 세지 않는다. 달리기·걷기. */
    SEGMENT,

    /** 1 km 이벤트를 내지 않는다. 등산(시간 기준 안내). */
    NONE,
}

/**
 * 종류별 기록 기준(스펙 §1.3, 카메라는 §5.1). 속도는 m/s, 시간은 밀리초, 거리는 미터.
 * 자전거 값은 V1.8.0 엔진의 기본값과 같다.
 *
 * @property pauseBelowMps 이 속도 미만이 [pauseAfterMs] 이어지면 자동 일시정지.
 * @property resumeAtMps 이 속도 이상이 [resumeAfterMs] 이어지면 다시 시작. 등산은 [Double.POSITIVE_INFINITY](속도로는 재개하지 않음).
 * @property resumeAfterMs 등산은 쓰이지 않으므로 0.
 * @property resumeDistanceM 멈춘 자리에서 이만큼 움직이면 다시 시작.
 * @property maxSpeedMps 앞 점과의 속도가 이것을 넘으면 튐으로 버린다.
 * @property minGapSpeedMps GPS 끊김(10초 넘는 틈)을 이동으로 셀 최소 평균 속도.
 * @property cameraSlowKmh 영상 카메라가 가장 확대되는 속도(km/h). 이하면 가장 가깝게 본다.
 * @property cameraFastKmh 영상 카메라가 가장 멀어지는 속도(km/h). 이상이면 가장 멀리 본다.
 */
data class ActivityProfile(
    val pauseBelowMps: Double,
    val pauseAfterMs: Long,
    val resumeAtMps: Double,
    val resumeAfterMs: Long,
    val resumeDistanceM: Double,
    val maxSpeedMps: Double,
    val minGapSpeedMps: Double,
    val kmBasis: KmBasis,
    val cameraSlowKmh: Double,
    val cameraFastKmh: Double,
) {
    companion object {
        fun of(type: ActivityType): ActivityProfile = when (type) {
            ActivityType.BIKE -> ActivityProfile(
                pauseBelowMps = 3.0 / 3.6,
                pauseAfterMs = 5_000,
                resumeAtMps = 5.0 / 3.6,
                resumeAfterMs = 2_000,
                resumeDistanceM = 15.0,
                maxSpeedMps = 80.0 / 3.6,
                minGapSpeedMps = 3.0 / 3.6,
                kmBasis = KmBasis.DAY,
                cameraSlowKmh = 10.0,
                cameraFastKmh = 30.0,
            )
            ActivityType.RUN -> ActivityProfile(
                pauseBelowMps = 2.0 / 3.6,
                pauseAfterMs = 5_000,
                resumeAtMps = 4.0 / 3.6,
                resumeAfterMs = 2_000,
                resumeDistanceM = 10.0,
                maxSpeedMps = 40.0 / 3.6,
                minGapSpeedMps = 2.0 / 3.6,
                kmBasis = KmBasis.SEGMENT,
                cameraSlowKmh = 6.0,
                cameraFastKmh = 15.0,
            )
            ActivityType.WALK -> ActivityProfile(
                pauseBelowMps = 1.0 / 3.6,
                pauseAfterMs = 10_000,
                resumeAtMps = 2.5 / 3.6,
                resumeAfterMs = 3_000,
                resumeDistanceM = 10.0,
                maxSpeedMps = 40.0 / 3.6,
                minGapSpeedMps = 1.0 / 3.6,
                kmBasis = KmBasis.SEGMENT,
                cameraSlowKmh = 2.0,
                cameraFastKmh = 6.0,
            )
            ActivityType.HIKE -> ActivityProfile(
                pauseBelowMps = 0.7 / 3.6,
                pauseAfterMs = 20_000,
                resumeAtMps = Double.POSITIVE_INFINITY,
                resumeAfterMs = 0,
                resumeDistanceM = 10.0,
                maxSpeedMps = 40.0 / 3.6,
                minGapSpeedMps = 0.7 / 3.6,
                kmBasis = KmBasis.NONE,
                cameraSlowKmh = 2.0,
                cameraFastKmh = 6.0,
            )
        }
    }
}
