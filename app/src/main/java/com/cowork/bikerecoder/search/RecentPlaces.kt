package com.cowork.bikerecoder.search

import com.cowork.bikerecoder.core.model.GeoMath
import kotlinx.coroutines.flow.Flow

/** The "최근 검색" list (spec §7.3): places picked from search results, newest first. */
object RecentPlaceRules {
    const val MAX = 10
    const val SAME_PLACE_M = 10.0

    /** [place] at the front, any earlier copy of the same place removed, at most [MAX]. The distance is not kept. */
    fun add(list: List<Place>, place: Place): List<Place> =
        (listOf(place.copy(distanceM = null)) + remove(list, place)).take(MAX)

    fun remove(list: List<Place>, place: Place): List<Place> = list.filterNot { isSamePlace(it, place) }

    /** The same name and at most [SAME_PLACE_M] apart. */
    private fun isSamePlace(a: Place, b: Place): Boolean =
        a.name == b.name && GeoMath.distanceM(a.point, b.point) <= SAME_PLACE_M
}

interface RecentPlaceStore {
    /** Newest first. */
    val places: Flow<List<Place>>

    suspend fun add(place: Place)

    suspend fun remove(place: Place)

    suspend fun clear()
}
