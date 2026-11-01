package com.cowork.bikerecoder.data

import com.cowork.bikerecoder.core.activity.ActivityType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The default hashtags per activity (V1.9.0 spec §5.2): the first values, a saved value, and "none". */
class AppSettingsHashtagsTest {

    @Test
    fun firstValuesPerActivity() {
        val settings = AppSettings()
        assertEquals("#자전거 #라이딩", settings.hashtagsFor(ActivityType.BIKE))
        assertEquals("#걷기 #산책", settings.hashtagsFor(ActivityType.WALK))
        assertEquals("#러닝 #달리기", settings.hashtagsFor(ActivityType.RUN))
        assertEquals("#등산 #하이킹", settings.hashtagsFor(ActivityType.HIKE))
    }

    @Test
    fun aSavedValueWinsAndEmptyMeansNone() {
        val settings = AppSettings(shareHashtags = "#자출", shareHashtagsWalk = "", shareHashtagsRun = "#마라톤", shareHashtagsHike = "#산")
        assertEquals("#자출", settings.hashtagsFor(ActivityType.BIKE))
        assertEquals("", settings.hashtagsFor(ActivityType.WALK))
        assertEquals("#마라톤", settings.hashtagsFor(ActivityType.RUN))
        assertEquals("#산", settings.hashtagsFor(ActivityType.HIKE))
    }

    @Test
    fun withHashtagsSetsOnlyThatActivity() {
        val settings = AppSettings().withHashtags(ActivityType.RUN, "#10k")
        assertEquals("#10k", settings.shareHashtagsRun)
        assertNull(settings.shareHashtags)
        assertNull(settings.shareHashtagsWalk)
        assertNull(settings.shareHashtagsHike)
        assertNull(settings.withHashtags(ActivityType.RUN, null).shareHashtagsRun)
    }
}
