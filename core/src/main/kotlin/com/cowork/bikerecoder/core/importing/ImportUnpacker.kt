package com.cowork.bikerecoder.core.importing

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** 가져오기에서 한 항목을 읽지 못한 이유. 문구는 앱이 정한다. */
enum class ImportProblem { NOT_GPX, NO_TIME, TOO_SHORT, TOO_LARGE, UNREADABLE }

/** 가져오기 제한 값(스펙 §3.1). */
data class ImportLimits(
    /** 파일·항목 하나(풀었을 때)의 최대 바이트. */
    val maxItemBytes: Long = 50_000_000,
    /** ZIP 하나에서 읽을 최대 항목 수(폴더 제외). */
    val maxZipEntries: Int = 2_000,
    /** 한 번에 찾는 최대 주행 수. [ImportUnpacker]는 쓰지 않고 호출하는 쪽이 센다. */
    val maxRides: Int = 500,
)

/** [ImportUnpacker]가 하나씩 넘겨 주는 결과. */
sealed interface ImportItem {
    /** 목록에 보일 이름. ZIP 안이면 "묶음.zip / activities/1.gpx". */
    val name: String

    /** 그 원본 안에서 0부터 매기는 항목 번호(ZIP이 아니면 0). */
    val index: Int

    /** GPX로 판단한 내용. */
    data class Gpx(override val name: String, override val index: Int, val bytes: ByteArray) : ImportItem {
        override fun equals(other: Any?): Boolean =
            other is Gpx && name == other.name && index == other.index && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = (name.hashCode() * 31 + index) * 31 + bytes.contentHashCode()
    }

    /** 읽지 못한 항목(GPX가 아님, 너무 큼, 깨짐). */
    data class Unreadable(override val name: String, override val index: Int, val problem: ImportProblem) : ImportItem

    /** ZIP의 항목 수가 한도를 넘어 [name] 묶음의 나머지는 읽지 않았다. */
    data class TooManyEntries(override val name: String, override val index: Int = -1) : ImportItem
}

/**
 * 고른 파일을 내용으로 판단해 풀고(스펙 §3.1) GPX 바이트를 하나씩 [ImportUnpacker.visit]의 visitor에 넘긴다.
 * - `PK\x03\x04`는 ZIP: 항목마다 같은 방법으로 판단한다. 폴더는 건너뛰고 ZIP 안의 ZIP은 읽지 않는다.
 * - `\x1f\x8b`는 gzip: 한 번만 풀고, 푼 내용이 GPX가 아니면(gzip·ZIP 포함) GPX가 아닌 것으로 본다.
 * - 그 밖은 앞 4 KB에 `<gpx` 여는 태그가 있으면 GPX. 파일 이름·확장자는 보지 않는다.
 * 큰 파일은 읽는 도중 끊어 한 항목을 [ImportLimits.maxItemBytes]까지만 메모리에 둔다.
 *
 * visitor는 suspend이고 항목 하나를 다 다룬 뒤 돌아온다. 그래서 메모리에는 항목 하나만 있다. visitor가 false를 돌려주면
 * 거기서 읽기를 멈춘다(ZIP의 나머지 항목을 읽지 않는다). 스트림 읽기는 막히는 호출이므로 호출하는 쪽이 IO 디스패처에서 부른다.
 */
object ImportUnpacker {

    private const val SNIFF_BYTES = 4096
    private const val CHUNK = 8 * 1024

    suspend fun visit(
        displayName: String,
        input: InputStream,
        limits: ImportLimits = ImportLimits(),
        visitor: suspend (ImportItem) -> Boolean,
    ) {
        val stream = if (input.markSupported()) input else BufferedInputStream(input)
        // 읽기만 try로 감싼다. 호출한 쪽 visitor의 예외는 그대로 올라가야 한다.
        val head = try {
            peek(stream, 4)
        } catch (_: IOException) {
            visitor(ImportItem.Unreadable(displayName, 0, ImportProblem.UNREADABLE))
            return
        }
        if (isZip(head)) {
            visitZip(displayName, stream, limits, visitor)
            return
        }
        val item = try {
            classify(displayName, 0, readBounded(stream, limits.maxItemBytes), limits)
        } catch (_: IOException) {
            ImportItem.Unreadable(displayName, 0, ImportProblem.UNREADABLE)
        }
        visitor(item)
    }

    private suspend fun visitZip(
        displayName: String,
        input: InputStream,
        limits: ImportLimits,
        visitor: suspend (ImportItem) -> Boolean,
    ) {
        val zip = ZipInputStream(input)
        var index = 0
        while (true) {
            val entry = try {
                zip.nextEntry
            } catch (_: IOException) {
                visitor(ImportItem.Unreadable(displayName, index, ImportProblem.UNREADABLE))
                break
            } ?: break
            if (entry.isDirectory) continue
            if (index >= limits.maxZipEntries) {
                visitor(ImportItem.TooManyEntries(displayName))
                break
            }
            val name = "$displayName / ${entry.name}"
            val item = try {
                classify(name, index, readBounded(zip, limits.maxItemBytes), limits)
            } catch (_: IOException) {
                ImportItem.Unreadable(name, index, ImportProblem.UNREADABLE)
            }
            if (!visitor(item)) break
            index++
        }
    }

    /** [bytes]의 종류를 판단한다. null은 한도 초과. */
    private fun classify(name: String, index: Int, bytes: ByteArray?, limits: ImportLimits): ImportItem {
        if (bytes == null) return ImportItem.Unreadable(name, index, ImportProblem.TOO_LARGE)
        return when {
            isZip(bytes) ->
                // ZIP 안의 ZIP은 읽지 않는다. (최상위 ZIP은 visit에서 먼저 걸러진다.)
                ImportItem.Unreadable(name, index, ImportProblem.NOT_GPX)
            isGzip(bytes) -> {
                val inner = try {
                    GZIPInputStream(ByteArrayInputStream(bytes)).use { readBounded(it, limits.maxItemBytes) }
                } catch (_: IOException) {
                    return ImportItem.Unreadable(name, index, ImportProblem.UNREADABLE)
                } ?: return ImportItem.Unreadable(name, index, ImportProblem.TOO_LARGE)
                if (looksLikeGpx(inner)) ImportItem.Gpx(name, index, inner)
                else ImportItem.Unreadable(name, index, ImportProblem.NOT_GPX)
            }
            looksLikeGpx(bytes) -> ImportItem.Gpx(name, index, bytes)
            else -> ImportItem.Unreadable(name, index, ImportProblem.NOT_GPX)
        }
    }

    private fun isZip(b: ByteArray) =
        b.size >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() && b[2] == 0x03.toByte() && b[3] == 0x04.toByte()

    private fun isGzip(b: ByteArray) = b.size >= 2 && b[0] == 0x1F.toByte() && b[1] == 0x8B.toByte()

    private val gpxOpenTag = Regex("""<(?:[A-Za-z_][\w.\-]*:)?gpx[\s/>]""")

    /** 앞 4 KB(BOM·공백 포함)에 `<gpx`(접두어 허용) 여는 태그가 있는가. UTF-16 BOM이 있으면 그 글자로 읽는다. */
    internal fun looksLikeGpx(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, SNIFF_BYTES)
        val charset = when {
            n >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            n >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            else -> Charsets.ISO_8859_1
        }
        return gpxOpenTag.containsMatchIn(String(bytes, 0, n, charset))
    }

    /** 앞 [count]바이트를 읽고 스트림을 되돌린다. */
    private fun peek(input: InputStream, count: Int): ByteArray {
        input.mark(count)
        val buf = ByteArray(count)
        var read = 0
        while (read < count) {
            val r = input.read(buf, read, count - read)
            if (r < 0) break
            read += r
        }
        input.reset()
        return buf.copyOf(read)
    }

    /** [max] 바이트까지만 읽는다. 넘치면 null(읽는 도중 끊는다). */
    private fun readBounded(input: InputStream, max: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(CHUNK)
        var total = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            total += r
            if (total > max) return null
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }
}
