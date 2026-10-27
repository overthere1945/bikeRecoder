package com.cowork.bikerecoder.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import androidx.compose.ui.graphics.asAndroidBitmap
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.Camera
import com.cowork.bikerecoder.core.video.FramePlan
import com.cowork.bikerecoder.core.video.VideoOptions
import com.cowork.bikerecoder.core.video.VideoTimeline
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.post.PhotoThumbnails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Music made ready for the video ([VideoSink.addAudio]); opaque to [VideoJob]. */
interface MusicTrack

/**
 * The video file being made: the encoder (1080, or 720 when the phone cannot configure 1080) and the frame renderer
 * built at the encoder's final size. Order: [addAudio] → [frame] × N → [finish]; [close] always (the encoder is closed
 * there, never while a frame is drawn).
 */
interface VideoSink : AutoCloseable {
    val width: Int
    val height: Int

    /** Made at 720p because 1080 could not be configured. */
    val downscaled: Boolean

    /** Before the first [frame]; null = no music. */
    fun addAudio(music: MusicTrack?)

    /** Draws [plan] seen from [camera] and encodes it as the next frame. */
    suspend fun frame(plan: FramePlan, camera: Camera)

    fun finish()
}

/** What [VideoJob] does through Android (storage, gallery, music, encoder, renderers); a fake in the JVM test. */
interface VideoBackend {
    /** Free bytes on the shared storage the gallery writes to. */
    fun freeBytes(): Long

    /** The chosen music cut to [durationUs] and encoded; null when the file cannot be read. */
    suspend fun loadMusic(uri: String, durationUs: Long): MusicTrack?

    suspend fun existingNames(kind: MediaKind): Set<String>

    /** A new `IS_PENDING` gallery item; its uri. */
    suspend fun createPending(kind: MediaKind, displayName: String): String

    suspend fun publish(uri: String)

    /** Nothing happens when it is already gone. */
    suspend fun delete(uri: String)

    /** Opens the encoder writing into gallery item [uri], then the renderer at the encoder's size. */
    suspend fun openVideo(uri: String, inputs: VideoInputs, options: VideoOptions): VideoSink

    /** Renders the summary image of [inputs] (up to 3 photos) and writes it as PNG into gallery item [uri]. */
    suspend fun writeImage(uri: String, inputs: VideoInputs, aspect: Aspect)
}

/** The real [VideoBackend]: MediaStore, [VideoEncoder], [AudioTrackMaker], [FrameRenderer], [SummaryImageRenderer]. */
class AndroidVideoBackend(
    private val context: Context,
    private val tileSource: TileSource,
    private val writer: MediaStoreWriter,
    private val thumbnails: PhotoThumbnails,
    private val log: WarnLog = androidWarnLog("VideoBackend"),
) : VideoBackend {

    private val resolver = context.contentResolver

    private class Music(val encoded: AudioTrackMaker.Encoded) : MusicTrack

    override fun freeBytes(): Long {
        // The app's external files dir lives on the same (primary shared) volume as Movies/ and Pictures/.
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return StatFs(dir.path).availableBytes
    }

    override suspend fun loadMusic(uri: String, durationUs: Long): MusicTrack? = try {
        Music(AudioTrackMaker(context).make(Uri.parse(uri), durationUs))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Music $uri cannot be read", e)
        null
    }

    override suspend fun existingNames(kind: MediaKind): Set<String> = withContext(Dispatchers.IO) { writer.existingNames(kind) }

    override suspend fun createPending(kind: MediaKind, displayName: String): String =
        withContext(Dispatchers.IO) { writer.createPending(kind, displayName).toString() }

    override suspend fun publish(uri: String) = withContext(Dispatchers.IO) { writer.publish(Uri.parse(uri)) }

    override suspend fun delete(uri: String) = withContext(Dispatchers.IO) { writer.delete(Uri.parse(uri)) }

    override suspend fun openVideo(uri: String, inputs: VideoInputs, options: VideoOptions): VideoSink = withContext(Dispatchers.IO) {
        // MediaMuxer needs a seekable descriptor: "rw".
        val pfd = resolver.openFileDescriptor(Uri.parse(uri), "rw") ?: throw IllegalStateException("cannot open $uri")
        try {
            val (encoder, downscaled) = VideoEncoder.open(pfd.fileDescriptor, options.aspect)
            try {
                // Built only now: the renderer, like the cameras, takes the encoder's final size (ruling V5).
                val renderer = FrameRenderer(context, tileSource, inputs, options, encoder.width, encoder.height, ::photo)
                EncoderSink(pfd, encoder, downscaled, renderer)
            } catch (e: Throwable) {
                encoder.close()
                throw e
            }
        } catch (e: Throwable) {
            pfd.close()
            throw e
        }
    }

    override suspend fun writeImage(uri: String, inputs: VideoInputs, aspect: Aspect) {
        val chosen = VideoTimeline.choosePhotos(inputs.photos, IMAGE_PHOTOS, inputs.timeline.totalMoveMs)
        val photos = chosen.mapNotNull { photo(it.mediaId) }
        val image = SummaryImageRenderer(context, tileSource).render(inputs, aspect, photos)
        try {
            withContext(Dispatchers.IO) {
                val out = resolver.openOutputStream(Uri.parse(uri), "w") ?: throw IllegalStateException("cannot open $uri")
                out.use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG not written" } }
            }
        } finally {
            image.recycle()
        }
    }

    /** A software bitmap (the frame Canvas is software, ruling V7); null when the photo cannot be read. */
    private suspend fun photo(mediaId: Long): Bitmap? = try {
        thumbnails.full(mediaId, PHOTO_MAX_PX, software = true)?.asAndroidBitmap()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Photo $mediaId cannot be read", e)
        null
    }

    /**
     * The renderer draws on a software Bitmap (its render suspends for the map snapshot), which is then copied into the
     * encoder's frame.
     */
    private class EncoderSink(
        private val pfd: ParcelFileDescriptor,
        private val encoder: VideoEncoder,
        override val downscaled: Boolean,
        private val renderer: FrameRenderer,
    ) : VideoSink {
        override val width: Int get() = encoder.width
        override val height: Int get() = encoder.height

        private val bitmap = Bitmap.createBitmap(encoder.width, encoder.height, Bitmap.Config.ARGB_8888)
        private val canvas = Canvas(bitmap)

        override fun addAudio(music: MusicTrack?) = encoder.addAudio((music as Music?)?.encoded)

        override suspend fun frame(plan: FramePlan, camera: Camera) {
            bitmap.eraseColor(Color.BLACK)
            renderer.render(plan, camera, canvas)
            encoder.drawFrame { it.drawBitmap(bitmap, 0f, 0f, null) }
        }

        override fun finish() = encoder.finish()

        /** Each part is released even if an earlier one throws. */
        override fun close() {
            try {
                renderer.close()
            } finally {
                try {
                    encoder.close()
                } finally {
                    bitmap.recycle()
                    pfd.close()
                }
            }
        }
    }

    private companion object {
        const val PHOTO_MAX_PX = 2048

        /** The summary image's photo row (spec §4). */
        const val IMAGE_PHOTOS = 3
    }
}
