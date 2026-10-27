package com.cowork.bikerecoder.post

import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HrRead

enum class HealthAvailability { AVAILABLE, NOT_INSTALLED, UPDATE_REQUIRED }

/** 워치 심박의 출처. */
interface HeartRateSource {
    fun availability(): HealthAvailability

    /** [permissions]가 모두 허용되어 있는지. Health Connect를 쓸 수 없으면 false. */
    suspend fun hasPermissions(): Boolean

    /** 요청할 Health Connect 읽기 권한(심박·운동·총 칼로리). */
    val permissions: Set<String>

    /**
     * [from, to] 의 심박 샘플(시간순)과 겹치는 운동(종류와 관계없이)의 칼로리.
     * 샘플이 없으면 [HrRead.Empty](빈 [HrRead.Samples]는 돌려주지 않는다),
     * Health Connect 없음·권한 없음·오류는 [HrRead.Failed].
     */
    suspend fun read(from: Long, to: Long): HrRead
}

/** 출처 계약대로 만든다: 샘플이 없으면 [HrRead.Empty], 있으면 시간순으로 정렬한 [HrRead.Samples]. */
fun hrReadOf(samples: List<HeartRateSample>, calories: Double?): HrRead =
    if (samples.isEmpty()) HrRead.Empty else HrRead.Samples(samples.sortedBy { it.timeMillis }, calories)
