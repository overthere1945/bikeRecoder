package com.cowork.bikerecoder.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.FPS
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 영상 인코딩(스펙 §3.1④, §3.4): H.264(약 8 Mbps, 키프레임 1초)를 MediaCodec 입력 Surface에 그려 만들고 MediaMuxer로
 * [output](읽기·쓰기·탐색 가능한 fd)에 MP4로 쓴다.
 *
 * 순서: [open] → (음악이 있으면) [addAudio] → [drawFrame] × N → [finish]. 언제든 [close]로 정리한다(취소·실패 포함;
 * 그때 파일은 불완전하니 호출자가 지운다). 호출은 겹치지 않게 차례로 한다(블로킹). 부르는 스레드는 바뀌어도 된다.
 * - 그리기: [drawFrame]의 Canvas는 출력 크기의 소프트웨어 Bitmap Canvas다(하드웨어 Bitmap은 그릴 수 없다 — 사진은
 *   소프트웨어로 디코딩해 넘긴다). 그 Bitmap을 OpenGL ES 텍스처로 입력 Surface(EGL)에 그린다. 입력 Surface의
 *   `lockHardwareCanvas`는 쓰지 않는다: S22에서 인코더보다 빨리 넘기면 장을 버리고(48장 중 10~18장만 인코딩),
 *   RenderThread가 비동기로 넘겨 마지막 장들이 EOS 뒤에 닿아 버려졌다. EGL은 `eglSwapBuffers`가 장을 그 자리에서
 *   넘기고 대기열이 차면 기다리므로 빠지는 장이 없다.
 * - 프레임 시각 = 순번 / [fps](`eglPresentationTimeANDROID`). B-프레임은 끈다.
 * - 출력은 콜백 스레드가 받아 쓴다(인코더가 몇 장을 붙잡고 있어도, 출력이 밀려도 그리는 쪽이 막히지 않게).
 * - muxer는 비디오 출력 형식이 나올 때(첫 장 직후) 비디오와 오디오 트랙을 함께 추가하고 시작한다. 그래서 음악은
 *   미리 인코딩해 메모리에 받아 둔 [AudioTrackMaker.Encoded]를 첫 장 전에 넘기고, 비디오 시각에 맞춰 섞어 쓴다.
 */
class VideoEncoder(
    output: FileDescriptor,
    val width: Int,
    val height: Int,
    private val fps: Int = FPS,
    bitRate: Int = 8_000_000,
) : AutoCloseable {

    companion object {
        private const val TAG = "VideoEncoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val KEYFRAME_INTERVAL_S = 1
        private const val FINISH_TIMEOUT_S = 30L

        /** 1080 크기로 열고, 인코더 구성이 실패하면 720×1280 / 1280×720으로 다시 연다. second=true면 720으로 열었다. */
        fun open(fd: FileDescriptor, aspect: Aspect): Pair<VideoEncoder, Boolean> = open(fd, aspect, forceFallback = false)

        /** [forceFallback]이면 1080을 건너뛰고 바로 720으로(기기 테스트용). */
        internal fun open(fd: FileDescriptor, aspect: Aspect, forceFallback: Boolean): Pair<VideoEncoder, Boolean> {
            if (!forceFallback) {
                try {
                    return VideoEncoder(fd, aspect.width, aspect.height) to false
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "${aspect.width}x${aspect.height} not configurable, falling back to 720", e)
                } catch (e: IllegalStateException) { // MediaCodec.CodecException 포함
                    Log.w(TAG, "${aspect.width}x${aspect.height} not configurable, falling back to 720", e)
                } catch (e: IOException) {
                    Log.w(TAG, "${aspect.width}x${aspect.height} not configurable, falling back to 720", e)
                }
            }
            // 1080×1920 → 720×1280, 1920×1080 → 1280×720.
            return VideoEncoder(fd, aspect.width * 2 / 3, aspect.height * 2 / 3) to true
        }
    }

    private enum class State { OPEN, FINISHED, CLOSED }

    private val callbackThread = HandlerThread("VideoEncoder").apply { start() }
    private val codec: MediaCodec
    private val surface: Surface
    private val muxer: MediaMuxer
    private val gl: GlSurface
    private val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val frameCanvas = Canvas(frame)

    private var state = State.OPEN
    private var framesPosted = 0L
    @Volatile private var audio: AudioTrackMaker.Encoded? = null

    // 아래는 콜백 스레드가 쓴다. 호출 스레드는 [endOfStream]을 기다린 뒤에만 읽는다.
    private val endOfStream = CountDownLatch(1)
    @Volatile private var failure: Throwable? = null
    private var audioNext = 0
    private val audioInfo = MediaCodec.BufferInfo()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var framesWritten = 0L

    init {
        val query = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        }
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_S)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        var codec: MediaCodec? = null
        var surface: Surface? = null
        var muxer: MediaMuxer? = null
        try {
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(query)
                ?: throw IllegalArgumentException("no H.264 encoder for ${width}x$height")
            codec = MediaCodec.createByCodecName(name)
            codec.setCallback(OutputCallback(), Handler(callbackThread.looper))
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            gl = GlSurface(surface, width, height)
        } catch (e: Exception) {
            muxer?.release()
            surface?.release()
            codec?.release()
            callbackThread.quitSafely()
            frame.recycle()
            throw e
        }
        this.codec = codec
        this.surface = surface
        this.muxer = muxer
    }

    /** 음악 트랙(null이면 음악 없음). 첫 [drawFrame] 전에 부른다. */
    fun addAudio(samples: AudioTrackMaker.Encoded?) {
        check(state == State.OPEN) { "encoder is $state" }
        check(framesPosted == 0L) { "addAudio must come before the first frame" }
        audio = samples?.takeIf { it.samples.isNotEmpty() }
    }

    /** 한 장을 그린다(검은 바탕에서 시작). 이 장의 시각 = (지금까지 그린 장 수) / fps. 장이 인코더에 들어간 뒤 돌아온다. */
    fun drawFrame(draw: (Canvas) -> Unit) {
        check(state == State.OPEN) { "encoder is $state" }
        throwIfFailed()
        frame.eraseColor(Color.BLACK)
        draw(frameCanvas)
        gl.post(frame, framesPosted * 1_000_000_000L / fps)
        framesPosted++
    }

    /** 남은 출력을 모두 쓰고 MP4를 닫는다. 그 뒤엔 [close]만 부른다. */
    fun finish() {
        check(state == State.OPEN) { "encoder is $state" }
        check(framesPosted > 0) { "no frames drawn" }
        throwIfFailed()
        codec.signalEndOfInputStream()
        check(endOfStream.await(FINISH_TIMEOUT_S, TimeUnit.SECONDS)) { "encoder did not reach end of stream" }
        throwIfFailed()
        if (framesWritten != framesPosted) Log.w(TAG, "posted $framesPosted frames but encoder wrote $framesWritten")
        writeAudioUpTo(Long.MAX_VALUE)
        muxer.stop()
        state = State.FINISHED
        close()
    }

    override fun close() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        gl.release()
        runCatching { codec.stop() }
        codec.release()
        surface.release()
        frame.recycle()
        // 콜백이 muxer를 쓰는 중에 풀지 않도록 콜백 스레드가 끝난 뒤에 푼다.
        callbackThread.quitSafely()
        callbackThread.join(1_000L)
        // 시작한 muxer는 release가 stop을 부르고, stop이 실패하면 네이티브 정리를 건너뛴다 — 한 번 더 부르면 정리된다.
        if (runCatching { muxer.release() }.isFailure) runCatching { muxer.release() }
    }

    private fun throwIfFailed() {
        failure?.let { throw IllegalStateException("video encoding failed", it) }
    }

    private fun fail(t: Throwable) {
        if (failure == null) failure = t
        endOfStream.countDown()
    }

    private inner class OutputCallback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // 입력은 Surface로

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (failure != null) return
            try {
                check(!muxerStarted) { "video format changed twice" }
                videoTrack = muxer.addTrack(format)
                audio?.let { audioTrack = muxer.addTrack(it.format) }
                muxer.start()
                muxerStarted = true
            } catch (e: Exception) {
                fail(e)
            }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (failure == null && info.size > 0 && !isConfig) {
                    check(muxerStarted) { "encoder output before its format" }
                    framesWritten++
                    writeAudioUpTo(info.presentationTimeUs)
                    muxer.writeSampleData(videoTrack, codec.getOutputBuffer(index)!!, info)
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) endOfStream.countDown()
            } catch (e: Exception) {
                fail(e)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = fail(e)
    }

    /** 비디오와 섞어 쓰도록 [ptsUs]까지의 오디오 샘플을 쓴다. */
    private fun writeAudioUpTo(ptsUs: Long) {
        val samples = audio?.samples ?: return
        while (audioNext < samples.size && samples[audioNext].ptsUs <= ptsUs) {
            val sample = samples[audioNext++]
            audioInfo.set(0, sample.data.size, sample.ptsUs, sample.flags)
            muxer.writeSampleData(audioTrack, ByteBuffer.wrap(sample.data), audioInfo)
        }
    }
}

/**
 * 인코더 입력 Surface 위의 EGL 창 표면과, Bitmap을 화면 가득 그리는 텍스처 사각형.
 * 컨텍스트는 [post] 동안만 그 스레드에 붙여 두어, 부르는 스레드가 바뀌어도 된다.
 */
private class GlSurface(surface: Surface, private val width: Int, private val height: Int) {
    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() { gl_Position = aPosition; vTexCoord = aTexCoord; }
        """
        const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }
        """
    }

    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val eglSurface: EGLSurface
    private var program = 0
    private var texture = 0
    private var textureAllocated = false
    private var released = false

    // x, y, s, t — Bitmap의 첫 줄(t = 0)이 화면 위(y = 1)에 오게.
    private val quad: FloatBuffer = floatArrayOf(
        -1f, -1f, 0f, 1f,
        1f, -1f, 1f, 1f,
        -1f, 1f, 0f, 0f,
        1f, 1f, 1f, 0f,
    ).let { ByteBuffer.allocateDirect(it.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(it) }

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize ${eglError()}" }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "no recordable EGL config ${eglError()}"
        }
        val config = configs[0]!!
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext ${eglError()}" }
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroyContext(display, context)
            throw IllegalStateException("eglCreateWindowSurface ${eglError()}")
        }
        try {
            current {
                program = buildProgram()
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                texture = ids[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                checkGl("setup")
            }
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    /** [bitmap]을 시각 [ptsNs]의 한 장으로 넘긴다. 인코더 대기열이 차 있으면 빌 때까지 기다린다. */
    fun post(bitmap: Bitmap, ptsNs: Long) = current {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        if (textureAllocated) {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        } else {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            textureAllocated = true
        }
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        quad.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(position)
        quad.position(2)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGl("draw")
        check(EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs)) { "eglPresentationTime ${eglError()}" }
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers ${eglError()}" }
    }

    /** EGL display는 앱 전체가 같이 쓰므로 eglTerminate는 하지 않는다. */
    fun release() {
        if (released) return
        released = true
        runCatching {
            current {
                if (program != 0) GLES20.glDeleteProgram(program)
                if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            }
        }
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
    }

    private inline fun <T> current(block: () -> T): T {
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent ${eglError()}" }
        try {
            return block()
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        }
    }

    private fun buildProgram(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) { "link: ${GLES20.glGetProgramInfoLog(program)}" }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE) { "compile: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun checkGl(what: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "GL error $error during $what" }
    }

    private fun eglError(): String = "0x" + Integer.toHexString(EGL14.eglGetError())
}
