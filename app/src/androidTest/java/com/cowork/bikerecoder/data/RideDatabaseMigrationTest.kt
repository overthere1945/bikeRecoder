package com.cowork.bikerecoder.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripStop
import com.cowork.bikerecoder.core.trip.TripType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** 스펙 ⑥: v1 데이터(여행·경유지)가 v2로 올라간 뒤에도 그대로이고, 새 테이블이 동작한다. */
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

    private companion object {
        const val DB = "migration-test.db"
    }
}
