package com.muyuchat.feature.dsh.workspace.device

/**
 * Outcome of one **experimental** wireless-pairing attempt, shaped for the console.
 *
 * [reasonCode] is one of the stable strings from [AdbPairingFailure] (`tls_failed`, `spake2_failed`,
 * `peer_info_rejected`, `timeout`, `cancelled`, `concurrent_pairing`, `invalid_input`, `unsupported`)
 * and never contains the pairing code or any key material. [unverifiedAssumptions] lists only
 * assumption *names* (e.g. `tls_exporter_label`), so a failure tells the user exactly which wire-format
 * decision is still unproven instead of pretending the code is finished.
 */
data class DshAdbPairingResult(
    val succeeded: Boolean,
    val reasonCode: String,
    /** Short, user-facing sentence (Chinese) with no device text and no secrets. */
    val message: String,
    val unverifiedAssumptions: List<String> = emptyList(),
    /** Endpoint proven by a successful pairing ("host:port"), null on failure. */
    val endpoint: String? = null
) {
    companion object {
        /** Every assumption name the console may print, in a fixed order. */
        val ASSUMPTION_LABELS: Map<String, String> = linkedMapOf(
            "tls_exporter_label" to "exporter label",
            "aes_gcm_derivation" to "GCM 派生",
            "peer_info_layout" to "PeerInfo 布局",
            "packet_type_values" to "packet type"
        )

        fun success(endpoint: String): DshAdbPairingResult = DshAdbPairingResult(
            succeeded = true,
            reasonCode = "",
            message = "配对成功，已可用 ADB 通道（无需 root）",
            endpoint = endpoint
        )

        fun failure(reason: AdbPairingFailure, detail: List<String> = emptyList()): DshAdbPairingResult =
            DshAdbPairingResult(
                succeeded = false,
                reasonCode = reason.reasonCode,
                message = "配对失败：${reason.reasonCode}",
                unverifiedAssumptions = detail
            )

        /** "exporter label / GCM 派生 / …" for the result line; unknown names are passed through. */
        fun formatAssumptions(names: List<String>): String =
            names.joinToString(" / ") { ASSUMPTION_LABELS[it] ?: it }
    }
}

/**
 * Console-facing gate around [AdbPairingController].
 *
 * Responsibilities, all testable without Android:
 * 1. **Opt-in gate.** While the experimental switch is off nothing touches the network; the attempt
 *    returns `unsupported` immediately.
 * 2. **Input validation** before any I/O: non-blank host, port `1..65535`, exactly six digits.
 * 3. **Endpoint recording only on success**, so a failed attempt can never make a later `connect()`
 *    look authorised.
 * 4. Reason-code mapping (see [DshAdbPairingResult]) with assumption names preserved for the UI.
 *
 * The controller itself still refuses to run while [AdbPairingWireAssumptions] has unverified entries;
 * this class intentionally does not add a second opinion, only the user-facing gate.
 */
class DshAdbPairingPort(
    /** Records an endpoint that a successful pairing proved (wired to the endpoint store). */
    private val saveEndpoint: (String) -> Unit = {},
    private val assumptions: AdbPairingWireAssumptions = AdbPairingWireAssumptions.EXPERIMENTAL,
    private val transportFactory: () -> AdbPairingTransport = { AdbPairingTls() }
) {

    /**
     * "Allow experimental wireless pairing" switch. Default **off**: the console hides the section and
     * [pair] refuses without touching the network. Instance state (not a global) so tests and future
     * multiple consoles stay independent.
     */
    private val experimentalEnabled = java.util.concurrent.atomic.AtomicBoolean(false)

    fun setExperimentalEnabled(enabled: Boolean) {
        experimentalEnabled.set(enabled)
    }

    fun isExperimentalEnabled(): Boolean = experimentalEnabled.get()

    /**
     * One controller per port, not per attempt: its single-flight slot is what makes a second tap
     * report `concurrent_pairing` instead of racing two sockets. It is safely reusable afterwards
     * because the controller resets its slot in `finally`.
     */
    private val controller by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AdbPairingController(transport = transportFactory(), assumptions = assumptions)
    }

    /**
     * Runs one pairing attempt. Blocking; callers on Android must dispatch it off the main thread
     * ([DshAdbChannel.pair] does).
     */
    fun pair(host: String, port: Int, pairingCode: String): DshAdbPairingResult {
        if (!experimentalEnabled.get()) {
            return DshAdbPairingResult.failure(
                AdbPairingFailure.UNSUPPORTED,
                listOf(NOT_ENABLED_ASSUMPTION)
            )
        }
        val normalizedHost = host.trim()
        val validationFailure = validate(normalizedHost, port, pairingCode)
        if (validationFailure != null) return validationFailure

        val result = controller.pair(normalizedHost, port, pairingCode.trim())
        return when (result) {
            is AdbPairingResult.Success -> {
                val endpoint = "$normalizedHost:$port"
                saveEndpoint(endpoint)
                DshAdbPairingResult.success(endpoint)
            }

            is AdbPairingResult.Failure -> DshAdbPairingResult.failure(
                reason = result.reason,
                detail = result.detail.ifEmpty {
                    // A verified-assumption failure has no assumption list; keep the reason only.
                    emptyList()
                }
            )
        }
    }

    /** Local-only checks; null means "go ahead". Never echoes the code back to the caller. */
    private fun validate(host: String, port: Int, pairingCode: String): DshAdbPairingResult? {
        if (host.isBlank()) return DshAdbPairingResult.failure(AdbPairingFailure.INVALID_INPUT)
        if (port !in 1..65_535) return DshAdbPairingResult.failure(AdbPairingFailure.INVALID_INPUT)
        val code = pairingCode.trim()
        if (code.length != PAIRING_CODE_DIGITS || !code.all { it in '0'..'9' }) {
            return DshAdbPairingResult.failure(AdbPairingFailure.INVALID_INPUT)
        }
        return null
    }

    companion object {
        /** adb pairing codes are exactly six digits. */
        const val PAIRING_CODE_DIGITS: Int = 6

        /**
         * Shown instead of "no assumptions missing" when the switch is off: the *feature* is gated, and
         * the console should say so rather than imply the protocol is ready.
         */
        internal const val NOT_ENABLED_ASSUMPTION: String = "experimental_switch"
    }
}
