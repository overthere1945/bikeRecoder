package com.cowork.bikerecoder.share.cards

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.trip.TripLabelStore
import com.cowork.bikerecoder.core.video.CardSize
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.post.PhotoThumbnails
import com.cowork.bikerecoder.share.ShareCache
import com.cowork.bikerecoder.share.ShareFile
import com.cowork.bikerecoder.video.SatelliteCheck
import com.cowork.bikerecoder.video.SatelliteTileSource
import com.cowork.bikerecoder.video.VideoBackend
import com.cowork.bikerecoder.video.VideoInputs
import com.cowork.bikerecoder.video.VideoInputsLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** The cards drawn over a trip (spec §2.2, §2.3): the satellite card and the photo card. */
enum class CardKind { SATELLITE, PHOTO }

/**
 * Draws the share sheet's cards into the share cache (spec §3) and hands them out as [ShareFile]s. A card is drawn again
 * on every call (the sheet keeps what it was given). Failures throw: [PhotoUnreadableException] when the photo cannot be
 * read (spec §6: the photo leaves the band), [com.cowork.bikerecoder.share.NoSpaceException] when the cache is full, any
 * other exception when drawing failed.
 */
interface ShareCardMaker {
    /**
     * The [kind] card of trip [tripId] at [size] ([CardSize.FEED] or [CardSize.STORY]); [photoMediaId] is the photo
     * card's background (required for [CardKind.PHOTO], ignored otherwise).
     */
    suspend fun card(tripId: Long, kind: CardKind, size: CardSize, photoMediaId: Long?): ShareFile

    /** The photo slide (spec §2.4) of photo [mediaId] at [size]. */
    suspend fun slide(tripId: Long, mediaId: Long, size: CardSize): ShareFile

    /** The transparent sticker (spec §2.5, §5.6). */
    suspend fun sticker(tripId: Long): ShareFile
}

/** Photo [mediaId] could not be read (deleted, broken; spec §6). */
class PhotoUnreadableException(val mediaId: Long) : Exception("Could not read photo $mediaId")

/**
 * [ShareCardMaker] through [ShareCardRenderer] (spec §3). It keeps the last trip's [VideoInputs] and [CardFacts] ([load],
 * [region]) and its renderer, whose satellite source is decided once per trip by [satelliteAt] at the first point (as
 * [com.cowork.bikerecoder.video.FeedThumbnailRenderer]: the start check OK → the satellite map; else, or when the check
 * throws, null → the plain map). Photos come from [photo] (software, upright, at most [PHOTO_MAX_PX]; the bitmap is
 * the decoder's and is not recycled here); the photo card marks where the photo was taken, the timeline point at its
 * [com.cowork.bikerecoder.core.video.PhotoCue.moveMs]. Cards and slides are written as JPEG [JPEG_QUALITY], the sticker
 * as PNG, through [cache] as `<mode>-<item>` (spec §3.2): `feed-satellite.jpg`, `story-photocard-<mediaId>.jpg`,
 * `feed-photo-<mediaId>.jpg`, `sticker.png`. Drawing and encoding run on [drawOn], writing on [Dispatchers.IO].
 */
class AndroidShareCardMaker(
    private val cache: ShareCache,
    private val load: suspend (tripId: Long) -> VideoInputs?,
    private val region: suspend (tripId: Long) -> String?,
    private val satelliteAt: suspend (start: GeoPoint) -> TileSource?,
    private val renderer: (satellite: TileSource?) -> ShareCardRenderer,
    private val photo: suspend (mediaId: Long) -> Bitmap?,
    private val drawOn: CoroutineDispatcher = Dispatchers.Default,
) : ShareCardMaker {

    constructor(
        context: Context,
        cache: ShareCache,
        inputs: VideoInputsLoader,
        labels: TripLabelStore,
        backend: VideoBackend,
        plainTiles: TileSource,
        satelliteKey: String,
        photos: PhotoThumbnails,
    ) : this(
        cache = cache,
        load = inputs::load,
        region = { labels.label(it).regionName },
        satelliteAt = { start ->
            if (backend.probeSatellite(start) == SatelliteCheck.OK) SatelliteTileSource(plainTiles, satelliteKey) else null
        },
        renderer = { satellite -> ShareCardRenderer(context, satellite, plainTiles) },
        photo = { mediaId -> photos.full(mediaId, PHOTO_MAX_PX, software = true)?.asAndroidBitmap() },
    )

    private class Prepared(val tripId: Long, val inputs: VideoInputs, val facts: CardFacts, val renderer: ShareCardRenderer)

    private val mutex = Mutex()
    private var last: Prepared? = null

    /** Photo slides draw no map: one renderer without sources for them all. */
    private val slides: ShareCardRenderer by lazy { renderer(null) }

    override suspend fun card(tripId: Long, kind: CardKind, size: CardSize, photoMediaId: Long?): ShareFile {
        val mode = modeOf(size)
        return when (kind) {
            CardKind.SATELLITE -> {
                val trip = prepared(tripId)
                write(tripId, "$mode-satellite.jpg", Format.JPEG) { trip.renderer.satellite(trip.inputs, trip.facts, size) }
            }
            CardKind.PHOTO -> {
                val mediaId = requireNotNull(photoMediaId) { "The photo card needs a photo" }
                val trip = prepared(tripId)
                val image = photoOf(mediaId)
                val takenAt = trip.inputs.photos.firstOrNull { it.mediaId == mediaId }
                    ?.let { trip.inputs.timeline.at(it.moveMs).point }
                write(tripId, "$mode-photocard-$mediaId.jpg", Format.JPEG) {
                    trip.renderer.photoCard(trip.inputs, trip.facts, size, image, takenAt)
                }
            }
        }
    }

    override suspend fun slide(tripId: Long, mediaId: Long, size: CardSize): ShareFile {
        val mode = modeOf(size)
        val image = photoOf(mediaId)
        return write(tripId, "$mode-photo-$mediaId.jpg", Format.JPEG) { slides.photoSlide(image, size) }
    }

    override suspend fun sticker(tripId: Long): ShareFile {
        val trip = prepared(tripId)
        return write(tripId, STICKER_NAME, Format.PNG) { trip.renderer.sticker(trip.inputs, trip.facts) }
    }

    /** The trip's inputs, facts and renderer: the kept ones when it is the last trip, else loaded (and kept). */
    private suspend fun prepared(tripId: Long): Prepared = mutex.withLock {
        last?.takeIf { it.tripId == tripId }?.let { return@withLock it }
        val inputs = load(tripId) ?: throw IllegalStateException("Trip $tripId has nothing to draw")
        val facts = cardFacts(inputs, region(tripId))
        val satellite = try {
            satelliteAt(inputs.timeline.points.first().point)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // As a failed start check: the plain map (spec §6, not an error).
        }
        Prepared(tripId, inputs, facts, renderer(satellite)).also { last = it }
    }

    private suspend fun photoOf(mediaId: Long): Bitmap = photo(mediaId) ?: throw PhotoUnreadableException(mediaId)

    /** Draws a bitmap (recycled here), encodes it as [format] and writes it to the trip's [name] in the cache. */
    private suspend fun write(tripId: Long, name: String, format: Format, draw: suspend () -> Bitmap): ShareFile {
        val bytes = withContext(drawOn) {
            val bitmap = draw()
            try {
                val out = ByteArrayOutputStream()
                check(bitmap.compress(format.compress, format.quality, out)) { "Could not encode $name" }
                out.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
        return withContext(Dispatchers.IO) {
            val file = cache.fileFor(tripId, name)
            cache.write(file, bytes)
            cache.share(file, format.mimeType)
        }
    }

    private enum class Format(val compress: Bitmap.CompressFormat, val quality: Int, val mimeType: String) {
        JPEG(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, "image/jpeg"),
        PNG(Bitmap.CompressFormat.PNG, 100, "image/png"),
    }

    companion object {
        /** JPEG quality of cards and photo slides (spec §3.2). */
        const val JPEG_QUALITY = 92

        /** The long side photos are decoded at, px. */
        const val PHOTO_MAX_PX = 2048

        const val STICKER_NAME = "sticker.png"

        /** The file name's mode: "feed" or "story"; a sticker is no card mode. */
        private fun modeOf(size: CardSize): String = when (size) {
            CardSize.FEED -> "feed"
            CardSize.STORY -> "story"
            CardSize.STICKER -> throw IllegalArgumentException("No card at the sticker's size")
        }
    }
}
