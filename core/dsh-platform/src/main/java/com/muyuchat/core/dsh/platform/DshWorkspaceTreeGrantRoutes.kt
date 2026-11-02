package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode
import com.muyuchat.core.dsh.contract.capability.DshCapabilityFailure
import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRequestId
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteHandler
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteTable
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeGrantRequest
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeGrantResult
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeGrantState
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeGrantStatus
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeReleaseRequest

/**
 * The three typed routes of the persistent workspace-tree grant.
 *
 * They are a *view* of [DshWorkspaceTreeGrantOwner]; no route keeps its own grant state and no
 * route ever receives the raw URI from DSH. The request route returns immediately with
 * `pending=true` because the capability server timeout is far shorter than one human picker
 * session; the client polls the status route for the terminal fact.
 *
 * Envelope: `{"ok":true,...}` for an observed state, `{"ok":false,"errorCode":...}` for a refusal.
 * The state names are the contract enum names, so a DSH-side adapter cannot invent a sixth state.
 */
class DshWorkspaceTreeGrantRoutes(private val owner: DshWorkspaceTreeGrantOwner) {

    fun register(builder: DshCapabilityRouteTable.Builder): DshCapabilityRouteTable.Builder {
        builder.route(
            STATUS_PATH,
            DshCapabilityRouteHandler { request -> status(request) }
        )
        builder.route(
            REQUEST_PATH,
            DshCapabilityRouteHandler { request -> requestGrant(request) }
        )
        builder.route(
            RELEASE_PATH,
            DshCapabilityRouteHandler { request -> release(request) }
        )
        return builder
    }

    private fun status(request: DshCapabilityRouteRequest): DshCapabilityRouteResponse {
        if (request.method != DshCapabilityHttpMethod.GET) return methodNotAllowed()
        rejectUnknownKeys(request, emptySet())?.let { return it }
        return json(200, statusBody(owner.status()))
    }

    private fun requestGrant(request: DshCapabilityRouteRequest): DshCapabilityRouteResponse {
        if (request.method != DshCapabilityHttpMethod.POST) return methodNotAllowed()
        rejectUnknownKeys(request, setOf(QUERY_REQUEST_ID, QUERY_WRITE, QUERY_REASON))?.let { return it }
        val writeAccess = when (request.query[QUERY_WRITE]) {
            null, "1", "true" -> true
            "0", "false" -> false
            else -> return rejected(
                DshCapabilityFailure(
                    DshCapabilityErrorCode.INVALID_REQUEST,
                    DshCapability.WORKSPACE_TREE,
                    "workspace tree write flag must be 0/1/true/false"
                )
            )
        }
        val typed = runCatching {
            DshWorkspaceTreeGrantRequest(
                requestId = requestId(request),
                generation = request.generation,
                reason = request.query[QUERY_REASON] ?: DEFAULT_REASON,
                writeAccess = writeAccess
            )
        }.getOrNull() ?: return rejected(
            DshCapabilityFailure(
                DshCapabilityErrorCode.INVALID_REQUEST,
                DshCapability.WORKSPACE_TREE,
                "workspace tree request is malformed"
            )
        )
        return when (val result = owner.requestGrant(typed)) {
            is DshWorkspaceTreeGrantResult.Granted -> json(200, statusBody(result.status))
            is DshWorkspaceTreeGrantResult.PickStarted -> json(200, statusBody(result.status))
            is DshWorkspaceTreeGrantResult.Released -> json(200, statusBody(result.status))
            is DshWorkspaceTreeGrantResult.Rejected -> rejected(result.failure)
        }
    }

    private fun release(request: DshCapabilityRouteRequest): DshCapabilityRouteResponse {
        if (request.method != DshCapabilityHttpMethod.POST) return methodNotAllowed()
        rejectUnknownKeys(request, setOf(QUERY_REQUEST_ID, QUERY_REASON))?.let { return it }
        val typed = runCatching {
            DshWorkspaceTreeReleaseRequest(
                requestId = requestId(request),
                generation = request.generation,
                reason = request.query[QUERY_REASON] ?: DEFAULT_RELEASE_REASON
            )
        }.isSuccess
        if (!typed) {
            return rejected(
                DshCapabilityFailure(
                    DshCapabilityErrorCode.INVALID_REQUEST,
                    DshCapability.WORKSPACE_TREE,
                    "workspace tree release is malformed"
                )
            )
        }
        return when (val result = owner.release()) {
            is DshWorkspaceTreeGrantResult.Released -> json(200, statusBody(result.status))
            is DshWorkspaceTreeGrantResult.Rejected -> rejected(result.failure)
            is DshWorkspaceTreeGrantResult.Granted,
            is DshWorkspaceTreeGrantResult.PickStarted ->
                rejected(
                    DshCapabilityFailure(
                        DshCapabilityErrorCode.INTERNAL,
                        DshCapability.WORKSPACE_TREE,
                        "workspace tree release returned an impossible result"
                    )
                )
        }
    }

    /** The idempotency id the caller supplied, or a bounded deterministic id for this call. */
    private fun requestId(request: DshCapabilityRouteRequest): DshCapabilityRequestId {
        val supplied = request.query[QUERY_REQUEST_ID]
        if (supplied != null) return DshCapabilityRequestId(supplied)
        return DshCapabilityRequestId(
            "ws-tree-request-${request.generation}-${request.receivedAtMillis}"
        )
    }

    private fun rejectUnknownKeys(
        request: DshCapabilityRouteRequest,
        allowed: Set<String>
    ): DshCapabilityRouteResponse? {
        val unknown = request.query.keys - allowed
        if (unknown.isEmpty()) return null
        return rejected(
            DshCapabilityFailure(
                DshCapabilityErrorCode.INVALID_REQUEST,
                DshCapability.WORKSPACE_TREE,
                "unsupported workspace tree query parameter"
            )
        )
    }

    private fun methodNotAllowed(): DshCapabilityRouteResponse = json(
        405,
        DshWorkspaceTreeJsonObject()
            .boolean("ok", false)
            .string("errorCode", DshCapabilityErrorCode.INVALID_REQUEST.name)
            .string("error", "workspace tree route does not accept this method")
            .build()
    )

    private fun rejected(failure: DshCapabilityFailure): DshCapabilityRouteResponse = json(
        200,
        DshWorkspaceTreeJsonObject()
            .boolean("ok", false)
            .string("errorCode", failure.code.name)
            .string("error", failure.message)
            .boolean("retryable", failure.retryable)
            .build()
    )

    private fun statusBody(status: DshWorkspaceTreeGrantStatus): String {
        val body = DshWorkspaceTreeJsonObject()
            .boolean("ok", true)
            .string("state", status.state.name)
            .boolean("pending", status.pendingPick)
            .string("access", status.access.kind.name)
            .boolean("persistentTreeGrant", status.isPersistentTreeGrant)
        if (status.state == DshWorkspaceTreeGrantState.GRANTED) {
            body.boolean("read", status.flags.read)
            body.boolean("write", status.flags.write)
        }
        status.grantedAtMillis?.let { body.number("grantedAtMillis", it) }
        status.detail?.let { body.string("detail", it) }
        return body.build()
    }

    private fun json(statusCode: Int, bodyJson: String): DshCapabilityRouteResponse =
        DshCapabilityRouteResponse.Json(statusCode, bodyJson)

    companion object {
        const val STATUS_PATH = "/workspace/tree/status"
        const val REQUEST_PATH = "/workspace/tree/request"
        const val RELEASE_PATH = "/workspace/tree/release"

        private const val QUERY_REQUEST_ID = "requestId"
        private const val QUERY_WRITE = "write"
        private const val QUERY_REASON = "reason"
        private const val DEFAULT_REASON = "DSH requested a persistent workspace folder grant"
        private const val DEFAULT_RELEASE_REASON = "DSH released the persistent workspace folder grant"

        fun paths(): List<String> = listOf(STATUS_PATH, REQUEST_PATH, RELEASE_PATH)

        /**
         * The authorization surface of one tree route. Status is a read; request and release are
         * side effects, so a token that may only read must not be able to open or drop a grant.
         */
        fun methodsFor(path: String): Set<DshCapabilityHttpMethod> = when (path) {
            STATUS_PATH -> setOf(DshCapabilityHttpMethod.GET)
            REQUEST_PATH, RELEASE_PATH -> setOf(DshCapabilityHttpMethod.POST)
            else -> throw IllegalArgumentException("unknown workspace tree route")
        }

        fun install(
            builder: DshCapabilityRouteTable.Builder,
            owner: DshWorkspaceTreeGrantOwner
        ): DshCapabilityRouteTable.Builder = DshWorkspaceTreeGrantRoutes(owner).register(builder)
    }
}

/** Minimal deterministic JSON writer; escapes provider/owner text, never locale-dependent. */
internal class DshWorkspaceTreeJsonObject {
    private val fields = ArrayList<String>()

    fun string(name: String, value: String?): DshWorkspaceTreeJsonObject {
        fields += quote(name) + ":" + if (value == null) "null" else quote(value)
        return this
    }

    fun boolean(name: String, value: Boolean): DshWorkspaceTreeJsonObject {
        fields += quote(name) + ":" + value
        return this
    }

    fun number(name: String, value: Long): DshWorkspaceTreeJsonObject {
        fields += quote(name) + ":" + value
        return this
    }

    fun build(): String = fields.joinToString(prefix = "{", postfix = "}")

    private fun quote(value: String): String {
        val out = StringBuilder(value.length + 2).append('"')
        for (character in value) {
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (character.code < 0x20) {
                    out.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(character)
                }
            }
        }
        return out.append('"').toString()
    }
}
