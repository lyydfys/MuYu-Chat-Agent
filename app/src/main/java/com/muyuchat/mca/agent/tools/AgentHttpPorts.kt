package com.muyuchat.mca.agent.tools

import com.muyuchat.mca.agent.security.AgentEgressOrigins
import java.net.URI

/**
 * Frozen rules for the destination of `http.get`.
 *
 * These checks run twice: in the argument normalizer (before the approval is shown, so the approval
 * binds a canonical destination) and again in the executor (so a forged frozen command cannot skip
 * them). They are a *destination* policy, not an authorization: whether this run may send the
 * request is decided independently by [AgentHttpGetGate].
 *
 * Rules (architecture 11.3 + TOOL-15):
 * - `https` only; cleartext, `file:`, `data:` and every other scheme are refused;
 * - no userinfo, no fragment, no control/whitespace characters, bounded length;
 * - loopback, link-local and private-range hosts are refused (a public tool must never become an
 *   SSRF primitive against the device or the LAN);
 * - redirects are not followed at all (the transport refuses a 3xx), because a redirect changes the
 *   authorized origin.
 */
internal sealed interface AgentHttpUrlResult {
    data class Valid(val url: String, val origin: String) : AgentHttpUrlResult

    data class Invalid(val code: String, val safeMessage: String) : AgentHttpUrlResult
}

internal object AgentHttpDestinationRules {
    const val MAX_URL_CHARS: Int = 2_048

    fun check(raw: String): AgentHttpUrlResult {
        if (raw.isBlank() || raw.length > MAX_URL_CHARS) return invalid("The URL is blank or too long.")
        if (raw.any { it.isWhitespace() || it < ' ' }) {
            return invalid("The URL contains whitespace or control characters.")
        }
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            return invalid("The URL is not a valid absolute URI.")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https") return invalid("Only absolute https URLs are supported.")
        if (uri.rawUserInfo != null) return invalid("The URL must not embed credentials.")
        if (uri.rawFragment != null) return invalid("The URL must not contain a fragment.")
        val host = uri.host?.lowercase()
        if (host.isNullOrBlank()) return invalid("The URL must name a host.")
        if (uri.port != -1 && uri.port !in 1..65_535) return invalid("The URL port is out of range.")
        val origin = if (uri.port == -1 || uri.port == 443) {
            "https://$host"
        } else {
            "https://$host:${uri.port}"
        }
        if (AgentEgressOrigins.isLocalOrPrivate(origin)) {
            return invalid("The URL host is a loopback, link-local or private address.")
        }
        return AgentHttpUrlResult.Valid(uri.toString(), origin)
    }

    private fun invalid(detail: String): AgentHttpUrlResult.Invalid =
        AgentHttpUrlResult.Invalid("POLICY_EGRESS_DENIED", detail)
}

/** One bounded GET request handed to the transport seam. */
data class AgentHttpGetRequest(
    val url: String,
    val origin: String,
    val maxBytes: Int,
    val timeoutMillis: Long
)

/** Transport outcome. Failures are typed and never carry a raw server body or a stack trace. */
sealed interface AgentHttpGetOutcome {
    /**
     * A 2xx answer. [text] is null when the payload is not valid UTF-8 text; [truncated] is true
     * when the server sent more than [AgentHttpGetRequest.maxBytes].
     */
    data class Response(
        val statusCode: Int,
        val contentType: String?,
        val bodyBytes: Int,
        val text: String?,
        val truncated: Boolean
    ) : AgentHttpGetOutcome

    data class Failure(
        val code: String,
        val safeMessage: String,
        val possibleSideEffects: Boolean = false
    ) : AgentHttpGetOutcome
}

/** Narrow HTTP seam; the production implementation is [OkHttpAgentHttpTextClient]. */
interface AgentHttpTextClient {
    suspend fun get(request: AgentHttpGetRequest): AgentHttpGetOutcome
}

/** Host-side facts needed to decide whether this run may contact one origin. */
data class AgentHttpGetAuthorization(
    val runId: String,
    val origin: String,
    val url: String
)

sealed interface AgentHttpGetGateDecision {
    data object Allowed : AgentHttpGetGateDecision

    data class Denied(val code: String, val safeMessage: String) : AgentHttpGetGateDecision
}

/**
 * Run-scoped egress authorization for `http.get`.
 *
 * The production implementation wraps [com.muyuchat.mca.agent.security.AgentNetworkEgressPolicy]
 * plus the persisted run grants; it is not wired by this slice (composition root is out of scope).
 * The default refuses everything, so a catalog registered without an explicit gate stays usable in
 * name only and never sends a byte.
 */
fun interface AgentHttpGetGate {
    suspend fun authorize(request: AgentHttpGetAuthorization): AgentHttpGetGateDecision

    companion object {
        val DENY_ALL: AgentHttpGetGate = AgentHttpGetGate {
            AgentHttpGetGateDecision.Denied(
                code = "POLICY_EGRESS_DENIED",
                safeMessage = "This run has no network egress authorization for http.get."
            )
        }
    }
}
