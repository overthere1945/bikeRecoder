package com.cowork.bikerecoder.location

import com.cowork.bikerecoder.core.model.LocationFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformWhile

/**
 * 위로는 [upstream]을 한 번만 수집하고 여러 구독자에게 나눠 준다. 늦게 온 구독자는 마지막 위치 1개부터 받는다(P4, replay = 1).
 * 구독자가 0이 되면 바로 멈추고 그 마지막 위치도 버리며(오래된 위치를 주지 않음), 다시 구독하면 새로 시작한다.
 * 위쪽이 끝나면 모든 구독자 flow가 정상 종료하고, 실패하면 모든 구독자 flow가 같은 예외로 끝난다.
 *
 * [upstream]은 (재)시작할 때마다 새 [LocationSource]를 받아 온다. 위쪽의 실패는 공유 flow 안에서 항목으로 바뀌어 구독자에게 전해지므로
 * [scope]로 새지 않는다.
 */
class SharedLocationSource(upstream: () -> LocationSource, scope: CoroutineScope) : LocationSource {

    private sealed interface Item {
        data class Fix(val fix: LocationFix) : Item
        data object End : Item
        data class Failed(val error: Throwable) : Item
    }

    private val shared: Flow<Item> = flow<Item> {
        upstream().fixes().collect { emit(Item.Fix(it)) }
        emit(Item.End)
    }
        .catch { emit(Item.Failed(it)) } // 취소는 catch가 건드리지 않는다.
        .shareIn(
            scope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0),
            replay = 1,
        )

    override fun fixes(): Flow<LocationFix> = flow {
        shared.transformWhile { item ->
            when (item) {
                is Item.Fix -> {
                    emit(item.fix)
                    true
                }
                Item.End -> false
                is Item.Failed -> throw item.error
            }
        }.collect { emit(it) }
    }
}
