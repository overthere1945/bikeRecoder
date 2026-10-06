package com.cowork.bikerecoder.offline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class SegmentInfo(
    val name: String,
    val required: Boolean,
    val installed: Boolean,
    val sizeBytes: Long?,
)

/** Manages the BRouter `.rd5` routing data files in [dir]. Pure JVM; no Android APIs. */
class SegmentRepository(
    private val dir: File,
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://brouter.de/brouter/segments4/"
        val REQUIRED = listOf("E125_N35", "E125_N30")
        val OPTIONAL = listOf("E130_N35", "E120_N35")
        private const val BUFFER_SIZE = 64 * 1024
    }

    private fun file(name: String) = File(dir, "$name.rd5")
    private fun partFile(name: String) = File(dir, "$name.rd5.part")

    fun list(): List<SegmentInfo> =
        (REQUIRED.map { it to true } + OPTIONAL.map { it to false }).map { (name, required) ->
            val f = file(name)
            val installed = f.isFile
            SegmentInfo(name, required, installed, if (installed) f.length() else null)
        }

    fun hasRequired(): Boolean = REQUIRED.all { file(it).isFile }

    suspend fun download(name: String, onProgress: (read: Long, total: Long) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            val part = partFile(name)
            try {
                dir.mkdirs()
                val call = client.newCall(Request.Builder().url(baseUrl.resolve("$name.rd5")!!).build())
                // Blocking socket reads are not cancellable by themselves; abort the call on coroutine cancellation.
                coroutineScope {
                    val watcher = launch {
                        try {
                            awaitCancellation()
                        } finally {
                            call.cancel()
                        }
                    }
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) {
                                return@coroutineScope Result.failure<File>(
                                    IOException("HTTP ${response.code} downloading $name.rd5"),
                                )
                            }
                            val body = response.body
                            val total = body.contentLength()
                            var read = 0L
                            val buffer = ByteArray(BUFFER_SIZE)
                            body.byteStream().use { input ->
                                part.outputStream().use { output ->
                                    while (true) {
                                        ensureActive()
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        output.write(buffer, 0, n)
                                        read += n
                                        onProgress(read, total)
                                    }
                                }
                            }
                            if (total >= 0 && read != total) {
                                throw IOException("Incomplete download of $name.rd5: $read of $total bytes")
                            }
                            val target = file(name)
                            moveReplacing(part, target)
                            response.headers.getDate("Last-Modified")?.let { target.setLastModified(it.time) }
                            Result.success(target)
                        }
                    } finally {
                        watcher.cancel()
                    }
                }
            } catch (e: CancellationException) {
                part.delete()
                throw e
            } catch (e: Exception) {
                part.delete()
                // An abort caused by cancellation (call.cancel -> IOException) must surface as cancellation.
                ensureActive()
                Result.failure(e)
            }
        }

    suspend fun hasUpdate(name: String): Boolean = withContext(Dispatchers.IO) {
        val local = file(name)
        if (!local.isFile) return@withContext true
        try {
            val request = Request.Builder().url(baseUrl.resolve("$name.rd5")!!).head().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val remote = response.headers.getDate("Last-Modified") ?: return@use false
                remote.time / 1000 > local.lastModified() / 1000
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun moveReplacing(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun delete(name: String) {
        file(name).delete()
        partFile(name).delete()
    }
}
