package com.cowork.bikerecoder.core.feed

import com.cowork.bikerecoder.core.activity.ActivityType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 피드 위성 그림의 키(스펙 §2.4): 그림을 바꾸는 것(구간 집합, 끝난 때, 거리)이 바뀌면 키가 바뀐다. */
class FeedThumbKeyTest {

    private val a = ThumbSegment(1, 1_000L, 1234.5)
    private val b = ThumbSegment(2, 2_000L, 800.0)

    @Test
    fun sameSegmentsSameKeyInAnyOrder() {
        assertEquals(FeedThumbKey.of(listOf(a, b)), FeedThumbKey.of(listOf(b, a)))
        assertEquals(FeedThumbKey.of(listOf(a, b)), FeedThumbKey.of(listOf(a, b)))
    }

    @Test
    fun distanceIsRoundedToTenthOfAMetre() {
        assertEquals(FeedThumbKey.of(listOf(a)), FeedThumbKey.of(listOf(a.copy(distanceM = 1234.52))))
        assertNotEquals(FeedThumbKey.of(listOf(a)), FeedThumbKey.of(listOf(a.copy(distanceM = 1234.6))))
    }

    @Test
    fun changedEndOrDistanceOrSetChangesKey() {
        val base = FeedThumbKey.of(listOf(a, b))
        assertNotEquals(base, FeedThumbKey.of(listOf(a, b.copy(endedAt = 2_001L))))
        assertNotEquals(base, FeedThumbKey.of(listOf(a, b.copy(distanceM = 900.0))))
        assertNotEquals(base, FeedThumbKey.of(listOf(a)))
        assertNotEquals(base, FeedThumbKey.of(listOf(a, b, ThumbSegment(3, 3_000L, 10.0))))
        assertNotEquals(base, FeedThumbKey.of(listOf(a, b.copy(id = 3))))
    }

    @Test
    fun twelveHexChars() {
        for (segments in listOf(emptyList(), listOf(a), listOf(a, b))) {
            val key = FeedThumbKey.of(segments)
            assertEquals(12, key.length)
            assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' }, key)
        }
    }

    /** 경로 선 색이 종류를 따르므로(V1.9.0) 구간의 종류가 바뀌면 키가 바뀐다. */
    @Test
    fun changedActivityChangesKey() {
        assertNotEquals(FeedThumbKey.of(listOf(a)), FeedThumbKey.of(listOf(a.copy(activity = ActivityType.RUN))))
        assertNotEquals(
            FeedThumbKey.of(listOf(a.copy(activity = ActivityType.WALK))),
            FeedThumbKey.of(listOf(a.copy(activity = ActivityType.HIKE))),
        )
    }

    /** 버전 3(V1.9.0): 경로 선이 주황에서 종류 색으로 바뀌어, 같은 구간이라도 버전 2의 키와 다르다. */
    @Test
    fun versionThreeSinceActivityColours() {
        assertEquals(3, FeedThumbKey.VERSION)
        // SHA-256 of "1:1000:12345:BIKE|v3".
        assertEquals("83244f27f9dd", FeedThumbKey.of(listOf(a)))
    }

    @Test
    fun segmentBoundariesAreNotAmbiguous() {
        // "1:23:4" + "5:6:7" must not equal "1:2:34" + "5:6:7"-like concatenations.
        assertNotEquals(
            FeedThumbKey.of(listOf(ThumbSegment(1, 23, 0.4))),
            FeedThumbKey.of(listOf(ThumbSegment(1, 2, 3.4))),
        )
    }
}
