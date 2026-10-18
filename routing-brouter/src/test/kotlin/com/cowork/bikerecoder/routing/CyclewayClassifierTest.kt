package com.cowork.bikerecoder.routing

import com.cowork.bikerecoder.routing.CyclewayClassifier.isCycleFriendly
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CyclewayClassifierTest {

    @Test
    fun classifies() {
        assertTrue(isCycleFriendly("highway=cycleway surface=asphalt"))
        assertTrue(isCycleFriendly("highway=residential route_bicycle_ncn=yes"))
        assertTrue(isCycleFriendly("highway=secondary cycleway:right=track"))
        assertFalse(isCycleFriendly("highway=primary cycleway=no"))
    }

    @Test
    fun `recognises every cycle route network and cycleway side`() {
        for (net in listOf("icn", "ncn", "rcn", "lcn")) {
            assertTrue(isCycleFriendly("highway=tertiary route_bicycle_$net=yes"), net)
        }
        for (key in listOf("cycleway", "cycleway:left", "cycleway:right", "cycleway:both")) {
            assertTrue(isCycleFriendly("highway=tertiary $key=lane"), "$key=lane")
            assertTrue(isCycleFriendly("highway=tertiary $key=track"), "$key=track")
        }
    }

    @Test
    fun `does not match lookalikes`() {
        assertFalse(isCycleFriendly("highway=cycleway_construction"))
        assertFalse(isCycleFriendly("highway=residential route_bicycle_ncn=proposed"))
        assertFalse(isCycleFriendly("highway=residential cycleway=shared_lane"))
        assertFalse(isCycleFriendly("highway=residential xcycleway=lane"))
        assertFalse(isCycleFriendly(""))
    }
}
