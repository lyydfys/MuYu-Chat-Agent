package com.muyuchat.core.dsh.maintenance

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Redacted diagnostic bundle (OPS-22).
 *
 * Only allowlisted engineering facts are exported: versions and hashes, host phase, closure
 * identity, self-check results, maintenance journal phases, bounded failure codes and tool
 * availability names. Message text, attachment bytes, credentials, cookies, tokens and absolute
 * private paths are never exported. If the rendered bundle still matches a secret pattern the
 * export fails closed instead of leaking it.
 */
data class DshDiagnosticInput(
    val appVersion: String,
    val runtimePhase: String,
    val hostGeneration: Long,
    val closureFingerprint: String? = null,
    val selfCheck: List<DshSelfCheckProbe> = emptyList(),
    val maintenancePhases: List<String> = emptyList(),
    val failures: List<DshMaintenanceFailure> = emptyList(),
    val toolAvailability: Map<String, String> = emptyMap(),
    val sessionCount: Int = 0,
    val recycleBinCount: Int = 0,
    val extra: Map<String, String> = emptyMap()
)

sealed interface DshDiagnosticsResult {
    data class Exported(val file: File, val bytes: Long, val sha256: String) : DshDiagnosticsResult
    data class Rejected(val failure: DshMaintenanceFailure) : DshDiagnosticsResult
}

class DshDiagnosticsExporter(
    private val engine: DshMaintenanceEngine,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun export(
        input: DshDiagnosticInput,
        output: File,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshDiagnosticsResult {
        val rendered = render(input)
        val scrubFailure = checkRedaction(rendered)
        if (scrubFailure != null) return DshDiagnosticsResult.Rejected(scrubFailure)
        if (rendered.length > MAX_BUNDLE_CHARS) {
            return DshDiagnosticsResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.SPACE_INSUFFICIENT, "diagnostic bundle exceeds its budget")
            )
        }
        var exported: DshDiagnosticsResult.Exported? = null
        val outcome = engine.execute(
            DshMaintenanceRequest(
                kind = DshMaintenanceKind.DIAGNOSTICS,
                requester = requester,
                quiescence = quiescence,
                targetRoot = engine.dataRootDirectory,
                steps = emptyList(),
                healthCheckRequired = false,
                detail = "diagnostics",
                body = {
                    try {
                        DshMaintenanceIo.writeDurably(output, rendered)
                        exported = DshDiagnosticsResult.Exported(
                            output,
                            output.length(),
                            DshMaintenanceIo.sha256(output)
                        )
                        null
                    } catch (io: Exception) {
                        DshMaintenanceFailure(
                            DshMaintenanceError.IO_FAILURE,
                            "diagnostic bundle could not be written"
                        )
                    }
                }
            )
        )
        exported?.let { if (outcome is DshMaintenanceOutcome.Committed) return it }
        return DshDiagnosticsResult.Rejected(
            when (outcome) {
                is DshMaintenanceOutcome.Rejected -> outcome.failure
                is DshMaintenanceOutcome.RolledBack -> outcome.failure
                is DshMaintenanceOutcome.RecoveryRequired -> outcome.failure
                is DshMaintenanceOutcome.Committed -> DshMaintenanceFailure(
                    DshMaintenanceError.INTERNAL,
                    "diagnostic export did not produce a bundle"
                )
            }
        )
    }

    private fun render(input: DshDiagnosticInput): String {
        val json = JSONObject()
        json.put("format", "mca.dsh.diagnostics")
        json.put("schemaVersion", 1)
        json.put("createdAt", clock())
        json.put("appVersion", clean(input.appVersion, 48))
        json.put("runtimePhase", clean(input.runtimePhase, 32))
        json.put("hostGeneration", input.hostGeneration)
        json.put("closureFingerprint", input.closureFingerprint?.take(64) ?: "")
        json.put("sessionCount", input.sessionCount)
        json.put("recycleBinCount", input.recycleBinCount)
        val checks = JSONArray()
        for (probe in input.selfCheck.take(32)) {
            checks.put(
                JSONObject().apply {
                    put("check", probe.check.name)
                    put("ok", probe.ok)
                    put("detail", clean(probe.detail, 120))
                }
            )
        }
        json.put("selfCheck", checks)
        json.put("maintenancePhases", JSONArray(input.maintenancePhases.take(40).map { clean(it, 80) }))
        val failures = JSONArray()
        for (failure in input.failures.take(20)) {
            failures.put(
                JSONObject().apply {
                    put("code", failure.code.name)
                    put("message", clean(failure.message, 120))
                    put("retryable", failure.retryable)
                }
            )
        }
        json.put("failures", failures)
        val tools = JSONObject()
        for ((name, state) in input.toolAvailability.toSortedMap()) {
            tools.put(clean(name, 48), clean(state, 32))
        }
        json.put("toolAvailability", tools)
        val extra = JSONObject()
        for ((name, value) in input.extra.toSortedMap()) {
            extra.put(clean(name, 48), clean(value, 120))
        }
        json.put("extra", extra)
        return json.toString()
    }

    private fun checkRedaction(rendered: String): DshMaintenanceFailure? {
        if (SECRET_PATTERNS.any { it.containsMatchIn(rendered) }) {
            return DshMaintenanceFailure(
                DshMaintenanceError.DIAGNOSTICS_REDACTION_FAILED,
                "diagnostic bundle still contains a secret-looking value"
            )
        }
        if (ABSOLUTE_PATH.containsMatchIn(rendered)) {
            return DshMaintenanceFailure(
                DshMaintenanceError.DIAGNOSTICS_REDACTION_FAILED,
                "diagnostic bundle still contains an absolute private path"
            )
        }
        return null
    }

    private fun clean(raw: String, limit: Int): String {
        var value = raw
        for (pattern in SECRET_PATTERNS) value = pattern.replace(value, "[redacted]")
        value = ABSOLUTE_PATH.replace(value, "[redacted-path]")
        if (value.length > limit) value = value.take(limit)
        return value
    }

    private companion object {
        const val MAX_BUNDLE_CHARS = 256 * 1024
        val SECRET_PATTERNS = listOf(
            Regex("(?i)\\bsk-[A-Za-z0-9_\\-]{12,}"),
            Regex("(?i)\\b(bearer)\\s+[A-Za-z0-9._\\-]{12,}"),
            Regex("(?i)\\b(api[_-]?key|access[_-]?token|refresh[_-]?token|password)\\s*[:=]\\s*[\"']?[A-Za-z0-9._\\-]{8,}")
        )
        val ABSOLUTE_PATH = Regex("(?i)(?:/data/|/storage/|/home/|[A-Za-z]:\\\\|/sdcard/)[^\\s\"']*")
    }
}
