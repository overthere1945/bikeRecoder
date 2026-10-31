package com.cowork.bikerecoder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AppVersionTest {
    @Test
    fun `debug version name is 1_5_0-dev`() = assertEquals("1.5.0-dev", BuildConfig.VERSION_NAME)

    @Test
    fun `version code follows rule`() = assertEquals(10500, BuildConfig.VERSION_CODE)

    @Test
    fun `display strips dev suffix and prefixes V`() {
        assertEquals("V1.0.0", AppVersion.display("1.0.0-dev"))
        assertEquals("V1.1.0", AppVersion.display("1.1.0-dev"))
        assertEquals("V1.2.0", AppVersion.display("1.2.0-dev"))
        assertEquals("V1.2.1", AppVersion.display("1.2.1-dev"))
        assertEquals("V1.3.0", AppVersion.display("1.3.0-dev"))
        assertEquals("V1.3.1", AppVersion.display("1.3.1-dev"))
        assertEquals("V1.4.0", AppVersion.display("1.4.0-dev"))
        assertEquals("V1.5.0", AppVersion.display("1.5.0-dev"))
        assertEquals("V1.2.3", AppVersion.display("1.2.3"))
    }

    @Test
    fun `kakao key matches secrets json`() {
        // Gradle runs app unit tests with app/ as the working directory.
        val secretsFile = File("../secrets.json")
        val expected = if (secretsFile.exists()) {
            val text = secretsFile.readText()
            Regex("\"kakaoRestApiKey\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: ""
        } else {
            ""
        }
        assertEquals(expected, BuildConfig.KAKAO_REST_API_KEY)
    }

    @Test
    fun `vworld key matches secrets json`() {
        val secretsFile = File("../secrets.json")
        val expected = if (secretsFile.exists()) {
            Regex("\"vworldApiKey\"\\s*:\\s*\"([^\"]*)\"").find(secretsFile.readText())?.groupValues?.get(1) ?: ""
        } else {
            ""
        }
        // A boolean check: a failure message must not show the key.
        assertTrue(expected == BuildConfig.VWORLD_API_KEY, "BuildConfig.VWORLD_API_KEY differs from secrets.json")
    }
}
