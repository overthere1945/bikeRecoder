package com.cowork.bikerecoder.location

import com.cowork.bikerecoder.core.model.LocationFix
import kotlinx.coroutines.flow.Flow

/** 위치 측위 결과 스트림의 공급원. 실기기(Fused)와 GPX 재생(테스트/데모) 구현이 있다. */
interface LocationSource {
    /** collect하는 동안만 위치를 공급하는 cold flow. */
    fun fixes(): Flow<LocationFix>
}
