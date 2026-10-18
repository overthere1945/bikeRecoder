package com.cowork.bikerecoder.location

import com.cowork.bikerecoder.core.model.LocationFix
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 미리 만들어 둔 [LocationFix] 목록을 순서대로 재생하는 [LocationSource].
 *
 * 첫 fix는 즉시, 이후는 원본 시간 간격 / [speedup] 만큼 기다린 뒤 방출한다.
 * 방출되는 fix의 `timeMillis`는 원본 값을 유지한다.
 */
class GpxLocationSource(
    private val fixes: List<LocationFix>,
    private val speedup: Double = 1.0,
) : LocationSource {

    init {
        require(speedup > 0.0) { "speedup must be positive" }
    }

    override fun fixes(): Flow<LocationFix> = flow {
        var previous: LocationFix? = null
        for (fix in fixes) {
            previous?.let { prev ->
                val gapMs = ((fix.timeMillis - prev.timeMillis) / speedup).toLong()
                if (gapMs > 0) delay(gapMs)
            }
            emit(fix)
            previous = fix
        }
    }
}
