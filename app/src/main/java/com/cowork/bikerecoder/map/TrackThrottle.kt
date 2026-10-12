package com.cowork.bikerecoder.map

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform

/**
 * The first value at once, then the latest value at most once per [periodMs] (values in between are dropped). The
 * last value is always delivered, so what is drawn ends up matching the source.
 */
internal fun <T> Flow<T>.atMostEvery(periodMs: Long): Flow<T> = conflate().transform {
    emit(it)
    delay(periodMs)
}
