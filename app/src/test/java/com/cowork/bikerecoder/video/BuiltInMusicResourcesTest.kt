package com.cowork.bikerecoder.video

import com.cowork.bikerecoder.core.video.BuiltInMusic
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The bundled tracks are reached only by a name-based android.resource URI, so the release resource
 * shrinker cannot see them. They must stay listed in res/raw/keep.xml and present as files.
 */
class BuiltInMusicResourcesTest {
    private val rawDir = File("src/main/res/raw")

    @Test
    fun everyBundledTrackIsKeptFromShrinking() {
        val keepFile = File(rawDir, "keep.xml")
        assertTrue(keepFile.isFile, "missing ${keepFile.path}")
        val keep = Regex("""tools:keep\s*=\s*"([^"]*)"""").find(keepFile.readText())
            ?.groupValues?.get(1)?.split(',')?.map { it.trim() }?.toSet()
            ?: error("no tools:keep attribute in ${keepFile.path}")
        BuiltInMusic.entries.forEach { m ->
            assertTrue("@raw/${m.rawName}" in keep, "${m.rawName} is not listed in keep.xml: $keep")
        }
    }

    @Test
    fun everyBundledTrackHasItsFile() {
        BuiltInMusic.entries.forEach { m ->
            assertTrue(File(rawDir, "${m.rawName}.m4a").isFile, "missing raw file for ${m.rawName}")
        }
    }
}
