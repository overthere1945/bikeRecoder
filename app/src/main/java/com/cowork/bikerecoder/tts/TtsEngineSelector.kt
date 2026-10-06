package com.cowork.bikerecoder.tts

/** 한 개의 TTS 엔진 인스턴스. 실제 구현은 TextToSpeech 래퍼, 테스트에서는 가짜. */
internal interface TtsEngineHandle {
    /** 한국어 설정·오디오 속성·진행 리스너 등록. 한국어를 쓸 수 없으면 false. */
    fun configureKorean(): Boolean
    /** QUEUE_ADD로 발화. 즉시 실패하면 false. */
    fun speak(text: String, utteranceId: String): Boolean
    fun stop()
    fun shutdown()
}

/**
 * 엔진을 우선순위대로 시도해 한국어가 되는 첫 엔진을 고른다.
 *
 * [factory]는 엔진 이름과 init 콜백(성공 여부)을 받아 핸들을 만든다. 콜백은 factory가 반환하기 전에
 * (생성자 안에서 동기적으로) 불릴 수도 있으므로, 이 경우 결과를 보관했다가 factory 반환 후에 처리한다.
 * 실패한 시도의 핸들은 항상 shutdown하며, 시도마다 한 번만 결정된다.
 * 결과는 [onSelected]로 정확히 한 번 전달된다(모두 실패하면 null).
 */
internal class TtsEngineSelector(
    private val engines: List<String>,
    private val factory: (engine: String, onInit: (Boolean) -> Unit) -> TtsEngineHandle,
    private val onSelected: (TtsEngineHandle?) -> Unit,
) {
    fun start() = attempt(0)

    private fun attempt(index: Int) {
        if (index >= engines.size) {
            onSelected(null)
            return
        }
        val lock = Any()
        var handle: TtsEngineHandle? = null
        var earlyResult: Boolean? = null
        var settled = false

        fun settle(h: TtsEngineHandle, initOk: Boolean) {
            synchronized(lock) {
                if (settled) return
                settled = true
            }
            if (initOk && h.configureKorean()) {
                onSelected(h)
            } else {
                h.shutdown()
                attempt(index + 1)
            }
        }

        val created = factory(engines[index]) { ok ->
            val h = synchronized(lock) {
                handle ?: run { earlyResult = ok; null }
            }
            if (h != null) settle(h, ok)
        }
        val early = synchronized(lock) {
            handle = created
            earlyResult
        }
        if (early != null) settle(created, early)
    }
}
