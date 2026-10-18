package com.cowork.bikerecoder.post

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.PhotoCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 갤러리(MediaStore)의 카메라 사진. 조회가 실패하면 예외를 그대로 던진다(후처리가 다음 실행 때 다시 찾는다).
 * MediaStore가 커서 대신 null을 주어도(공급자에 닿지 못함) [IllegalStateException] — "사진 없음"이나 "모두 지워짐"으로 읽지 않는다.
 */
class MediaStorePhotoSource internal constructor(
    context: Context,
    private val warnLog: WarnLog,
    /** `ContentResolver.query(uri, projection, selection, args, null)`. 테스트가 null 커서를 흉내 내려고 바꾼다. */
    private val query: (uri: Uri, projection: Array<String>, selection: String, args: Array<String>) -> Cursor?,
) : PhotoSource {
    constructor(context: Context, warnLog: WarnLog = androidWarnLog("MediaStorePhotos")) : this(
        context,
        warnLog,
        { uri, projection, selection, args ->
            context.applicationContext.contentResolver.query(uri, projection, selection, args, null)
        },
    )

    private val context = context.applicationContext
    private val collection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    override fun access(): PhotoAccess = photoAccessOf(Build.VERSION.SDK_INT, ::granted)

    override suspend fun photos(from: Long, to: Long): List<PhotoCandidate> = withContext(Dispatchers.IO) {
        if (access() == PhotoAccess.NONE) return@withContext emptyList()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        // DATE_ADDED는 초 단위라 넉넉히 거르고, 정확한 창은 아래에서 다시 거른다.
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND (" +
            "(${MediaStore.Images.Media.DATE_TAKEN} BETWEEN ? AND ?) OR " +
            "((${MediaStore.Images.Media.DATE_TAKEN} IS NULL OR ${MediaStore.Images.Media.DATE_TAKEN} <= 0) " +
            "AND ${MediaStore.Images.Media.DATE_ADDED} BETWEEN ? AND ?))"
        val args = arrayOf(
            CAMERA_RELATIVE_PATH,
            from.toString(),
            to.toString(),
            Math.floorDiv(from, 1000L).toString(),
            Math.floorDiv(to, 1000L).toString(),
        )
        val rows = ArrayList<Pair<Long, Long>>()
        queryOrThrow(collection, projection, selection, args).use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val taken = if (c.isNull(takenCol)) null else c.getLong(takenCol)
                val takenAt = photoTakenAt(taken, c.getLong(addedCol))
                if (takenAt in from..to) rows += c.getLong(idCol) to takenAt
            }
        }
        val withLocation = granted(Manifest.permission.ACCESS_MEDIA_LOCATION)
        rows.sortedWith(compareBy({ it.second }, { it.first }))
            .map { (id, takenAt) -> PhotoCandidate(id, takenAt, if (withLocation) exifLocation(id) else null) }
    }

    override suspend fun existing(mediaIds: Collection<Long>): Set<Long> = withContext(Dispatchers.IO) {
        if (mediaIds.isEmpty()) return@withContext emptySet()
        if (access() != PhotoAccess.FULL) return@withContext mediaIds.toSet()
        val found = HashSet<Long>()
        // API 30 SQLite allows at most 999 bind arguments.
        for (chunk in mediaIds.distinct().chunked(500)) {
            val selection = "${MediaStore.Images.Media._ID} IN (${chunk.joinToString(",") { "?" }})"
            val args = chunk.map { it.toString() }.toTypedArray()
            queryOrThrow(collection, arrayOf(MediaStore.Images.Media._ID), selection, args)
                .use { c -> while (c.moveToNext()) found += c.getLong(0) }
        }
        found
    }

    override fun uri(mediaId: Long): Uri = ContentUris.withAppendedId(collection, mediaId)

    /** EXIF GPS 위치(원본에서 읽음). 없거나 읽지 못하면 null. */
    private fun exifLocation(mediaId: Long): GeoPoint? = try {
        context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri(mediaId)))?.use { input ->
            val latLong = FloatArray(2)
            if (ExifInterface(input).getLatLong(latLong)) GeoPoint(latLong[0].toDouble(), latLong[1].toDouble()) else null
        }
    } catch (e: Exception) {
        warnLog.warn("EXIF location unreadable for media $mediaId", e)
        null
    }

    private fun queryOrThrow(uri: Uri, projection: Array<String>, selection: String, args: Array<String>): Cursor =
        query(uri, projection, selection, args) ?: throw IllegalStateException("MediaStore query returned null for $uri")

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** 카메라가 찍은 사진만(화면 캡처·다운로드·메신저 사진 제외). */
        const val CAMERA_RELATIVE_PATH = "DCIM/Camera/"
    }
}

/**
 * [sdkInt]에서 [granted] 권한으로 얻는 사진 접근. Android 13+는 `READ_MEDIA_IMAGES`(모든 사진),
 * Android 14+는 `READ_MEDIA_VISUAL_USER_SELECTED`만 있으면 선택한 사진만, Android 11–12는 `READ_EXTERNAL_STORAGE`(모든 사진).
 */
internal fun photoAccessOf(sdkInt: Int, granted: (String) -> Boolean): PhotoAccess = when {
    granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.FULL
    sdkInt <= Build.VERSION_CODES.S_V2 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> PhotoAccess.FULL
    sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.PARTIAL
    else -> PhotoAccess.NONE
}

/** 찍은 시각: `DATE_TAKEN`(ms), 없거나 0 이하이면 `DATE_ADDED`(초) × 1000. */
internal fun photoTakenAt(dateTaken: Long?, dateAddedSeconds: Long): Long =
    if (dateTaken != null && dateTaken > 0) dateTaken else dateAddedSeconds * 1000L
