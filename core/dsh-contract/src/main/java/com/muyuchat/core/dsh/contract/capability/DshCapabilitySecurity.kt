package com.muyuchat.core.dsh.contract.capability

import java.security.SecureRandom

/**
 * The separate per-generation capability credential.
 *
 * Format: `dshc_` followed by 32 SecureRandom bytes encoded as unpadded base64url (43 characters,
 * 48 characters total). It is a fourth credential and must never reuse the DSH Web launch token or
 * the `dshg_` model-gateway token. Never log, persist, place in an Intent, or put it in a URL
 * query. [toString] is redacted so accidental interpolation cannot leak it.
 */
class DshCapabilityToken private constructor(val value: String) {

    init {
        require(isWellFormed(value)) {
            "capability token must be ${LENGTH} characters with the ${PREFIX} prefix"
        }
    }

    /** Constant-time comparison suitable for bearer-token checks. */
    fun matches(candidate: String): Boolean = constantTimeEquals(value, candidate)

    override fun equals(other: Any?): Boolean =
        other is DshCapabilityToken && matches(other.value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "DshCapabilityToken(redacted)"

    companion object {
        const val PREFIX = "dshc_"
        const val RANDOM_BYTES = 32
        const val ENCODED_CHARS = 43
        val LENGTH = PREFIX.length + ENCODED_CHARS

        /** Mints a fresh token for exactly one runtime generation. */
        fun mint(random: SecureRandom = SecureRandom()): DshCapabilityToken {
            val bytes = ByteArray(RANDOM_BYTES)
            random.nextBytes(bytes)
            return DshCapabilityToken(PREFIX + encodeBase64Url(bytes))
        }

        /** Parses an externally supplied token; returns null for any malformed value. */
        fun parse(raw: String): DshCapabilityToken? =
            if (isWellFormed(raw)) DshCapabilityToken(raw) else null
    }
}

/**
 * One active grant: the token, generation and capability set authorized for that generation.
 * Dropping the grant (see [DshCapabilityAuthority.revoke]) kills the token immediately.
 *
 * The capability set is copied on construction and exposed read-only so a caller cannot widen an
 * active grant by mutating the set it passed in.
 */
class DshCapabilityGrant internal constructor(
    val generation: Long,
    val token: DshCapabilityToken,
    capabilities: Set<DshCapability>
) {
    val capabilities: Set<DshCapability> = capabilities.toSet()

    init {
        require(generation >= 0L) {
            "capability grant generation must be non-negative"
        }
    }

    fun allows(capability: DshCapability): Boolean = capability in capabilities

    override fun toString(): String =
        "DshCapabilityGrant(generation=$generation, capabilities=$capabilities, token=redacted)"
}

/** Result of authenticating one `(token, generation, capability)` triple. */
sealed interface DshCapabilityAccess {
    data object Authorized : DshCapabilityAccess

    data class Denied(val failure: DshCapabilityFailure) : DshCapabilityAccess
}

/**
 * Single app-level authority for the `dshc_` credential.
 *
 * The owner starts one grant per runtime generation, publishes its endpoint, and calls [revoke]
 * when the generation stops, restarts or fails. Authorization checks generation first, then the
 * constant-time token match, then the granted capability set. A revoked or replaced grant fails
 * closed with `STALE_GENERATION`.
 */
class DshCapabilityAuthority(
    private val tokenFactory: () -> DshCapabilityToken = { DshCapabilityToken.mint() }
) {
    private val lock = Any()
    private var active: DshCapabilityGrant? = null
    private var lastGeneration: Long = -1L

    /** Starts the grant for a new, strictly advancing generation. */
    fun startGeneration(
        generation: Long,
        capabilities: Set<DshCapability>
    ): DshCapabilityGrant {
        require(generation >= 0L) {
            "capability generation must be non-negative"
        }
        return synchronized(lock) {
            require(generation > lastGeneration) {
                "capability generation must advance"
            }
            val grant = DshCapabilityGrant(
                generation = generation,
                token = tokenFactory(),
                capabilities = capabilities.toSet()
            )
            lastGeneration = generation
            active = grant
            grant
        }
    }

    /** Revokes the active generation. Returns true when a grant was active. */
    fun revoke(): Boolean = synchronized(lock) {
        val hadActive = active != null
        active = null
        hadActive
    }

    /** The current grant, including its secret token. In-memory owner use only. */
    fun currentGrant(): DshCapabilityGrant? = synchronized(lock) { active }

    /**
     * Authenticates one request triple. The caller must not call [DshCapabilityPort] when this
     * returns [DshCapabilityAccess.Denied].
     */
    fun authorize(
        token: String,
        generation: Long,
        capability: DshCapability
    ): DshCapabilityAccess = synchronized(lock) {
        val grant = active
            ?: return@synchronized denied(
                DshCapabilityErrorCode.STALE_GENERATION,
                capability,
                "capability authority is not active for this generation"
            )
        if (grant.generation != generation) {
            return@synchronized denied(
                DshCapabilityErrorCode.STALE_GENERATION,
                capability,
                "capability generation is stale"
            )
        }
        if (!grant.token.matches(token)) {
            return@synchronized denied(
                DshCapabilityErrorCode.UNAUTHORIZED,
                capability,
                "capability credential was rejected"
            )
        }
        if (!grant.allows(capability)) {
            return@synchronized denied(
                DshCapabilityErrorCode.CAPABILITY_UNAVAILABLE,
                capability,
                "capability is not granted for this generation"
            )
        }
        DshCapabilityAccess.Authorized
    }

    private fun denied(
        code: DshCapabilityErrorCode,
        capability: DshCapability,
        message: String
    ): DshCapabilityAccess.Denied = DshCapabilityAccess.Denied(
        DshCapabilityFailure(
            code = code,
            capability = capability,
            message = message,
            retryable = false
        )
    )
}

/**
 * Loopback coordinates for one runtime generation.
 *
 * The endpoint carries a secret. [toString] is redacted; never log, persist or put this object in
 * an Intent. The token is delivered to the DSH host through its environment, never through a URL
 * query or DSH session data.
 */
data class DshCapabilityEndpoint(
    val baseUrl: String,
    val token: DshCapabilityToken,
    val generation: Long
) {
    init {
        require(isLoopbackBaseUrl(baseUrl)) {
            "capability endpoint must be http://127.0.0.1:<port>"
        }
        require(generation >= 0L) {
            "capability endpoint generation must be non-negative"
        }
    }

    override fun toString(): String =
        "DshCapabilityEndpoint(generation=$generation, token=redacted)"
}

/** Null means no capability listener for this generation; DSH may still start. */
fun interface DshCapabilityEndpointProvider {
    suspend fun endpoint(): DshCapabilityEndpoint?
}

private val LOOPBACK_BASE_URL = Regex("""http://127\.0\.0\.1:(\d{1,5})""")

private fun isLoopbackBaseUrl(value: String): Boolean {
    val match = LOOPBACK_BASE_URL.matchEntire(value) ?: return false
    val port = match.groupValues[1].toIntOrNull() ?: return false
    return port in 1..65535
}

private fun isWellFormed(raw: String): Boolean {
    if (raw.length != DshCapabilityToken.LENGTH) return false
    if (!raw.startsWith(DshCapabilityToken.PREFIX)) return false
    for (index in DshCapabilityToken.PREFIX.length until raw.length) {
        if (!isBase64UrlCharacter(raw[index])) return false
    }
    return true
}

private fun isBase64UrlCharacter(character: Char): Boolean =
    character in 'A'..'Z' ||
        character in 'a'..'z' ||
        character in '0'..'9' ||
        character == '-' ||
        character == '_'

private fun constantTimeEquals(expected: String, candidate: String): Boolean {
    if (expected.length != candidate.length) return false
    var difference = 0
    for (index in expected.indices) {
        difference = difference or (expected[index].code xor candidate[index].code)
    }
    return difference == 0
}

private fun encodeBase64Url(bytes: ByteArray): String {
    val builder = StringBuilder((bytes.size * 4 + 2) / 3)
    var index = 0
    while (index + 3 <= bytes.size) {
        val chunk = ((bytes[index].toInt() and 0xFF) shl 16) or
            ((bytes[index + 1].toInt() and 0xFF) shl 8) or
            (bytes[index + 2].toInt() and 0xFF)
        builder.append(BASE64URL_ALPHABET[(chunk ushr 18) and 0x3F])
        builder.append(BASE64URL_ALPHABET[(chunk ushr 12) and 0x3F])
        builder.append(BASE64URL_ALPHABET[(chunk ushr 6) and 0x3F])
        builder.append(BASE64URL_ALPHABET[chunk and 0x3F])
        index += 3
    }
    when (bytes.size - index) {
        1 -> {
            val chunk = (bytes[index].toInt() and 0xFF) shl 16
            builder.append(BASE64URL_ALPHABET[(chunk ushr 18) and 0x3F])
            builder.append(BASE64URL_ALPHABET[(chunk ushr 12) and 0x3F])
        }
        2 -> {
            val chunk = ((bytes[index].toInt() and 0xFF) shl 16) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8)
            builder.append(BASE64URL_ALPHABET[(chunk ushr 18) and 0x3F])
            builder.append(BASE64URL_ALPHABET[(chunk ushr 12) and 0x3F])
            builder.append(BASE64URL_ALPHABET[(chunk ushr 6) and 0x3F])
        }
    }
    return builder.toString()
}

private const val BASE64URL_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
