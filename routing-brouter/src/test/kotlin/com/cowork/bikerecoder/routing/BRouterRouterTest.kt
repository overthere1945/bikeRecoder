package com.cowork.bikerecoder.routing

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.routing.RouteFailure
import com.cowork.bikerecoder.core.routing.RouteRequest
import com.cowork.bikerecoder.core.routing.RouteResult
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Error mapping that needs no real segment data. */
class BRouterRouterTest {

    private val a = GeoPoint(37.5284, 126.9327)
    private val b = GeoPoint(37.5100, 126.9960)

    @Test
    fun `engine exception becomes Failure OTHER instead of escaping`(@TempDir segDir: File, @TempDir profDir: File) {
        ProfileInstaller.install(profDir)
        // No stops: BRouter's doRun throws IllegalArgumentException("we need two lat/lon points ...").
        val result = runBlocking {
            BRouterRouter(segDir, profDir).route(RouteRequest(a, null, emptyList(), RouteProfile.BALANCED))
        }
        val failure = assertIs<RouteResult.Failure>(result, result.toString())
        assertEquals(RouteFailure.OTHER, failure.reason, failure.detail)
    }

    @Test
    fun `missing profile files never throw`(@TempDir segDir: File, @TempDir emptyProfileDir: File) {
        val result = runBlocking {
            BRouterRouter(segDir, emptyProfileDir).route(RouteRequest(a, null, listOf(b), RouteProfile.BALANCED))
        }
        println("missing profile -> $result")
        assertIs<RouteResult.Failure>(result, result.toString())
    }
}
