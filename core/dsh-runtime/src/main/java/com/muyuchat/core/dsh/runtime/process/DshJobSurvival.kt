package com.muyuchat.core.dsh.runtime.process

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Composition boundary for background-job survival: one durable ledger, one census and one
 * recovery policy, owned by the single runtime owner.
 *
 * It is not a second runtime. It only answers two questions the owner's Node-host memory cannot:
 * which job processes of the READY generation exist (census), and which job processes of an older
 * generation must be settled before the next one starts (recovery). Every entry point contains
 * its own failures so a broken ledger can never fail a host start or a stop.
 */
internal class DshJobSurvival(
    ledgerDir: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val source: DshJobProcessSource = DshProcJobProcessSource(),
    maxRecoveryCandidates: Int = DshJobRecovery.DEFAULT_MAX_CANDIDATES
) {
    private val ledger = DshJobLedger(ledgerDir)
    private val recovery = DshJobRecovery(
        source = source,
        ledger = ledger,
        maxCandidates = maxRecoveryCandidates,
        clock = clock,
        log = log
    )

    @Volatile
    private var census: DshJobCensus? = null

    /**
     * Runs before a new Node generation launches. Returns null when the ledger/recovery raised:
     * the start continues, because job recovery is additive and must never block the host.
     */
    suspend fun recoverOrphans(generation: Long): DshJobRecoveryResult? = withContext(ioDispatcher) {
        val result = try {
            recovery.recover(generation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            log("dsh: job recovery failed (${failure.javaClass.simpleName})")
            return@withContext null
        }
        log(
            "dsh: job recovery generation=$generation examined=${result.examined} " +
                "reaped=${result.reaped.size} skipped=${result.skipped.size} " +
                "survived=${result.survived.size} truncated=${result.truncated}"
        )
        result
    }

    /** Starts polling the READY host's descendants; a null PID is reported, never guessed. */
    fun startCensus(hostPid: Int?, generation: Long) {
        stopCensus()
        if (hostPid == null || hostPid <= 0) {
            log("dsh: job census not started (host pid unavailable)")
            return
        }
        val started = DshJobCensus(
            source = source,
            ledger = ledger,
            generation = generation,
            hostPid = hostPid,
            clock = clock,
            log = log
        )
        census = started
        started.start()
    }

    fun stopCensus() {
        val active = census ?: return
        census = null
        active.stop()
    }

    /** Bounded, honest projection; the official job records themselves are not reconstructable. */
    fun orphans(limit: Int): List<DshJobOrphan> = ledger.orphans(limit)
}
