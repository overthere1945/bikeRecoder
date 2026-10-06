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
 * 엔진 우선순위: 삼성 SMT → Google TTS. 둘 다 한국어를 못 쓰면 `available = false`.
 * 발화는 QUEUE_ADD로 쌓고, 대기열이 비어 있다가 채워질 때 내비게이션 오디오 포커스(MAY_DUCK)를
 * 요청하며 마지막 발화가 끝나면(onDone/onError/onStop) 반환한다.
 * 초기화가 끝나기 전의 [speak]는 보관했다가 초기화 성공 후 재생하고, 실패하면 버린다.
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

    @Volatile
    override var muted: Boolean = false

    private val lock = Any()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var engineIndex = 0
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
        startEngine()
    }

    private fun startEngine() {
        if (engineIndex >= ENGINES.size) {
            failInit()
            return
        }
        // init 콜백이 생성자 반환 전에 불릴 수 있으므로 holder로 인스턴스를 넘긴다.
        val holder = arrayOfNulls<TextToSpeech>(1)
        val created = TextToSpeech(appContext, { status ->
            val instance = holder[0] ?: synchronized(lock) { tts }
            onEngineInit(instance, status)
        }, ENGINES[engineIndex])
        holder[0] = created
        synchronized(lock) {
            if (closed) {
                created.shutdown()
                return
            }
            tts = created
        }
    }

    private fun onEngineInit(instance: TextToSpeech?, status: Int) {
        val usable = status == TextToSpeech.SUCCESS && instance != null && configure(instance)
        var flush: List<String> = emptyList()
        synchronized(lock) {
            if (closed) {
                instance?.shutdown()
                return
            }
            if (usable) {
                ready = true
                _available.value = true
                flush = preInitQueue.toList()
                preInitQueue.clear()
            } else {
                instance?.shutdown()
                tts = null
                engineIndex++
            }
        }
        if (usable) flush.forEach { enqueue(it) } else startEngine()
    }

    private fun configure(engine: TextToSpeech): Boolean {
        val lang = engine.setLanguage(Locale.KOREAN)
        if (lang < TextToSpeech.LANG_AVAILABLE) return false
        engine.setAudioAttributes(audioAttributes)
        engine.setOnUtteranceProgressListener(progressListener)
        return true
    }

    private fun failInit() {
        synchronized(lock) {
            _available.value = false
            preInitQueue.clear()
        }
    }

    override fun speak(text: String) {
        if (muted || text.isBlank()) return
        synchronized(lock) {
            if (closed) return
            if (!ready) {
                // 초기화가 끝났거나(실패 포함) 엔진이 모두 소진됐다면 버린다.
                if (engineIndex < ENGINES.size) preInitQueue.add(text)
                return
            }
        }
        enqueue(text)
    }

    private fun enqueue(text: String) {
        val engine: TextToSpeech
        val id = "nav-${idCounter.incrementAndGet()}"
        synchronized(lock) {
            if (closed || muted) return
            engine = tts ?: return
            if (pendingIds.isEmpty() && !hasFocus) {
                hasFocus = audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_FAILED
            }
            pendingIds.add(id)
        }
        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        if (result == TextToSpeech.ERROR) finished(id)
    }

    private fun finished(utteranceId: String?) {
        if (utteranceId == null) return
        synchronized(lock) {
            if (!pendingIds.remove(utteranceId)) return
            if (pendingIds.isEmpty()) abandonFocusLocked()
        }
    }

    private fun abandonFocusLocked() {
        if (hasFocus) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            hasFocus = false
        }
    }

    override fun shutdown() {
        val engine: TextToSpeech?
        synchronized(lock) {
            if (closed) return
            closed = true
            ready = false
            engine = tts
            tts = null
            preInitQueue.clear()
            pendingIds.clear()
            abandonFocusLocked()
            _available.value = false
        }
        engine?.stop()
        engine?.shutdown()
    }

    private companion object {
        val ENGINES = listOf("com.samsung.SMT", "com.google.android.tts")
    }
}
