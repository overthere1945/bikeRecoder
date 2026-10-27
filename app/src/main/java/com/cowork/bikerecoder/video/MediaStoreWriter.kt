package com.cowork.bikerecoder.video

import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore

/**
 * 갤러리 저장(스펙 §3.5): 영상은 `Movies/bikeRecoder`, 이미지는 `Pictures/bikeRecoder`. [createPending]으로
 * `IS_PENDING` 1 항목을 만들어 쓰고, 끝나면 [publish](0), 취소·실패면 [delete]. 앱이 만든 파일이라 권한이 필요 없다.
 * 조회에서 MediaStore가 커서 대신 null을 주면 [IllegalStateException] — "없음"으로 읽지 않는다. 블로킹 IO이므로 메인 스레드 밖에서 부른다.
 */
class MediaStoreWriter(private val resolver: ContentResolver) {

    fun createPending(kind: MediaKind, displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, kind.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, kind.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(kind.collection, values)
            ?: throw IllegalStateException("MediaStore did not create $displayName")
    }

    fun publish(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        check(resolver.update(uri, values, null, null) == 1) { "MediaStore did not publish $uri" }
    }

    /** 이미 없으면 아무 일도 하지 않는다(MediaStore는 없는 항목을 지우려 하면 [SecurityException]을 던진다). */
    fun delete(uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (e: SecurityException) {
            if (exists(uri)) throw e
        }
    }

    /** 갤러리에 아직 있는지(만드는 중인 항목 포함). 사용자가 갤러리에서 지운 항목은 false. */
    fun exists(uri: Uri): Boolean =
        query(uri, null).use { it.moveToFirst() }

    /** [kind]의 폴더에 있는 이름들(확장자 포함, 만드는 중·휴지통 항목 포함) — `MediaNaming.unique`에 넘긴다. */
    fun existingNames(kind: MediaKind): Set<String> {
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val names = HashSet<String>()
        query(kind.collection, selection, arrayOf(kind.relativePath), includeTrashed = true).use { c ->
            val col = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (c.moveToNext()) c.getString(col)?.let(names::add)
        }
        return names
    }

    private fun query(uri: Uri, selection: String?, args: Array<String>? = null, includeTrashed: Boolean = false): Cursor {
        val queryArgs = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            if (includeTrashed) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            if (selection != null) {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            }
        }
        return resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), queryArgs, null)
            ?: throw IllegalStateException("MediaStore query failed for $uri")
    }

    private companion object {
        val MediaKind.collection: Uri
            get() = when (this) {
                MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                MediaKind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }

        /** MediaStore가 돌려주는 모양 그대로(끝에 `/`). */
        val MediaKind.relativePath: String
            get() = when (this) {
                MediaKind.VIDEO -> "Movies/bikeRecoder/"
                MediaKind.IMAGE -> "Pictures/bikeRecoder/"
            }

        val MediaKind.mimeType: String
            get() = when (this) {
                MediaKind.VIDEO -> "video/mp4"
                MediaKind.IMAGE -> "image/png"
            }
    }
}
