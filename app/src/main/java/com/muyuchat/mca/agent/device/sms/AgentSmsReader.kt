package com.muyuchat.mca.agent.device.sms

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Read-only SMS lane of the DSH Android bridge.
 *
 * Privacy rules (hard requirements, not suggestions):
 *  - **read-only**: there is no send/delete/mark-read path anywhere in this file;
 *  - `READ_SMS` is a sensitive permission, so the capability is off by default and needs both the
 *    Settings switch *and* the Android grant, plus one confirmation per query unless the user
 *    explicitly skipped it for the current app session;
 *  - the response carries only a masked sender (last 4 digits), the receive timestamp and a
 *    120-character body preview: never the full body, never the raw number;
 *  - nothing is logged, cached, exported or written into a backup row: readings live in the one
 *    response object;
 *  - "no permission / not granted" is a typed refusal, never an empty list: an empty list means
 *    "this device really has no matching message".
 */
enum class AgentSmsReasonCode {
    /** The user has not switched SMS reading on in Settings. */
    NOT_GRANTED,

    /** Switched on, but the Android `READ_SMS` permission is missing. */
    PERMISSION_MISSING,

    /** The user denied the per-query confirmation. */
    CONFIRMATION_DENIED,

    /** The platform query itself failed (provider missing, security exception, …). */
    QUERY_FAILED
}

/** One masked SMS row; the full body never leaves the platform query. */
data class AgentSmsMessage(
    val senderMasked: String,
    val receivedAtMillis: Long,
    val bodyPreview: String
)

/** Raw platform row used by the pure query pipeline. */
data class AgentSmsRawMessage(
    val address: String?,
    val body: String?,
    val receivedAtMillis: Long
)

/** Raw source result: rows, or a typed platform failure (never an empty list on failure). */
sealed interface AgentSmsRawQueryResult {
    data class Rows(val messages: List<AgentSmsRawMessage>) : AgentSmsRawQueryResult
    data class Failed(val reason: AgentSmsReasonCode, val detail: String) : AgentSmsRawQueryResult
}

/** Pure query outcome, mapped to the `/sms` response by the route handler. */
sealed interface AgentSmsQueryResult {
    data class Success(
        val messages: List<AgentSmsMessage>,
        /** How many platform rows were scanned before keyword filtering. */
        val scanned: Int,
        /** How many rows were dropped by the keyword filter. */
        val filteredOut: Int
    ) : AgentSmsQueryResult

    data class Refused(
        val reason: AgentSmsReasonCode,
        val detail: String,
        val settingsHint: String? = null
    ) : AgentSmsQueryResult
}

/**
 * Pure rules of the SMS lane: masking, preview truncation, limit, sorting and keyword filtering.
 *
 * The whole matrix is unit-testable; [AndroidAgentSmsReader] only executes the platform query.
 */
object AgentSmsPolicy {

    /** Hard ceiling of one response; the tool contract is "recent N", not "dump the inbox". */
    const val MAX_RESULTS: Int = 20

    /** Used when the caller does not ask for a count. */
    const val DEFAULT_RESULTS: Int = 10

    /** The body is previewed, never echoed; 120 characters is the documented contract. */
    const val PREVIEW_CHARS: Int = 120

    /** Longest accepted keyword; anything longer is a malformed request, not a silent no-match. */
    const val MAX_KEYWORD_CHARS: Int = 64

    /** How many platform rows one query may scan before keyword filtering. */
    const val MAX_SCAN_ROWS: Int = 400

    const val UNKNOWN_SENDER: String = "未知号码"

    /** `limit` is clamped into `1..MAX_RESULTS`; a missing value uses [DEFAULT_RESULTS]. */
    fun clampLimit(requested: Int?): Int = (requested ?: DEFAULT_RESULTS).coerceIn(1, MAX_RESULTS)

    /**
     * Masks a sender: keep at most the last four digits, never the country code or the full number.
     * A sender without digits (short code, alphanumeric) is reduced to a fixed alias so a bank
     * short code or a contact id cannot be re-identified from the response.
     */
    fun maskSender(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return UNKNOWN_SENDER
        val digits = value.filter(Char::isDigit)
        if (digits.length >= 4) return "****" + digits.takeLast(4)
        // Fewer than four digits (short code, or an alphanumeric sender): keep only the fact that
        // a sender existed, never the identifier itself.
        return "****"
    }

    /**
     * Body preview: first [PREVIEW_CHARS] characters after collapsing CR/LF into spaces, so a
     * preview never becomes a multi-line transcript. No ellipsis is appended: a consumer that
     * cares about truncation compares `preview.length == PREVIEW_CHARS`.
     */
    fun preview(raw: String?): String =
        raw.orEmpty()
            .replace("\r\n", " ")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .take(PREVIEW_CHARS)

    /**
     * Sorts newest-first, applies the optional case-insensitive keyword over the raw sender/body,
     * takes [limit] rows and maps each row through [maskSender]/[preview].
     *
     * A blank body is still returned as a row (an MMS with only a subject is still a message); the
     * preview is then empty and the caller can decide what to show.
     */
    fun query(
        rows: List<AgentSmsRawMessage>,
        limit: Int,
        keyword: String?
    ): AgentSmsQueryResult.Success {
        val needle = keyword?.trim().orEmpty()
        val scanned = rows.take(MAX_SCAN_ROWS)
        val matching = if (needle.isEmpty()) {
            scanned
        } else {
            scanned.filter { row ->
                row.body.orEmpty().contains(needle, ignoreCase = true) ||
                    row.address.orEmpty().contains(needle, ignoreCase = true)
            }
        }
        val sorted = matching.sortedByDescending { it.receivedAtMillis }
        val selected = sorted.take(limit)
        return AgentSmsQueryResult.Success(
            messages = selected.map { row ->
                AgentSmsMessage(
                    senderMasked = maskSender(row.address),
                    receivedAtMillis = row.receivedAtMillis,
                    bodyPreview = preview(row.body)
                )
            },
            scanned = scanned.size,
            filteredOut = scanned.size - matching.size
        )
    }

    /** True when the keyword itself is too long to be an honest query. */
    fun keywordTooLong(keyword: String?): Boolean =
        (keyword?.trim()?.length ?: 0) > MAX_KEYWORD_CHARS
}

/**
 * Per-query confirmation throttle.
 *
 * Every query asks unless the user switched "本次运行内不再询问" on; the consent store clears that
 * flag when the app process starts, so a restart re-arms the prompt. The gate serialises
 * confirmations so two concurrent queries cannot produce two dialogs, and it never remembers an
 * answer: the next query asks again.
 *
 * The wait is bounded by [CONFIRMATION_TIMEOUT_MILLIS], which stays below the capability server's
 * request timeout: a user who never answers gets a typed "not run" answer instead of an HTTP
 * timeout that hides whether the query executed. Cancelling the wait also clears the dialog.
 */
class AgentSmsConfirmationGate(
    private val skipThisSession: () -> Boolean,
    private val confirmationTimeoutMillis: Long = CONFIRMATION_TIMEOUT_MILLIS
) {
    private val mutex = Mutex()

    /** True when a confirmation dialog is required for this call. */
    fun needsConfirmation(): Boolean = !skipThisSession()

    /**
     * Runs [port] when required and maps the outcome. A denial and a timeout are different answers
     * so the route/DSH can report "user refused" vs "nobody answered"; neither is an execution.
     */
    suspend fun confirm(
        detail: String,
        port: com.muyuchat.mca.agent.device.AgentSmsConfirmationPort
    ): AgentSmsConfirmationOutcome {
        if (!needsConfirmation()) return AgentSmsConfirmationOutcome.SKIPPED_BY_SESSION_SWITCH
        return mutex.withLock {
            // Re-check inside the lock: a query that waited here must not re-prompt after the
            // user turned the session-skip switch on in the meantime.
            if (!needsConfirmation()) return@withLock AgentSmsConfirmationOutcome.SKIPPED_BY_SESSION_SWITCH
            when (withTimeoutOrNull(confirmationTimeoutMillis.coerceAtLeast(1L)) { port.confirm(detail) }) {
                true -> AgentSmsConfirmationOutcome.ALLOWED
                false -> AgentSmsConfirmationOutcome.DENIED
                null -> AgentSmsConfirmationOutcome.TIMED_OUT
            }
        }
    }

    companion object {
        /** Below the capability server's 15 s request timeout; the dialog is cleared on timeout. */
        const val CONFIRMATION_TIMEOUT_MILLIS: Long = 10_000L
    }
}

/** Outcome of one per-query SMS confirmation. */
enum class AgentSmsConfirmationOutcome {
    /** The user switched "本次运行内不再询问" on for this process; no prompt was shown. */
    SKIPPED_BY_SESSION_SWITCH,
    ALLOWED,
    DENIED,
    TIMED_OUT
}
