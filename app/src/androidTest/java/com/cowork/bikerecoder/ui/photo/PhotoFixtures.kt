package com.cowork.bikerecoder.ui.photo

import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.PhotoPositionSource
import com.cowork.bikerecoder.post.FakePhotoSource
import com.cowork.bikerecoder.post.PhotoThumbnails
import com.cowork.bikerecoder.post.RidePhoto
import java.time.ZoneId
import java.time.ZonedDateTime

/** Thumbnails over the fake gallery: its content:// URIs have no provider, so every preview stays grey. */
internal fun fakeThumbnails() = PhotoThumbnails(
    InstrumentationRegistry.getInstrumentation().targetContext.contentResolver,
    FakePhotoSource(),
    warnLog = { _, _ -> },
)

/** 2026-10-18 at [hour]:[minute] in Seoul. */
internal fun seoul(hour: Int, minute: Int): Long =
    ZonedDateTime.of(2026, 10, 18, hour, minute, 0, 0, ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()

internal fun ridePhoto(id: Long, takenAt: Long, hidden: Boolean = false) = RidePhoto(
    id = id,
    segmentId = 1,
    mediaId = 100 + id,
    takenAt = takenAt,
    point = GeoPoint(37.5, 127.0),
    source = PhotoPositionSource.TRACK,
    hidden = hidden,
)
