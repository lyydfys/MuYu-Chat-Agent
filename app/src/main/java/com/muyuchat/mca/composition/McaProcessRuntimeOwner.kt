package com.muyuchat.mca.composition

import android.app.Application
import com.muyuchat.mca.agent.workflow.WorkflowScheduleService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single-flight process holder for the application-only container and the Agent execution root.
 *
 * It lives in the composition package (not the Application business surface) and holds no Activity,
 * View or ViewModel. A process has at most one live container/root pair; closing the holder clears
 * both so a later startup in the same process starts clean.
 */
internal class McaProcessRuntimeOwner(private val application: Application) {
    private val lock = Any()

    @Volatile
    private var container: McaAppContainer? = null

    @Volatile
    private var root: McaProcessRuntimeRoot? = null

    fun container(): McaAppContainer = synchronized(lock) {
        container ?: McaAppContainer(application).also { container = it }
    }

    fun root(installationScopeId: String): McaProcessRuntimeRoot = synchronized(lock) {
        root?.takeIf { !it.isClosed } ?: run {
            val shared = container()
            McaProcessRuntimeRoot(
                application = application,
                container = shared,
                installationScopeId = installationScopeId
            ).also { root = it }
        }
    }

    fun close() = synchronized(lock) {
        val current = root
        root = null
        container = null
        current?.close()
    }

    /** Serializes worker cold starts: two WorkManager wakes must not reconcile the same root twice. */
    private val schedulesMutex = Mutex()

    /**
     * Worker/background entry into the one process root. It never constructs a second assembly:
     * WorkManager may have started this process, so the root performs its normal recovery before
     * any due schedule can create or resume work. A root closed by a racing Activity finish is
     * reported instead of handing a closed service to the wake.
     */
    suspend fun workflowSchedules(): WorkflowScheduleService = schedulesMutex.withLock {
        val scopeId = container().runtimeProfileStore.installationScopeId()
        val processRoot = root(scopeId)
        val agentHost = processRoot.assembly.agentHost
        agentHost.runtime().reconcileStartup()
        check(!processRoot.isClosed) { "The process runtime root closed during worker startup." }
        agentHost.workflowScheduleService()
    }

    /**
     * Worker/background entry for one durable park wake.
     *
     * It borrows the same root, lets startup recovery/fence run first, and then asks the owner to
     * validate the exact `(runId, wakeId)` token. It never constructs a second owner or loop.
     */
    suspend fun wakeParkedRun(
        runId: String,
        wakeId: String
    ): com.muyuchat.core.agent.runtime.run.AgentParkWakeResult = schedulesMutex.withLock {
        val scopeId = container().runtimeProfileStore.installationScopeId()
        val processRoot = root(scopeId)
        val agentHost = processRoot.assembly.agentHost
        agentHost.runtime().reconcileStartup()
        check(!processRoot.isClosed) { "The process runtime root closed during the park wake." }
        agentHost.runtime().wakeParked(runId, wakeId)
    }

    /**
     * Background entry for one received external workflow trigger.
     *
     * It borrows the same root as the schedule workers, lets startup recovery/fence run first, then
     * delegates to the one workflow facade. The registration table is the platform singleton the
     * receiver already consulted, so the same table decides here as there — a broadcast cannot
     * register an action on its way in.
     */
    suspend fun dispatchWorkflowIntent(
        envelope: com.muyuchat.mca.agent.workflow.WorkflowIntentEnvelope
    ): com.muyuchat.mca.agent.workflow.WorkflowIntentDispatchResult = schedulesMutex.withLock {
        val scopeId = container().runtimeProfileStore.installationScopeId()
        val processRoot = root(scopeId)
        val agentHost = processRoot.assembly.agentHost
        agentHost.runtime().reconcileStartup()
        check(!processRoot.isClosed) { "The process runtime root closed during the intent dispatch." }
        com.muyuchat.mca.agent.workflow.WorkflowIntentTrigger(
            registry = com.muyuchat.mca.agent.platform.WorkflowIntentPlatform.registry(),
            facade = agentHost.workflowTriggerFacade()
        ).dispatch(envelope)
    }
}
