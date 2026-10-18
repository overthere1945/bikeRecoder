package com.cowork.bikerecoder.routing

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Downloads the real BRouter segment file(s) the integration tests route against, into the
 * directory named by the `segmentsDir` system property, unless already present.
 *
 * This talks to the real network (https://brouter.de) and fails loudly — rather than skipping —
 * when the download cannot complete, so a broken network shows up as a test failure, not a
 * silently-skipped test.
 */
object SegmentFixture {

    private const val BASE_URL = "https://brouter.de/brouter/segments4/"

    /**
     * Ensures [segmentFileName] (e.g. "E125_N35.rd5") exists under the `segmentsDir` system
     * property directory, downloading it from [BASE_URL] first if it is missing. Returns the
     * directory containing the segment files.
     */
    fun ensure(segmentFileName: String = "E125_N35.rd5"): File {
        val segmentsDirPath = System.getProperty("segmentsDir")
            ?: error("System property \"segmentsDir\" is not set; the integrationTest Gradle task must set it.")
        val segmentsDir = File(segmentsDirPath)
        segmentsDir.mkdirs()

        val target = File(segmentsDir, segmentFileName)
        if (target.exists() && target.length() > 0L) {
            return segmentsDir
        }

        val partFile = File(segmentsDir, "$segmentFileName.part")
        val url = BASE_URL + segmentFileName
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()

        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofFile(partFile.toPath()))
        } catch (e: Exception) {
            partFile.delete()
            throw AssertionError(
                "Could not download BRouter segment file from $url into $partFile. " +
                    "This integration test needs real network access to brouter.de " +
                    "to fetch a real routing data tile.",
                e,
            )
        }

        if (response.statusCode() != 200) {
            partFile.delete()
            throw AssertionError(
                "Downloading BRouter segment file from $url failed with HTTP ${response.statusCode()}.",
            )
        }

        if (!partFile.renameTo(target)) {
            throw AssertionError("Could not rename $partFile to $target after download.")
        }

        return segmentsDir
    }
}
