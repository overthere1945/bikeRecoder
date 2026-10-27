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
import kotlinx.coroutines.async
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
            if (::context.isInitialized) (ourRegions() + regionsOf(OTHER)).forEach { delete(it) }
        } finally {
            if (::db.isInitialized) db.close()
        }
    }

    private fun controller(maxTiles: Long = MapLibreOfflineController.DEFAULT_MAX_TILES, online: Boolean = true) = MapLibreOfflineController(
        context,
        OpenFreeMapSource(okhttp3.OkHttpClient(), cacheFile = null),
        refs,
        SettingsRepository(context),
        object : NetworkWaiter(context) {
            override suspend fun awaitUnmetered() = Unit
            override fun isOnline() = online
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

    /**
     * About 80 km due north somewhere inland in Korea, a new place each run (not in the tile cache): its VIDEO map
     * (z13–16) takes far longer than a few seconds to download.
     */
    private fun longTrack(): List<GeoPoint> {
        val random = kotlin.random.Random(System.nanoTime())
        val lon = 127.0 + random.nextDouble() * 1.8
        val lat = 35.3 + random.nextDouble() * 0.6
        return (0..80).map { GeoPoint(lat + it * 1_000.0 / 111_320.0, lon) }
    }

    private suspend fun download(c: MapLibreOfflineController, trip: Long = TRIP) {
        c.downloadForTrip(trip, shortRoute(), 0.0)
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

    private suspend fun regionsOf(trip: Long) = allRegions().filter { OfflineRegionMeta.decode(it.metadata)?.tripId == trip }

    /** Regions of trips this test does not own; deleteAll must not run while there are any. */
    private suspend fun foreignRegions() =
        allRegions().filter { OfflineRegionMeta.decode(it.metadata)?.let { m -> m.tripId != TRIP && m.tripId != OTHER } == true }

    /** Waits until [trip]'s VIDEO region exists and has started to arrive; asserts it is far from complete. */
    private suspend fun awaitVideoDownloading(trip: Long) {
        val region = withTimeout(60_000) {
            var found: OfflineRegion? = null
            while (found == null) {
                val id = refs.forTrip(trip).firstOrNull { it.kind == "VIDEO" }?.mapLibreRegionId
                found = id?.let { allRegions().firstOrNull { r -> r.id == it } }
                if (found == null) kotlinx.coroutines.delay(50)
            }
            while (status(found).completedResourceCount == 0L) kotlinx.coroutines.delay(50)
            found
        }
        val status = status(region)
        assertFalse("the video map is still downloading (${status.completedResourceCount}/${status.requiredResourceCount})", status.isComplete)
    }

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

    @Test
    fun trackDownloadAddsAVideoRegionThatIsDeletedAlone() = runBlocking {
        val c = controller()
        download(c)
        val tripMap = ourRegions().map { it.id }.toSet()

        assertTrue(withTimeout(DOWNLOAD_TIMEOUT_MS) { c.downloadForTrack(TRIP, shortRoute().points) })

        val regions = ourRegions()
        assertEquals(listOf(RegionKind.CORRIDOR, RegionKind.OVERVIEW, RegionKind.VIDEO).sorted(), metas(regions).map { it.kind }.sorted())
        val video = regions.single { OfflineRegionMeta.decode(it.metadata)!!.kind == RegionKind.VIDEO }
        assertTrue("video region complete", status(video).isComplete)
        assertEquals(setOf("CORRIDOR", "OVERVIEW", "VIDEO"), refs.forTrip(TRIP).map { it.kind }.toSet())

        // The trip map is still complete: a second trip download skips, the video region does not count.
        download(c)
        assertEquals(tripMap + video.id, ourRegions().map { it.id }.toSet())
        // Asked again, the complete video region is used as it is.
        assertTrue(c.downloadForTrack(TRIP, shortRoute().points))
        assertEquals(tripMap + video.id, ourRegions().map { it.id }.toSet())

        c.deleteVideoRegions(TRIP)

        assertEquals(tripMap, ourRegions().map { it.id }.toSet())
        assertEquals(setOf("CORRIDOR", "OVERVIEW"), refs.forTrip(TRIP).map { it.kind }.toSet())
    }

    @Test
    fun trackDownloadWithoutInternetOrMapIsRefused() = runBlocking {
        assertFalse(controller(online = false).downloadForTrack(TRIP, shortRoute().points))
        assertTrue(ourRegions().isEmpty())
        assertTrue(refs.forTrip(TRIP).isEmpty())
    }

    @Test
    fun deleteForTripAlsoRemovesTheVideoRegion() = runBlocking {
        val c = controller()
        assertTrue(withTimeout(DOWNLOAD_TIMEOUT_MS) { c.downloadForTrack(TRIP, shortRoute().points) })
        assertEquals(1, ourRegions().size)

        c.deleteForTrip(TRIP)

        assertTrue(ourRegions().isEmpty())
        assertTrue(refs.forTrip(TRIP).isEmpty())
    }

    /**
     * Final review I1: a VIDEO download does not hold the map lock for minutes. Deleting another trip's map while it
     * runs returns at once; the video download returns true with what arrived; the trip's own map is untouched.
     */
    @Test
    fun aVideoDownloadYieldsToDeleteForTrip() = runBlocking {
        val c = controller()
        download(c)
        download(c, OTHER)
        val tripMap = ourRegions().map { it.id }.toSet()
        assertEquals(2, regionsOf(OTHER).size)

        val video = async(Dispatchers.Default) { c.downloadForTrack(TRIP, longTrack()) }
        awaitVideoDownloading(TRIP)

        val took = kotlin.system.measureTimeMillis { withTimeout(5_000) { c.deleteForTrip(OTHER) } }
        assertTrue("the video download returns with what arrived", withTimeout(5_000) { video.await() })

        assertTrue("deleteForTrip took $took ms", took < 5_000)
        assertTrue(regionsOf(OTHER).isEmpty())
        assertTrue(refs.forTrip(OTHER).isEmpty())
        assertEquals(
            listOf(RegionKind.CORRIDOR, RegionKind.OVERVIEW, RegionKind.VIDEO).sorted(),
            metas(ourRegions()).map { it.kind }.sorted(),
        )
        assertTrue("the trip map is untouched", ourRegions().map { it.id }.containsAll(tripMap))

        // The job's own cleanup then leaves no VIDEO region behind.
        c.deleteVideoRegions(TRIP)
        assertEquals(tripMap, ourRegions().map { it.id }.toSet())
        assertEquals(setOf("CORRIDOR", "OVERVIEW"), refs.forTrip(TRIP).map { it.kind }.toSet())
    }

    /** Final review I1: [지도 모두 지우기] during a VIDEO download returns at once and leaves nothing behind. */
    @Test
    fun aVideoDownloadYieldsToDeleteAll() = runBlocking {
        Assume.assumeTrue("device has trip maps of its own; not deleting them", foreignRegions().isEmpty())
        val c = controller()

        val video = async(Dispatchers.Default) { c.downloadForTrack(TRIP, longTrack()) }
        awaitVideoDownloading(TRIP)

        val took = kotlin.system.measureTimeMillis { withTimeout(5_000) { c.deleteAll() } }
        assertTrue("the video download returns with what arrived", withTimeout(5_000) { video.await() })

        assertTrue("deleteAll took $took ms", took < 5_000)
        assertTrue("no VIDEO region is left", ourRegions().isEmpty())
        assertTrue(refs.all().isEmpty())
    }

    private companion object {
        /** No real trip has such an id, so this test never touches the user's own offline regions. */
        const val TRIP = 987_654_321L

        /** A second trip no real trip has. */
        const val OTHER = 987_654_322L
        const val DOWNLOAD_TIMEOUT_MS = 180_000L
    }
}
