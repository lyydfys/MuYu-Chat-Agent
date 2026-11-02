package com.muyuchat.core.dsh.runtime.process

import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteHandler
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteTable
import com.muyuchat.core.dsh.contract.runtime.DshTerminalCloseOutcome
import com.muyuchat.core.dsh.contract.runtime.DshTerminalSize
import com.muyuchat.core.dsh.runtime.payload.DshJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Authenticated App-local bridge surface for the official DSH terminal tools.
 *
 * The official kernel executes terminal tools in Node; Android owns the only real PTY
 * (`libdshpty.so`). The official subprocess seam therefore forwards the terminal primitives to
 * this table over the same per-generation loopback bridge and `X-DSH-Token` the other official
 * mobile packages already use (see `DshCapabilityBridgeContract`). Every handler:
 *
 *  - parses a bounded JSON body and rejects malformed/over-limit input with `bad-args`;
 *  - never sees or logs the token (the server authenticates before dispatch);
 *  - delegates to exactly one [DshTerminalToolService] owner, so the bridge cannot create a
 *    second terminal runtime;
 *  - returns the real transport (`pty`/`pipe`), real exit facts and real stop facts. A refusal is
 *    `ok:false` with a typed reason, never a fabricated success.
 *
 * Paths are fixed. Mounting them is the only App-composition step; the capability family used
 * for authorization is chosen by the composition owner (the handlers intentionally do not encode
 * a capability enum here).
 */
object DshTerminalToolRoutes {

    const val OPEN = "/terminal/open"
    const val ATTACH = "/terminal/attach"
    const val READ = "/terminal/read"
    const val WRITE = "/terminal/write"
    const val CONTROL = "/terminal/control"
    const val RESIZE = "/terminal/resize"
    const val LIST = "/terminal/list"
    const val CLOSE = "/terminal/close"
    const val TOOLCHAIN_VERIFY = "/toolchain/verify"

    val PATHS: List<String> = listOf(OPEN, ATTACH, READ, WRITE, CONTROL, RESIZE, LIST, CLOSE, TOOLCHAIN_VERIFY)

    /** Builds the immutable table for one generation's service. */
    fun table(service: DshTerminalToolService): DshCapabilityRouteTable {
        val builder = DshCapabilityRouteTable.builder()
        builder.route(OPEN, io(service) { target, request -> target.openSession(request) })
        builder.route(ATTACH, io(service) { target, request -> target.attachSession(request) })
        builder.route(READ, io(service) { target, request -> target.readSession(request) })
        builder.route(WRITE, io(service) { target, request -> target.writeSession(request) })
        builder.route(CONTROL, io(service) { target, request -> target.controlSession(request) })
        builder.route(RESIZE, io(service) { target, request -> target.resizeSession(request) })
        builder.route(LIST, io(service) { target, _ -> target.sessions() })
        builder.route(CLOSE, io(service) { target, request -> target.closeSession(request) })
        builder.route(TOOLCHAIN_VERIFY, io(service) { target, _ -> target.toolchainReport() })
        return builder.build()
    }

    /**
     * Wraps a blocking handler: the bridge server runs handlers on its own dispatcher, but PTY
     * open/read/close and the toolchain probes block on purpose, so they are moved to IO.
     */
    private fun io(
        service: DshTerminalToolService,
        handler: (DshTerminalToolService, DshCapabilityRouteRequest) -> DshCapabilityRouteResponse
    ): DshCapabilityRouteHandler = DshCapabilityRouteHandler { request ->
        withContext(Dispatchers.IO) { handler(service, request) }
    }

    // ------------------------------------------------------------------ operations

    private fun DshTerminalToolService.openSession(request: DshCapabilityRouteRequest): DshCapabilityRouteResponse {
        val body = bodyOf(request) ?: return badArgs("the request body is not a JSON object")
        val type = DshJson.optString(body, "type")?.trim().takeUnless { it.isNullOrEmpty() } ?: DshTerminalHost.SHELL_TYPE
        val name = DshJson.optString(body, "name")
        val cwd = DshJson.optString(body, "cwd")
        val size = sizeOf(body) ?: return badArgs("cols/rows must both be integers in 1..${DshTerminalSize.MAX}")
        val requirePty = booleanOf(body, "requirePty") ?: requiresRealPty
        return when (val outcome = open(type, name, cwd, size, requirePty)) {
            is DshTerminalOpenOutcome.Opened -> json(
                200,
                mapOf(
                    "ok" to true,
                    "session" to snapshotOf(outcome.snapshot),
                    "motd" to outcome.motd.take(MAX_MOTD_CHARS)
                )
            )

            is DshTerminalOpenOutcome.Refused -> refusal(outcome.reason, outcome.detail)
        }
    }

    private fun DshTerminalToolService.attachSession(request: DshCapabilityRouteRequest): DshCapabilityRouteResponse {
        val body = bodyOf(request) ?: return badArgs("the request body is not a JSON object")
        val sessionId = sessionIdOf(body) ?: return badArgs("sessionId is required")
        // Absent cols/rows on attach means "reconnect without changing geometry", not 80x24.
        val requestedSize = if (body["cols"] == null && body["rows"] == null) {
            null
        } else {
            sizeOf(body) ?: return badArgs("cols/rows must both be integers in 1..${DshTerminalSize.MAX}")
        }
        val maxBytes = maxBytesOf(body) ?: return badArgs("maxBytes is out of range")
        return when (val outcome = attach(sessionId, requestedSize, maxBytes)) {
            is DshTerminalReadOutcome.Ok -> json(200, readPayload(outcome.page))
            is DshTerminalReadOutcome.Refused -> refusal(outcome.reason, outcome.detail)
        }
    }

    private fun DshTerminalToolService.readSession(request: DshCapabilityRouteRequest): DshCapabilityRouteResponse {
        val body = bodyOf(request) ?: return badArgs("the request body is not a JSON object")
        val sessionId = sessionIdOf(body) ?: return badArgs("sessionId is required")
        val offset = DshJson.optLong(body, "offset") ?: 0L
        if (offset < 0L) return badArgs("offset must not be negative")
        val maxBytes = maxBytesOf(body) ?: return badArgs("maxBytes is out of range")
        return when (val outcome = read(sessionId, offset, maxBytes)) {
            is DshTerminalReadOutcome.Ok -> json(200, readPayload(outcome.page))
            is DshTerminalReadOutcome.Refused -> refusal(outcome.reason, outcome.detail)
        }
    }

    private fun DshTerminalToolService.writeSession(request: DshCapabilityRouteRequest): DshCapabilityRouteResponse {
        val body = bodyOf(request) ?: return badArgs("the request body is not a JSON object")
        val sessionId = sessionIdOf(body) ?: return badArgs("sessionId is required")
        val text = DshJson.optString(body, "text") ?: return badArgs("text is required")
        if (text.toByteArray(Charsets.UTF_8).size > MAX_INPUT_BYTES) {
            return json(413, error("input-too-large", "terminal input exceeds $MAX_INPUT_BYTES bytes"))
        }
        val submit = booleanOf(body, "submit") ?: true
        return writeOutcome(write(sessionId, text, submit))
    }

    private fun DshTerminalToolService.controlSession(request: DshCapabilityRouteRequest): DshCapabilityRouteResponse {
        val body = bodyOf(request) ?: return badArgs("the request body is not a JSON object")
        val sessionId = sessionIdOf(body) ?: return badArgs("sessionId is required")
        val raw = DshJson.optString(body, "control")?.trim()?.uppercase()
            ?: return badArgs("control is required")
        val control = DshTerminalControl.entries.firstOrNull { it.name == raw }
            ?: return badArgs("unknown control '$raw'")
        return writeOutcome(control(sessionId, control))
    }

    private fun DshTerminalToolService.resizeSession(request: DshCapabilityRouteRequest): DshCapabilityRouteResponse {
        val body = bodyOf(request) ?: return badArgs("the request body is not a JSON object")
        val sessionId = sessionIdOf(body) ?: return badArgs("sessionId is required")
        val size = sizeOf(body) ?: return badArgs("cols/rows must both be integers in 1..${DshTerminalSize.MAX}")
        return when (val outcome = resize(sessionId, size)) {
            is DshTerminalResizeOutcome.Applied -> json(
                200,
                mapOf(
                    "ok" to true,
                    "applied" to true,
                    "coalesced" to false,
                    "transport" to outcome.transport.name,
                    "cols" to outcome.size.columns,
                    "rows" to outcome.size.rows
                )
            )

            is DshTerminalResizeOutcome.Coalesced -> json(
                200,
                mapOf(
                    "ok" to true,
                    "applied" to false,
                    "coalesced" to true,
                    "transport" to outcome.transport.name,
                    "cols" to outcome.size.columns,
                    "rows" to outcome.size.rows,
                    "detail" to "resize coalesced; it is applied on the next pump frame"
                )
            )

            is DshTerminalResizeOutcome.Unsupported -> json(
                200,
                mapOf(
                    "ok" to false,
                    "applied" to false,
                    "coalesced" to false,
                    "reason" to "resize-unsupported",
                    "transport" to outcome.transport.name,
                    "cols" to outcome.size.columns,
                    "rows" to outcome.size.rows,
                    "detail" to outcome.detail
                )
            )

            is DshTerminalResizeOutcome.Refused -> refusal(outcome.reason, outcome.detail)
        }
    }

    private fun DshTerminalToolService.sessions(): DshCapabilityRouteResponse =
        json(200, mapOf("ok" to true, "sessions" to list().map { snapshotOf(it) }))

    private fun DshTerminalToolService.closeSession(request: DshCapabilityRouteRequest): DshCapabilityRouteResponse {
        val body = bodyOf(request) ?: return badArgs("the request body is not a JSON object")
        val sessionId = sessionIdOf(body) ?: return badArgs("sessionId is required")
        val outcome = close(sessionId) ?: return json(
            404,
            error("unknown-session", "unknown terminal session '$sessionId' for this owner")
        )
        return closePayload(outcome)
    }

    private fun DshTerminalToolService.toolchainReport(): DshCapabilityRouteResponse {
        val report = verifyToolchain(force = false)
        return json(
            200,
            mapOf(
                "ok" to report.usable,
                "usable" to report.usable,
                "summary" to report.summary(),
                "verifiedAtMillis" to report.verifiedAtMillis,
                "probes" to report.probes.map { probe ->
                    mapOf(
                        "role" to probe.role.name,
                        "launch" to probe.launch,
                        "argv" to probe.argv,
                        "state" to probe.state.name,
                        "passed" to probe.passed,
                        "exitCode" to probe.exitCode,
                        "output" to probe.output,
                        "detail" to probe.detail,
                        "durationMillis" to probe.durationMillis
                    )
                },
                "dependencies" to report.dependencies.map { dependency ->
                    mapOf(
                        "name" to dependency.name,
                        "kind" to dependency.kind.name,
                        "path" to dependency.path,
                        "present" to dependency.present,
                        "detail" to dependency.detail
                    )
                },
                "failures" to report.failures.map { it.role.name },
                "missingDependencies" to report.missingDependencies.map { it.name },
                // The typed "is this GNU bash?" answer, separate from the probe list: a passing
                // `sh` marker probe is never presented as GNU bash support.
                "bashCompatibility" to report.shellCompatibility.facts()
            )
        )
    }

    // ------------------------------------------------------------------ payloads

    private fun readPayload(page: DshTerminalRead): Map<String, Any?> = mapOf(
        "ok" to true,
        "sessionId" to page.sessionId,
        "text" to page.text,
        "bytes" to page.bytes,
        "nextOffset" to page.nextOffset,
        "truncated" to page.truncated,
        "status" to page.status.name,
        "exitCode" to page.exit?.code,
        "signal" to page.exit?.signal
    )

    private fun snapshotOf(snapshot: DshTerminalSnapshot): Map<String, Any?> = mapOf(
        "sessionId" to snapshot.sessionId,
        "name" to snapshot.name,
        "type" to snapshot.type,
        "transport" to snapshot.transport.name,
        "realPty" to snapshot.realPty,
        "pid" to snapshot.pid,
        "status" to snapshot.status.name,
        "exitCode" to snapshot.exit?.code,
        "signal" to snapshot.exit?.signal,
        "cols" to snapshot.size?.columns,
        "rows" to snapshot.size?.rows,
        "createdAtMillis" to snapshot.createdAtMillis,
        "firstRetainedOffset" to snapshot.firstRetainedOffset,
        "nextOffset" to snapshot.nextOffset
    )

    private fun writeOutcome(outcome: DshTerminalWriteOutcome): DshCapabilityRouteResponse = when (outcome) {
        is DshTerminalWriteOutcome.Written -> json(200, mapOf("ok" to true, "bytes" to outcome.bytes))
        is DshTerminalWriteOutcome.PeerClosed -> json(
            409,
            error("peer-closed", "the terminal child is gone; poll terminal_status/read for the exit fact")
        )

        is DshTerminalWriteOutcome.Refused -> refusal(outcome.reason, outcome.detail)
    }

    private fun closePayload(outcome: DshTerminalCloseOutcome): DshCapabilityRouteResponse = json(
        if (outcome.settled) 200 else 409,
        mapOf(
            "ok" to outcome.settled,
            "settled" to outcome.settled,
            "escalated" to outcome.escalated,
            "detail" to outcome.detail,
            "reason" to if (outcome.settled) null else "stop-timeout"
        )
    )

    private fun refusal(reason: DshTerminalRefusal, detail: String): DshCapabilityRouteResponse =
        json(statusOf(reason), error(reason.wireName(), detail.take(MAX_DETAIL_CHARS)))

    private fun DshTerminalRefusal.wireName(): String = name.lowercase().replace('_', '-')

    private fun statusOf(reason: DshTerminalRefusal): Int = when (reason) {
        DshTerminalRefusal.UNSUPPORTED_TYPE,
        DshTerminalRefusal.INVALID_WORKING_DIRECTORY,
        DshTerminalRefusal.INVALID_NAME -> 400

        DshTerminalRefusal.LIMIT_REACHED -> 429
        DshTerminalRefusal.UNKNOWN_SESSION -> 404
        DshTerminalRefusal.HOST_CLOSED -> 410
        DshTerminalRefusal.PTY_UNAVAILABLE,
        DshTerminalRefusal.SESSION_EXITED,
        DshTerminalRefusal.SESSION_FAILED -> 409

        DshTerminalRefusal.SHELL_UNAVAILABLE,
        DshTerminalRefusal.OPEN_FAILED,
        DshTerminalRefusal.MAINTENANCE_ACTIVE -> 503
    }

    // ------------------------------------------------------------------ parsing

    private fun bodyOf(request: DshCapabilityRouteRequest): Map<String, Any?>? {
        val raw = request.bodyJson?.trim().orEmpty()
        if (raw.isEmpty()) return emptyMap()
        return try {
            DshJson.asObject(DshJson.parse(raw), "body")
        } catch (t: IllegalArgumentException) {
            null
        }
    }

    private fun sessionIdOf(body: Map<String, Any?>): String? {
        val id = DshJson.optString(body, "sessionId")?.trim().orEmpty()
        if (id.isEmpty() || id.length > MAX_SESSION_ID_CHARS || id.any { it.code < 0x20 }) return null
        return id
    }

    private fun sizeOf(body: Map<String, Any?>): DshTerminalSize? {
        val cols = DshJson.optLong(body, "cols")
        val rows = DshJson.optLong(body, "rows")
        if (cols == null && rows == null) return DshTerminalSize.DEFAULT
        if (cols == null || rows == null) return null
        if (cols !in 1..DshTerminalSize.MAX.toLong() || rows !in 1..DshTerminalSize.MAX.toLong()) return null
        return DshTerminalSize.sanitized(cols.toInt(), rows.toInt())
    }

    private fun maxBytesOf(body: Map<String, Any?>): Int? {
        val raw = DshJson.optLong(body, "maxBytes") ?: return DshTerminalHost.DEFAULT_READ_BYTES
        if (raw !in 1..DshTerminalHost.MAX_READ_BYTES.toLong()) return null
        return raw.toInt()
    }

    private fun booleanOf(body: Map<String, Any?>, key: String): Boolean? =
        body[key]?.let { it as? Boolean }

    private fun json(status: Int, body: Map<String, Any?>): DshCapabilityRouteResponse.Json =
        DshCapabilityRouteResponse.Json(status, DshJson.write(body))

    private fun error(reason: String, detail: String): Map<String, Any?> =
        mapOf("ok" to false, "reason" to reason, "detail" to detail)

    private fun badArgs(detail: String): DshCapabilityRouteResponse =
        json(400, error("bad-args", detail))

    private const val MAX_INPUT_BYTES = 64 * 1024
    private const val MAX_SESSION_ID_CHARS = 64
    private const val MAX_DETAIL_CHARS = 300
    private const val MAX_MOTD_CHARS = 8 * 1024
}
