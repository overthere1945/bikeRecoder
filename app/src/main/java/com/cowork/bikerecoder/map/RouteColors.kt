package com.cowork.bikerecoder.map

import com.cowork.bikerecoder.core.elevation.ElevationProfile
import com.cowork.bikerecoder.core.elevation.GradeBand
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
    var total = 0.0
    return paths.map { path ->
        path.mapIndexed { i, p ->
            if (i > 0) total += GeoMath.distanceM(path[i - 1], p)
            bandHex(profile.bandAt(total), flatHex)
        }
    }
}

/** "#RRGGBB" of [band], or [flatHex] for FLAT when given. */
fun bandHex(band: GradeBand, flatHex: String? = null): String =
    if (band == GradeBand.FLAT && flatHex != null) flatHex else String.format(Locale.ROOT, "#%06X", band.argb and 0xFFFFFF)
