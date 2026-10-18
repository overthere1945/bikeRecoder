package com.cowork.bikerecoder.post

import com.cowork.bikerecoder.core.post.HrRead
import java.util.concurrent.CopyOnWriteArrayList

// Shared by the JVM tests (src/test) and the device tests (src/androidTest). Thread-safe.

/**
 * Health Connect in memory. [read] fails like the real source when Health Connect is unavailable or not [permitted];
 * otherwise it returns [reads] normalised to the source contract (samples clipped to [from, to] and sorted; none left →
 * Empty). Records every call.
 */
class FakeHeartRateSource(
    @Volatile var availability: HealthAvailability = HealthAvailability.AVAILABLE,
    @Volatile var permitted: Boolean = true,
    @Volatile var reads: (from: Long, to: Long) -> HrRead = { _, _ -> HrRead.Empty },
) : HeartRateSource {
    val calls: MutableList<Pair<Long, Long>> = CopyOnWriteArrayList()

    override val permissions: Set<String> = setOf(
        "android.permission.health.READ_HEART_RATE",
        "android.permission.health.READ_EXERCISE",
        "android.permission.health.READ_TOTAL_CALORIES_BURNED",
    )

    override fun availability(): HealthAvailability = availability

    override suspend fun hasPermissions(): Boolean = availability == HealthAvailability.AVAILABLE && permitted

    override suspend fun read(from: Long, to: Long): HrRead {
        calls += from to to
        if (!hasPermissions()) return HrRead.Failed
        return when (val r = reads(from, to)) {
            is HrRead.Samples -> hrReadOf(r.samples.filter { it.timeMillis in from..to }, r.calories)
            else -> r
        }
    }
}
