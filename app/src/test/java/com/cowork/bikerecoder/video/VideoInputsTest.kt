package com.cowork.bikerecoder.video

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.post.PhotoPositionSource
import com.cowork.bikerecoder.core.post.PlacedPhoto
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.post.FakePostStore
import com.cowork.bikerecoder.post.SegmentExtras
import com.cowork.bikerecoder.ride.FakeRideStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

/** 여행 → 영상 입력(스펙 §1.1 대상 구간, §2.1 제목, §2.4 합계, 규칙 V1·V3·V4). */
class VideoInputsTest {

    private val trips = FakeTripStore()
    private val rides = FakeRideStore(trips)
    private val posts = FakePostStore { rides.segments(TRIP) }
    private val loader = VideoInputsLoader(rides, posts, trips)

    private val seoul = ZoneId.of("Asia/Seoul")
    private val day1 = LocalDate.of(2026, 10, 17)
    private val day2 = LocalDate.of(2026, 10, 18)
    private val sunday = LocalDate.of(2026, 10, 25)

    private fun trip(type: TripType) {
        trips.trips[TRIP] = Trip(TRIP, type, TripStatus.COMPLETED, RouteProfile.BALANCED, 0L, 0L, 0L)
    }

    private fun at(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(seoul).toInstant().toEpochMilli()

    /** [seconds]+1 points one second apart going north 5 m per second, from [startLat]. */
    private fun track(start: Long, seconds: Int, startLat: Double = 37.5) = (0..seconds).map {
        TrackPoint(start + it * 1_000L, GeoPoint(startLat + it * 5.0 / 111_195.0, 127.0), 30.0 + it, 5f, 5f)
    }

    private fun totals(distanceM: Double, movingMs: Long, maxSpeed: Double = 6.0, ascent: Double = 10.0) =
        SegmentTotals(distanceM, movingMs, movingMs, maxSpeed, ascent, 0.0, 30.0, 40.0)

    /** A segment with [points]; closed unless [open]. */
    private suspend fun segment(date: LocalDate, points: List<TrackPoint>, distanceM: Double, open: Boolean = false,
                                maxSpeed: Double = 6.0, ascent: Double = 10.0, startedAt: Long = points.first().timeMillis): Long {
        val id = rides.openSegment(TRIP, startedAt, date)
        val t = totals(distanceM, points.last().timeMillis - points.first().timeMillis, maxSpeed, ascent)
        rides.appendPoints(id, points, t)
        if (!open) rides.closeSegment(id, points.last().timeMillis, t)
        return id
    }

    @Test
    fun onlyFinishedSegmentsOfTenMetresOrMoreAreUsed() = runTest {
        trip(TripType.SINGLE_DAY)
        val ride = segment(sunday, track(at(sunday, 9), 100), distanceM = 500.0, maxSpeed = 7.0, ascent = 12.0)
        segment(sunday, track(at(sunday, 11), 1), distanceM = 5.0)
        segment(sunday, track(at(sunday, 13), 100), distanceM = 500.0, open = true)

        val inputs = loader.load(TRIP)!!

        assertEquals(101, inputs.timeline.points.size)
        assertTrue(inputs.timeline.points.all { it.segmentIndex == 0 })
        assertEquals(100_000L, inputs.timeline.totalMoveMs)
        assertEquals(TripTotals(500.0, 100_000L, 5.0, 7.0, 12.0, avgBpm = null, calories = null), inputs.totals)
        assertEquals(TRIP, inputs.tripId)
        assertEquals(ride, rides.segments(TRIP).first().id)
    }

    @Test
    fun hiddenPhotosAreLeftOutAndPhotosOutsideTheTrackAttachToItsEnds() = runTest {
        trip(TripType.SINGLE_DAY)
        val start = at(sunday, 9)
        val id = segment(sunday, track(start, 100), distanceM = 500.0)
        val p = GeoPoint(37.5, 127.0)
        posts.upsertPhotos(id, listOf(
            PlacedPhoto(11, start + 40_000, p, PhotoPositionSource.EXIF),
            PlacedPhoto(12, start + 50_000, p, PhotoPositionSource.EXIF),
            PlacedPhoto(13, start - 60_000, p, PhotoPositionSource.TRACK),
            PlacedPhoto(14, start + 200_000, p, PhotoPositionSource.TRACK),
        ))
        posts.setHidden(posts.photos.value.single { it.mediaId == 12L }.id, true)

        val photos = loader.load(TRIP)!!.photos

        assertEquals(listOf(13L, 11L, 14L), photos.sortedBy { it.moveMs }.map { it.mediaId })
        assertEquals(40_000L, photos.single { it.mediaId == 11L }.moveMs)
        assertEquals(0L, photos.single { it.mediaId == 13L }.moveMs)
        assertEquals(100_000L, photos.single { it.mediaId == 14L }.moveMs)
        assertEquals(start + 40_000, photos.single { it.mediaId == 11L }.takenAt)
    }

    @Test
    fun aPhotoOnTwoSegmentsAppearsOnce() = runTest {
        trip(TripType.SINGLE_DAY)
        val start = at(sunday, 9)
        val a = segment(sunday, track(start, 100), distanceM = 500.0)
        val b = segment(sunday, track(start + 101_000, 100), distanceM = 500.0)
        val photo = PlacedPhoto(21, start + 100_500, GeoPoint(37.5, 127.0), PhotoPositionSource.TRACK)
        posts.upsertPhotos(a, listOf(photo))
        posts.upsertPhotos(b, listOf(photo))

        assertEquals(listOf(21L), loader.load(TRIP)!!.photos.map { it.mediaId })
    }

    @Test
    fun theFirstStartIsTheFirstTargetSegmentsStartNotItsFirstFix() = runTest {
        trip(TripType.SINGLE_DAY)
        segment(sunday, track(at(sunday, 8), 1), distanceM = 5.0) // too short: not a target
        val pressedStart = at(sunday, 9) - 90_000 // 08:58:30; the first GPS fix came at 09:00
        segment(sunday, track(at(sunday, 9), 100), distanceM = 500.0, startedAt = pressedStart)
        segment(sunday, track(at(sunday, 11), 100), distanceM = 500.0)

        assertEquals(pressedStart, loader.load(TRIP)!!.firstStartedAt)
    }

    @Test
    fun singleDayTitle() = runTest {
        trip(TripType.SINGLE_DAY)
        segment(sunday, track(at(sunday, 9), 100), distanceM = 500.0)

        val inputs = loader.load(TRIP)!!

        assertEquals("10월 25일 (일)", inputs.title)
        assertTrue(inputs.dayStarts.isEmpty())
    }

    @Test
    fun multiDayTripHasDayCaptionsRangeTitle() = runTest {
        trip(TripType.MULTI_DAY)
        segment(day1, track(at(day1, 9), 100), distanceM = 500.0)
        segment(day1, track(at(day1, 14), 100), distanceM = 500.0)
        segment(day2, track(at(day2, 9), 100, startLat = 37.6), distanceM = 500.0)

        val inputs = loader.load(TRIP)!!

        assertEquals("10월 17일 ~ 18일", inputs.title)
        assertEquals(listOf(200_000L to "2일째 · 10월 18일"), inputs.dayStarts)
        assertEquals(inputs.timeline.dayStarts, inputs.dayStarts)
        assertEquals(1_500.0, inputs.totals.distanceM)
    }

    @Test
    fun rangeAcrossMonthsNamesBothMonths() = runTest {
        trip(TripType.MULTI_DAY)
        val oct31 = LocalDate.of(2026, 10, 31)
        val nov1 = LocalDate.of(2026, 11, 1)
        segment(oct31, track(at(oct31, 9), 100), distanceM = 500.0)
        segment(nov1, track(at(nov1, 9), 100), distanceM = 500.0)

        assertEquals("10월 31일 ~ 11월 1일", loader.load(TRIP)!!.title)
    }

    @Test
    fun multiDayTripRiddenOnOneDayShowsThatDay() = runTest {
        trip(TripType.MULTI_DAY)
        segment(sunday, track(at(sunday, 9), 100), distanceM = 500.0)

        val inputs = loader.load(TRIP)!!

        assertEquals("10월 25일 (일)", inputs.title)
    }

    @Test
    fun rideTitleFromFirstStart() = runTest {
        trip(TripType.SINGLE_DAY)
        val evening = sunday.atTime(18, 30).atZone(seoul).toInstant().toEpochMilli()
        segment(sunday, track(evening, 100), distanceM = 500.0)

        assertEquals("저녁 라이딩", loader.load(TRIP)!!.rideTitle)
    }

    @Test
    fun rideTitleOfTwoDaysIsATrip() = runTest {
        trip(TripType.MULTI_DAY)
        segment(day1, track(at(day1, 18), 100), distanceM = 500.0)
        segment(day2, track(at(day2, 9), 100, startLat = 37.6), distanceM = 500.0)

        assertEquals("2일 여행", loader.load(TRIP)!!.rideTitle)
    }

    @Test
    fun heartRateSamplesAverageAndColouring() = runTest {
        trip(TripType.SINGLE_DAY)
        val start = at(sunday, 9)
        val a = segment(sunday, track(start, 100), distanceM = 500.0)
        val b = segment(sunday, track(start + 3_600_000, 100), distanceM = 500.0)
        posts.replaceSamples(b, listOf(HeartRateSample(start + 3_600_000, 140), HeartRateSample(start + 3_650_000, 150)))
        posts.replaceSamples(a, listOf(HeartRateSample(start, 120), HeartRateSample(start + 50_000, 130)))
        posts.saveExtras(SegmentExtras(a, hrStatus = HrStatus.READY, hrDetailed = true, avgBpm = 125, maxBpm = 130, calories = 40.0))
        posts.saveExtras(SegmentExtras(b, hrStatus = HrStatus.READY, hrDetailed = false, avgBpm = 145, maxBpm = 150, calories = 60.0))

        val inputs = loader.load(TRIP)!!

        assertEquals(listOf(120, 130, 140, 150), inputs.heartRate.map { it.bpm })
        assertEquals(inputs.heartRate.sortedBy { it.timeMillis }, inputs.heartRate)
        assertTrue(inputs.canColorByHeart)
        assertEquals(135, inputs.totals.avgBpm)
        assertEquals(100.0, inputs.totals.calories)
    }

    @Test
    fun noDetailedHeartRateMeansNoHeartColouring() = runTest {
        trip(TripType.SINGLE_DAY)
        val a = segment(sunday, track(at(sunday, 9), 100), distanceM = 500.0)
        posts.saveExtras(SegmentExtras(a, hrStatus = HrStatus.READY, hrDetailed = false, avgBpm = 125, maxBpm = 130))

        val inputs = loader.load(TRIP)!!

        assertFalse(inputs.canColorByHeart)
        assertEquals(125, inputs.totals.avgBpm)
        assertNull(inputs.totals.calories)
    }

    @Test
    fun nothingToMakeGivesNull() = runTest {
        trip(TripType.SINGLE_DAY)
        assertNull(loader.load(TRIP))

        segment(sunday, track(at(sunday, 9), 1), distanceM = 5.0)
        segment(sunday, track(at(sunday, 10), 100), distanceM = 500.0, open = true)
        assertNull(loader.load(TRIP))

        assertNull(loader.load(TRIP + 1))
    }

    private companion object {
        const val TRIP = 7L
    }
}
