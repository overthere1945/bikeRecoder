package com.cowork.bikerecoder.routing

import java.io.File

/**
 * Installs the BRouter profile bundle (lookups.dat + the three bike profiles) that ships as
 * classpath resources into a plain directory on disk, where BRouter's [btools.router.ProfileCache]
 * expects to find them next to each other.
 */
object ProfileInstaller {

    private val RESOURCE_NAMES = listOf(
        "lookups.dat",
        "cycleway-first.brf",
        "balanced.brf",
        "shortest.brf",
    )

    /**
     * Copies the profile resources into [targetDir] (creating it if needed), overwriting a file
     * only when its content differs from the bundled resource. Returns [targetDir].
     */
    fun install(targetDir: File): File {
        targetDir.mkdirs()
        for (name in RESOURCE_NAMES) {
            val resourceBytes = javaClass.getResourceAsStream("/brouter/profiles/$name")
                ?.use { it.readBytes() }
                ?: error("Missing bundled BRouter profile resource: /brouter/profiles/$name")

            val targetFile = File(targetDir, name)
            if (!targetFile.exists() || !targetFile.readBytes().contentEquals(resourceBytes)) {
                targetFile.writeBytes(resourceBytes)
            }
        }
        return targetDir
    }
}
