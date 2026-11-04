package com.muyuchat.feature.dsh.workspace.device

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Which transport is asking for a dangerous command. */
enum class DshCommandApprovalKind {
    ADB,
    ROOT
}

/**
 * One pending "may this run?" question, as the floating window renders it.
 *
 * [commandMasked] is produced by [DshCommandApprovalMasking] and is the **only** command text that ever
 * reaches the window: the name plus the first two arguments, each cut to 24 characters. The full command
 * never leaves the channel that is executing it.
 *
 * [timeoutMillis] is carried on the request so the card can draw its own countdown without reading the
 * queue, and so a UI that appears a moment later still shows the real remaining time (deadline =
 * [atMillis] + [timeoutMillis]).
 */
data class DshCommandApprovalRequest(
    val id: Long,
    val kind: DshCommandApprovalKind,
    val commandMasked: String,
    val reasonCode: String,
    val atMillis: Long,
    val timeoutMillis: Long = DshCommandApprovalQueue.DEFAULT_TIMEOUT_MILLIS
) {
    /** Wall-clock instant after which an answer is no longer accepted. */
    val deadlineMillis: Long get() = atMillis + timeoutMillis
}

/** The only two answers a user can give. There is deliberately no "always allow". */
enum class DshCommandApprovalDecision {
    ALLOW_ONCE,
    DENY
}

/**
 * Shows one approval card somewhere outside this module (the app-owned floating window) and removes it.
 *
 * [present] returns `false` when nothing can display the card — no window attached, window disabled, or
 * the host refused it. The gate then denies immediately: a prompt nobody can see must never turn into a
 * silent allow.
 */
interface DshCommandApprovalPresenter {
    fun present(request: DshCommandApprovalRequest): Boolean

    fun dismiss(id: Long)
}

/**
 * Builds the masked command string shown on the card.
 *
 * Rules (all pinned by tests):
 *  - only the command name and the first [MAX_ARGUMENTS] arguments are kept;
 *  - every kept token is cut to [MAX_TOKEN_CHARS] characters and marked with `…`;
 *  - anything beyond the kept arguments is replaced by a trailing `…`;
 *  - quoting is preserved so the user can see that a value was quoted, but the quote does not swallow
 *    the whole command: tokens are split on whitespace outside quotes.
 */
object DshCommandApprovalMasking {
    const val MAX_TOKEN_CHARS = 24
    const val MAX_ARGUMENTS = 2

    fun mask(command: String): String {
        val tokens = tokenize(command)
        if (tokens.isEmpty()) return ""
        val kept = tokens.take(1 + MAX_ARGUMENTS).joinToString(" ") { token ->
            if (token.length > MAX_TOKEN_CHARS) token.take(MAX_TOKEN_CHARS) + "…" else token
        }
        return if (tokens.size > 1 + MAX_ARGUMENTS) "$kept …" else kept
    }

    /** Whitespace split that keeps quoted runs together and keeps the quotes visible. */
    private fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var index = 0
        while (index < command.length) {
            val char = command[index]
            when {
                quote != null -> {
                    current.append(char)
                    if (char == quote) quote = null
                    index += 1
                }

                char == '\'' || char == '"' -> {
                    quote = char
                    current.append(char)
                    index += 1
                }

                char == '\\' && index + 1 < command.length -> {
                    current.append(char).append(command[index + 1])
                    index += 2
                }

                char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                    index += 1
                }

                else -> {
                    current.append(char)
                    index += 1
                }
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }
}

/**
 * Exactly one pending approval at a time.
 *
 * Why a queue at all instead of a list: an overlay that stacks cards is unreadable, and two allowed
 * commands in flight from one tap is unsafe. A newer request therefore **replaces** the older one, and
 * the older one is answered DENY immediately (its caller sees a normal policy rejection).
 *
 * Every transition is idempotent: answering twice, answering an expired request, or answering after the
 * card was dismissed all return `false` and never change a decision that already exists.
 */
class DshCommandApprovalQueue(
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class Pending(
        val request: DshCommandApprovalRequest,
        val deferred: CompletableDeferred<DshCommandApprovalDecision>
    )

    private val lock = Any()
    private var pending: Pending? = null

    /**
     * Registers [request] as the only pending question.
     *
     * Returns the id of the request it replaced (answered DENY), or null when nothing was pending.
     */
    fun register(request: DshCommandApprovalRequest): Registration = synchronized(lock) {
        val previous = pending
        val previousId = previous?.request?.id
        if (previous != null) previous.deferred.complete(DshCommandApprovalDecision.DENY)
        val deferred = CompletableDeferred<DshCommandApprovalDecision>()
        pending = Pending(request, deferred)
        Registration(request = request, previousId = previousId, deferred = deferred)
    }

    /** The request waiting for an answer, if any. */
    fun current(): DshCommandApprovalRequest? = synchronized(lock) { pending?.request }

    /** The deferred the awaiting caller is blocked on; null when nothing is pending. */
    fun currentDeferred(): CompletableDeferred<DshCommandApprovalDecision>? =
        synchronized(lock) { pending?.deferred }

    /**
     * Answers [id]. Returns true only when this call is the one that decided it.
     *
     * An answer that arrives after the deadline is converted to DENY and reported as `false`: the user's
     * tap did happen, but too late to authorise anything.
     */
    fun decide(id: Long, decision: DshCommandApprovalDecision): Boolean = synchronized(lock) {
        val current = pending ?: return false
        if (current.request.id != id) return false
        if (current.deferred.isCompleted) return false
        val effective = if (clock() > current.request.deadlineMillis) {
            DshCommandApprovalDecision.DENY
        } else {
            decision
        }
        pending = null
        current.deferred.complete(effective)
        effective == decision
    }

    /** Expires the pending request when its deadline passed; returns the id it denied. */
    fun expireIfDue(nowMillis: Long = clock()): Long? = synchronized(lock) {
        val current = pending ?: return null
        if (nowMillis <= current.request.deadlineMillis) return null
        pending = null
        current.deferred.complete(DshCommandApprovalDecision.DENY)
        current.request.id
    }

    /** Resolves [id] as DENY without a user answer (cancellation, window lost, override). */
    fun cancel(id: Long): Boolean = synchronized(lock) {
        val current = pending ?: return false
        if (current.request.id != id) return false
        pending = null
        current.deferred.complete(DshCommandApprovalDecision.DENY)
        true
    }

    /** One registration outcome: the request, the replaced id, and the deferred to await. */
    class Registration internal constructor(
        val request: DshCommandApprovalRequest,
        val previousId: Long?,
        internal val deferred: CompletableDeferred<DshCommandApprovalDecision>
    )

    companion object {
        /** Long enough to read a command, short enough that a forgotten card cannot authorise later. */
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
    }
}

/**
 * The bridge between a channel and the floating window.
 *
 * [await] asks the presenter to show a card and waits for the user. Every path that is not an explicit
 * [DshCommandApprovalDecision.ALLOW_ONCE] ends as [DshCommandApprovalDecision.DENY]:
 *  - approval switched off;
 *  - no presenter installed;
 *  - the presenter could not display the card (no window);
 *  - the 30 s timeout;
 *  - the caller being cancelled (the pending request is answered DENY and the cancellation is then
 *    rethrown, so this never swallows a `CancellationException`).
 */
class DshCommandApprovalGate(
    private val queue: DshCommandApprovalQueue,
    private val presenter: () -> DshCommandApprovalPresenter?,
    private val enabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nextRequestId: () -> Long = DEFAULT_REQUEST_IDS::incrementAndGet
) {

    suspend fun await(
        kind: DshCommandApprovalKind,
        commandMasked: String,
        reasonCode: String
    ): DshCommandApprovalDecision {
        if (!enabled()) return DshCommandApprovalDecision.DENY
        val target = presenter() ?: return DshCommandApprovalDecision.DENY
        val request = DshCommandApprovalRequest(
            id = nextRequestId(),
            kind = kind,
            commandMasked = commandMasked,
            reasonCode = reasonCode,
            atMillis = clock(),
            timeoutMillis = queue.timeoutMillis
        )
        val registration = queue.register(request)
        val shown = runCatching { target.present(request) }.getOrDefault(false)
        if (!shown) {
            queue.cancel(request.id)
            return DshCommandApprovalDecision.DENY
        }
        return try {
            withTimeoutOrNull(queue.timeoutMillis) { registration.deferred.await() }
                ?: DshCommandApprovalDecision.DENY
        } finally {
            // Runs on answer, timeout and cancellation alike: never leave a ghost card, and never leave
            // the request waitable. Rethrowing cancellation is left to the coroutine machinery.
            if (!registration.deferred.isCompleted) queue.cancel(request.id)
            runCatching { target.dismiss(request.id) }
        }
    }

    companion object {
        private val DEFAULT_REQUEST_IDS = AtomicLong()
    }
}

/**
 * Process-level owner of the approval path, wired by the app composition.
 *
 * Defaults are the strict ones: no presenter, approval disabled → [await] denies without showing
 * anything, which is exactly the previous "static refusal" behaviour.
 */
object DshCommandApproval {

    private val queue = DshCommandApprovalQueue()

    @Volatile
    private var presenter: DshCommandApprovalPresenter? = null

    @Volatile
    private var enabledProvider: () -> Boolean = { false }

    private val gate = DshCommandApprovalGate(
        queue = queue,
        presenter = { presenter },
        enabled = { enabledProvider() }
    )

    /** Installs the window that renders cards. Called once by the app composition. */
    fun installPresenter(next: DshCommandApprovalPresenter) {
        presenter = next
    }

    /** Removes [next] only if it is still the installed presenter (idempotent teardown). */
    fun clearPresenter(next: DshCommandApprovalPresenter) {
        if (presenter === next) presenter = null
    }

    /** The one switch: "may the window ask?" — wired to the persisted user preference. */
    fun setEnabledProvider(provider: () -> Boolean) {
        enabledProvider = provider
    }

    /** Answers a pending card. Idempotent; see [DshCommandApprovalQueue.decide]. */
    fun decide(id: Long, decision: DshCommandApprovalDecision): Boolean = queue.decide(id, decision)

    /** Defensive sweep used by the window tick; the gate's own timeout normally wins. */
    fun expireIfDue(nowMillis: Long = System.currentTimeMillis()): Long? = queue.expireIfDue(nowMillis)

    /** The pending request, if the window needs to re-render after a recreation. */
    fun current(): DshCommandApprovalRequest? = queue.current()

    suspend fun await(
        kind: DshCommandApprovalKind,
        commandMasked: String,
        reasonCode: String
    ): DshCommandApprovalDecision = gate.await(kind, commandMasked, reasonCode)

    internal fun resetForTesting() {
        presenter = null
        enabledProvider = { false }
        queue.currentDeferred()?.complete(DshCommandApprovalDecision.DENY)
    }
}

/** Rejections a user may explicitly authorise. Structural failures never are. */
fun DshDevicePolicyRejection.isApprovalCandidate(): Boolean = when (this) {
    DshDevicePolicyRejection.DENIED_COMMAND,
    DshDevicePolicyRejection.DENIED_ARGUMENT,
    DshDevicePolicyRejection.NOT_WHITELISTED -> true

    // Nothing to authorise: the command is empty, too long, or cannot be re-rendered safely.
    DshDevicePolicyRejection.EMPTY_COMMAND,
    DshDevicePolicyRejection.COMMAND_TOO_LONG,
    DshDevicePolicyRejection.SHELL_COMPOSITION,
    DshDevicePolicyRejection.EMPTY_SEGMENT -> false
}

/**
 * The exact string a channel may run after an ALLOW_ONCE, or null when the command must stay blocked.
 *
 * Re-parses the submitted command structurally (never through the allow/deny lists, which are exactly
 * what the user is overriding) and re-renders it with single-quote escaping. A command that the scanner
 * cannot break into plain argv segments can never be approved.
 */
internal suspend fun dshApprovedSegmentsFor(
    command: String,
    rejection: DshDevicePolicyRejection,
    kind: DshCommandApprovalKind
): List<DshDeviceCommandSegment>? {
    if (!rejection.isApprovalCandidate()) return null
    val segments = dshStructuralSegments(command) ?: return null
    val decision = DshCommandApproval.await(
        kind = kind,
        commandMasked = DshCommandApprovalMasking.mask(command),
        reasonCode = rejection.code
    )
    return segments.takeIf { decision == DshCommandApprovalDecision.ALLOW_ONCE }
}

/**
 * Structural parse only: segments whose rendering is quote-safe.
 *
 * No semantic judgement happens here — that is the whole point of an approval. The checks that remain
 * are the ones that keep re-rendering honest: a blank command, an unparseable shell composition, an
 * empty segment, a bare `name` that is really a path.
 */
internal fun dshStructuralSegments(command: String): List<DshDeviceCommandSegment>? {
    val raw = command.trim()
    if (raw.isEmpty() || raw.length > DshDeviceCommandPolicy.MAX_COMMAND_LENGTH) return null
    val segments = DshDeviceCommandScanner(raw).scan() ?: return null
    if (segments.isEmpty()) return null
    if (segments.any { it.argv.isEmpty() }) return null
    if (segments.any { it.argv.first().contains('/') }) return null
    return segments
}
