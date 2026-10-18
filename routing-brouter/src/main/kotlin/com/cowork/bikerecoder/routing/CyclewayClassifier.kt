package com.cowork.bikerecoder.routing

/** Decides from BRouter's `WayTags` string whether a way segment counts as cycle-friendly. */
object CyclewayClassifier {

    private val CYCLE_FRIENDLY = Regex(
        """(?:^|\s)(?:highway=cycleway|route_bicycle_(?:icn|ncn|rcn|lcn)=yes|cycleway(?::left|:right|:both)?=(?:lane|track))(?=\s|$)""",
    )

    /** True for `highway=cycleway`, a signed cycle route, or a cycle lane/track on either side. */
    fun isCycleFriendly(wayTags: String): Boolean = CYCLE_FRIENDLY.containsMatchIn(wayTags)
}
