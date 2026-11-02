package com.muyuchat.core.dsh.contract.capability

/**
 * Shared transport contract of the MCA DSH Android capability bridge.
 *
 * The pinned mobile reference (`woaiys3/deepseek-harness-android-app` @ `98162a23`) ships five
 * official DSH tool packages that call the App over a loopback HTTP surface:
 *
 *  - `dsh-tool-android`, `dsh-tool-shizuku`, `dsh-tool-browser`: `127.0.0.1:$APP_NOTIFY_PORT`
 *    with header `X-DSH-Token: $APP_LOCAL_TOKEN`;
 *  - `dsh-tool-accessibility`: `127.0.0.1:$APP_A11Y_PORT` with the same header;
 *  - `dsh-tool-vscreen`: its own bridge port (the plugin launches the privileged bridge itself).
 *
 * MCA keeps those official tool packages and their tool schemas, and replaces the untrusted App
 * side with an authenticated bridge: a per-generation token, a fixed route table, an idempotency
 * ledger and typed handlers. The contract below is the seam between that server (in
 * `:core:dsh-platform`) and the per-domain handlers (`:core:dsh-android-actions`, `:core:dsh-a11y`,
 * `:core:dsh-vscreen`, `:core:dsh-browser`). It stays platform-free: no Android type, no `Uri`,
 * no `Context`, no JSON library.
 */
object DshCapabilityRouteLimits {
    /** Hard ceiling for one request body, in bytes, before any handler runs. */
    const val MAX_BODY_BYTES = 262_144

    /** Hard ceiling for the whole query string. */
    const val MAX_QUERY_CHARS = 4_096

    /** Hard ceiling for distinct query pairs. */
    const val MAX_QUERY_PAIRS = 24

    /** Hard ceiling for one query key or value. */
    const val MAX_QUERY_VALUE_CHARS = 1_024

    /** Hard ceiling for the local path. */
    const val MAX_PATH_CHARS = 128

    /** Hard ceiling for a response body produced by a handler. */
    const val MAX_RESPONSE_BYTES = 8_388_608

    /** Longest accepted `X-DSH-Token`. */
    const val MAX_TOKEN_CHARS = 128

    /** Longest accepted consent prompt text. */
    const val MAX_CONSENT_CHARS = 300
}

/** Only these two verbs exist on the App-local capability surface. */
enum class DshCapabilityHttpMethod { GET, POST }

/**
 * One authenticated request as the route handler sees it.
 *
 * [localToken] is the raw `X-DSH-Token` value only so the server can compare it with a
 * constant-time helper; a handler must never log, persist or echo it. [generation] is the host
 * generation that owns the token, so a handler can reject a request from a dead generation.
 */
data class DshCapabilityRouteRequest(
    val path: String,
    val method: DshCapabilityHttpMethod,
    val query: Map<String, String>,
    val bodyJson: String?,
    val localToken: String?,
    val generation: Long,
    val receivedAtMillis: Long
) {
    init {
        require(path.length in 1..DshCapabilityRouteLimits.MAX_PATH_CHARS && path.startsWith('/')) {
            "capability route path must be an absolute local path"
        }
        require(query.size <= DshCapabilityRouteLimits.MAX_QUERY_PAIRS) {
            "capability request carries too many query pairs"
        }
        query.forEach { (key, value) ->
            require(key.length <= DshCapabilityRouteLimits.MAX_QUERY_VALUE_CHARS) {
                "capability query key is too long"
            }
            require(value.length <= DshCapabilityRouteLimits.MAX_QUERY_VALUE_CHARS) {
                "capability query value is too long"
            }
        }
        require(bodyJson == null || bodyJson.length <= DshCapabilityRouteLimits.MAX_BODY_BYTES) {
            "capability request body exceeds the contract limit"
        }
        require(localToken == null || localToken.length <= DshCapabilityRouteLimits.MAX_TOKEN_CHARS) {
            "capability token exceeds the contract limit"
        }
    }

    /** Log-safe projection: never includes the token or the raw body. */
    val redacted: String
        get() = "DshCapabilityRouteRequest($method $path, queryKeys=${query.keys.sorted()}, " +
            "bodyChars=${bodyJson?.length ?: 0}, generation=$generation)"
}

/**
 * Result of one route execution.
 *
 * The official tool packages parse the App response as JSON and mostly read an `ok` flag, so a
 * handler owns the exact response document. The server adds nothing: it only writes [bodyJson]
 * with [statusCode] after the ledger recorded the terminal outcome.
 */
sealed interface DshCapabilityRouteResponse {
    data class Json(val statusCode: Int, val bodyJson: String) : DshCapabilityRouteResponse {
        init {
            require(statusCode in 100..599) { "capability status code is out of range" }
            require(bodyJson.length <= DshCapabilityRouteLimits.MAX_RESPONSE_BYTES) {
                "capability response body exceeds the contract limit"
            }
        }
    }

    /** No handler for this path in the current generation; the server answers 404. */
    data object Unrouted : DshCapabilityRouteResponse
}

/** A typed, post-authorization handler for exactly one local path. */
fun interface DshCapabilityRouteHandler {
    suspend fun handle(request: DshCapabilityRouteRequest): DshCapabilityRouteResponse
}

/**
 * Immutable path -> handler table. Built once per app composition and shared read-only.
 *
 * Duplicate registration is rejected at construction time instead of silently shadowing a
 * handler: two owners of `/shell`, for example, must never both be reachable.
 */
class DshCapabilityRouteTable private constructor(
    private val routes: Map<String, DshCapabilityRouteHandler>
) {
    fun handlerFor(path: String): DshCapabilityRouteHandler? = routes[path]

    fun paths(): List<String> = routes.keys.sorted()

    class Builder {
        private val routes = LinkedHashMap<String, DshCapabilityRouteHandler>()

        fun route(path: String, handler: DshCapabilityRouteHandler): Builder {
            require(path.startsWith('/') && path.length <= DshCapabilityRouteLimits.MAX_PATH_CHARS) {
                "capability route path must be an absolute local path"
            }
            require(routes.put(path, handler) == null) {
                "capability route '$path' is already owned by another handler"
            }
            return this
        }

        fun build(): DshCapabilityRouteTable = DshCapabilityRouteTable(routes.toMap())
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}

/**
 * The environment the official tool packages need in the DSH Node process.
 *
 * Values follow the pinned reference so the official packages stay unmodified:
 * `APP_NOTIFY_PORT`, `APP_LOCAL_TOKEN`, `APP_A11Y_PORT`, `ROOT_AVAILABLE`, `SHIZUKU_AVAILABLE`,
 * `SHIZUKU_APP_ID`, `SHIZUKU_DEX`, `SHIZUKU_APPROVE`.
 *
 * [localToken] is a per-generation secret. It is held in memory only, never written to the DSH
 * home, never put in a WebView, never logged; [toString] is redacted so an accidental log of the
 * endpoint stays safe.
 */
data class DshCapabilityBridgeEndpoint(
    val appLocalPort: Int,
    val accessibilityPort: Int?,
    val generation: Long,
    val localToken: String,
    val rootAvailable: Boolean,
    val shizukuAvailable: Boolean,
    val shizukuAppId: String? = null,
    val shizukuDexPath: String? = null,
    val approvalAsk: Boolean = true
) {
    init {
        require(appLocalPort in 1..65_535) { "App-local capability port is out of range" }
        require(accessibilityPort == null || accessibilityPort in 1..65_535) {
            "accessibility capability port is out of range"
        }
        require(generation >= 1L) { "capability bridge generation must be positive" }
        require(localToken.length in 16..DshCapabilityRouteLimits.MAX_TOKEN_CHARS) {
            "capability token must be 16..${DshCapabilityRouteLimits.MAX_TOKEN_CHARS} characters"
        }
        require(localToken.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) {
            "capability token must be URL-safe"
        }
    }

    /** Environment additions for the official mobile tool packages. Never log this map. */
    fun environment(): Map<String, String> = buildMap {
        put("APP_NOTIFY_PORT", appLocalPort.toString())
        put("APP_LOCAL_TOKEN", localToken)
        if (accessibilityPort != null) put("APP_A11Y_PORT", accessibilityPort.toString())
        put("ROOT_AVAILABLE", if (rootAvailable) "1" else "0")
        put("SHIZUKU_AVAILABLE", if (shizukuAvailable) "1" else "0")
        put("SHIZUKU_APPROVE", if (approvalAsk) "ask" else "auto")
        shizukuAppId?.let { put("SHIZUKU_APP_ID", it) }
        shizukuDexPath?.let { put("SHIZUKU_DEX", it) }
    }

    override fun toString(): String =
        "DshCapabilityBridgeEndpoint(appLocalPort=$appLocalPort, accessibilityPort=$accessibilityPort, " +
            "generation=$generation, rootAvailable=$rootAvailable, shizukuAvailable=$shizukuAvailable, " +
            "token=<redacted>)"
}

/** Narrow provider the runtime owner asks for the current generation's bridge endpoint. */
fun interface DshCapabilityBridgeEndpointProvider {
    suspend fun endpoint(): DshCapabilityBridgeEndpoint?
}

/** What the bridge asks the user before a high-consequence platform action runs. */
data class DshCapabilityConsentPrompt(
    val title: String,
    val detail: String,
    val capability: DshCapability
) {
    init {
        require(title.isNotBlank() && title.length <= DshCapabilityRouteLimits.MAX_CONSENT_CHARS)
        require(detail.length <= DshCapabilityRouteLimits.MAX_CONSENT_CHARS)
    }
}

/** Fail-closed decision: anything other than [ALLOW] must not run the action. */
enum class DshCapabilityConsentDecision { ALLOW, DENY, TIMEOUT }

/**
 * User-presence bound consent. The platform owner calls this only while a resumed surface can
 * show the prompt; in the background it returns [DshCapabilityConsentDecision.DENY] instead of
 * pulling an Activity forward.
 */
fun interface DshCapabilityConsentPort {
    suspend fun requestConsent(prompt: DshCapabilityConsentPrompt): DshCapabilityConsentDecision
}
