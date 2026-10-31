package com.cowork.bikerecoder.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.core.importing.ImportedRide
import com.cowork.bikerecoder.core.importing.ReplayedRide
import com.cowork.bikerecoder.core.importing.TimeSpan
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RoomImportStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var store: RoomImportStore
    private lateinit var rides: RoomRideStore
    private lateinit var trips: RoomTripStore

    private val day = LocalDate.of(2023, 5, 14)
    private val totals = SegmentTotals(
        distanceM = 1234.5, movingTimeMs = 300_000, elapsedMs = 360_000, maxSpeedMps = 9.5,
        ascentM = 12.0, descentM = 7.0, minAltitudeM = 20.0, maxAltitudeM = 40.0,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        store = RoomImportStore(db)
        rides = RoomRideStore(db)
        trips = RoomTripStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun point(t: Long, lat: Double = 37.5) =
        TrackPoint(t, GeoPoint(lat, 127.0), altitudeM = 30.0, speedMps = null, accuracyM = 5f)

    private fun imported(
        startedAt: Long = 1_000_000,
        points: List<TrackPoint> = listOf(point(1_000_000), point(1_100_000, 37.51)),
        fileName: String = "Relive_0514.gpx",
    ) = ImportedRide(
        ride = ReplayedRide(startedAt, startedAt + 360_000, day, points, totals),
        profile = RouteProfile.BALANCED,
        fileName = fileName,
        importedAt = 5_000_000,
    )

    private suspend fun countOf(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun savesTripSegmentPointsAndImportRow() = runTest {
        val tripId = store.saveImported(imported())

        val trip = trips.trip(tripId)!!
        assertEquals(TripType.SINGLE_DAY, trip.type)
        assertEquals(TripStatus.COMPLETED, trip.status)
        assertEquals(RouteProfile.BALANCED, trip.profile)
        assertEquals(1_000_000L, trip.createdAt)
        assertEquals(1_360_000L, trip.lastActiveAt)
        assertEquals(1_360_000L, trip.completedAt)
        assertEquals(emptyList<Any>(), trips.stops(tripId))

        val segment = rides.segments(tripId).single()
        assertEquals(1_000_000L, segment.startedAt)
        assertEquals(1_360_000L, segment.endedAt)
        assertEquals(day, segment.localDate)
        assertEquals(totals, segment.totals)
        assertEquals(listOf(point(1_000_000), point(1_100_000, 37.51)), rides.points(segment.id))

        assertEquals(mapOf(tripId to "Relive_0514.gpx"), store.observeImported().first())
        assertEquals(5_000_000L, db.openHelper.readableDatabase.query("SELECT importedAt FROM trip_import").use {
            it.moveToFirst(); it.getLong(0)
        })
    }

    @Test
    fun failureMidSaveLeavesNothing() = runTest {
        // NaN is stored as NULL, which breaks track_point.lat NOT NULL after the trip and segment were inserted.
        val bad = imported(points = listOf(point(1_000_000), point(1_100_000, Double.NaN)))
        val failed = runCatching { store.saveImported(bad) }
        assertTrue("save should fail on the constraint: ${failed.exceptionOrNull()}", failed.exceptionOrNull() is android.database.sqlite.SQLiteConstraintException)

        assertEquals(0, countOf("trip"))
        assertEquals(0, countOf("ride_segment"))
        assertEquals(0, countOf("track_point"))
        assertEquals(0, countOf("trip_import"))
        assertEquals(emptyMap<Long, String>(), store.observeImported().first())
    }

    @Test
    fun deleteAllImportedKeepsRecordedTrips() = runTest {
        val recorded = trips.insertTrip(
            Trip(0, TripType.SINGLE_DAY, TripStatus.COMPLETED, RouteProfile.BALANCED, 1, 2, 2),
        )
        val seg = rides.openSegment(recorded, 10, day)
        rides.closeSegment(seg, 20, totals)
        store.saveImported(imported(startedAt = 2_000_000, fileName = "a.gpx"))
        store.saveImported(imported(startedAt = 3_000_000, fileName = "b.gpx"))

        assertEquals(2, store.deleteAllImported())

        assertNotNull(trips.trip(recorded))
        assertEquals(listOf(seg), rides.segments(recorded).map { it.id })
        assertEquals(1, countOf("trip"))
        assertEquals(1, countOf("ride_segment"))
        assertEquals(0, countOf("track_point"))
        assertEquals(0, countOf("trip_import"))
        assertEquals(emptyMap<Long, String>(), store.observeImported().first())
        assertEquals(0, store.deleteAllImported())
    }

    @Test
    fun deletingOneTripCascadesImportRow() = runTest {
        val a = store.saveImported(imported(startedAt = 2_000_000, fileName = "a.gpx"))
        val b = store.saveImported(imported(startedAt = 3_000_000, fileName = "b.gpx"))

        rides.deleteTrip(a)

        assertNull(trips.trip(a))
        assertEquals(mapOf(b to "b.gpx"), store.observeImported().first())
        assertEquals(1, countOf("trip_import"))
    }

    @Test
    fun segmentSpansUseNowForOpenSegment() = runTest {
        store.saveImported(imported(startedAt = 1_000_000))
        val tripId = trips.insertTrip(
            Trip(0, TripType.SINGLE_DAY, TripStatus.ACTIVE, RouteProfile.BALANCED, 1, 2, null),
        )
        rides.openSegment(tripId, 9_000_000, day)

        val spans = store.segmentSpans(now = 9_500_000).toSet()

        assertEquals(setOf(TimeSpan(1_000_000, 1_360_000), TimeSpan(9_000_000, 9_500_000)), spans)
    }

    @Test
    fun longFileNameIsCut() = runTest {
        val tripId = store.saveImported(imported(fileName = "x".repeat(250)))

        assertEquals("x".repeat(200), store.observeImported().first()[tripId])
    }

    @Test
    fun fileNameCutKeepsSurrogatePairs() = runTest {
        // The emoji (2 chars) sits across the 200 cut: 199 chars + high surrogate + low surrogate.
        val tripId = store.saveImported(imported(fileName = "x".repeat(199) + "\uD83D\uDEB4" + "tail"))
        val kept = store.observeImported().first()[tripId]!!
        assertEquals("x".repeat(199), kept)

        // A pair that fits whole is kept.
        val fits = store.saveImported(imported(startedAt = 2_000_000, fileName = "x".repeat(198) + "\uD83D\uDEB4" + "tail"))
        assertEquals("x".repeat(198) + "\uD83D\uDEB4", store.observeImported().first()[fits])
    }
}
