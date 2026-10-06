package com.cowork.bikerecoder

import com.cowork.bikerecoder.core.routing.RouteFailure
import com.cowork.bikerecoder.core.routing.RouteRequest
import com.cowork.bikerecoder.core.routing.RouteResult
import com.cowork.bikerecoder.core.routing.Router
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds the real router (and installs its profiles) on [Dispatchers.IO] the first time it is needed.
 * A failing [create] (e.g. disk full while copying profiles) is reported as a [RouteResult.Failure] and
 * retried on the next call.
 */
internal class LazyRouter(create: () -> Router) : Router {
    private val delegate by lazy(create)

    override suspend fun route(request: RouteRequest): RouteResult {
        val router = try {
            withContext(Dispatchers.IO) { delegate }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RouteResult.Failure(RouteFailure.OTHER, e.message ?: e.toString())
        }
        return router.route(request)
    }
}
