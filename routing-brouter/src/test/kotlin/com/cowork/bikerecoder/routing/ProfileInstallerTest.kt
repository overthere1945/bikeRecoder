package com.cowork.bikerecoder.routing

import java.io.File
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProfileInstallerTest {

    @Test
    fun `installs four profile files`(@TempDir dir: File) {
        ProfileInstaller.install(dir)

        assertEquals(
            setOf("lookups.dat", "cycleway-first.brf", "balanced.brf", "shortest.brf"),
            dir.list()!!.toSet(),
        )
    }

    @Test
    fun `returns the target directory`(@TempDir dir: File) {
        val result = ProfileInstaller.install(dir)

        assertEquals(dir, result)
    }

    @Test
    fun `does not rewrite a file whose content already matches`(@TempDir dir: File) {
        ProfileInstaller.install(dir)
        val balanced = File(dir, "balanced.brf")
        val firstModified = balanced.lastModified()

        // Ensure the filesystem's mtime resolution would actually show a rewrite if one happened.
        Thread.sleep(50)
        ProfileInstaller.install(dir)

        assertEquals(firstModified, balanced.lastModified())
    }

    @Test
    fun `creates the target directory when it does not exist yet`(@TempDir parent: File) {
        val dir = File(parent, "profiles")

        ProfileInstaller.install(dir)

        assertEquals(
            setOf("lookups.dat", "cycleway-first.brf", "balanced.brf", "shortest.brf"),
            dir.list()!!.toSet(),
        )
    }
}
