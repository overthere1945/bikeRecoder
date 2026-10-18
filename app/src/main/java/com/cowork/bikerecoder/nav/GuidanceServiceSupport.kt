package com.cowork.bikerecoder.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether [NavigationService] protects guidance from screen-off. When the location foreground service could
 * not start, the guidance screen shows [warning] instead of the failure only being logged.
 */
class GuidanceServiceStatus {
    private val _warning = MutableStateFlow<String?>(null)
    val warning: StateFlow<String?> = _warning.asStateFlow()

    fun foregroundStarted() {
        _warning.value = null
    }

    fun foregroundFailed() {
        _warning.value = MSG_SCREEN_OFF_RISK
    }

    companion object {
        const val MSG_SCREEN_OFF_RISK = "화면이 꺼지면 안내가 멈출 수 있습니다"
    }
}

/**
 * Waits until [speaking] is false, at most [maxWaitMs]: the service keeps the process in the foreground
 * until the last announcement ("목적지에 도착했습니다") has been spoken.
 */
suspend fun awaitVoiceIdle(speaking: StateFlow<Boolean>, maxWaitMs: Long) {
    withTimeoutOrNull(maxWaitMs) { speaking.first { !it } }
}
