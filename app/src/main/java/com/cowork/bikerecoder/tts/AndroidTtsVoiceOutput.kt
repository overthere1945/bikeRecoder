package com.cowork.bikerecoder.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android [TextToSpeech] 기반 한국어 [VoiceOutput].
 *
 * 엔진 우선순위: 삼성 SMT → Google TTS ([TtsEngineSelector]). 둘 다 한국어를 못 쓰면 `available = false`.
 * 발화는 QUEUE_ADD로 쌓고, 대기열이 비어 있다가 채워질 때 내비게이션 오디오 포커스(MAY_DUCK)를
 * 요청하며 마지막 발화가 끝나면(onDone/onError/onStop) 반환한다.
 * 초기화가 끝나기 전의 [speak]는 보관했다가 초기화 성공 후 순서대로 재생하고, 실패하면 버린다.
 */
class AndroidTtsVoiceOutput(context: Context) : VoiceOutput {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes)
        .build()

    private val _available = MutableStateFlow(false)
    override val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    @Volatile
    override var muted: Boolean = false

    private val lock = Any()
    private var engine: TtsEngineHandle? = null
    private var selecting = true
    private var closed = false
    private val preInitQueue = ArrayList<String>()
    private val pendingIds = HashSet<String>()
    private var hasFocus = false
    private val idCounter = AtomicLong()

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) = finished(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finished(utteranceId)
        override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
    }

    init {
        TtsEngineSelector(
            engines = ENGINES,
            factory = { name, onInit ->
                // init 콜백이 생성자 안에서 동기 호출돼도 selector가 처리한다.
                val tts = TextToSpeech(appContext, { status -> onInit(status == TextToSpeech.SUCCESS) }, name)
                TextToSpeechHandle(tts, audioAttributes, progressListener)
            },
            onSelected = ::onEngineSelected,
        ).start()
    }

    private fun onEngineSelected(selected: TtsEngineHandle?) {
        synchronized(lock) {
            selecting = false
            if (closed || selected == null) {
                selected?.shutdown()
                preInitQueue.clear()
                updateSpeakingLocked()
                return
            }
            engine = selected
            _available.value = true
            // 락 안에서 flush해 동시에 들어오는 speak()보다 항상 먼저 재생되도록 한다.
            val queued = preInitQueue.toList()
            preInitQueue.clear()
            queued.forEach { enqueueLocked(it) }
            updateSpeakingLocked()
        }
    }

    override fun speak(text: String) {
        if (muted || text.isBlank()) return
        synchronized(lock) {
            if (closed) return
            when {
                engine != null -> enqueueLocked(text)
                selecting -> preInitQueue.add(text)
                // 모든 엔진 실패: 버린다.
            }
            updateSpeakingLocked()
        }
    }

    private fun enqueueLocked(text: String) {
        val e = engine ?: return
        if (closed || muted) return
        val id = "nav-${idCounter.incrementAndGet()}"
        if (pendingIds.isEmpty() && !hasFocus) {
            hasFocus = audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        pendingIds.add(id)
        if (!e.speak(text, id)) finishedLocked(id)
    }

    private fun finished(utteranceId: String?) {
        if (utteranceId == null) return
        synchronized(lock) { finishedLocked(utteranceId) }
    }

    private fun finishedLocked(utteranceId: String) {
        if (!pendingIds.remove(utteranceId)) return
        if (pendingIds.isEmpty()) abandonFocusLocked()
        updateSpeakingLocked()
    }

    private fun updateSpeakingLocked() {
        _speaking.value = pendingIds.isNotEmpty() || preInitQueue.isNotEmpty()
    }

    private fun abandonFocusLocked() {
        if (hasFocus) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            hasFocus = false
        }
    }

    override fun shutdown() {
        val toStop: TtsEngineHandle?
        synchronized(lock) {
            if (closed) return
            closed = true
            toStop = engine
            engine = null
            preInitQueue.clear()
            pendingIds.clear()
            abandonFocusLocked()
            updateSpeakingLocked()
            _available.value = false
        }
        toStop?.stop()
        toStop?.shutdown()
    }

    private class TextToSpeechHandle(
        private val tts: TextToSpeech?,
        private val audioAttributes: AudioAttributes,
        private val listener: UtteranceProgressListener,
    ) : TtsEngineHandle {
        override fun configureKorean(): Boolean {
            val t = tts ?: return false
            if (t.setLanguage(Locale.KOREAN) < TextToSpeech.LANG_AVAILABLE) return false
            t.setAudioAttributes(audioAttributes)
            t.setOnUtteranceProgressListener(listener)
            return true
        }

        override fun speak(text: String, utteranceId: String): Boolean =
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId) != TextToSpeech.ERROR

        override fun stop() {
            tts?.stop()
        }

        override fun shutdown() {
            tts?.shutdown()
        }
    }

    private companion object {
        val ENGINES = listOf("com.samsung.SMT", "com.google.android.tts")
    }
}
