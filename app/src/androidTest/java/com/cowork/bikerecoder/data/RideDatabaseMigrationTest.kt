package com.cowork.bikerecoder.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.post.CumulativeEntry
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripStop
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.favorite.Favorite
import com.cowork.bikerecoder.favorite.FavoriteKind
import com.cowork.bikerecoder.post.RidePhoto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** 스펙 ⑥: 이전 버전 데이터가 마이그레이션 뒤에도 그대로이고, 새 테이블이 동작한다(1→2, 2→3, 3→4). */
@RunWith(AndroidJUnit4::class)
class RideDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @After
    fun tearDown() {
        context.deleteDatabase(DB)
    }

    @Test
    fun v1DataSurvivesMigrationTo2() = runBlocking {
        helper.createDatabase(DB, 1).apply {
            execSQL(
                "INSERT INTO trip (id, type, status, profile, createdAt, lastActiveAt, completedAt) " +
                    "VALUES (5, 'MULTI_DAY', 'ACTIVE', 'BALANCED', 1000, 2000, NULL)",
            )
            execSQL(
                "INSERT INTO waypoint (id, tripId, stop_order, name, lat, lon, isDestination, visitedAt) " +
                    "VALUES (11, 5, 0, '도착', 37.5, 127.0, 1, NULL)",
            )
            close()
        }

        helper.runMigrationsAndValidate(DB, 2, true).close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, DB).build()
        try {
            val trips = RoomTripStore(db)
            val trip = trips.trip(5)
            assertNotNull(trip)
            assertEquals(TripType.MULTI_DAY, trip!!.type)
            assertEquals(TripStatus.ACTIVE, trip.status)
            assertEquals(RouteProfile.BALANCED, trip.profile)
            assertEquals(1_000L, trip.createdAt)
            assertEquals(2_000L, trip.lastActiveAt)
            assertEquals(
                listOf(TripStop(11, 5, 0, "도착", GeoPoint(37.5, 127.0), isDestination = true, visitedAt = null)),
                trips.stops(5),
            )

            val rides = RoomRideStore(db)
            assertEquals(emptyList<Any>(), rides.tripSummaries())
            val segmentId = rides.openSegment(5, startedAt = 3_000L, localDate = LocalDate.of(2026, 10, 12))
            assertEquals(listOf(segmentId), rides.openSegments().map { it.id })
        } finally {
            db.close()
        }
    }

    @Test
    fun v2DataSurvivesMigrationTo3() = runBlocking {
        helper.createDatabase(DB, 2).apply {
            execSQL(
                "INSERT INTO trip (id, type, status, profile, createdAt, lastActiveAt, completedAt) " +
                    "VALUES (5, 'MULTI_DAY', 'ACTIVE', 'BALANCED', 1000, 2000, NULL)",
            )
            execSQL(
                "INSERT INTO ride_segment (id, tripId, startedAt, endedAt, localDate, distanceM, movingTimeMs, " +
                    "elapsedMs, maxSpeedMps, ascentM, descentM, minAltitudeM, maxAltitudeM) " +
                    "VALUES (21, 5, 3000, 4000, '2026-10-12', 1500.0, 60000, 90000, 8.5, 10.0, 4.0, NULL, NULL)",
            )
            close()
        }

        helper.runMigrationsAndValidate(DB, 3, true).close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, DB).build()
        try {
            val trip = RoomTripStore(db).trip(5)
            assertNotNull(trip)
            assertEquals(TripType.MULTI_DAY, trip!!.type)
            assertEquals(TripStatus.ACTIVE, trip.status)
            assertEquals(1_000L, trip.createdAt)
            assertEquals(2_000L, trip.lastActiveAt)

            val segments = RoomRideStore(db).segments(5)
            assertEquals(1, segments.size)
            assertEquals(21L, segments[0].id)
            assertEquals(4_000L, segments[0].endedAt)
            assertEquals(1500.0, segments[0].totals.distanceM, 0.0)

            val favorites = RoomFavoriteStore(db)
            assertEquals(emptyList<Favorite>(), favorites.all())
            val id = favorites.insert(Favorite(0, FavoriteKind.HOME, "집", GeoPoint(37.5, 127.0), null, 5_000L))
            assertEquals(listOf(id), favorites.all().map { it.id })
        } finally {
            db.close()
        }
    }

    @Test
    fun v3DataSurvivesMigrationTo4() = runBlocking {
        helper.createDatabase(DB, 3).apply {
            execSQL(
                "INSERT INTO trip (id, type, status, profile, createdAt, lastActiveAt, completedAt) " +
                    "VALUES (5, 'MULTI_DAY', 'ACTIVE', 'BALANCED', 1000, 2000, NULL)",
            )
            execSQL(
                "INSERT INTO ride_segment (id, tripId, startedAt, endedAt, localDate, distanceM, movingTimeMs, " +
                    "elapsedMs, maxSpeedMps, ascentM, descentM, minAltitudeM, maxAltitudeM) " +
                    "VALUES (21, 5, 3000, 4000, '2026-10-12', 1500.0, 60000, 90000, 8.5, 10.0, 4.0, NULL, NULL)",
            )
            execSQL(
                "INSERT INTO track_point (id, segmentId, timeMillis, lat, lon, altitudeM, speedMps, accuracyM) " +
                    "VALUES (31, 21, 3500, 37.5, 127.0, 12.0, 5.0, 4.0)",
            )
            execSQL(
                "INSERT INTO favorite_place (id, kind, name, lat, lon, address, createdAt) " +
                    "VALUES (41, 'HOME', '집', 37.5, 127.0, NULL, 5000)",
            )
            close()
        }

        helper.runMigrationsAndValidate(DB, 4, true).close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, DB).build()
        try {
            val trip = RoomTripStore(db).trip(5)
            assertNotNull(trip)
            assertEquals(TripType.MULTI_DAY, trip!!.type)
            assertEquals(2_000L, trip.lastActiveAt)

            val rides = RoomRideStore(db)
            val segments = rides.segments(5)
            assertEquals(listOf(21L), segments.map { it.id })
            assertEquals(4_000L, segments[0].endedAt)
            assertEquals(1500.0, segments[0].totals.distanceM, 0.0)
            assertEquals(
                listOf(TrackPoint(3_500L, GeoPoint(37.5, 127.0), altitudeM = 12.0, speedMps = 5f, accuracyM = 4f)),
                rides.points(21),
            )

            assertEquals(
                listOf(Favorite(41, FavoriteKind.HOME, "집", GeoPoint(37.5, 127.0), null, 5_000L)),
                RoomFavoriteStore(db).all(),
            )

            // 새 표는 비어 있고, 지난 기록은 후처리 대상이다.
            val post = RoomPostStore(db)
            assertNull(post.extras(21))
            assertEquals(emptyList<RidePhoto>(), post.observePhotos(listOf(21)).first())
            assertEquals(emptyList<HeartRateSample>(), post.samples(21))
            assertEquals(emptyList<CumulativeEntry>(), post.cumulativeEntries())
            assertEquals(listOf(21L), post.segmentsNeedingWork(now = 5_000L).map { it.id })
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
