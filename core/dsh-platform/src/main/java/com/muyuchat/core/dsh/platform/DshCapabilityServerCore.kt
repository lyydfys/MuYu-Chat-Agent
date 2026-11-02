package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapabilityAccess
import com.muyuchat.core.dsh.contract.capability.DshCapabilityAuthority
import com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode
import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityLedgerDecision
import com.muyuchat.core.dsh.contract.capability.DshCapabilityOperationState
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRequestId
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteLimits
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceAdmission
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceAdmissionGate
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceLeaseKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pure-Kotlin dispatch policy of the App-local capability server.
 *
 * It owns the order of every decision and nothing else: authenticate, resolve a fixed route,
 * enforce rate/concurrency admission, consult the idempotency ledger, run one typed handler with a
 * bounded timeout, and settle exactly one outcome. Socket IO lives in
 * [DshCapabilityLoopbackServer], so every branch below is testable on the JVM without a device.
 *
 * Identity headers (an MCA addition on the managed transport; the official packages send none):
 *  - `X-DSH-Token: <dshc_...>` is required on every request.
 *  - `X-DSH-Generation: <long>` is optional; when present it must equal the active generation.
 *  - `X-DSH-Request-Id: <16..64 URL-safe chars>` (or query `requestId`) enables idempotency.
 *    Without it the request is single-shot and the ledger is not consulted.
 *
 * One pinned-package compatibility exception: `dsh-tool-shizuku`'s app-process shell path sends
 * `POST /shell` with the token only in its JSON body, not in the header (verified in the locked
 * payload's `appShellCmd`). For that one route/method a well-formed `dshc_` body token is accepted
 * as the credential. Every other route ignores a body token, and the same generation, constant-time
 * token and capability-set checks run either way; this is authentication transport, not a bypass.
 */
class DshCapabilityServerCore(
    private val authority: DshCapabilityAuthority,
    private val registry: DshCapabilityRouteRegistry,
    private val ledger: DshCapabilityLedger,
    private val throttle: DshCapabilityThrottle,
    private val requestTimeoutMillis: Long = 15_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Optional per-path budget. The terminal family is a poller by nature (one read every 40 ms
     * while a shell is open), so it must not consume the general 120/min anti-abuse budget; every
     * other path keeps [throttle]. Both budgets are still bounded and per generation.
     */
    private val budgetFor: (String) -> DshCapabilityThrottle = { throttle },
    /**
     * Shared maintenance admission (D07m/D22a). Every capability request takes one MUTATION
     * lease for its whole execution, so an exclusive maintenance barrier cannot start while a
     * tool call is live, and a tool call is refused while the barrier holds the gate. The lease is
     * MUTATION (not READ) for every route because any handler may write user data; a future
     * read-only route can be narrowed to READ once it is classified.
     */
    private val maintenance: DshMaintenanceAdmissionGate? = null
) {
    init {
        require(requestTimeoutMillis in 1L..300_000L) { "capability request timeout is out of range" }
    }

    /**
     * Dispatches one parsed request. A parse rejection never reaches this function; the adapter
     * converts [DshCapabilityHttpParseException] into [DshCapabilityErrorEnvelope.error].
     */
    suspend fun handle(request: ParsedDshCapabilityRequest): DshCapabilityHttpResult {
        val grant = authority.currentGrant()
            ?: return fail(DshCapabilityErrorCode.STALE_GENERATION, "capability bridge is not active")
        val token = request.header(TOKEN_HEADER)
            ?.takeIf { it.isNotEmpty() }
            ?: shellBodyToken(request)
        if (token.isNullOrEmpty() || token.length > DshCapabilityRouteLimits.MAX_TOKEN_CHARS) {
            return fail(DshCapabilityErrorCode.UNAUTHORIZED, "capability credential was rejected")
        }
        if (!grant.token.matches(token)) {
            return fail(DshCapabilityErrorCode.UNAUTHORIZED, "capability credential was rejected")
        }
        val declaredGeneration = request.header(GENERATION_HEADER)
        val generation = if (declaredGeneration == null) {
            grant.generation
        } else {
            val parsed = declaredGeneration.trim().toLongOrNull()
            if (parsed == null || parsed < 0L) {
                return fail(
                    DshCapabilityErrorCode.INVALID_REQUEST,
                    "capability generation header is malformed"
                )
            }
            parsed
        }
        if (generation != grant.generation) {
            return fail(
                DshCapabilityErrorCode.STALE_GENERATION,
                "capability generation is stale"
            )
        }
        val method = DshCapabilityHttpMethod.entries.firstOrNull { it.name == request.method }
        val definition = when (val resolution = registry.resolve(request.path, method)) {
            DshCapabilityRouteResolution.NotFound ->
                return fail(DshCapabilityErrorCode.INVALID_REQUEST, "capability route is not registered", 404)

            is DshCapabilityRouteResolution.MethodNotAllowed -> {
                val allow = resolution.allowed
                    .sortedBy { it.name }
                    .joinToString(", ") { it.name }
                return DshCapabilityErrorEnvelope.error(
                    code = DshCapabilityErrorCode.INVALID_REQUEST,
                    message = "capability method is not allowed for this route",
                    statusCode = 405
                ).copy(extraHeaders = mapOf("Allow" to allow))
            }

            is DshCapabilityRouteResolution.Found -> resolution.definition
        }
        val routeMethod = requireNotNull(method) { "a resolved route always carries a known method" }
        when (val access = authority.authorize(token, generation, definition.capability)) {
            DshCapabilityAccess.Authorized -> Unit
            is DshCapabilityAccess.Denied ->
                return DshCapabilityErrorEnvelope.error(access.failure.code, access.failure.message)
        }

        val requestId = request.header(REQUEST_ID_HEADER)
            ?: request.query[REQUEST_ID_QUERY_PARAM]
        val parsedRequestId = if (requestId == null) {
            null
        } else {
            runCatching { DshCapabilityRequestId(requestId) }.getOrNull()
                ?: return fail(
                    DshCapabilityErrorCode.INVALID_REQUEST,
                    "capability request id is malformed"
                )
        }
        val fingerprint = capabilityPayloadFingerprint(
            routeMethod,
            request.path,
            request.query,
            request.body
        )
        if (parsedRequestId != null) {
            ledger.begin(parsedRequestId, request.path, fingerprint, generation, clock()).let { decision ->
                when (decision) {
                    DshCapabilityLedgerDecision.New -> Unit
                    is DshCapabilityLedgerDecision.Replay ->
                        return DshCapabilityHttpResult(decision.statusCode, decision.bodyJson)

                    DshCapabilityLedgerDecision.InProgress -> return fail(
                        DshCapabilityErrorCode.REQUEST_IN_PROGRESS,
                        "an identical capability request is still in progress"
                    )

                    DshCapabilityLedgerDecision.OutcomeUnknown -> return fail(
                        DshCapabilityErrorCode.OUTCOME_UNKNOWN,
                        "the previous attempt has no known outcome and is not re-executed"
                    )

                    DshCapabilityLedgerDecision.FingerprintMismatch -> return fail(
                        DshCapabilityErrorCode.INVALID_REQUEST,
                        "capability request id was reused with different parameters",
                        statusCode = 409
                    )

                    DshCapabilityLedgerDecision.Expired -> return fail(
                        DshCapabilityErrorCode.OUTCOME_UNKNOWN,
                        "capability request id has expired and is not re-executed"
                    )

                    DshCapabilityLedgerDecision.CapacityExceeded -> return fail(
                        DshCapabilityErrorCode.RATE_LIMITED,
                        "capability ledger is full; no new identity is admitted",
                        retryAfterMillis = LEDGER_RETRY_MILLIS
                    )
                }
            }
        }

        val lease = maintenance?.let { gate ->
            when (val admission = gate.tryAcquire(DshMaintenanceLeaseKind.MUTATION, generation)) {
                is DshMaintenanceAdmission.Refused -> return fail(
                    DshCapabilityErrorCode.CAPABILITY_UNAVAILABLE,
                    "capability bridge is paused for DSH maintenance",
                    statusCode = 503,
                    retryAfterMillis = MAINTENANCE_RETRY_MILLIS
                )

                is DshMaintenanceAdmission.Admitted -> admission.lease
            }
        }
        try {
            val limiter = budgetFor(request.path)
            return when (val admission = limiter.tryAcquire()) {
                is DshCapabilityThrottleDecision.Admitted -> {
                    try {
                        execute(definition, request, routeMethod, generation, parsedRequestId, fingerprint)
                    } finally {
                        limiter.release(admission.leaseId)
                    }
                }

                is DshCapabilityThrottleDecision.RateLimited -> fail(
                    DshCapabilityErrorCode.RATE_LIMITED,
                    "capability request rate limit reached",
                    retryAfterMillis = admission.retryAfterMillis
                )

                DshCapabilityThrottleDecision.ConcurrencyLimited -> fail(
                    DshCapabilityErrorCode.RATE_LIMITED,
                    "too many concurrent capability requests"
                )
            }
        } finally {
            lease?.let { held -> maintenance?.release(held.leaseId, held.generation) }
        }
    }

    private suspend fun execute(
        definition: DshCapabilityRouteDefinition,
        request: ParsedDshCapabilityRequest,
        method: DshCapabilityHttpMethod,
        generation: Long,
        requestId: DshCapabilityRequestId?,
        fingerprint: String
    ): DshCapabilityHttpResult {
        val routeRequest = DshCapabilityRouteRequest(
            path = request.path,
            method = method,
            query = request.query,
            bodyJson = request.body.ifEmpty { null },
            localToken = null,
            generation = generation,
            receivedAtMillis = clock()
        )
        val response = try {
            withTimeoutOrNull(requestTimeoutMillis) {
                definition.handler.handle(routeRequest)
            }
        } catch (cancellation: CancellationException) {
            settleUnknown(requestId, fingerprint)
            throw cancellation
        } catch (failure: Throwable) {
            settleUnknown(requestId, fingerprint)
            return fail(
                DshCapabilityErrorCode.INTERNAL,
                "capability handler failed; outcome is unknown"
            )
        }
        if (response == null) {
            settleUnknown(requestId, fingerprint)
            return fail(
                DshCapabilityErrorCode.TIMEOUT,
                "capability handler timed out; outcome is unknown"
            )
        }
        return when (response) {
            is DshCapabilityRouteResponse.Json -> {
                requestId?.let {
                    ledger.complete(
                        requestId = it,
                        payloadFingerprint = fingerprint,
                        state = DshCapabilityOperationState.OBSERVED,
                        statusCode = response.statusCode,
                        bodyJson = response.bodyJson,
                        nowMillis = clock()
                    )
                }
                DshCapabilityHttpResult(response.statusCode, response.bodyJson)
            }

            DshCapabilityRouteResponse.Unrouted -> {
                val result = fail(
                    DshCapabilityErrorCode.INVALID_REQUEST,
                    "capability route is no longer registered",
                    404
                )
                requestId?.let {
                    ledger.complete(
                        requestId = it,
                        payloadFingerprint = fingerprint,
                        state = DshCapabilityOperationState.REJECTED,
                        statusCode = result.statusCode,
                        bodyJson = result.bodyJson,
                        nowMillis = clock()
                    )
                }
                result
            }
        }
    }

    /**
     * The pinned `dsh-tool-shizuku` app-shell path authenticates through the body only. Extract a
     * strictly well-formed MCA capability token for `POST /shell`; any other route/method, malformed
     * value or oversized body yields null and the normal 401 path. The value is compared with
     * [DshCapabilityToken.matches] like a header token and is never echoed or logged.
     */
    private fun shellBodyToken(request: ParsedDshCapabilityRequest): String? {
        if (request.path != SHELL_ROUTE || request.method != DshCapabilityHttpMethod.POST.name) return null
        if (request.body.isEmpty()) return null
        return SHELL_BODY_TOKEN.find(request.body)?.groupValues?.get(1)
    }

    private fun settleUnknown(requestId: DshCapabilityRequestId?, fingerprint: String) {
        if (requestId != null) {
            ledger.markUnknown(requestId, fingerprint, clock())
        }
    }

    private fun fail(
        code: DshCapabilityErrorCode,
        message: String,
        statusCode: Int = DshCapabilityErrorEnvelope.statusFor(code),
        retryAfterMillis: Long? = null
    ): DshCapabilityHttpResult =
        DshCapabilityErrorEnvelope.error(code, message, statusCode, retryAfterMillis)

    companion object {
        const val TOKEN_HEADER = "x-dsh-token"
        const val GENERATION_HEADER = "x-dsh-generation"
        const val REQUEST_ID_HEADER = "x-dsh-request-id"
        const val REQUEST_ID_QUERY_PARAM = "requestId"

        private const val SHELL_ROUTE = "/shell"

        /** Retry hint while an exclusive maintenance barrier holds the admission gate. */
        const val MAINTENANCE_RETRY_MILLIS = 1_000L

        /**
         * Exact token grammar, not a general JSON parse: `"token":"dshc_"` plus 43 URL-safe chars.
         * The strict shape means a body that merely mentions the word cannot authorize anything.
         */
        private val SHELL_BODY_TOKEN =
            Regex("\"token\"\\s*:\\s*\"(dshc_[A-Za-z0-9_-]{43})\"")

        private const val LEDGER_RETRY_MILLIS = 1_000L
    }
}
