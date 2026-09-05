package com.muyuchat.mca

import org.json.JSONObject

/**
 * The architecture reported by QNN preflight is not necessarily the
 * architecture used by a completed graph.  In particular, HTP loading is
 * deferred until the first graph execution, so a preflight value of zero is
 * expected and must not replace the execution proof.  Keep this resolution
 * deliberately small and side-effect free so every product/debug projection
 * can use the same precedence rules.
 */
internal data class QnnHtpArchTelemetry(
    val selectedHtpArch: Int?,
    val transportHtpArch: Int?
)

/**
 * Resolves HTP architecture telemetry from a native QNN result.
 *
 * Positive values are treated as evidence; zero, negative, malformed, and
 * generic (unversioned) library names are ignored.  The native execution
 * result always wins over staging hints because a context can be compiled for
 * one HTP generation while the transport runs on another generation.
 */
internal fun resolveQnnHtpArchTelemetry(
    nativeResult: JSONObject,
    stagedRuntime: QnnImageStagedRuntime? = null
): QnnHtpArchTelemetry {
    val executionRuntime = nativeResult.optJSONObject("executionRuntime")
    val runtime = nativeResult.optJSONObject("runtime")
    val runtimeEvidence = nativeResult.optJSONObject("runtimeEvidence")
    val runtimeInspection = nativeResult.optJSONObject("runtimeInspection")

    fun firstPositive(vararg values: Int?): Int? = values.firstOrNull { it != null && it > 0 }

    fun architectureFromPaths(json: JSONObject?): Int? {
        if (json == null) return null
        return sequenceOf(
            json.optString("htpSkelLibraryPath").takeIf(String::isNotBlank),
            json.optString("htpStubLibraryPath").takeIf(String::isNotBlank)
        ).mapNotNull { path -> path?.let(::qnnHtpArchFromVersionedLibraryPath) }.firstOrNull()
    }

    val selected = firstPositive(
        nativeResult.positiveIntOrNull("selectedHtpArch"),
        nativeResult.positiveIntOrNull("htpArchVersion"),
        executionRuntime?.positiveIntOrNull("htpArchVersion"),
        architectureFromPaths(executionRuntime),
        runtime?.positiveIntOrNull("htpArchVersion"),
        architectureFromPaths(runtime),
        runtimeEvidence?.positiveIntOrNull("htpArchVersion"),
        architectureFromPaths(runtimeEvidence),
        runtimeInspection?.positiveIntOrNull("htpArchVersion"),
        architectureFromPaths(runtimeInspection),
        stagedRuntime?.htpArchVersion?.takeIf { it > 0 }
    )

    // For split SDXL products the UNet transport is the primary transport;
    // VAE/encoder values are retained as fallbacks for phase-specific runs.
    val phaseTransport = firstPositive(
        nativeResult.positiveIntOrNull("unetTransportHtpArch"),
        nativeResult.positiveIntOrNull("vaeTransportHtpArch"),
        nativeResult.positiveIntOrNull("encoderTransportHtpArch")
    )
    val executionTransport = firstPositive(
        executionRuntime?.positiveIntOrNull("transportHtpArch"),
        executionRuntime?.positiveIntOrNull("htpArchVersion"),
        architectureFromPaths(executionRuntime),
        runtime?.positiveIntOrNull("transportHtpArch"),
        runtime?.positiveIntOrNull("htpArchVersion"),
        architectureFromPaths(runtime)
    )
    val transport = firstPositive(
        nativeResult.positiveIntOrNull("transportHtpArch"),
        phaseTransport,
        executionTransport,
        stagedRuntime?.transportHtpArchVersion?.takeIf { it > 0 },
        selected
    )
    return QnnHtpArchTelemetry(selectedHtpArch = selected, transportHtpArch = transport)
}

private fun JSONObject.positiveIntOrNull(name: String): Int? {
    val value = opt(name) ?: return null
    val integer = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            .takeIf { it in 1L..Int.MAX_VALUE }
            ?.toInt()
        is Float, is Double -> (value as Number).toDouble()
            .takeIf { it.isFinite() && it >= 1.0 && it <= Int.MAX_VALUE && it == it.toLong().toDouble() }
            ?.toInt()
        is String -> value.trim().toLongOrNull()
            ?.takeIf { it in 1L..Int.MAX_VALUE }
            ?.toInt()
        else -> null
    }
    return integer
}

/**
 * Infers an HTP generation only from an explicitly versioned Skel/Stub
 * library.  `libQnnHtp.so` is intentionally not accepted because it carries
 * no architecture identity and is commonly a generic loader shim.
 */
private fun qnnHtpArchFromVersionedLibraryPath(path: String): Int? {
    val match = QNN_VERSIONED_HTP_LIBRARY.find(path.trim()) ?: return null
    return match.groupValues[1].toLongOrNull()
        ?.takeIf { it in 1L..Int.MAX_VALUE }
        ?.toInt()
}

private val QNN_VERSIONED_HTP_LIBRARY = Regex(
    "(?:^|[/\\\\])libQnnHtpV([0-9]+)(?:Skel|Stub)\\.so$"
)
