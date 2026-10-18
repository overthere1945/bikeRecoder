package com.cowork.bikerecoder

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.routing.RouteFailure
import com.cowork.bikerecoder.core.routing.RouteRequest
import com.cowork.bikerecoder.core.routing.RouteResult
import com.cowork.bikerecoder.core.routing.Router
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LazyRouterTest {
    private val request = RouteRequest(GeoPoint(37.5, 127.0), null, listOf(GeoPoint(37.6, 127.1)), RouteProfile.BALANCED)

    @Test
    fun `creation failure becomes a Failure result and is retried next time`() = runBlocking {
        var attempts = 0
        val ok = object : Router {
            override suspend fun route(request: RouteRequest) = RouteResult.Failure(RouteFailure.NO_ROUTE, "delegate")
        }
        val router = LazyRouter {
            attempts++
            if (attempts == 1) error("profile install failed")
            ok
        }
        val first = router.route(request)
        assertInstanceOf(RouteResult.Failure::class.java, first)
        assertEquals(RouteFailure.OTHER, (first as RouteResult.Failure).reason)
        assertEquals("profile install failed", first.detail)

        assertEquals(RouteFailure.NO_ROUTE, (router.route(request) as RouteResult.Failure).reason)
        assertEquals(2, attempts)
    }

    @Test
    fun `cancellation during creation is rethrown`() {
        val router = LazyRouter { throw CancellationException("cancelled") }
        assertThrows(CancellationException::class.java) { runBlocking { router.route(request) } }
    }
}
