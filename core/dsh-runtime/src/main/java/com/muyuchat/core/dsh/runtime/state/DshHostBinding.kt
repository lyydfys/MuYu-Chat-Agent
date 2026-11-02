package com.muyuchat.core.dsh.runtime.state

import com.muyuchat.core.dsh.contract.DshGatewayEndpoint
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import com.muyuchat.core.dsh.contract.capability.DshCapabilityBridgeEndpoint
import java.security.MessageDigest

/**
 * Token-free identity of one complete host binding.
 *
 * The DSH host reads its model provider, workspace and capability coordinates once, at process
 * start. Everything it was started with therefore has to be named by a single value that can be
 * compared across processes without ever moving a secret: the id is a truncated SHA-256 over the
 * exact binding fields, so two bindings are equal only when the gateway URL, its bearer token, the
 * catalog revision, the capability bridge coordinates and the app-composition identity are all
 * equal. [value] is safe for logs, Binder and diagnostics.
 */
@JvmInline
value class DshBindingId(val value: String) {
    init {
        require(value.startsWith(PREFIX) && value.length <= MAX_CHARS) {
            "dsh: a binding id must be a token-free '${PREFIX}' digest, was \"$value\""
        }
    }

    override fun toString(): String = value

    companion object {
        private const val PREFIX = "b-"
        private const val MAX_CHARS = 32
        private const val DIGEST_CHARS = 16
        private const val HEX = "0123456789abcdef"

        /** Placeholder for a malformed/missing wire value; never equal to a computed binding. */
        val Unknown = DshBindingId("${PREFIX}unknown")

        /** Computes the id of one binding. [raw] may contain secrets; only the digest escapes. */
        fun of(raw: String): DshBindingId = DshBindingId(PREFIX + digest(raw))

        /** Restores an id that crossed Binder without trusting an arbitrary client string. */
        fun fromWire(value: String?): DshBindingId {
            val candidate = value?.takeIf { it.startsWith(PREFIX) && it.length <= MAX_CHARS }
            return if (candidate == null) Unknown else DshBindingId(candidate)
        }

        private fun digest(raw: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            val text = StringBuilder(DIGEST_CHARS)
            for (index in 0 until DIGEST_CHARS / 2) {
                val value = bytes[index].toInt() and 0xFF
                text.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
            }
            return text.toString()
        }
    }
}

/**
 * The complete desired binding of the Node host for one generation.
 *
 * It carries secrets (gateway bearer token, capability bridge token) because it is exactly what a
 * start needs, so:
 * - it is held in memory only and never persisted, logged or turned into a String extra;
 * - [toString] is redacted; [summary] is the log-safe projection;
 * - identity/equality is [id] only, so it can be used in a revision check without leaking a token.
 *
 * [hostBuild] is the app-composition identity supplied by the caller (for example an engine
 * generation). A main-process rebuild that produces an identical model catalog but a different
 * inference owner must still reload the host, and this is the only field that can express it.
 */
class DshHostConfig(
    val gateway: DshGatewayEndpoint? = null,
    val capability: DshCapabilityBridgeEndpoint? = null,
    hostBuild: String = ""
) {
    /**
     * Normalized at construction so the app process and the `:dsh` process always agree on the
     * identity of the same binding (the value crosses Binder and is trimmed on the way back).
     */
    val hostBuild: String = hostBuild.trim()

    /** Token-free identity of this binding. */
    val id: DshBindingId = DshBindingId.of(
        listOf(
            gateway?.baseUrl ?: NONE,
            gateway?.bearerToken ?: NONE,
            gateway?.catalogRevision ?: NONE,
            capability?.generation?.toString() ?: NONE,
            capability?.appLocalPort?.toString() ?: NONE,
            capability?.accessibilityPort?.toString() ?: NONE,
            capability?.localToken ?: NONE,
            capability?.rootAvailable?.toString() ?: NONE,
            capability?.shizukuAvailable?.toString() ?: NONE,
            // A NUL here would be the field separator, so a caller-supplied label is normalized.
            hostBuild.replace(SEPARATOR_CHAR, ' ')
        ).joinToString(SEPARATOR)
    )

    /** True only when an MCA model gateway is part of this binding. */
    val gatewayBound: Boolean get() = gateway != null

    /** Log-safe projection: endpoint, catalog revision and composition id, never a token. */
    val summary: String
        get() = buildString {
            append("gateway=").append(gateway?.baseUrl ?: "none")
            append(" catalog=").append(gateway?.catalogRevision ?: "none")
            append(" capabilityGeneration=").append(capability?.generation ?: 0L)
            append(" hostBuild=").append(safeHostBuild())
        }

    private fun safeHostBuild(): String =
        hostBuild.filter { c -> isLogSafeChar(c) }.take(MAX_BUILD_CHARS).ifEmpty { "none" }

    override fun equals(other: Any?): Boolean = other is DshHostConfig && other.id == id

    override fun hashCode(): Int = id.hashCode()

    /** Never prints the bearer/bridge token. */
    override fun toString(): String = "DshHostConfig(id=$id, $summary)"

    private companion object {
        const val NONE = "-"
        const val SEPARATOR = "\u0000"
        const val SEPARATOR_CHAR = '\u0000'
        const val MAX_BUILD_CHARS = 40
        fun isLogSafeChar(c: Char): Boolean = c.isLetterOrDigit() || c in "-_.:@/"
    }
}

/**
 * One `ensureApplied` request.
 *
 * [inFlightCalls] is the caller's observation of the work the current generation is still running
 * (for MCA this is the app-process gateway's admitted model calls). `null` means "not observable";
 * the policy never treats it as zero: a READY generation whose work cannot be observed is kept and
 * the request is deferred as [DshApplyStatus.DEFERRED_IN_FLIGHT] with
 * [DshApplyResult.inFlightUnobservable] set. A caller that has decided to terminate that work
 * anyway must send [force].
 *
 * [force] is the explicit user/recovery decision to apply even when a drain is refused: the old
 * generation is stopped anyway, which terminates its calls instead of letting them continue on a
 * connection whose credentials are about to be replaced.
 */
data class DshApplyRequest(
    val inFlightCalls: Int? = null,
    val force: Boolean = false,
    /** Free-form, log-safe caller label such as `ensureStarted` or `model-catalog-changed`. */
    val source: String = "ensure",
    /**
     * Absolute path of a committed closure `payload` tree the next generation must run.
     * `null` leaves the current choice unchanged; `""` returns to the installed payload.
     */
    val payloadRoot: String? = null
) {
    init {
        require(inFlightCalls == null || inFlightCalls >= 0) {
            "dsh: in-flight observation must be null or non-negative, was $inFlightCalls"
        }
        require(source.isNotBlank() && source.length <= MAX_SOURCE_CHARS) {
            "dsh: apply source must be a short non-blank label"
        }
        require(payloadRoot == null || payloadRoot.length <= MAX_PAYLOAD_ROOT_CHARS) {
            "dsh: payload root must stay a short absolute path"
        }
    }

    private companion object {
        const val MAX_SOURCE_CHARS = 64
        const val MAX_PAYLOAD_ROOT_CHARS = 512
    }
}

/**
 * Typed outcome of one `ensureApplied` call.
 *
 * [APPLIED] is the only success that claims the requested binding runs; it is only published after
 * the readiness gate proved the new generation. Every other value keeps the previous state
 * truthful: nothing is ever reported as applied when it was not.
 */
enum class DshApplyStatus {
    /** The READY generation already runs exactly the requested binding; nothing was touched. */
    UNCHANGED,

    /** A new generation reached READY with the requested binding. */
    APPLIED,

    /** The requested binding differs but running work still owns the current generation. */
    DEFERRED_IN_FLIGHT,

    /**
     * The requested binding drops the MCA gateway (or has no observable gateway) while a READY
     * generation is serving models. A transient catalog/gateway failure is not a reason to tear a
     * working model channel down; an explicit [DshApplyRequest.force] is.
     */
    DEFERRED_GATEWAY_UNAVAILABLE,

    /** The apply failed and the previous binding was started again and reached READY. */
    ROLLED_BACK,

    /** The apply failed and no generation is READY; [DshApplyResult.retained] is the last good id. */
    FAILED
}

/**
 * Immutable, log-safe result of [com.muyuchat.core.dsh.runtime.DshRuntimeOwner.ensureApplied].
 *
 * [applied] is the binding of the generation that is actually READY (null when none is). For
 * [DshApplyStatus.APPLIED] it always equals [requested]; for [DshApplyStatus.ROLLED_BACK] it equals
 * [retained] and never the requested id — that difference is the confirmation the caller needs.
 */
data class DshApplyResult(
    val status: DshApplyStatus,
    val requested: DshBindingId,
    val applied: DshBindingId?,
    val retained: DshBindingId?,
    val epoch: Long,
    val snapshot: DshRuntimeSnapshot,
    /** Token-free description of what differed: `gateway-token`, `model-catalog`, ... */
    val change: String? = null,
    val inFlightCalls: Int? = null,
    /**
     * Observed calls that lost their generation because it was stopped for this apply. The count
     * is the decision-time observation, not a completion proof: stopping the Node host closes the
     * connections those calls use, and the app-process gateway cancels them on peer disconnect.
     */
    val inFlightTerminated: Int = 0,
    /** True when the caller could not observe in-flight work; never reported as a drain. */
    val inFlightUnobservable: Boolean = false,
    val detail: String? = null
) {
    init {
        require(inFlightTerminated >= 0) { "dsh: terminated call count must be non-negative" }
        when (status) {
            DshApplyStatus.APPLIED,
            DshApplyStatus.UNCHANGED -> require(applied == requested) {
                "dsh: $status must report exactly the requested binding as applied"
            }

            DshApplyStatus.ROLLED_BACK -> require(
                applied != null && applied == retained && applied != requested
            ) { "dsh: a rollback must report the retained binding, not the requested one" }

            DshApplyStatus.DEFERRED_IN_FLIGHT,
            DshApplyStatus.DEFERRED_GATEWAY_UNAVAILABLE,
            DshApplyStatus.FAILED -> require(applied == null) {
                "dsh: $status must not claim a READY binding"
            }
        }
    }

    /** True only when the requested binding was verified READY. */
    val confirmed: Boolean
        get() = status == DshApplyStatus.APPLIED && applied == requested
}

/**
 * Observation seam for the work the current host generation is still running.
 *
 * The owner lives in the `:dsh` process and cannot see the app-process gateway itself, so the
 * caller supplies one observation with each apply request. Returning null is honest ("cannot
 * observe") and never becomes a zero — see [DshApplyPolicy.decide].
 */
fun interface DshInFlightObservation {
    suspend fun inFlightCalls(): Int?

    companion object {
        val Unavailable: DshInFlightObservation = DshInFlightObservation { null }
    }
}

/**
 * One channel through which a live DSH generation can still be running work.
 *
 * A channel is named so an observation can say *what* it covered: "no MCA model calls" is a
 * different fact from "the MCA gateway could not be asked at all".
 */
enum class DshInFlightChannel(val label: String) {
    /** Calls admitted by this generation's MCA loopback model gateway. */
    MODEL_GATEWAY("model-gateway"),

    /**
     * Tool executions dispatched through the app-local capability bridge. MCA sees the bridge's
     * own ledger, so this is real evidence when the bridge is wired and running.
     */
    TOOL_EXECUTION("tool-execution"),

    /**
     * Turns served by the official DeepSeek account provider directly from the Node host. MCA has
     * no request-level view of that channel; it is reported as uncovered instead of assumed idle.
     */
    ACCOUNT_DIRECT("account-direct")
}

/**
 * Tri-state answer of one observation channel.
 *
 * [Observed] always carries a real count — including [Observed.calls] == 0, which is the only
 * value that may be read as "this channel is idle". [Unobservable] says the channel could not be
 * asked, and is never equivalent to zero.
 */
sealed interface DshInFlightEvidence {
    data class Observed(val channel: DshInFlightChannel, val calls: Int) : DshInFlightEvidence {
        init { require(calls >= 0) { "dsh: observed calls must be non-negative, was $calls" } }
    }

    data class Unobservable(val channel: DshInFlightChannel, val reason: String) : DshInFlightEvidence {
        init { require(reason.isNotBlank()) { "dsh: an unobservable channel needs a reason" } }
    }
}

/**
 * Complete in-flight observation handed to the reload decision.
 *
 * [evidence] holds what was actually asked. [uncovered] holds channels this observation does not
 * cover at all (for example the official account provider): they are reported, never summed, and
 * never turned into a zero. [observedCalls] is non-null only when every listed channel answered;
 * a single [DshInFlightEvidence.Unobservable] makes the whole observation unobservable, because a
 * partial sum could read as "idle" while another channel is busy.
 */
data class DshInFlightSnapshot(
    val evidence: List<DshInFlightEvidence>,
    val uncovered: List<DshInFlightChannel> = emptyList()
) {
    /** Real total only when every asked channel answered; null when anything is unobservable. */
    val observedCalls: Int?
        get() = if (evidence.any { it is DshInFlightEvidence.Unobservable }) {
            null
        } else {
            evidence.filterIsInstance<DshInFlightEvidence.Observed>().sumOf { it.calls }
        }

    val unobservable: Boolean get() = observedCalls == null

    val observedChannels: List<DshInFlightChannel>
        get() = evidence.filterIsInstance<DshInFlightEvidence.Observed>().map { it.channel }

    val unobservableChannels: List<DshInFlightChannel>
        get() = evidence.filterIsInstance<DshInFlightEvidence.Unobservable>().map { it.channel }

    /** Log-safe, token-free projection used by diagnostics and the status line. */
    val detail: String
        get() = buildString {
            evidence.forEachIndexed { index, item ->
                if (index > 0) append(';')
                when (item) {
                    is DshInFlightEvidence.Observed -> append(item.channel.label).append('=').append(item.calls)
                    is DshInFlightEvidence.Unobservable -> append(item.channel.label)
                        .append("=unobservable(")
                        .append(item.reason.take(48))
                        .append(')')
                }
            }
            if (uncovered.isNotEmpty()) {
                if (isNotEmpty()) append(';')
                append("uncovered=").append(uncovered.joinToString(",") { it.label })
            }
        }

    /** The `DshInFlightObservation` view: the real count or an honest null. */
    fun asObservation(): DshInFlightObservation = DshInFlightObservation { observedCalls }

    companion object {
        /** Nothing could be asked; the reason is kept for logs. */
        fun unavailable(reason: String, channel: DshInFlightChannel = DshInFlightChannel.MODEL_GATEWAY) =
            DshInFlightSnapshot(listOf(DshInFlightEvidence.Unobservable(channel, reason)))

        /** A fully answered observation; any channel left out is explicitly uncovered. */
        fun observed(
            calls: Map<DshInFlightChannel, Int>,
            uncovered: List<DshInFlightChannel> = emptyList()
        ) = DshInFlightSnapshot(
            evidence = calls.map { (channel, count) -> DshInFlightEvidence.Observed(channel, count) },
            uncovered = uncovered
        )
    }
}
