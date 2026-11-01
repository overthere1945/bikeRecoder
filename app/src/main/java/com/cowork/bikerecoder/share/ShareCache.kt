package com.cowork.bikerecoder.share

import java.io.File
import java.io.IOException

/**
 * The share cards' files (spec §3.2): `root/<tripId>/<name>` under the app cache (`cacheDir/share/`), handed out through
 * the FileProvider ([uriOf]: the file's content uri). [sweep] runs when the share sheet opens and deletes the files last
 * modified over [MAX_AGE_MS] ago by [now]; nothing is deleted when the sheet closes (the receiving app may read late).
 * Blocking IO: call off the main thread.
 */
class ShareCache(
    private val root: File,
    private val uriOf: (File) -> String,
    private val now: () -> Long,
) {

    /** `root/<tripId>/<name>`, its directory made. */
    fun fileFor(tripId: Long, name: String): File {
        val dir = File(root, tripId.toString())
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot make $dir")
        return File(dir, name)
    }

    /**
     * Writes [bytes] as [file] whole: into a temporary file beside it, then renamed, so a receiving app never reads half a
     * card. A write that fails for want of space throws [NoSpaceException].
     */
    fun write(file: File, bytes: ByteArray) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) throw IOException("cannot rename $temp to $file")
        } catch (e: IOException) {
            temp.delete()
            throw noSpaceOr(e)
        }
    }

    /** [file] as a [ShareFile] of [mimeType] with its length (null when it is not there). */
    fun share(file: File, mimeType: String): ShareFile =
        ShareFile(uriOf(file), mimeType, file.takeIf { it.isFile }?.length())

    /** Deletes the files last modified over [MAX_AGE_MS] ago, then the trip directories left empty. */
    fun sweep() {
        val limit = now() - MAX_AGE_MS
        val dirs = root.listFiles() ?: return
        for (dir in dirs) {
            if (!dir.isDirectory) {
                if (dir.lastModified() < limit) dir.delete()
                continue
            }
            dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < limit) it.delete() }
            if (dir.list()?.isEmpty() == true) dir.delete()
        }
    }

    companion object {
        /** A file older than this is swept (spec §3.2: 24 h). */
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}

/** A card could not be written for want of storage (spec §6: "저장 공간이 부족해 카드를 만들지 못했습니다"). */
class NoSpaceException(cause: IOException) : IOException(cause.message, cause)

/** [e] as a [NoSpaceException] when it or its cause says ENOSPC / "No space left"; else [e] itself. */
fun noSpaceOr(e: IOException): IOException {
    val noSpace = generateSequence<Throwable>(e) { it.cause }.take(MAX_CAUSES).any { t ->
        t.message?.let { "ENOSPC" in it || NO_SPACE in it } == true
    }
    return if (noSpace && e !is NoSpaceException) NoSpaceException(e) else e
}

private const val NO_SPACE = "No space left"
private const val MAX_CAUSES = 8
