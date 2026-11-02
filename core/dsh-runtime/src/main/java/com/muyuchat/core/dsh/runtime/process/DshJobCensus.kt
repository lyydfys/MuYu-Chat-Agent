package com.muyuchat.core.dsh.runtime.process

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Periodic census of the Node host's descendants during one READY generation.
 *
 * Every [pollMillis] it enumerates the descendants of the host PID, reads each candidate's
 * `/proc/<pid>/environ`, and records only processes that carry
 * [DshJobIdentity.GENERATION_ENV] exactly equal to this generation. That token is inherited from
 * the host environment, so it attributes a process to this installation even after the host dies
 * (the official payload is never patched). A candidate is described by PID + kernel start time +
 * command-line hash; the census **never signals** anything.
 *
 * Threading: one daemon thread, started on READY and stopped on host exit/service destroy.
 * [stop] clears the running flag, interrupts the sleep and joins with a bound, so no census thread
 * outlives the generation it describes. [pollOnce] is synchronous and is the whole policy, which
 * keeps it unit-testable without threads.
 */
internal class DshJobCensus(
    private val source: DshJobProcessSource,
    private val ledger: DshJobLedger,
    private val generation: Long,
    private val hostPid: Int,
    private val pollMillis: Long = DEFAULT_POLL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private val generationToken = generation.toString()

    @Volatile
    private var thread: Thread? = null

    fun start() {
        if (hostPid <= 0 || !running.compareAndSet(false, true)) return
        val started = Thread({ loop() }, "dsh-job-census-$generation").apply {
            isDaemon = true
        }
        thread = started
        started.start()
        log("dsh: job census started generation=$generation host=$hostPid")
    }

    fun stop() {
        running.set(false)
        val active = thread ?: return
        thread = null
        active.interrupt()
        if (active !== Thread.currentThread()) {
            runCatching { active.join(STOP_JOIN_MILLIS) }
        }
        log("dsh: job census stopped generation=$generation")
    }

    private fun loop() {
        while (running.get() && source.isAlive(hostPid)) {
            try {
                pollOnce()
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (t: Throwable) {
                // One failed scan must not end the census; the next poll retries.
                if (running.get()) log("dsh: job census poll failed (${t.javaClass.simpleName})")
            }
            if (!running.get()) return
            try {
                Thread.sleep(pollMillis)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        running.set(false)
    }

    /**
     * One scan. Records only token-carrying descendants, then marks a previously observed job
     * `exited` when its PID is provably gone (a live PID that merely left the descendant tree was
     * reparented, not ended, so it stays `observed` for the next generation's recovery).
     */
    fun pollOnce() {
        val now = clock()
        val seen = HashSet<String>()
        for (pid in source.descendantsOf(hostPid)) {
            if (pid <= 0 || pid == hostPid) continue
            val token = source.environmentValue(pid, DshJobIdentity.GENERATION_ENV) ?: continue
            if (token != generationToken) continue
            val startTime = source.startTime(pid) ?: continue
            val cmdline = source.cmdline(pid) ?: continue
            val taskId = DshJobIdentity.taskId(generation, pid, startTime)
            ledger.observe(
                taskId = taskId,
                generation = generation,
                hostPid = hostPid,
                pid = pid,
                startTime = startTime,
                cmdlineHash = DshJobIdentity.cmdlineHash(cmdline),
                command = DshJobIdentity.readableCommand(cmdline),
                nowMs = now
            )
            seen.add(taskId)
        }
        for (record in ledger.records()) {
            if (record.generation != generation || record.status != DshJobStatus.OBSERVED) continue
            if (record.taskId in seen || source.isAlive(record.pid)) continue
            ledger.mark(record.taskId, DshJobStatus.EXITED, now)
        }
    }

    companion object {
        const val DEFAULT_POLL_MILLIS = 1_000L
        const val STOP_JOIN_MILLIS = 2_000L
    }
}
