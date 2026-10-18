package com.cowork.bikerecoder.offline

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.RouteSummary
import com.cowork.bikerecoder.data.AppDatabase
import com.cowork.bikerecoder.data.SettingsRepository
import com.cowork.bikerecoder.map.OpenFreeMapSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionStatus
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.cos

/**
 * Needs network (OpenFreeMap) and runs against the device's real MapLibre offline database, so it uses a trip
 * id no real trip has and removes every region carrying it afterwards.
 */
@RunWith(AndroidJUnit4::class)
class MapLibreOfflineControllerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val refs get() = db.offlineRegionRefDao()

    @Before
    fun setUp() {
        // MapLibre is initialised by BikeApp (and by the controller, on the main thread); calling
        // MapLibre.getInstance here would run on the instrumentation thread and throw.
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = runBlocking {
        try {
            // Whatever the test did (or failed half-way through), leave no region of this trip behind.
            if (::context.isInitialized) ourRegions().forEach { delete(it) }
        } finally {
            if (::db.isInitialized) db.close()
        }
    }

    private fun controller(maxTiles: Long = MapLibreOfflineController.DEFAULT_MAX_TILES) = MapLibreOfflineController(
        context,
        OpenFreeMapSource(okhttp3.OkHttpClient(), cacheFile = null),
        refs,
        SettingsRepository(context),
        object : NetworkWaiter(context) {
            override suspend fun awaitUnmetered() = Unit
        },
        clock = System::currentTimeMillis,
        maxTiles = maxTiles,
    )

    /** About 1 km due east in central Seoul. */
    private fun shortRoute(): Route {
        val lat = 37.5665
        val metersPerDegLon = 111_320.0 * cos(Math.toRadians(lat))
        val points = (0..10).map { GeoPoint(lat, 126.9780 + it * 100.0 / metersPerDegLon) }
        return Route(
            points = points,
            cumulativeM = points.indices.map { it * 100.0 },
            instructions = emptyList(),
            stopPointIndices = listOf(points.lastIndex),
            summary = RouteSummary(1_000.0, 0, 0.0, RouteProfile.BALANCED),
        )
    }

    private suspend fun download(c: MapLibreOfflineController) {
        c.downloadForTrip(TRIP, shortRoute(), 0.0)
        withTimeout(DOWNLOAD_TIMEOUT_MS) { c.awaitDownloads() }
    }

    // ---- MapLibre helpers (main thread) ----

    private suspend fun allRegions(): List<OfflineRegion> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            OfflineManager.getInstance(context).listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(offlineRegions: Array<OfflineRegion>?) = cont.resume(offlineRegions?.toList().orEmpty())
                    override fun onError(error: String) = cont.resumeWithException(IllegalStateException(error))
                },
            )
        }
    }

    private suspend fun ourRegions() = allRegions().filter { OfflineRegionMeta.decode(it.metadata)?.tripId == TRIP }

    private suspend fun delete(region: OfflineRegion) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            region.delete(
                object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() = cont.resume(Unit)
                    override fun onError(error: String) = cont.resumeWithException(IllegalStateException(error))
                },
            )
        }
    }

    private suspend fun status(region: OfflineRegion): OfflineRegionStatus = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            region.getStatus(
                object : OfflineRegion.OfflineRegionStatusCallback {
                    override fun onStatus(status: OfflineRegionStatus?) = cont.resume(status!!)
                    override fun onError(error: String?) = cont.resumeWithException(IllegalStateException(error))
                },
            )
        }
    }

    private fun metas(regions: List<OfflineRegion>) = regions.map { OfflineRegionMeta.decode(it.metadata)!! }

    // ---- tests ----

    @Test
    fun downloadCreatesTwoRegionsAndRefs() = runBlocking {
        val c = controller()

        download(c)

        val regions = ourRegions()
        assertEquals(setOf(RegionKind.CORRIDOR, RegionKind.OVERVIEW), metas(regions).map { it.kind }.toSet())
        assertEquals(2, regions.size)
        assertTrue(metas(regions).none { it.truncated })
        regions.forEach {
            val status = status(it)
            assertTrue("region ${it.id} complete", status.isComplete)
            assertTrue("region ${it.id} count precise", status.isRequiredResourceCountPrecise)
            assertTrue("region ${it.id} has resources", status.completedResourceCount > 0)
        }
        val saved = refs.forTrip(TRIP)
        assertEquals(setOf("CORRIDOR", "OVERVIEW"), saved.map { it.kind }.toSet())
        assertEquals(regions.map { it.id }.toSet(), saved.map { it.mapLibreRegionId }.toSet())
        assertNull("progress is cleared once the download is complete", c.progress.value)
    }

    @Test
    fun deleteRemovesRegionsAndRefs() = runBlocking {
        val c = controller()
        download(c)
        assertEquals(2, ourRegions().size)

        c.deleteForTrip(TRIP)

        assertTrue(ourRegions().isEmpty())
        assertTrue(refs.forTrip(TRIP).isEmpty())
    }

    @Test
    fun secondDownloadForSameTripIsSkipped() = runBlocking {
        val c = controller()
        download(c)
        val firstIds = ourRegions().map { it.id }.toSet()

        download(c)

        val regions = ourRegions()
        assertEquals(2, regions.size)
        assertEquals("regions were not recreated", firstIds, regions.map { it.id }.toSet())
        assertEquals(2, refs.forTrip(TRIP).size)
    }

    @Test
    fun truncatedRegionIsReplacedOnNextDownload() = runBlocking {
        download(controller(maxTiles = 1))
        val truncated = ourRegions()
        assertEquals(2, truncated.size)
        assertTrue(metas(truncated).all { it.truncated })

        download(controller())

        val regions = ourRegions()
        assertEquals(2, regions.size)
        assertTrue("previous regions deleted", regions.map { it.id }.none { it in truncated.map { t -> t.id } })
        assertFalse(metas(regions).any { it.truncated })
        assertEquals(regions.map { it.id }.toSet(), refs.forTrip(TRIP).map { it.mapLibreRegionId }.toSet())
    }

    @Test
    fun totalBytesCoversTheDownloadedRegions() = runBlocking {
        val c = controller()
        download(c)

        val ours = ourRegions().sumOf { status(it).completedResourceSize }

        assertTrue("our regions have a size", ours > 0)
        assertTrue("total includes them", c.totalBytes() >= ours)
    }

    @Test
    fun deleteAllRemovesEveryTripRegionAndRef() = runBlocking {
        // deleteAll wipes every trip map of the app; never run it where the device holds regions we did not create.
        val foreign = allRegions().filter { OfflineRegionMeta.decode(it.metadata)?.let { m -> m.tripId != TRIP } == true }
        Assume.assumeTrue("device has trip maps of its own; not deleting them", foreign.isEmpty())
        val c = controller()
        download(c)
        assertEquals(2, ourRegions().size)

        c.deleteAll()

        assertTrue(ourRegions().isEmpty())
        assertTrue(refs.all().isEmpty())
        assertEquals(0L, c.totalBytes())
    }

    @Test
    fun deleteAllCancelsARunningDownloadAndLeavesNothing() = runBlocking {
        val foreign = allRegions().filter { OfflineRegionMeta.decode(it.metadata)?.let { m -> m.tripId != TRIP } == true }
        Assume.assumeTrue("device has trip maps of its own; not deleting them", foreign.isEmpty())
        val c = controller()

        c.downloadForTrip(TRIP, shortRoute(), 0.0)
        // In flight: the corridor region exists (its ref is stored at creation) and progress is shown.
        withTimeout(60_000) {
            while (refs.forTrip(TRIP).isEmpty() || c.progress.value == null) kotlinx.coroutines.delay(10)
        }

        withTimeout(60_000) { c.deleteAll() }

        // deleteAll joined the cancelled job: nothing is running and nothing is left behind.
        withTimeout(5_000) { c.awaitDownloads() }
        assertNull(c.progress.value)
        assertTrue(ourRegions().isEmpty())
        assertTrue(refs.all().isEmpty())
        kotlinx.coroutines.delay(1_000)
        assertTrue("no region is created after the cancel", ourRegions().isEmpty())
    }

    private companion object {
        /** No real trip has such an id, so this test never touches the user's own offline regions. */
        const val TRIP = 987_654_321L
        const val DOWNLOAD_TIMEOUT_MS = 180_000L
    }
}
