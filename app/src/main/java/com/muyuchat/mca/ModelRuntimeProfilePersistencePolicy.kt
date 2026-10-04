package com.muyuchat.mca

import org.json.JSONArray
import org.json.JSONObject

/** Pure policy kept outside Room so transition, redaction, and retry bounds are JVM-testable. */
internal object RuntimeProfilePersistencePolicy {
    const val MAX_RECOVERY_ATTEMPTS = 1
    const val MAX_STAGE_CHARS = 96
    const val MAX_CODE_CHARS = 96
    const val MAX_FAILURE_SUMMARY_CHARS = 512
    const val MAX_DIAGNOSTIC_JSON_CHARS = 32 * 1024
    const val MAX_PROFILE_JSON_CHARS = 256 * 1024

    private val localPathPattern = Regex(
        "(?i)(?<![A-Za-z0-9:])(?:[A-Z]:[\\\\/]|/(?:data|storage|sdcard|mnt|home|Users|var|tmp|system|vendor|product|apex)(?:/|\\b))[^\\s,;\\\"']*"
    )
    private val bearerPattern = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+")
    private val secretPattern = Regex(
        "(?i)(?:sk-[A-Za-z0-9_-]{12,}|AIza[A-Za-z0-9_-]{20,}|(?:api[_-]?key|access[_-]?token|authorization|secret)\\s*[:=]\\s*[^\\s,;]+)"
    )
    private val sensitiveKeyPattern = Regex(
        "(?i)(?:prompt|messages?|conversation|authorization|api[_-]?key|access[_-]?token|secret|password|absolute[_-]?path)"
    )

    fun canUpdateJournalStage(from: TuningJournalState, to: TuningJournalState): Boolean =
        when (from) {
            TuningJournalState.STAGED -> to == TuningJournalState.STAGED ||
                to == TuningJournalState.APPLYING || to == TuningJournalState.VALIDATING
            TuningJournalState.APPLYING -> to == TuningJournalState.APPLYING ||
                to == TuningJournalState.VALIDATING
            TuningJournalState.VALIDATING -> to == TuningJournalState.VALIDATING
            else -> false
        }

    fun canCompleteIsolatedProbe(journal: TuningJournalEntity): Boolean =
        journal.transactionId.startsWith("probe-") &&
            journal.jobId == null &&
            journal.state in setOf(
                TuningJournalState.STAGED.name,
                TuningJournalState.APPLYING.name,
                TuningJournalState.VALIDATING.name
            )

    fun canTransitionTuningJob(
        from: PersistedTuningJobState,
        to: PersistedTuningJobState,
        cancellationRequested: Boolean = false
    ): Boolean {
        if (cancellationRequested && to !in CANCELLATION_COMPLETION_JOB_STATES) return false
        if (from == to) return true
        return to in when (from) {
            PersistedTuningJobState.QUEUED -> setOf(
                PersistedTuningJobState.RUNNING,
                PersistedTuningJobState.PAUSED,
                PersistedTuningJobState.CANCELING,
                PersistedTuningJobState.FAILED,
                PersistedTuningJobState.RECOVERING
            )
            PersistedTuningJobState.RUNNING -> setOf(
                PersistedTuningJobState.PAUSED,
                PersistedTuningJobState.CANCELING,
                PersistedTuningJobState.VALIDATING,
                PersistedTuningJobState.FAILED,
                PersistedTuningJobState.RECOVERING
            )
            PersistedTuningJobState.PAUSED -> setOf(
                PersistedTuningJobState.RUNNING,
                PersistedTuningJobState.CANCELING,
                PersistedTuningJobState.FAILED,
                PersistedTuningJobState.RECOVERING
            )
            PersistedTuningJobState.CANCELING -> setOf(
                PersistedTuningJobState.FAILED,
                PersistedTuningJobState.RECOVERING
            )
            PersistedTuningJobState.VALIDATING -> setOf(
                PersistedTuningJobState.CANCELING,
                PersistedTuningJobState.SUCCEEDED,
                PersistedTuningJobState.FAILED,
                PersistedTuningJobState.RECOVERING
            )
            PersistedTuningJobState.RECOVERING -> setOf(PersistedTuningJobState.FAILED)
            PersistedTuningJobState.SUCCEEDED,
            PersistedTuningJobState.FAILED -> emptySet()
        }
    }

    fun pauseTuningJobTarget(from: PersistedTuningJobState): PersistedTuningJobState? = when (from) {
        PersistedTuningJobState.QUEUED,
        PersistedTuningJobState.RUNNING,
        PersistedTuningJobState.PAUSED -> PersistedTuningJobState.PAUSED
        else -> null
    }

    fun resumeTuningJobTarget(from: PersistedTuningJobState): PersistedTuningJobState? = when (from) {
        PersistedTuningJobState.QUEUED,
        PersistedTuningJobState.RUNNING,
        PersistedTuningJobState.PAUSED -> PersistedTuningJobState.RUNNING
        else -> null
    }

    fun cancellationTuningJobTarget(from: PersistedTuningJobState): PersistedTuningJobState = when (from) {
        PersistedTuningJobState.QUEUED,
        PersistedTuningJobState.RUNNING,
        PersistedTuningJobState.PAUSED,
        PersistedTuningJobState.CANCELING,
        PersistedTuningJobState.VALIDATING -> PersistedTuningJobState.CANCELING
        PersistedTuningJobState.RECOVERING,
        PersistedTuningJobState.SUCCEEDED,
        PersistedTuningJobState.FAILED -> from
    }

    fun recoveryDecision(recoveryAttempts: Int, hasRollbackTarget: Boolean): RecoveryAttemptDecision {
        val boundedAttempts = recoveryAttempts.coerceAtLeast(0)
        return when {
            !hasRollbackTarget -> RecoveryAttemptDecision(false, boundedAttempts, "NO_ROLLBACK_TARGET")
            boundedAttempts >= MAX_RECOVERY_ATTEMPTS ->
                RecoveryAttemptDecision(false, boundedAttempts, "RECOVERY_LIMIT_REACHED")
            else -> RecoveryAttemptDecision(true, boundedAttempts + 1, "ROLLBACK_PENDING")
        }
    }

    fun sanitizeStage(value: String): String = sanitizeCode(value, MAX_STAGE_CHARS, "UNSPECIFIED_STAGE")

    fun sanitizeFailureCode(value: String): String = sanitizeCode(value, MAX_CODE_CHARS, "UNKNOWN_FAILURE")

    fun sanitizeFailureSummary(value: String): String = value
        .replace(localPathPattern, "<path>")
        .replace(bearerPattern, "Bearer <redacted>")
        .replace(secretPattern, "<redacted>")
        .take(MAX_FAILURE_SUMMARY_CHARS)

    fun sanitizeDiagnosticJson(value: String): String {
        if (value.isBlank()) return "{}"
        val sanitized = runCatching {
            when (val parsed = if (value.trimStart().startsWith("[")) JSONArray(value) else JSONObject(value)) {
                is JSONObject -> sanitizeObject(parsed, 0)
                is JSONArray -> sanitizeArray(parsed, 0)
                else -> JSONObject().put("summary", sanitizeFailureSummary(value))
            }.toString()
        }.getOrElse {
            JSONObject().put("summary", sanitizeFailureSummary(value)).toString()
        }
        return if (sanitized.length <= MAX_DIAGNOSTIC_JSON_CHARS) sanitized else JSONObject()
            .put("summary", sanitizeFailureSummary(value))
            .put("truncated", true)
            .toString()
    }

    fun requireSafeProfileJson(value: String) {
        require(value.length <= MAX_PROFILE_JSON_CHARS) { "Profile snapshot is too large." }
        require(!localPathPattern.containsMatchIn(value)) { "Profile snapshot contains a raw local path." }
        require(!bearerPattern.containsMatchIn(value) && !secretPattern.containsMatchIn(value)) {
            "Profile snapshot contains credential-like material."
        }
    }

    fun isInstallationLocalResource(field: String, rawValue: String): Boolean {
        val normalizedField = field.lowercase()
        return normalizedField.endsWith("_path") || normalizedField.endsWith("_uri") ||
            rawValue.startsWith("content://", ignoreCase = true) ||
            rawValue.startsWith("file://", ignoreCase = true) ||
            localPathPattern.containsMatchIn(rawValue)
    }

    private fun sanitizeCode(value: String, maxChars: Int, fallback: String): String = value
        .trim()
        .uppercase()
        .replace(Regex("[^A-Z0-9_.-]+"), "_")
        .trim('_')
        .take(maxChars)
        .ifBlank { fallback }

    private fun sanitizeObject(source: JSONObject, depth: Int): JSONObject {
        if (depth >= 8) return JSONObject().put("truncated", true)
        return JSONObject().also { target ->
            source.keys().asSequence().toList().sorted().take(256).forEach { key ->
                val value = source.opt(key)
                target.put(
                    key.take(96),
                    if (sensitiveKeyPattern.containsMatchIn(key)) "<redacted>"
                    else sanitizeJsonValue(value, depth + 1)
                )
            }
        }
    }

    private fun sanitizeArray(source: JSONArray, depth: Int): JSONArray {
        if (depth >= 8) return JSONArray().put("<truncated>")
        return JSONArray().also { target ->
            repeat(minOf(source.length(), 256)) { index ->
                target.put(sanitizeJsonValue(source.opt(index), depth + 1))
            }
        }
    }

    private fun sanitizeJsonValue(value: Any?, depth: Int): Any? = when (value) {
        null, JSONObject.NULL -> JSONObject.NULL
        is JSONObject -> sanitizeObject(value, depth)
        is JSONArray -> sanitizeArray(value, depth)
        is String -> sanitizeFailureSummary(value)
        is Number, is Boolean -> value
        else -> sanitizeFailureSummary(value.toString())
    }

    private val CANCELLATION_COMPLETION_JOB_STATES = setOf(
        PersistedTuningJobState.CANCELING,
        PersistedTuningJobState.RECOVERING,
        PersistedTuningJobState.FAILED
    )
}

