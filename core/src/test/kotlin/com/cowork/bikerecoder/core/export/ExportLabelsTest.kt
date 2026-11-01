package com.cowork.bikerecoder.core.export

import com.cowork.bikerecoder.core.activity.ActivityType
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class ExportLabelsTest {

    private val date = LocalDate.of(2026, 10, 6)

    @Test
    fun rideNamePerType() {
        assertEquals("2026-10-06 주행", ExportLabels.rideName(date))
        assertEquals("2026-10-06 주행", ExportLabels.rideName(date, ActivityType.BIKE))
        assertEquals("2026-10-06 걷기", ExportLabels.rideName(date, ActivityType.WALK))
        assertEquals("2026-10-06 달리기", ExportLabels.rideName(date, ActivityType.RUN))
        assertEquals("2026-10-06 등산", ExportLabels.rideName(date, ActivityType.HIKE))
    }
}
