package com.muyuchat.mca

import com.muyuchat.core.dsh.runtime.state.DshApplyResult
import com.muyuchat.core.dsh.runtime.state.DshApplyStatus
import com.muyuchat.core.dsh.runtime.state.DshInFlightSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Why an automatic DSH binding refresh did not replace the running generation.
 *
 * The three reasons are different facts and are never merged into one "busy" flag: a turn that is
 * really in flight, a model channel that could not be observed, and a gateway that is currently
 * unavailable all leave the old generation serving but require different user copy.
 */
enum class DshBindingRefreshDeferReason {
    /** At least one model call was observed in flight; the reload would cut it. */
    IN_FLIGHT,

    /**
     * In-flight work could not be observed. The owner refuses to read that as "idle", so the
     * refresh is deferred instead of assuming the generation is free.
     */
    UNOBSERVABLE,

    /** The requested binding has no usable MCA gateway while the running host serves models. */
    GATEWAY_UNAVAILABLE
}

/**
 * Typed outcome of the automatic DSH binding refresh.
 *
 * [Idle] and [NotReady] are not failures: nothing was asked because DSH was not running. Every
 * other value mirrors a real `ensureApplied` result; nothing is ever reported as applied unless the
 * runtime owner confirmed the new binding.
 */
sealed interface DshBindingRefreshState {
    data object Idle : DshBindingRefreshState

    /** DSH is not READY, so nothing was refreshed and — deliberately — nothing was started. */
    data object NotReady : DshBindingRefreshState

    data object Refreshing : DshBindingRefreshState

    /** The running generation already carried the current model catalog. */
    data object Unchanged : DshBindingRefreshState

    data class Applied(val epoch: Long, val change: String?) : DshBindingRefreshState

    data class Deferred(
        val reason: DshBindingRefreshDeferReason,
        val detail: String?
    ) : DshBindingRefreshState

    /** The new binding failed and the previous one was restored and confirmed READY. */
    data class RolledBack(val change: String?) : DshBindingRefreshState

    data class Failed(val change: String?, val detail: String?) : DshBindingRefreshState

    /** True only for a confirmed replacement of the running generation. */
    val confirmed: Boolean get() = this is Applied
}

/**
 * The single app-side call site that reconciles a changed model catalog with a running DSH host.
 *
 * Responsibilities and hard limits:
 *  - it never starts DSH: [ready] is checked before every attempt and the owner's
 *    `refreshActiveBinding` itself returns null when the runtime is not READY;
 *  - it never forces a reload: only an explicit user action may terminate in-flight work, so a
 *    deferred (in-flight or unobservable) answer stays deferred;
 *  - bursts are coalesced: signals that arrive during the debounce window or during an attempt are
 *    collapsed into the next attempt instead of producing one reload per signal;
 *  - an unobservable deferral is retried a bounded number of times (the health probe can be busy
 *    for one try), then reported as deferred instead of spinning;
 *  - an in-flight deferral is reconciled once the running turn really ends: a bounded idle probe
 *    ([observeInFlight]) watches for the observed call count to drop to `0` and then enqueues
 *    exactly one coalesced retry for that idle transition. A `null`/unobservable probe is kept
 *    distinct from idle — it never triggers the idle retry — and the probe never starts a host;
 *  - the resulting [state] is a typed projection of the owner's own result, so a rolled-back or
 *    unobservable reload is never shown as success.
 *
 * The class is platform-free (clock/sleep/log are injected) so the debounce, guard and tri-state
 * mapping are unit-testable without Android, a device or a running DSH host.
 */
class DshBindingRefreshCoordinator(
    private val scope: CoroutineScope,
    /** True only while the DSH runtime snapshot phase is READY. */
    private val ready: () -> Boolean,
    /**
     * Applies the current binding when a host is already READY; null means "there was no READY
     * host". It must route to `DshHostAssembly.refreshActiveBinding`, i.e. the composition's
     * guarded, non-starting seam.
     */
    private val refresh: suspend (force: Boolean) -> DshApplyResult?,
    private val onState: (DshBindingRefreshState) -> Unit = {},
    /** Coalescing window for a burst of model/cloud-provider changes. */
    private val debounceMillis: Long = 700L,
    /** Pause before retrying a deferred-unobservable refresh. */
    private val retryDelayMillis: Long = 2_000L,
    /** Bounded retries for an unobservable deferral, per burst. */
    private val maxAttempts: Int = 2,
    /**
     * Tri-state observation of the work the current host generation is still running, used only
     * after a [DshApplyStatus.DEFERRED_IN_FLIGHT] answer to detect the real idle transition.
     *
     * It must route to `DshHostAssembly.observeInFlight` (the gateway's own typed observation): it
     * is only valid while READY, never starts a host, and `null` means "cannot observe". The
     * default is deliberately unobservable so a coordinator without the seam never guesses idle.
     */
    private val observeInFlight: suspend () -> DshInFlightSnapshot? = { null },
    /** Pause between two idle probes while a deferred in-flight refresh waits for its turn. */
    private val idlePollIntervalMillis: Long = 2_000L,
    /**
     * Upper bound of idle probes for one deferred in-flight refresh.
     *
     * At the default 2 s interval this watches a turn for ~10 minutes, which covers the longest
     * real local-model turn observed on device (a 4 B cold start ran 9 m 10 s, MODEL-ACCEPTANCE-R20).
     * It still bounds the watchdog for a turn that never ends; the next explicit change or DSH
     * start reconciles the catalog instead.
     */
    private val maxIdlePolls: Int = 300,
    /** Minimum gap between two attempts when a newer signal arrives during a reload. */
    private val minIntervalMillis: Long = 1_200L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {}
) {
    init {
        require(debounceMillis >= 0L) { "dsh: debounce must be non-negative" }
        require(retryDelayMillis >= 0L) { "dsh: retry delay must be non-negative" }
        require(maxAttempts >= 1) { "dsh: at least one attempt is required" }
        require(idlePollIntervalMillis > 0L) { "dsh: the idle poll interval must be positive" }
        require(maxIdlePolls >= 0) { "dsh: the idle poll bound must be non-negative" }
        require(minIntervalMillis >= 0L) { "dsh: minimum interval must be non-negative" }
    }

    private val _state = MutableStateFlow<DshBindingRefreshState>(DshBindingRefreshState.Idle)

    /** Last typed outcome; read-only for UI/diagnostics. */
    val state: StateFlow<DshBindingRefreshState> = _state.asStateFlow()

    private val lock = Any()
    private var job: Job? = null

    /**
     * Exactly one idle watchdog per deferred in-flight answer. [idleWatchGeneration] invalidates a
     * watchdog that belongs to an answer the coordinator has already superseded/closed.
     */
    private var idleJob: Job? = null
    private var idleWatchGeneration = 0

    /** A change arrived and has not been applied yet. */
    private var pending = false

    /** Number of distinct signals coalesced into the pending work (log/diagnostics only). */
    private var coalesced = 0

    /** Completion time of the previous attempt, for [minIntervalMillis]. */
    private var lastAttemptAtMillis = 0L

    /**
     * Records one model-catalog/cloud-provider change and schedules a coalesced refresh.
     *
     * It is safe to call from any thread and on every model change: signals inside the debounce
     * window or during an in-flight attempt are merged. [reason] is a short log-safe label (for
     * example `model-loaded`), never user content.
     */
    fun onModelCatalogChanged(reason: String) {
        synchronized(lock) {
            pending = true
            coalesced = (coalesced + 1).coerceAtMost(MAX_COALESCED)
            if (job?.isActive == true) {
                log("dsh: binding refresh already scheduled; coalescing '$reason'")
                return
            }
            job = scope.launch { runPending(initialDelayMillis = debounceMillis) }
        }
    }

    /** Drops a scheduled refresh; used when the app is done with DSH for this process. */
    fun cancel() {
        synchronized(lock) {
            pending = false
            coalesced = 0
            job?.cancel()
            job = null
        }
        disarmIdleReconcile()
    }

    private suspend fun runPending(initialDelayMillis: Long) {
        var waitMillis = initialDelayMillis
        var attempts = 0
        try {
            while (true) {
                if (waitMillis > 0L) sleep(waitMillis)
                val queued = synchronized(lock) {
                    if (!pending) return
                    pending = false
                    coalesced to true
                }
                val signals = queued.first
                synchronized(lock) { coalesced = 0 }
                attempts += 1
                log("dsh: refresh scheduled after $signals coalesced change(s)")
                // Do not hammer the host when two changes arrive back to back.
                val sinceLast = clock() - lastAttemptAtMillis
                if (lastAttemptAtMillis > 0L && sinceLast in 0 until minIntervalMillis) {
                    sleep(minIntervalMillis - sinceLast)
                }
                val outcome = attempt()
                publish(outcome)
                if (outcome is Attempt.Deferred && outcome.reason == DshBindingRefreshDeferReason.UNOBSERVABLE &&
                    attempts < maxAttempts
                ) {
                    log("dsh: in-flight observation unobservable; retrying the refresh")
                    waitMillis = retryDelayMillis
                    synchronized(lock) { pending = true }
                    continue
                }
                val more = synchronized(lock) { pending }
                if (!more) return
                if (attempts >= MAX_ATTEMPTS_PER_BURST) {
                    // Keep the newest pending signal out of a tight loop; the next explicit change
                    // (or the next DSH start) reconciles the catalog instead.
                    log("dsh: refresh burst limit reached; newer change left pending")
                    synchronized(lock) { pending = false }
                    return
                }
                waitMillis = debounceMillis
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            publish(
                Attempt.Failed(
                    DshBindingRefreshState.Failed(
                        change = null,
                        detail = "refresh failed: " + failure.javaClass.simpleName
                    )
                )
            )
        } finally {
            var reschedule = false
            synchronized(lock) {
                job = null
                reschedule = pending
            }
            if (reschedule) {
                synchronized(lock) {
                    if (job == null) {
                        job = scope.launch { runPending(initialDelayMillis = debounceMillis) }
                    }
                }
            }
        }
    }

    private suspend fun attempt(): Attempt {
        if (!ready()) {
            // Never start DSH from a model change: the next real start reconciles the catalog.
            return Attempt.NotReady
        }
        _state.value = DshBindingRefreshState.Refreshing
        val result = try {
            refresh(false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            log("dsh: binding refresh threw " + failure.javaClass.simpleName)
            return Attempt.Failed(
                DshBindingRefreshState.Failed(
                    change = null,
                    detail = "refresh threw " + failure.javaClass.simpleName
                )
            )
        } finally {
            lastAttemptAtMillis = clock()
        }
        if (result == null) return Attempt.NotReady
        return map(result)
    }

    private fun map(result: DshApplyResult): Attempt {
        val state = when (result.status) {
            DshApplyStatus.UNCHANGED -> DshBindingRefreshState.Unchanged
            DshApplyStatus.APPLIED -> DshBindingRefreshState.Applied(result.epoch, result.change)
            DshApplyStatus.DEFERRED_IN_FLIGHT -> DshBindingRefreshState.Deferred(
                reason = if (result.inFlightUnobservable) {
                    DshBindingRefreshDeferReason.UNOBSERVABLE
                } else {
                    DshBindingRefreshDeferReason.IN_FLIGHT
                },
                detail = result.detail
            )

            DshApplyStatus.DEFERRED_GATEWAY_UNAVAILABLE -> DshBindingRefreshState.Deferred(
                reason = DshBindingRefreshDeferReason.GATEWAY_UNAVAILABLE,
                detail = result.detail
            )

            DshApplyStatus.ROLLED_BACK -> DshBindingRefreshState.RolledBack(result.change)
            DshApplyStatus.FAILED -> DshBindingRefreshState.Failed(result.change, result.detail)
        }
        return when (state) {
            is DshBindingRefreshState.Deferred -> Attempt.Deferred(state)
            is DshBindingRefreshState.Failed -> Attempt.Failed(state)
            is DshBindingRefreshState.RolledBack -> Attempt.RolledBack(state)
            else -> Attempt.Settled(state)
        }
    }

    private fun publish(outcome: Attempt) {
        val state = when (outcome) {
            is Attempt.Settled -> outcome.state
            is Attempt.Deferred -> outcome.state
            is Attempt.RolledBack -> outcome.state
            is Attempt.Failed -> outcome.state
            Attempt.NotReady -> DshBindingRefreshState.NotReady
        }
        _state.value = state
        log("dsh: binding refresh outcome=" + describe(state))
        runCatching { onState(state) }
        // Only a real observed in-flight answer arms the idle watchdog. A settled answer retires
        // it: the catalog it was waiting for has been reconciled (or the host is gone).
        if (state is DshBindingRefreshState.Deferred &&
            state.reason == DshBindingRefreshDeferReason.IN_FLIGHT
        ) {
            armIdleReconcile()
        } else {
            disarmIdleReconcile()
        }
    }

    /**
     * Starts the single idle watchdog for one observed in-flight deferral.
     *
     * It is armed only for [DshBindingRefreshState.Deferred] with
     * [DshBindingRefreshDeferReason.IN_FLIGHT], i.e. a real `observedCalls > 0` answer. An
     * unobservable deferral is never reconciled this way: "cannot observe" is not idle.
     */
    private fun armIdleReconcile() {
        if (maxIdlePolls == 0) return
        synchronized(lock) {
            if (idleJob?.isActive == true) return
            val generation = ++idleWatchGeneration
            idleJob = scope.launch { watchForIdle(generation) }
        }
    }

    private fun disarmIdleReconcile() {
        synchronized(lock) {
            idleWatchGeneration += 1
            idleJob?.cancel()
            idleJob = null
        }
    }

    private fun isCurrentIdleWatch(generation: Int): Boolean = synchronized(lock) {
        idleWatchGeneration == generation && idleJob?.isActive == true
    }

    /** Retires [generation] without cancelling the caller (used from inside the watchdog). */
    private fun retireIdleWatch(generation: Int) {
        synchronized(lock) {
            if (idleWatchGeneration != generation) return
            idleJob = null
        }
    }

    /**
     * Bounded poll for the real end of the turn that deferred the refresh.
     *
     * State machine, per iteration:
     *  1. wait [idlePollIntervalMillis];
     *  2. stop silently when a newer outcome retired this watchdog, or when the host is no longer
     *     READY (a stop/restart reconciles the catalog on its own path and this seam never starts
     *     a host);
     *  3. `null` / `observedCalls == null` is "cannot observe", never idle: keep the refresh
     *     deferred and keep probing up to [maxIdlePolls] before giving up;
     *  4. `observedCalls > 0` means the turn is still running: keep polling and never cut it;
     *  5. `observedCalls == 0` is the only idle answer: retire the watchdog and enqueue exactly
     *     one retry for this transition through the normal debounce/coalescing/burst limits.
     */
    private suspend fun watchForIdle(generation: Int) {
        var polls = 0
        while (true) {
            sleep(idlePollIntervalMillis)
            polls += 1
            if (!isCurrentIdleWatch(generation)) return
            if (!ready()) {
                log("dsh: idle reconcile stopped; the host is no longer READY")
                retireIdleWatch(generation)
                return
            }
            val snapshot = try {
                observeInFlight()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                log("dsh: idle in-flight observation failed (${failure.javaClass.simpleName})")
                null
            }
            val observed = snapshot?.observedCalls
            if (observed == null) {
                log(
                    "dsh: in-flight work is not observable" +
                        (snapshot?.let { " (" + it.detail + ")" } ?: "") +
                        "; the refresh stays deferred and is never retried as idle"
                )
                if (polls >= maxIdlePolls) {
                    retireIdleWatch(generation)
                    log("dsh: idle reconcile stopped after $polls unobservable probe(s)")
                    return
                }
                continue
            }
            if (observed > 0) {
                if (polls >= maxIdlePolls) {
                    retireIdleWatch(generation)
                    log("dsh: turn still in flight after $polls probe(s); the refresh stays deferred")
                    return
                }
                continue
            }
            retireIdleWatch(generation)
            log("dsh: host went idle (observedCalls=0); retrying the deferred refresh once")
            enqueueIdleReconcile()
            return
        }
    }

    /** Queues the single idle retry as a normal signal, so debounce/coalescing/burst limits apply. */
    private fun enqueueIdleReconcile() {
        synchronized(lock) {
            pending = true
            coalesced = (coalesced + 1).coerceAtMost(MAX_COALESCED)
            if (job?.isActive == true) {
                log("dsh: an attempt is running; the idle retry is coalesced into it")
                return
            }
            job = scope.launch { runPending(initialDelayMillis = 0L) }
        }
    }

    /** Internal attempt result; keeps the retry decision separate from the published state. */
    private sealed interface Attempt {
        data class Settled(val state: DshBindingRefreshState) : Attempt
        data class Deferred(val state: DshBindingRefreshState.Deferred) : Attempt {
            val reason: DshBindingRefreshDeferReason get() = state.reason
        }
        data class RolledBack(val state: DshBindingRefreshState.RolledBack) : Attempt
        data class Failed(val state: DshBindingRefreshState.Failed) : Attempt
        data object NotReady : Attempt
    }

    private companion object {
        const val MAX_COALESCED = 99
        const val MAX_ATTEMPTS_PER_BURST = 3

        fun describe(state: DshBindingRefreshState): String = when (state) {
            DshBindingRefreshState.Idle -> "idle"
            DshBindingRefreshState.NotReady -> "not-ready"
            DshBindingRefreshState.Refreshing -> "refreshing"
            DshBindingRefreshState.Unchanged -> "unchanged"
            is DshBindingRefreshState.Applied -> "applied(change=${state.change ?: "n/a"})"
            is DshBindingRefreshState.Deferred -> "deferred(${state.reason.name})"
            is DshBindingRefreshState.RolledBack -> "rolled-back"
            is DshBindingRefreshState.Failed -> "failed(change=${state.change ?: "n/a"})"
        }
    }
}

/**
 * Canonical signature of everything the DSH model catalog is built from.
 *
 * The gateway derives its model list from the loaded local model plus the configured cloud CHAT
 * records, so exactly those advertised facts form the change signal. Unrelated UI state (result
 * text, tab, scroll, ...) must not trigger a reload; a signature that changed means the host
 * overlay is stale. The function is pure and unit-tested with the debounce/guard tests.
 *
 * Advertised capability facts that are part of this signature (see `DshModelCatalog` and
 * `DshOpenAiWire.modelsJson`):
 *  - local: `id`, display name and the **context window** the adapter derives from the loaded
 *    execution profile's `n_ctx`. A reload of the same model with a different `n_ctx` changes the
 *    gateway's `context_window` (and therefore whether official compaction can work) without
 *    changing the id — that was the D07/D08 gap this field closes;
 *  - cloud: `id`, display name, model name, `apiFormat` (it decides native vs prompt-compat tool
 *    calling), the `supportsTools` and `supportsVision` declarations and `updatedAt`.
 *
 * Fields the gateway would need but that do not exist in `MainUiState`/`CloudModelRecord` today are
 * reported by the caller instead of being invented here: there is no cloud `contextWindow` /
 * `maxOutputTokens` field and no per-model vision flag on `DshGatewayModel`. Until those exist, a
 * cloud row is advertised without `contextWindow` and official compaction fails closed for it —
 * that gap is surfaced by `DshContextCapacityProbe` (gateway) and the seeder warning, not hidden
 * by a fabricated window here.
 */
internal object DshModelCatalogSignature {

    /** One configured cloud chat record, reduced to the fields the gateway advertises. */
    data class CloudChat(
        val id: String,
        val displayName: String,
        val modelName: String,
        val apiFormat: String,
        val supportsVision: Boolean,
        val supportsTools: Boolean,
        val configured: Boolean,
        val updatedAt: Long
    )

    /**
     * @param localContextWindow the effective context window of the loaded local model (the
     *   gateway advertises it as `context_window`); null/`<= 0` means the adapter could not
     *   derive one, which is itself a capacity fact that must change the signature.
     */
    fun of(
        loadedModelId: String?,
        localModelName: String?,
        localContextWindow: Int?,
        cloudChatModels: List<CloudChat>
    ): String = buildString {
        append("local=").append(loadedModelId.orEmpty().trim())
        append('|').append(localModelName.orEmpty().trim())
        append("|ctx=").append(localContextWindow?.takeIf { it > 0 }?.toString().orEmpty())
        append("|cloud=")
        cloudChatModels
            .asSequence()
            .filter { it.configured && it.id.isNotBlank() }
            .sortedBy { it.id }
            .forEach { record ->
                append(record.id).append(',')
                append(record.displayName.replace(FIELD_SEPARATOR, ' ')).append(',')
                append(record.modelName.replace(FIELD_SEPARATOR, ' ')).append(',')
                append(record.apiFormat).append(',')
                append(if (record.supportsVision) 'v' else '-')
                append(if (record.supportsTools) 't' else '-')
                append(',').append(record.updatedAt).append(';')
            }
    }

    private const val FIELD_SEPARATOR = '\u0000'
}
