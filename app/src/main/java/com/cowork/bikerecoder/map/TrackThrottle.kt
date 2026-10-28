package com.cowork.bikerecoder.map

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest

/**
 * The first value at once, then the latest value at most once per [periodMs] (values in between are dropped; the last
 * one is always delivered, so what is drawn ends up matching the source) — except that a value for which [immediate]
 * (last value delivered, this value) is true goes out at once, and the period counts from it. [now] is the clock the
 * period is measured on (milliseconds).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> Flow<T>.atMostEveryUnless(
    periodMs: Long,
    now: () -> Long,
    immediate: (last: T, next: T) -> Boolean,
): Flow<T> {
    var sent = false
    var last: T? = null
    var sentAt = 0L
    return transformLatest { next ->
        @Suppress("UNCHECKED_CAST")
        if (sent && !immediate(last as T, next)) delay((sentAt + periodMs - now()).coerceIn(0L, periodMs))
        emit(next)
        sent = true
        last = next
        sentAt = now()
    }
}
