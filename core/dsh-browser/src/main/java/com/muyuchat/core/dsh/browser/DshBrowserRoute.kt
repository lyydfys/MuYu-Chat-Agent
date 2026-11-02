package com.muyuchat.core.dsh.browser

import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteHandler
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse

/**
 * Strict request entry of the browser protocol.
 *
 * Parsing happens here (not in the encoder): an envelope that fails validation never reaches the
 * session, and a project-wide contract failure is returned in the same `{ok:false,reason,error}`
 * shape the official package already renders.
 */
class DshBrowserRequestHandler(private val session: DshBrowserSession) {

    suspend fun handleBody(bodyJson: String?): String = when (val parsed = DshBrowserCallParser.parse(bodyJson)) {
        is DshBrowserCallResult.Invalid -> DshBrowserJson.failure(parsed.failure).toString()
        is DshBrowserCallResult.Parsed -> DshBrowserJson.encode(session.execute(parsed.call))
    }
}

/** In-process transport: the session runs in the caller's process (tests, degraded wiring). */
class DshBrowserInProcessTransport(private val session: DshBrowserSession) : DshBrowserTransport {
    private val handler = DshBrowserRequestHandler(session)

    override suspend fun call(bodyJson: String): String = handler.handleBody(bodyJson)
}

/**
 * Cross-process seam to the `:browser` service.
 *
 * The wire is the very same JSON envelope the App-local `/browser` route receives, so the service
 * that owns the WebViews runs the identical strict parser and session. A Binder implementation only
 * has to move two strings; it must not re-interpret the payload.
 */
fun interface DshBrowserTransport {
    /** Returns the browser response JSON; throws only for transport-level cancellation. */
    suspend fun call(bodyJson: String): String
}

/**
 * `DshCapabilityRouteHandler` for the App-local `/browser` route.
 *
 * The route is POST + JSON envelope, matching the pinned `dsh-tool-browser` package exactly
 * (`POST 127.0.0.1:$APP_NOTIFY_PORT/browser`, header `X-DSH-Token`; authentication itself is the
 * capability server's job, this handler runs post-authorization).
 *
 * Logical browser failures stay `200` with `ok:false`, because the official package parses the
 * body unconditionally and renders `reason`/`error`; a transport failure is still a JSON body so
 * the model gets `engine-unavailable` instead of a generic HTTP error.
 */
class DshBrowserRouteAdapter(
    private val transport: DshBrowserTransport
) : DshCapabilityRouteHandler {

    override suspend fun handle(request: DshCapabilityRouteRequest): DshCapabilityRouteResponse {
        if (request.path != ROUTE_PATH) return DshCapabilityRouteResponse.Unrouted
        if (request.method != DshCapabilityHttpMethod.POST) {
            return DshCapabilityRouteResponse.Json(
                statusCode = 405,
                bodyJson = DshBrowserJson.failure(
                    DshBrowserFailure(
                        DshBrowserFailureReason.BAD_ARGS,
                        "The browser route only accepts POST JSON action envelopes"
                    )
                ).toString()
            )
        }
        val body = request.bodyJson
        if (body.isNullOrBlank()) {
            return DshCapabilityRouteResponse.Json(
                statusCode = 200,
                bodyJson = DshBrowserJson.failure(
                    DshBrowserFailure(
                        DshBrowserFailureReason.BAD_ARGS,
                        "The browser request body is empty"
                    )
                ).toString()
            )
        }
        if (body.length > DshBrowserLimits.MAX_REQUEST_BYTES) {
            return DshCapabilityRouteResponse.Json(
                statusCode = 200,
                bodyJson = DshBrowserJson.failure(
                    DshBrowserFailure(
                        DshBrowserFailureReason.TOO_LARGE,
                        "The browser request body exceeds the contract limit"
                    )
                ).toString()
            )
        }
        val response = try {
            transport.call(body)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            DshBrowserJson.failure(
                DshBrowserFailure(
                    DshBrowserFailureReason.ENGINE_UNAVAILABLE,
                    "The isolated browser process is not reachable",
                    "the :browser process may have been stopped; the next request restarts it"
                )
            ).toString()
        }
        return DshCapabilityRouteResponse.Json(statusCode = 200, bodyJson = response)
    }

    companion object {
        /** Exact path the official `dsh-tool-browser` package posts to. */
        const val ROUTE_PATH = "/browser"

        /** Contract capability this route is authorized under. */
        const val CAPABILITY = "BROWSER"
    }
}
