package com.cowork.bikerecoder.core.places

import com.cowork.bikerecoder.core.model.GeoPoint
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceMatcherTest {

    private val origin = GeoPoint(36.0, 128.0)

    private fun east(from: GeoPoint, metres: Double) =
        GeoPoint(from.lat, from.lon + metres / (111_320.0 * cos(Math.toRadians(from.lat))))

    private fun north(from: GeoPoint, metres: Double) =
        GeoPoint(from.lat + metres / 110_540.0, from.lon)

    /** Track points every [stepM] metres heading east for [lengthM]. */
    private fun eastTrack(from: GeoPoint, lengthM: Int, stepM: Int = 40): List<GeoPoint> =
        (0..lengthM step stepM).map { east(from, it.toDouble()) }

    private fun peak(tag: String, at: GeoPoint, group: String? = null) =
        Place(PlaceKind.PEAK, tag, group, null, listOf(listOf(at)))

    private fun route(tag: String, group: String?, vararg pts: GeoPoint) =
        Place(PlaceKind.ROUTE, tag, group, null, listOf(pts.toList()))

    private fun data(vararg places: Place) = PlaceData(1, "2026-11-01", places.toList())

    private fun matcher(vararg places: Place) = PlaceMatcher(data(*places))

    @Test
    fun peakWithin150mTags() {
        val m = matcher(peak("#북한산", north(east(origin, 1_000.0), 140.0), "#100대명산"))
        assertEquals(listOf("#북한산", "#100대명산"), m.tags(listOf(eastTrack(origin, 2_000))))
    }

    @Test
    fun peakAt160mDoesNot() {
        val m = matcher(peak("#북한산", north(east(origin, 1_000.0), 160.0), "#100대명산"))
        assertEquals(emptyList(), m.tags(listOf(eastTrack(origin, 2_000))))
    }

    @Test
    fun parallelLineTagsAfter1km() {
        val m = matcher(route("#낙동강자전거길", "#국토종주", north(origin, 90.0), north(east(origin, 3_000.0), 90.0)))
        assertEquals(listOf("#낙동강자전거길", "#국토종주"), m.tags(listOf(eastTrack(origin, 1_200))))
    }

    @Test
    fun shortParallelDoesNot() {
        val m = matcher(route("#낙동강자전거길", "#국토종주", north(origin, 90.0), north(east(origin, 3_000.0), 90.0)))
        assertEquals(emptyList(), m.tags(listOf(eastTrack(origin, 900))))
    }

    @Test
    fun bridgeCrossingDoesNot() {
        // Route runs north-south; the track runs east-west across it.
        val x = east(origin, 1_000.0)
        val m = matcher(route("#낙동강자전거길", "#국토종주", north(x, -2_000.0), north(x, 2_000.0)))
        assertEquals(emptyList(), m.tags(listOf(eastTrack(origin, 2_000, 10))))
    }

    @Test
    fun farParallelDoesNot() {
        val m = matcher(route("#낙동강자전거길", "#국토종주", north(origin, 120.0), north(east(origin, 3_000.0), 120.0)))
        assertEquals(emptyList(), m.tags(listOf(eastTrack(origin, 2_000))))
    }

    @Test
    fun centreTagsItsRoute() {
        // The route is 5 km north of the track, so only the certification centre is near.
        val routeLine = route("#금강자전거길", "#국토종주", north(origin, 5_000.0), north(east(origin, 3_000.0), 5_000.0))
        val centre = Place(
            PlaceKind.CENTER, "#금강하굿둑", "#국토종주", 0,
            listOf(listOf(north(east(origin, 1_000.0), 140.0))),
        )
        val m = matcher(routeLine, centre)
        assertEquals(listOf("#금강자전거길", "#국토종주"), m.tags(listOf(eastTrack(origin, 2_000))))
    }

    @Test
    fun badCentreRouteIndexIsIgnored() {
        val centre = Place(PlaceKind.CENTER, "#c", null, 7, listOf(listOf(east(origin, 100.0))))
        val peakAsRoute = peak("#p", origin)
        val centre2 = Place(PlaceKind.CENTER, "#c2", null, 0, listOf(listOf(east(origin, 100.0))))
        assertEquals(emptyList(), matcher(centre).tags(listOf(eastTrack(origin, 500))))
        assertEquals(listOf("#p"), matcher(peakAsRoute, centre2).tags(listOf(eastTrack(origin, 500))))
    }

    @Test
    fun orderIsFirstMatchAndTagsAreUnique() {
        val a = peak("#A", north(east(origin, 100.0), 50.0), "#100대명산")
        val r1 = route("#동해안자전거길", "#국토종주", north(east(origin, 1_000.0), 30.0), north(east(origin, 3_000.0), 30.0))
        val r2 = route("#동해안자전거길", "#국토종주", north(east(origin, 3_500.0), 30.0), north(east(origin, 6_000.0), 30.0))
        // Listed in reverse so the order must come from the track, not the data.
        val m = matcher(r2, r1, a)
        assertEquals(
            listOf("#A", "#동해안자전거길", "#100대명산", "#국토종주"),
            m.tags(listOf(eastTrack(origin, 7_000))),
        )
    }

    @Test
    fun atMostSixPlusGroups() {
        val peaks = (0 until 8).map { peak("#P$it", north(east(origin, 1_000.0 * it), 20.0), "#100대명산") }
        val m = PlaceMatcher(PlaceData(1, "d", peaks))
        assertEquals(
            (0 until 6).map { "#P$it" } + "#100대명산",
            m.tags(listOf(eastTrack(origin, 8_000))),
        )
    }

    @Test
    fun allTracksCount() {
        val day2 = north(origin, 20_000.0)
        val m = matcher(peak("#A", east(origin, 500.0)), peak("#B", east(day2, 500.0)))
        assertEquals(listOf("#A", "#B"), m.tags(listOf(eastTrack(origin, 1_000), eastTrack(day2, 1_000))))
    }

    @Test
    fun emptyTrackIsEmpty() {
        val m = matcher(peak("#A", origin))
        assertEquals(emptyList(), m.tags(emptyList()))
        assertEquals(emptyList(), m.tags(listOf(emptyList())))
    }
}
