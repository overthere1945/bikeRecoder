package com.cowork.bikerecoder.search

import com.cowork.bikerecoder.core.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RecentPlaceRulesTest {
    private val station = Place("부산역", "부산 동구", GeoPoint(35.115, 129.041), null)
    private val beach = Place("해운대해수욕장", "부산 해운대구", GeoPoint(35.158, 129.160), null)

    /** [p] moved [metres] north (1° latitude ≈ 111.2 km). */
    private fun north(p: Place, metres: Double) = p.copy(point = GeoPoint(p.point.lat + metres / 111_195.0, p.point.lon))

    @Test
    fun `a new place goes to the front`() {
        assertEquals(listOf(beach, station), RecentPlaceRules.add(listOf(station), beach))
    }

    @Test
    fun `picking the same place again moves it to the top once`() {
        val list = RecentPlaceRules.add(RecentPlaceRules.add(listOf(station), beach), north(station, 5.0))
        assertEquals(listOf(north(station, 5.0), beach), list)
    }

    @Test
    fun `the same name more than 10 m away is another place`() {
        val far = north(station, 15.0)
        assertEquals(listOf(far, station), RecentPlaceRules.add(listOf(station), far))
    }

    @Test
    fun `another name at the same point is another place`() {
        val other = station.copy(name = "부산역 광장")
        assertEquals(listOf(other, station), RecentPlaceRules.add(listOf(station), other))
    }

    @Test
    fun `the eleventh place drops the oldest`() {
        val places = (1..11).map { Place("장소 $it", "", GeoPoint(35.0 + it / 100.0, 129.0), null) }
        val list = places.fold(emptyList<Place>()) { acc, p -> RecentPlaceRules.add(acc, p) }
        assertEquals(RecentPlaceRules.MAX, list.size)
        assertEquals(places.drop(1).reversed(), list)
    }

    @Test
    fun `the distance is not stored`() {
        val list = RecentPlaceRules.add(emptyList(), station.copy(distanceM = 3_400))
        assertNull(list.single().distanceM)
        assertEquals(station, list.single())
    }

    @Test
    fun `remove takes out only the same place`() {
        val list = listOf(beach, station)
        assertEquals(listOf(beach), RecentPlaceRules.remove(list, station.copy(distanceM = 120)))
        assertEquals(list, RecentPlaceRules.remove(list, north(station, 15.0)))
    }
}
