package com.cowork.bikerecoder.tts

import kotlinx.coroutines.flow.StateFlow

/** 길 안내 음성 출력. */
interface VoiceOutput {
    /** 한국어 음성 엔진을 쓸 수 있는지. 초기화 전/실패 시 false. */
    val available: StateFlow<Boolean>

    /** 말하는 중이거나 말할 문장이 대기 중이면 true. 마지막 안내가 끝날 때까지 기다리는 데 쓴다. */
    val speaking: StateFlow<Boolean>

    /** true이면 [speak]가 문장을 버린다(모아두지 않음). */
    var muted: Boolean

    fun speak(text: String)
    fun shutdown()
}
