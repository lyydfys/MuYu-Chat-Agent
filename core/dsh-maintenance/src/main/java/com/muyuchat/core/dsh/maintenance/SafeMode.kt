package com.muyuchat.core.dsh.maintenance

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Safe mode + start history (D22b).
 *
 * Safe mode never deletes sessions, credentials, the workspace or the home marker. It stashes the
 * user-loadable layers (user patches, local plugin overlays, user profile overlay) into a
 * transaction-owned directory, keeps the managed profile authoritative, and restores the stash on
 * exit. Both directions are resumable: the state file records every item as moved/not moved, so a
 * crash mid-way is completed on the next start instead of leaving a half-stashed home.
 */

enum class DshStartOutcome { READY, FAILED, CRASHED }

data class DshStartEvent(
    val atMillis: Long,
    val outcome: DshStartOutcome,
    val stage: String = "",
    /** Bounded crash signature (stable error family), never a log body. */
    val signature: String = ""
)

data class DshStartHistory(
    val consecutiveFailures: Int = 0,
    val safeModeAttempts: Int = 0,
    val autoRepairAttempts: Int = 0,
    val events: List<DshStartEvent> = emptyList(),
    val updatedAtMillis: Long = 0L
)

/** Durable, bounded start history (`maintenance/start-history.json`). */
class DshStartHistoryStore(private val file: File) {

    fun load(): DshStartHistory {
        if (!file.isFile) return DshStartHistory()
        return runCatching {
            val json = JSONObject(DshMaintenanceIo.readText(file))
            val eventsJson = json.optJSONArray("events") ?: JSONArray()
            val events = ArrayList<DshStartEvent>(eventsJson.length())
            for (index in 0 until eventsJson.length()) {
                val event = eventsJson.optJSONObject(index) ?: continue
                val outcome = DshStartOutcome.entries.firstOrNull { it.name == event.optString("outcome") }
                    ?: continue
                events.add(
                    DshStartEvent(
                        atMillis = event.optLong("at"),
                        outcome = outcome,
                        stage = event.optString("stage"),
                        signature = event.optString("signature")
                    )
                )
            }
            DshStartHistory(
                consecutiveFailures = json.optInt("consecutiveFailures"),
                safeModeAttempts = json.optInt("safeModeAttempts"),
                autoRepairAttempts = json.optInt("autoRepairAttempts"),
                events = events,
                updatedAtMillis = json.optLong("updatedAt")
            )
        }.getOrDefault(DshStartHistory())
    }

    fun save(history: DshStartHistory) {
        val json = JSONObject()
        json.put("consecutiveFailures", history.consecutiveFailures)
        json.put("safeModeAttempts", history.safeModeAttempts)
        json.put("autoRepairAttempts", history.autoRepairAttempts)
        json.put("updatedAt", history.updatedAtMillis)
        val events = JSONArray()
        for (event in history.events.takeLast(MAX_EVENTS)) {
            events.put(
                JSONObject().apply {
                    put("at", event.atMillis)
                    put("outcome", event.outcome.name)
                    put("stage", event.stage.take(60))
                    put("signature", event.signature.take(80))
                }
            )
        }
        json.put("events", events)
        DshMaintenanceIo.writeDurably(file, json.toString())
    }

    fun record(outcome: DshStartOutcome, atMillis: Long, stage: String = "", signature: String = ""): DshStartHistory {
        val current = load()
        val next = current.copy(
            consecutiveFailures = if (outcome == DshStartOutcome.READY) 0 else current.consecutiveFailures + 1,
            safeModeAttempts = if (outcome == DshStartOutcome.READY) 0 else current.safeModeAttempts,
            autoRepairAttempts = if (outcome == DshStartOutcome.READY) 0 else current.autoRepairAttempts,
            events = (current.events + DshStartEvent(atMillis, outcome, stage, signature)).takeLast(MAX_EVENTS),
            updatedAtMillis = atMillis
        )
        save(next)
        return next
    }

    fun recordSafeModeAttempt(atMillis: Long): DshStartHistory {
        val current = load()
        val next = current.copy(safeModeAttempts = current.safeModeAttempts + 1, updatedAtMillis = atMillis)
        save(next)
        return next
    }

    fun recordAutoRepair(atMillis: Long): DshStartHistory {
        val current = load()
        val next = current.copy(autoRepairAttempts = current.autoRepairAttempts + 1, updatedAtMillis = atMillis)
        save(next)
        return next
    }

    private companion object {
        const val MAX_EVENTS = 30
    }
}

enum class DshSafeModeDecision { NORMAL, SAFE_MODE, RECOVERY_REQUIRED }

/**
 * Decision matrix. A single failed start is normal; repeated consecutive failures, a burst of
 * crashes inside the window without a successful boot, or an explicit user request enter safe
 * mode. A safe mode that keeps failing stops at RECOVERY_REQUIRED instead of looping forever.
 */
class DshSafeModePolicy(
    val maxConsecutiveFailures: Int = 3,
    val crashWindowMillis: Long = 15L * 60L * 1000L,
    val crashBurstThreshold: Int = 2,
    val maxSafeModeAttempts: Int = 2,
    val maxAutoRepairsPerSession: Int = 2
) {
    fun decide(history: DshStartHistory, nowMillis: Long, requestedSafeMode: Boolean): DshSafeModeDecision {
        if (requestedSafeMode) return safeModeDecision(history)
        val recent = history.events.filter { it.atMillis >= nowMillis - crashWindowMillis }
        val readySinceWindow = recent.any { it.outcome == DshStartOutcome.READY }
        val recentFailures = recent.count { it.outcome != DshStartOutcome.READY }
        if (history.consecutiveFailures >= maxConsecutiveFailures) return safeModeDecision(history)
        if (!readySinceWindow && recentFailures >= crashBurstThreshold) return safeModeDecision(history)
        return DshSafeModeDecision.NORMAL
    }

    fun canAutoRepair(history: DshStartHistory): Boolean =
        history.autoRepairAttempts < maxAutoRepairsPerSession

    private fun safeModeDecision(history: DshStartHistory): DshSafeModeDecision =
        if (history.safeModeAttempts >= maxSafeModeAttempts) {
            DshSafeModeDecision.RECOVERY_REQUIRED
        } else {
            DshSafeModeDecision.SAFE_MODE
        }
}

enum class DshSafeModePhase { INACTIVE, ENTERING, ACTIVE, EXITING }

data class DshSafeModeItem(val relativePath: String, val moved: Boolean)

data class DshSafeModeState(
    val phase: DshSafeModePhase = DshSafeModePhase.INACTIVE,
    val enteredAtMillis: Long = 0L,
    val stashDir: String? = null,
    val items: List<DshSafeModeItem> = emptyList(),
    val attempts: Int = 0
)

/** Durable safe-mode state (`maintenance/safe-mode.json`). */
class DshSafeModeStateStore(private val file: File) {

    fun load(): DshSafeModeState {
        if (!file.isFile) return DshSafeModeState()
        return runCatching {
            val json = JSONObject(DshMaintenanceIo.readText(file))
            val phase = DshSafeModePhase.entries.firstOrNull { it.name == json.optString("phase") }
                ?: return DshSafeModeState()
            val itemsJson = json.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<DshSafeModeItem>(itemsJson.length())
            for (index in 0 until itemsJson.length()) {
                val item = itemsJson.optJSONObject(index) ?: continue
                items.add(DshSafeModeItem(item.optString("relativePath"), item.optBoolean("moved")))
            }
            DshSafeModeState(
                phase = phase,
                enteredAtMillis = json.optLong("enteredAt"),
                stashDir = json.optString("stashDir").takeIf { it.isNotEmpty() },
                items = items,
                attempts = json.optInt("attempts")
            )
        }.getOrDefault(DshSafeModeState())
    }

    fun save(state: DshSafeModeState) {
        val json = JSONObject()
        json.put("phase", state.phase.name)
        json.put("enteredAt", state.enteredAtMillis)
        json.put("stashDir", state.stashDir ?: "")
        json.put("attempts", state.attempts)
        val items = JSONArray()
        for (item in state.items) {
            items.put(JSONObject().apply { put("relativePath", item.relativePath); put("moved", item.moved) })
        }
        json.put("items", items)
        DshMaintenanceIo.writeDurably(file, json.toString())
    }
}

sealed interface DshSafeModeResult {
    data class Active(val state: DshSafeModeState) : DshSafeModeResult
    data class Inactive(val state: DshSafeModeState) : DshSafeModeResult
    data class Rejected(val failure: DshMaintenanceFailure) : DshSafeModeResult
}

/**
 * Safe-mode lifecycle owner. [resume] must run at startup (after the maintenance journal sweep,
 * before the managed host) so a crashed enter/exit is finished deterministically.
 */
class DshSafeModeController(
    private val engine: DshMaintenanceEngine,
    private val homeDir: File,
    private val historyStore: DshStartHistoryStore,
    private val stateStore: DshSafeModeStateStore,
    private val policy: DshSafeModePolicy = DshSafeModePolicy(),
    private val stashCandidates: List<String> = DEFAULT_STASH_CANDIDATES,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun currentState(): DshSafeModeState = stateStore.load()

    fun decide(nowMillis: Long = clock(), requestedSafeMode: Boolean = false): DshSafeModeDecision {
        val state = stateStore.load()
        if (state.phase == DshSafeModePhase.ACTIVE ||
            state.phase == DshSafeModePhase.ENTERING ||
            state.phase == DshSafeModePhase.EXITING
        ) {
            return if (state.attempts >= policy.maxSafeModeAttempts &&
                state.phase == DshSafeModePhase.ENTERING
            ) {
                DshSafeModeDecision.RECOVERY_REQUIRED
            } else {
                DshSafeModeDecision.SAFE_MODE
            }
        }
        return policy.decide(historyStore.load(), nowMillis, requestedSafeMode)
    }

    /** Returns the bounded repair budget state for the automatic self-check. */
    fun canAutoRepair(): Boolean = policy.canAutoRepair(historyStore.load())

    fun consumedAutoRepair(atMillis: Long = clock()) {
        historyStore.recordAutoRepair(atMillis)
    }

    fun recordBootResult(
        outcome: DshStartOutcome,
        stage: String = "",
        signature: String = ""
    ): DshStartHistory = historyStore.record(outcome, clock(), stage, signature)

    /**
     * Stashes every existing user-loadable layer. Sessions, credentials, attachments and the
     * workspace are not in the candidate list and are therefore untouched by construction.
     */
    fun enter(
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshSafeModeResult {
        val existing = stashCandidates
            .map { File(homeDir, it) }
            .filter { it.exists() }
        val stashDir = File(engine.dataRootDirectory, "maintenance/safe-mode/${clock()}")
        if (!stashDir.isDirectory && !stashDir.mkdirs() && !stashDir.isDirectory) {
            return DshSafeModeResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.IO_FAILURE, "cannot create the safe-mode stash")
            )
        }
        var state = DshSafeModeState(
            phase = DshSafeModePhase.ENTERING,
            enteredAtMillis = clock(),
            stashDir = stashDir.absolutePath,
            items = existing.map { DshSafeModeItem(relative(it), false) },
            attempts = stateStore.load().attempts + 1
        )
        stateStore.save(state)
        val history = historyStore.recordSafeModeAttempt(clock())
        val outcome = engine.execute(
            DshMaintenanceRequest(
                kind = DshMaintenanceKind.SAFE_MODE_ENTER,
                requester = requester,
                quiescence = quiescence,
                targetRoot = homeDir,
                steps = emptyList(),
                healthCheckRequired = false,
                detail = "stash=${existing.size};attempt=${history.safeModeAttempts}",
                body = {
                    try {
                        state = completeEnter(state, stashDir)
                        null
                    } catch (io: IOException) {
                        DshMaintenanceFailure(
                            DshMaintenanceError.IO_FAILURE,
                            "stashing a user layer failed; the next start resumes safe mode",
                            detail = io.message?.take(200)
                        )
                    }
                }
            )
        )
        return when (outcome) {
            is DshMaintenanceOutcome.Committed -> DshSafeModeResult.Active(stateStore.load())
            is DshMaintenanceOutcome.Rejected -> DshSafeModeResult.Rejected(outcome.failure)
            is DshMaintenanceOutcome.RolledBack -> DshSafeModeResult.Rejected(outcome.failure)
            is DshMaintenanceOutcome.RecoveryRequired -> DshSafeModeResult.Rejected(outcome.failure)
        }
    }

    /** Restores the stashed layers and returns to the normal profile. */
    fun exit(
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshSafeModeResult {
        val initial = stateStore.load()
        if (initial.phase == DshSafeModePhase.INACTIVE) return DshSafeModeResult.Inactive(initial)
        val stashDir = initial.stashDir?.let(::File)
            ?: return DshSafeModeResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.RECOVERY_REQUIRED, "safe-mode stash path is missing")
            )
        var state = initial.copy(phase = DshSafeModePhase.EXITING)
        stateStore.save(state)
        val outcome = engine.execute(
            DshMaintenanceRequest(
                kind = DshMaintenanceKind.SAFE_MODE_EXIT,
                requester = requester,
                quiescence = quiescence,
                targetRoot = homeDir,
                steps = emptyList(),
                healthCheckRequired = false,
                detail = "stash=${state.items.size}",
                body = {
                    try {
                        state = completeExit(state, stashDir)
                        null
                    } catch (io: IOException) {
                        DshMaintenanceFailure(
                            DshMaintenanceError.IO_FAILURE,
                            "restoring a stashed layer failed; the next start resumes the exit",
                            detail = io.message?.take(200)
                        )
                    }
                }
            )
        )
        return when (outcome) {
            is DshMaintenanceOutcome.Committed -> DshSafeModeResult.Inactive(stateStore.load())
            is DshMaintenanceOutcome.Rejected -> DshSafeModeResult.Rejected(outcome.failure)
            is DshMaintenanceOutcome.RolledBack -> DshSafeModeResult.Rejected(outcome.failure)
            is DshMaintenanceOutcome.RecoveryRequired -> DshSafeModeResult.Rejected(outcome.failure)
        }
    }

    /** Startup completion of a crashed enter/exit. Idempotent when the state is stable. */
    fun resume(
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshSafeModeResult {
        val state = stateStore.load()
        val stashDir = state.stashDir?.let(::File)
        return when (state.phase) {
            DshSafeModePhase.INACTIVE -> DshSafeModeResult.Inactive(state)
            DshSafeModePhase.ACTIVE -> DshSafeModeResult.Active(state)
            DshSafeModePhase.ENTERING -> {
                if (stashDir == null) {
                    return DshSafeModeResult.Rejected(
                        DshMaintenanceFailure(
                            DshMaintenanceError.RECOVERY_REQUIRED,
                            "safe mode was entering without a stash path"
                        )
                    )
                }
                var resumed = state
                val outcome = engine.execute(
                    DshMaintenanceRequest(
                        kind = DshMaintenanceKind.SAFE_MODE_ENTER,
                        requester = requester,
                        quiescence = quiescence,
                        targetRoot = homeDir,
                        steps = emptyList(),
                        healthCheckRequired = false,
                        detail = "resume-enter",
                        body = {
                            try {
                                resumed = completeEnter(resumed, stashDir)
                                null
                            } catch (io: IOException) {
                                DshMaintenanceFailure(
                                    DshMaintenanceError.IO_FAILURE,
                                    "could not finish entering safe mode",
                                    detail = io.message?.take(200)
                                )
                            }
                        }
                    )
                )
                when (outcome) {
                    is DshMaintenanceOutcome.Committed -> DshSafeModeResult.Active(stateStore.load())
                    else -> DshSafeModeResult.Rejected(failureOf(outcome))
                }
            }

            DshSafeModePhase.EXITING -> {
                if (stashDir == null) {
                    return DshSafeModeResult.Rejected(
                        DshMaintenanceFailure(
                            DshMaintenanceError.RECOVERY_REQUIRED,
                            "safe mode was exiting without a stash path"
                        )
                    )
                }
                var resumed = state
                val outcome = engine.execute(
                    DshMaintenanceRequest(
                        kind = DshMaintenanceKind.SAFE_MODE_EXIT,
                        requester = requester,
                        quiescence = quiescence,
                        targetRoot = homeDir,
                        steps = emptyList(),
                        healthCheckRequired = false,
                        detail = "resume-exit",
                        body = {
                            try {
                                resumed = completeExit(resumed, stashDir)
                                null
                            } catch (io: IOException) {
                                DshMaintenanceFailure(
                                    DshMaintenanceError.IO_FAILURE,
                                    "could not finish leaving safe mode",
                                    detail = io.message?.take(200)
                                )
                            }
                        }
                    )
                )
                when (outcome) {
                    is DshMaintenanceOutcome.Committed -> DshSafeModeResult.Inactive(stateStore.load())
                    else -> DshSafeModeResult.Rejected(failureOf(outcome))
                }
            }
        }
    }

    private fun failureOf(outcome: DshMaintenanceOutcome): DshMaintenanceFailure = when (outcome) {
        is DshMaintenanceOutcome.Rejected -> outcome.failure
        is DshMaintenanceOutcome.RolledBack -> outcome.failure
        is DshMaintenanceOutcome.RecoveryRequired -> outcome.failure
        is DshMaintenanceOutcome.Committed -> DshMaintenanceFailure(
            DshMaintenanceError.INTERNAL,
            "unexpected committed outcome"
        )
    }

    private fun completeEnter(state: DshSafeModeState, stashDir: File): DshSafeModeState {
        var current = state
        for ((index, item) in current.items.withIndex()) {
            val target = File(homeDir, item.relativePath)
            val stash = File(stashDir, index.toString())
            if (!target.exists()) {
                if (!item.moved) {
                    current = current.copy(
                        items = current.items.mapIndexed { i, value ->
                            if (i == index) value.copy(moved = true) else value
                        }
                    )
                    stateStore.save(current)
                }
                continue
            }
            if (!stash.exists()) {
                target.parentFile?.let { parent ->
                    if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                        throw IOException("cannot create the safe-mode stash parent")
                    }
                }
                DshMaintenanceIo.moveDurably(target, stash)
            }
            current = current.copy(
                items = current.items.mapIndexed { i, value ->
                    if (i == index) value.copy(moved = true) else value
                }
            )
            stateStore.save(current)
        }
        val active = current.copy(phase = DshSafeModePhase.ACTIVE)
        stateStore.save(active)
        return active
    }

    private fun completeExit(state: DshSafeModeState, stashDir: File): DshSafeModeState {
        var current = state
        for ((index, item) in current.items.withIndex()) {
            val stash = File(stashDir, index.toString())
            if (!stash.exists()) continue
            val target = File(homeDir, item.relativePath)
            if (target.exists()) {
                val replaced = File(stashDir, "replaced-$index")
                if (replaced.exists()) DshMaintenanceIo.deleteOwnedTree(replaced)
                DshMaintenanceIo.moveDurably(target, replaced)
            }
            target.parentFile?.let { parent ->
                if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                    throw IOException("cannot create the restored layer parent")
                }
            }
            DshMaintenanceIo.moveDurably(stash, target)
        }
        val inactive = current.copy(phase = DshSafeModePhase.INACTIVE, items = emptyList())
        stateStore.save(inactive)
        return inactive
    }

    private fun relative(file: File): String =
        DshMaintenanceIo.relativeWithin(homeDir, file)
            ?: throw IllegalArgumentException("safe-mode candidate escapes the home root")

    private companion object {
        /**
         * User-loadable layers only. `sessions`, `sessions-deleted`, `attachments`, the workspace
         * and `.credentials.yaml` are deliberately absent: safe mode must preserve them.
         */
        val DEFAULT_STASH_CANDIDATES = listOf(
            "user-patches",
            "patches",
            "plugins-user",
            "profile/user"
        )
    }
}
