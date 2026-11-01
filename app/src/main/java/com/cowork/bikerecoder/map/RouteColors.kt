package com.cowork.bikerecoder.map

import com.cowork.bikerecoder.core.elevation.ElevationProfile
import com.cowork.bikerecoder.core.elevation.GradeBand
import com.cowork.bikerecoder.core.elevation.TrackAxis
import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import java.util.Locale

/**
 * The gradient route line (V1.7.0 spec §3.1) for [MapOverlay.routeColors]: per point of [route] the "#RRGGBB" of the
 * profile's grade band at that point's distance ([Route.cumulativeM]); FLAT is [flatHex] when given (the guidance line
 * keeps its own colour), else the table's blue. Null without a [profile] (the line stays one colour).
 */
fun routeColors(route: Route, profile: ElevationProfile?, flatHex: String?): List<String>? {
    if (profile == null) return null
    return route.cumulativeM.map { d -> bandHex(profile.bandAt(d), flatHex) }
}

/**
 * The colours of recorded or planned [paths] laid along one [profile] (one list per path, one "#RRGGBB" per point),
 * for [MapLine.colors]. Distances run on as the profile's do: from point to point within a path, nothing across the gap
 * from one path to the next ([ElevationProfile.fromSegments]); FLAT as in [routeColors].
 */
fun pathColors(paths: List<List<GeoPoint>>, profile: ElevationProfile, flatHex: String? = null): List<List<String>> {
    val distancesM = DoubleArray(paths.sumOf { it.size })
    var total = 0.0
    var k = 0
    for (path in paths) {
        for (i in path.indices) {
            if (i > 0) total += GeoMath.distanceM(path[i - 1], path[i])
            distancesM[k++] = total
        }
    }
    return colorsAlong(paths, distancesM) { d -> bandHex(profile.bandAt(d), flatHex) }
}

/**
 * The colours of [axis]'s [paths] (the same points, [TrackAxis.paths]) by [colorAt] of each point's axis distance, for
 * a speed or heart-rate line; a point without a colour (no value there) is [NO_VALUE_HEX] grey.
 */
fun pathColors(paths: List<List<GeoPoint>>, colorAt: (Double) -> String?, axis: TrackAxis): List<List<String>> {
    require(paths.map { it.size } == axis.paths.map { it.size }) { "paths do not match the axis" }
    return colorsAlong(paths, axis.distancesM, colorAt)
}

/** [colorAt] of [distancesM] (one per point of [paths], in order), cut into the paths' lists; null is [NO_VALUE_HEX]. */
private fun colorsAlong(paths: List<List<GeoPoint>>, distancesM: DoubleArray, colorAt: (Double) -> String?): List<List<String>> {
    var k = 0
    return paths.map { path -> path.map { colorAt(distancesM[k++]) ?: NO_VALUE_HEX } }
}

/** The line's grey where a speed or heart-rate line has no value ([HrBand.NONE][com.cowork.bikerecoder.core.post.HrBand.NONE]'s grey). */
private const val NO_VALUE_HEX = "#9E9E9E"

/** "#RRGGBB" of [band], or [flatHex] for FLAT when given. */
fun bandHex(band: GradeBand, flatHex: String? = null): String =
    if (band == GradeBand.FLAT && flatHex != null) flatHex else BAND_HEX.getValue(band)

/** The bands' "#RRGGBB", formatted once (a long route asks for one per point). */
private val BAND_HEX: Map<GradeBand, String> =
    GradeBand.entries.associateWith { String.format(Locale.ROOT, "#%06X", it.argb and 0xFFFFFF) }
