package com.muyuchat.core.dsh.runtime.process

import com.muyuchat.core.dsh.contract.capability.DshTerminalSessionAdmission
import com.muyuchat.core.dsh.contract.runtime.DshTerminalCapability
import com.muyuchat.core.dsh.contract.runtime.DshTerminalCloseOutcome
import com.muyuchat.core.dsh.contract.runtime.DshTerminalCloseReason
import com.muyuchat.core.dsh.contract.runtime.DshTerminalEvent
import com.muyuchat.core.dsh.contract.runtime.DshTerminalExit
import com.muyuchat.core.dsh.contract.runtime.DshTerminalSize
import com.muyuchat.core.dsh.runtime.terminal.DshTerminalFactory
import com.muyuchat.core.dsh.runtime.terminal.DshTerminalResizeResult
import com.muyuchat.core.dsh.runtime.terminal.DshTerminalSession
import com.muyuchat.core.dsh.runtime.terminal.DshTerminalSessionOpen
import com.muyuchat.core.dsh.runtime.terminal.DshTerminalWriteResult
import java.io.File

import java.util.concurrent.atomic.AtomicLong


/** Owner-facing handle of one session; the host keeps the pump and the retention window. */
class DshTerminalSessionHandle internal constructor(
    private val host: DshTerminalHost,
    private val record: DshTerminalHost.Record
) {
    val sessionId: String get() = record.id
    val ownerKey: String get() = record.ownerKey

    fun snapshot(): DshTerminalSnapshot = host.snapshotOf(record)

    /** Re-attaches after a UI/page reconnect: applies the pending size and replays retained output. */
    fun attach(size: DshTerminalSize? = null, maxBytes: Int = DshTerminalHost.DEFAULT_READ_BYTES): DshTerminalReadOutcome =
        host.attach(ownerKey, sessionId, size, maxBytes)

    fun read(
        offset: Long = 0L,
        maxBytes: Int = DshTerminalHost.DEFAULT_READ_BYTES
    ): DshTerminalReadOutcome = host.read(ownerKey, sessionId, offset, maxBytes)

    fun write(text: String, submit: Boolean = true): DshTerminalWriteOutcome =
        host.write(ownerKey, sessionId, text, submit)

    fun writeBytes(bytes: ByteArray): DshTerminalWriteOutcome = host.writeBytes(ownerKey, sessionId, bytes)

    fun control(control: DshTerminalControl): DshTerminalWriteOutcome =
        host.control(ownerKey, sessionId, control)

    fun resize(size: DshTerminalSize): DshTerminalResizeOutcome =
        host.resize(ownerKey, sessionId, size)

    fun close(reason: DshTerminalCloseReason = DshTerminalCloseReason.CLIENT_CLOSED): DshTerminalCloseOutcome? =
        host.close(ownerKey, sessionId, reason)
}

/**
 * Production owner of every real terminal child of one DSH generation.
 *
 * Five ownership questions, answered here:
 *  1. who creates: [open] via [DshTerminalFactory] (real `libdshpty.so` PTY; an explicitly
 *     reported pipe fallback only when the caller allows it);
 *  2. who uses: the official tool seam / workspace UI through a [DshTerminalSessionHandle] or the
 *     typed methods; the host serialises every call;
 *  3. who cancels: [close] (SIGTERM -> SIGKILL on the child's process group via the native
 *     primitive) and [closeAll] for generation/service stop;
 *  4. who releases: the pump thread ends on exit/close and joins the close; descriptors are
 *     released by the port; retained output is bounded and dropped with the record;
 *  5. who recovers: a session outlives attachment loss. Output is retained with a monotonic byte
 *     cursor, so a reconnecting client calls [attach]/[read] from its last offset; the child is
 *     never re-spawned for a reconnect.
 *
 * Background work is a session without an attachment: it keeps running, keeps its bounded
 * scrollback, and is stopped by the same owner. Ctrl+C is a tty line-discipline write (`0x03`),
 * not a synthetic Java signal, so it reaches the real foreground process group. Resizes go through
 * the session's coalescing and are reported UNSAFE when the transport cannot apply them (pipe).
 *
 * Threading: one pump thread per running session; every public method is safe from any thread.
 * [autoPump] exists only so JVM tests can advance the pump deterministically; production always
 * leaves it true.
 */
class DshTerminalHost(
    private val factory: DshTerminalFactory,
    private val modelProvider: () -> DshCommandClosureModel,
    private val environmentProvider: () -> Map<String, String>,
    private val workingDirectoryProvider: () -> String,
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val maxSessionsPerOwner: Int = DEFAULT_MAX_SESSIONS_PER_OWNER,
    private val retainedOutputBytes: Int = DEFAULT_RETAINED_BYTES,
    private val initialOutputMillis: Long = DEFAULT_INITIAL_OUTPUT_MILLIS,
    private val pumpIdleMillis: Long = DEFAULT_PUMP_IDLE_MILLIS,
    private val autoPump: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /**
     * Optional maintenance admission for a session's whole lifetime. A PTY outlives the request
     * that opened it, so the lease is held until the session is closed (or the host stops).
     */
    private val sessionAdmission: DshTerminalSessionAdmission? = null
) {

    internal class Record(
        val id: String,
        val ownerKey: String,
        val name: String?,
        val type: String,
        val session: DshTerminalSession,
        val createdAtMillis: Long,
        retainedBytes: Int,
        /** Releases the session's maintenance lease exactly once; set when the session is admitted. */
        private val releaseAdmission: () -> Unit = {}
    ) {
        private val admissionReleased = java.util.concurrent.atomic.AtomicBoolean(false)

        fun releaseAdmissionOnce() {
            if (admissionReleased.compareAndSet(false, true)) {
                runCatching { releaseAdmission() }
            }
        }

        val ring = DshTerminalOutputRing(retainedBytes)
        /** Per-session read buffer: sessions pump on independent threads and must not share it. */
        val buffer = ByteArray(8 * 1024)
        @Volatile var status: DshTerminalStatus = DshTerminalStatus.RUNNING
        @Volatile var exit: DshTerminalExit? = null
        @Volatile var failure: String? = null
        @Volatile var size: DshTerminalSize? = null
        @Volatile var closing = false
        @Volatile var pumpThread: Thread? = null
        @Volatile var pumpFailure: String? = null
    }

    private val lock = Any()
    private val records = LinkedHashMap<String, Record>()
    private val sequence = AtomicLong(0L)

    @Volatile
    private var closed = false

    /** What the platform actually offers, without opening anything. */
    fun capability(): DshTerminalCapability = factory.capability()

    /** True while this owner accepts new sessions. */
    val acceptingSessions: Boolean get() = !closed

    /** Opens the registered `shell` backend. Never silently substitutes a transport. */
    fun open(
        ownerKey: String,
        type: String = SHELL_TYPE,
        name: String? = null,
        cwd: String? = null,
        size: DshTerminalSize = DshTerminalSize.DEFAULT,
        requireRealPty: Boolean = false
    ): DshTerminalOpenOutcome {
        if (closed) {
            return DshTerminalOpenOutcome.Refused(DshTerminalRefusal.HOST_CLOSED, "the terminal owner is closed")
        }
        if (type != SHELL_TYPE) {
            return DshTerminalOpenOutcome.Refused(
                DshTerminalRefusal.UNSUPPORTED_TYPE,
                "the only registered terminal backend type is '$SHELL_TYPE', not '$type'"
            )
        }
        val normalizedName = name?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedName != null && (normalizedName.length > MAX_NAME_CHARS || normalizedName.any { it.code < 0x20 })) {
            return DshTerminalOpenOutcome.Refused(
                DshTerminalRefusal.INVALID_NAME,
                "a terminal name must be 1..$MAX_NAME_CHARS printable characters"
            )
        }
        val workingDirectory = cwd?.trim()?.takeIf { it.isNotEmpty() } ?: workingDirectoryProvider()
        if (!isUsableWorkingDirectory(workingDirectory)) {
            return DshTerminalOpenOutcome.Refused(
                DshTerminalRefusal.INVALID_WORKING_DIRECTORY,
                "terminal cwd must be an existing absolute directory: $workingDirectory"
            )
        }
        synchronized(lock) {
            if (closed) {
                return DshTerminalOpenOutcome.Refused(DshTerminalRefusal.HOST_CLOSED, "the terminal owner is closed")
            }
            val live = records.values.count { it.status != DshTerminalStatus.CLOSED }
            if (live >= maxSessions) {
                return DshTerminalOpenOutcome.Refused(
                    DshTerminalRefusal.LIMIT_REACHED,
                    "the terminal owner already has $live sessions (limit $maxSessions)"
                )
            }
            val ownerLive = records.values.count {
                it.ownerKey == ownerKey && it.status != DshTerminalStatus.CLOSED
            }
            if (ownerLive >= maxSessionsPerOwner) {
                return DshTerminalOpenOutcome.Refused(
                    DshTerminalRefusal.LIMIT_REACHED,
                    "owner '$ownerKey' already has $ownerLive sessions (limit $maxSessionsPerOwner)"
                )
            }
        }

        val environment = runCatching { environmentProvider() }.getOrElse { failure ->
            return DshTerminalOpenOutcome.Refused(
                DshTerminalRefusal.OPEN_FAILED,
                "terminal environment unavailable: ${failure.javaClass.simpleName}"
            )
        }
        val model = runCatching { modelProvider() }.getOrElse { failure ->
            return DshTerminalOpenOutcome.Refused(
                DshTerminalRefusal.OPEN_FAILED,
                "the payload closure cannot be scanned: ${failure.javaClass.simpleName}"
            )
        }
        // The session's maintenance lease is acquired before the child exists: a refusal must not
        // spawn a process that a running maintenance transaction could race with.
        val id = "term-" + sequence.incrementAndGet()
        val releaseAdmission = sessionAdmission?.acquire(ownerKey, id)
        if (sessionAdmission != null && releaseAdmission == null) {
            return DshTerminalOpenOutcome.Refused(
                DshTerminalRefusal.MAINTENANCE_ACTIVE,
                "an exclusive maintenance write owns the workspace; the session was not opened"
            )
        }
        val opened = try {
            factory.openShell(
                model = model,
                workingDirectory = workingDirectory,
                environment = environment,
                size = size,
                arguments = SHELL_ARGUMENTS
            )
        } catch (t: Throwable) {
            releaseAdmission?.invoke()
            return DshTerminalOpenOutcome.Refused(
                DshTerminalRefusal.OPEN_FAILED,
                "the shell could not be opened: ${t.javaClass.simpleName}" +
                    t.message?.take(120)?.let { ": $it" }.orEmpty()
            )
        }
        val session = when (opened) {
            is DshTerminalSessionOpen.Unavailable -> {
                releaseAdmission?.invoke()
                return DshTerminalOpenOutcome.Refused(
                    if (opened.detail.contains("no shell", ignoreCase = true)) {
                        DshTerminalRefusal.SHELL_UNAVAILABLE
                    } else {
                        DshTerminalRefusal.OPEN_FAILED
                    },
                    opened.detail
                )
            }

            is DshTerminalSessionOpen.Opened -> opened.session
        }
        if (requireRealPty && !session.isRealPty) {
            val detail = "a real PTY is required but the transport is ${session.transport.name}: " +
                closureCapabilityDetail()
            runCatching { session.close(DshTerminalCloseReason.FAILED, 500L, 1_000L) }
            releaseAdmission?.invoke()
            return DshTerminalOpenOutcome.Refused(DshTerminalRefusal.PTY_UNAVAILABLE, detail)
        }
        val record = Record(
            id = id,
            ownerKey = ownerKey,
            name = normalizedName,
            type = type,
            session = session,
            createdAtMillis = clock(),
            retainedBytes = retainedOutputBytes,
            releaseAdmission = releaseAdmission ?: {}
        )
        // The opener receives the terminal already presented: mark the session attached so the
        // requested geometry is applied now rather than being held as a pre-attach resize.
        session.attach(size)
        // A pipe has no kernel geometry: report null instead of echoing the requested size.
        record.size = if (session.isRealPty) session.sizeOrRequested(size) else null
        val rejection = synchronized(lock) {
            val live = records.values.count { it.status != DshTerminalStatus.CLOSED }
            val ownerLive = records.values.count {
                it.ownerKey == ownerKey && it.status != DshTerminalStatus.CLOSED
            }
            when {
                closed -> DshTerminalRefusal.HOST_CLOSED
                live >= maxSessions || ownerLive >= maxSessionsPerOwner -> DshTerminalRefusal.LIMIT_REACHED
                else -> {
                    records[id] = record
                    null
                }
            }
        }
        if (rejection != null) {
            // A stop or a concurrent open won the race: the freshly started child must still be
            // reaped, never inserted after the close snapshot (that would be an orphan).
            closeRecord(record, DshTerminalCloseReason.SERVICE_STOPPED)
            return DshTerminalOpenOutcome.Refused(
                rejection,
                if (rejection == DshTerminalRefusal.HOST_CLOSED) {
                    "the terminal owner closed during open"
                } else {
                    "the terminal session limit was reached during open"
                }
            )
        }
        log(
            "dsh terminal open id=$id type=$type transport=${session.transport} " +
                "pty=${session.isRealPty} pid=${session.processId ?: "?"} cwd=$workingDirectory"
        )
        // Capture the startup banner/prompt before the caller receives the session, so a
        // "started" acknowledgement is backed by real bytes when the shell printed any.
        pumpInitial(record)
        if (record.closing || record.status == DshTerminalStatus.CLOSED) {
            return DshTerminalOpenOutcome.Refused(DshTerminalRefusal.HOST_CLOSED, "the terminal owner closed during open")
        }
        if (record.status == DshTerminalStatus.FAILED && record.exit == null && session.phase == DshTerminalSession.Phase.FAILED) {
            closeRecord(record, DshTerminalCloseReason.FAILED)
            return DshTerminalOpenOutcome.Refused(
                DshTerminalRefusal.OPEN_FAILED,
                "the shell child failed immediately: ${record.failure ?: "unknown read failure"}"
            )
        }
        startPump(record)
        val initial = readRecord(record, 0L, DEFAULT_READ_BYTES)
        return DshTerminalOpenOutcome.Opened(
            snapshot = snapshotOf(record),
            motd = if (initial.truncated) "" else initial.text
        )
    }

    fun list(ownerKey: String? = null): List<DshTerminalSnapshot> {
        val copy = synchronized(lock) {
            records.values.filter { ownerKey == null || it.ownerKey == ownerKey }
        }
        return copy.map { snapshotOf(it) }
    }

    fun find(ownerKey: String, sessionId: String): DshTerminalSessionHandle? {
        val record = synchronized(lock) {
            records[sessionId]?.takeIf { it.ownerKey == ownerKey && it.status != DshTerminalStatus.CLOSED }
        } ?: return null
        return DshTerminalSessionHandle(this, record)
    }

    fun attach(
        ownerKey: String,
        sessionId: String,
        size: DshTerminalSize? = null,
        maxBytes: Int = DEFAULT_READ_BYTES
    ): DshTerminalReadOutcome {
        val record = resolve(ownerKey, sessionId) ?: return unknownSession(sessionId)
        if (size != null) {
            record.session.attach(size)
        } else {
            record.session.attach()
        }
        record.size = if (record.session.isRealPty) record.session.size() else null
        val geometry = record.size ?: DshTerminalSize.DEFAULT
        log("dsh terminal attach id=$sessionId size=${geometry.columns}x${geometry.rows}")
        return DshTerminalReadOutcome.Ok(readRecord(record, 0L, maxBytes))
    }

    fun read(
        ownerKey: String,
        sessionId: String,
        offset: Long = 0L,
        maxBytes: Int = DEFAULT_READ_BYTES
    ): DshTerminalReadOutcome {
        val record = resolve(ownerKey, sessionId) ?: return unknownSession(sessionId)
        return DshTerminalReadOutcome.Ok(readRecord(record, offset, maxBytes))
    }

    fun write(ownerKey: String, sessionId: String, text: String, submit: Boolean = true): DshTerminalWriteOutcome =
        writeBytes(ownerKey, sessionId, (text + if (submit) "\r" else "").toByteArray(Charsets.UTF_8))

    fun writeBytes(ownerKey: String, sessionId: String, bytes: ByteArray): DshTerminalWriteOutcome {
        val record = resolve(ownerKey, sessionId) ?: return refusedUnknown(sessionId)
        if (record.status == DshTerminalStatus.EXITED) {
            return DshTerminalWriteOutcome.Refused(
                DshTerminalRefusal.SESSION_EXITED,
                "the terminal child already exited" + record.exit?.let { " (code=${it.code}, signal=${it.signal})" }.orEmpty()
            )
        }
        if (record.status == DshTerminalStatus.FAILED) {
            return DshTerminalWriteOutcome.Refused(
                DshTerminalRefusal.SESSION_FAILED,
                record.failure ?: "the terminal transport failed"
            )
        }
        return when (val result = record.session.write(bytes)) {
            is DshTerminalWriteResult.Written -> DshTerminalWriteOutcome.Written(result.bytes)
            is DshTerminalWriteResult.PeerClosed -> DshTerminalWriteOutcome.PeerClosed
            is DshTerminalWriteResult.Rejected -> DshTerminalWriteOutcome.Refused(
                DshTerminalRefusal.SESSION_FAILED,
                "${result.code.name}: ${result.detail}"
            )
        }
    }

    fun control(ownerKey: String, sessionId: String, control: DshTerminalControl): DshTerminalWriteOutcome {
        val record = resolve(ownerKey, sessionId) ?: return refusedUnknown(sessionId)
        if (!record.session.isRealPty) {
            return DshTerminalWriteOutcome.Refused(
                DshTerminalRefusal.PTY_UNAVAILABLE,
                "the pipe fallback has no tty line discipline; Ctrl+${control.name.removePrefix("CTRL_")} " +
                    "cannot be delivered to a foreground process group"
            )
        }
        return writeBytes(ownerKey, sessionId, byteArrayOf(control.byte))
    }

    fun resize(ownerKey: String, sessionId: String, size: DshTerminalSize): DshTerminalResizeOutcome {
        val record = resolve(ownerKey, sessionId) ?: return DshTerminalResizeOutcome.Refused(
            DshTerminalRefusal.UNKNOWN_SESSION,
            "unknown terminal session '$sessionId'"
        )
        return when (val result = record.session.resize(size)) {
            is DshTerminalResizeResult.Applied -> {
                record.size = result.size
                DshTerminalResizeOutcome.Applied(result.size, record.session.transport)
            }

            is DshTerminalResizeResult.Coalesced -> {
                record.size = result.size
                DshTerminalResizeOutcome.Coalesced(result.size, record.session.transport)
            }

            is DshTerminalResizeResult.Ignored -> if (record.session.isRealPty) {
                DshTerminalResizeOutcome.Applied(result.size, record.session.transport)
            } else {
                DshTerminalResizeOutcome.Unsupported(
                    result.size,
                    record.session.transport,
                    "the pipe fallback has no kernel geometry; resize has no effect"
                )
            }

            is DshTerminalResizeResult.Rejected -> DshTerminalResizeOutcome.Refused(
                DshTerminalRefusal.UNKNOWN_SESSION,
                result.detail.ifBlank { "resize was rejected" }
            )

            is DshTerminalResizeResult.Unsupported -> DshTerminalResizeOutcome.Unsupported(
                result.size,
                record.session.transport,
                result.detail
            )

            is DshTerminalResizeResult.Rejected -> DshTerminalResizeOutcome.Refused(
                DshTerminalRefusal.SESSION_FAILED,
                "${result.code.name}: ${result.detail}"
            )
        }
    }

    fun close(
        ownerKey: String,
        sessionId: String,
        reason: DshTerminalCloseReason = DshTerminalCloseReason.CLIENT_CLOSED
    ): DshTerminalCloseOutcome? {
        val record = synchronized(lock) { records[sessionId]?.takeIf { it.ownerKey == ownerKey } }
            ?: return null
        return closeRecord(record, reason)
    }

    /**
     * Stops every session this owner created: SIGTERM to each child's session/process group, then
     * SIGKILL after [graceMillis], waiting for the pump to drain. A timeout is reported as an
     * unsettled session, never as stopped.
     */
    fun closeAll(
        reason: DshTerminalCloseReason = DshTerminalCloseReason.SERVICE_STOPPED,
        graceMillis: Long = DshTerminalSession.DEFAULT_GRACE_MILLIS,
        killMillis: Long = DshTerminalSession.DEFAULT_KILL_MILLIS
    ): DshTerminalHostShutdown {
        closed = true
        val snapshot = synchronized(lock) { records.values.toList() }
        val reports = snapshot.map { record ->
            val outcome = closeRecord(record, reason, graceMillis, killMillis)
            DshTerminalCloseReport(record.id, record.status, outcome)
        }
        return DshTerminalHostShutdown(settled = reports.all { it.outcome.settled }, sessions = reports)
    }

    // ------------------------------------------------------------------ internals

    internal fun snapshotOf(record: Record): DshTerminalSnapshot {
        val bounds = record.ring.bounds()
        return DshTerminalSnapshot(
            sessionId = record.id,
            ownerKey = record.ownerKey,
            name = record.name,
            type = record.type,
            transport = record.session.transport,
            realPty = record.session.isRealPty,
            pid = record.session.processId,
            status = record.status,
            exit = record.exit,
            size = record.size,
            createdAtMillis = record.createdAtMillis,
            firstRetainedOffset = bounds.firstRetainedOffset,
            nextOffset = bounds.nextOffset
        )
    }

    private fun resolve(ownerKey: String, sessionId: String): Record? = synchronized(lock) {
        records[sessionId]?.takeIf { it.ownerKey == ownerKey && it.status != DshTerminalStatus.CLOSED }
    }

    private fun unknownSession(sessionId: String): DshTerminalReadOutcome = DshTerminalReadOutcome.Refused(
        DshTerminalRefusal.UNKNOWN_SESSION,
        "unknown terminal session '$sessionId' for this owner"
    )

    private fun refusedUnknown(sessionId: String): DshTerminalWriteOutcome = DshTerminalWriteOutcome.Refused(
        DshTerminalRefusal.UNKNOWN_SESSION,
        "unknown terminal session '$sessionId' for this owner"
    )

    private fun readRecord(record: Record, offset: Long, maxBytes: Int): DshTerminalRead {
        val page = record.ring.read(offset.coerceAtLeast(0L), maxBytes.coerceIn(1, MAX_READ_BYTES))
        return DshTerminalRead(
            sessionId = record.id,
            text = String(page.data, Charsets.UTF_8),
            bytes = page.data.size,
            nextOffset = page.nextOffset,
            truncated = page.truncated,
            status = record.status,
            exit = record.exit
        )
    }

    private fun pumpInitial(record: Record) {
        if (initialOutputMillis <= 0L) return
        val deadline = clock() + initialOutputMillis
        repeat(INITIAL_PUMP_ROUNDS) {
            val remaining = deadline - clock()
            if (remaining <= 0L || record.status != DshTerminalStatus.RUNNING) return
            pumpOnce(record, minOf(remaining, INITIAL_PUMP_SLICE_MILLIS))
        }
    }

    private fun startPump(record: Record) {
        if (!autoPump || record.status != DshTerminalStatus.RUNNING) return
        val thread = Thread({ pumpLoop(record) }, "dsh-terminal-${record.id}").apply {
            isDaemon = true
        }
        record.pumpThread = thread
        thread.start()
    }

    private fun pumpLoop(record: Record) {
        try {
            while (!record.closing && record.status == DshTerminalStatus.RUNNING) {
                pumpOnce(record, pumpIdleMillis)
            }
        } catch (t: Throwable) {
            record.pumpFailure = "${t.javaClass.simpleName}: ${t.message?.take(120).orEmpty()}"
            log("dsh terminal pump ended id=${record.id} failure=${record.pumpFailure}")
        }
    }

    /**
     * One bounded pump step shared by the production thread and JVM tests ([autoPump] false).
     * Returns true when the pump produced output or a terminal state.
     */
    internal fun pumpOnce(record: Record, timeoutMillis: Long): Boolean {
        if (record.closing || record.status != DshTerminalStatus.RUNNING) return false
        val events = try {
            record.session.pump(record.buffer, timeoutMillis.coerceAtLeast(0L))
        } catch (t: Throwable) {
            if (!record.closing) {
                record.status = DshTerminalStatus.FAILED
                record.failure = "terminal pump raised ${t.javaClass.simpleName}"
            }
            return true
        }
        var progressed = false
        for (event in events) {
            when (event) {
                is DshTerminalEvent.Output -> {
                    record.ring.append(event.bytes)
                    progressed = true
                }

                is DshTerminalEvent.Exited -> {
                    record.exit = event.exit
                    record.status = DshTerminalStatus.EXITED
                    progressed = true
                }

                is DshTerminalEvent.Resized -> {
                    record.size = event.size
                    progressed = true
                }

                is DshTerminalEvent.Failed -> {
                    record.failure = "${event.code.name}: ${event.detail}"
                    record.status = DshTerminalStatus.FAILED
                    progressed = true
                }

                is DshTerminalEvent.Closed -> {
                    record.exit = record.exit ?: record.session.exit
                    record.status = DshTerminalStatus.EXITED
                    progressed = true
                }
            }
        }
        if (record.closing) return progressed
        if (record.status == DshTerminalStatus.RUNNING) {
            val observed = try {
                record.session.pollExit(0L)
            } catch (t: Throwable) {
                null
            }
            if (observed != null) {
                record.exit = observed
                record.status = DshTerminalStatus.EXITED
                progressed = true
            } else if (record.session.observedEndOfStream && record.pumpFailure != null) {
                record.failure = record.pumpFailure
                record.status = DshTerminalStatus.FAILED
                progressed = true
            }
        }
        return progressed
    }

    /** Test seam: advances the pump without the production thread ([autoPump] false). */
    internal fun pumpForTest(ownerKey: String, sessionId: String, rounds: Int = 4): Boolean {
        val record = resolve(ownerKey, sessionId) ?: return false
        var progressed = false
        repeat(rounds.coerceAtLeast(1)) {
            if (record.status != DshTerminalStatus.RUNNING) return progressed
            progressed = pumpOnce(record, 0L) || progressed
        }
        return progressed
    }

    private fun closeRecord(
        record: Record,
        reason: DshTerminalCloseReason,
        graceMillis: Long = DshTerminalSession.DEFAULT_GRACE_MILLIS,
        killMillis: Long = DshTerminalSession.DEFAULT_KILL_MILLIS
    ): DshTerminalCloseOutcome {
        if (record.closing || record.status == DshTerminalStatus.CLOSED) {
            return DshTerminalCloseOutcome(settled = true, escalated = false, detail = "already closed")
        }
        record.closing = true
        val outcome = try {
            when (val event = record.session.close(reason, graceMillis, killMillis)) {
                is DshTerminalEvent.Closed -> event.outcome
                else -> DshTerminalCloseOutcome(false, false, "terminal close produced ${event.javaClass.simpleName}")
            }
        } catch (t: Throwable) {
            DshTerminalCloseOutcome(
                settled = false,
                escalated = false,
                detail = "terminal close raised ${t.javaClass.simpleName}"
            )
        } finally {
            record.status = DshTerminalStatus.CLOSED
            record.exit = record.exit ?: record.session.exit
            record.releaseAdmissionOnce()
        }
        runCatching { record.pumpThread?.join(PUMP_JOIN_MILLIS) }
        synchronized(lock) { records.remove(record.id) }
        log(
            "dsh terminal close id=${record.id} reason=${reason.name} settled=${outcome.settled} " +
                "escalated=${outcome.escalated} detail=${outcome.detail}"
        )
        return outcome
    }

    private fun closureCapabilityDetail(): String = when (val capability = factory.capability()) {
        is DshTerminalCapability.RealPty -> "real pty available via ${capability.nativeLibrary}"
        is DshTerminalCapability.PipeFallback ->
            "real pty unavailable (${capability.reason.name}: ${capability.detail})"

        is DshTerminalCapability.Unavailable ->
            "no terminal transport (${capability.reason.name}: ${capability.detail})"
    }

    private fun isUsableWorkingDirectory(path: String): Boolean {
        if (path.contains('\u0000')) return false
        return runCatching { File(path).let { it.isAbsolute && it.isDirectory } }.getOrDefault(false)
    }

    private fun DshTerminalSession.sizeOrRequested(fallback: DshTerminalSize): DshTerminalSize = size() ?: fallback


    companion object {
        const val SHELL_TYPE = "shell"
        private val SHELL_ARGUMENTS = listOf("-i")

        const val DEFAULT_MAX_SESSIONS = 8
        const val DEFAULT_MAX_SESSIONS_PER_OWNER = 4
        const val DEFAULT_RETAINED_BYTES = 256 * 1024
        const val DEFAULT_READ_BYTES = 64 * 1024
        const val MAX_READ_BYTES = 256 * 1024
        const val DEFAULT_INITIAL_OUTPUT_MILLIS = 450L
        /** Short bounded read wait: input/resize share the session monitor with the pump. */
        const val DEFAULT_PUMP_IDLE_MILLIS = 50L

        private const val MAX_NAME_CHARS = 64
        private const val INITIAL_PUMP_ROUNDS = 3
        private const val INITIAL_PUMP_SLICE_MILLIS = 150L
        private const val PUMP_JOIN_MILLIS = 1_500L
    }
}
