package com.cowork.bikerecoder.map

/** Map tile/style provider. Swappable so an offline PMTiles source can replace OpenFreeMap later. */
interface TileSource {
    val styleUrl: String
    val attribution: String

    /** Returns the complete MapLibre style JSON, ready for `Style.Builder().fromJson`. */
    suspend fun styleJson(): String
}
