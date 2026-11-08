package com.muyuchat.mca.composition

import android.app.Application
import android.util.Log
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.runtime.run.AgentRunCatalogSnapshotRecorder
import com.muyuchat.core.agent.runtime.workflow.WorkspaceCommandWorkflowAgentTaskRunner
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.mca.CloudApiStore
import com.muyuchat.mca.McaGenerationForegroundService
import com.muyuchat.mca.McaRoomDatabase
import com.muyuchat.mca.ModelRuntimeProfileStore
import com.muyuchat.mca.agent.composition.AgentDataAssembly
import com.muyuchat.mca.agent.composition.AgentHttpToolCatalogProvider
import com.muyuchat.mca.agent.composition.AgentMemoryToolCatalogProvider
import com.muyuchat.mca.agent.composition.AgentRuntimeAssembly
import com.muyuchat.mca.agent.composition.AgentToolPermissionHook
import com.muyuchat.mca.agent.composition.RunScopedAgentToolCatalogProvider
import com.muyuchat.mca.agent.composition.RunScopedSkillCatalogProvider
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpRunGrantBinder
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpServerStore
import com.muyuchat.mca.agent.extensions.skills.AgentSkillManager
import com.muyuchat.mca.agent.extensions.skills.AgentSkillRevocationPort
import com.muyuchat.mca.agent.extensions.skills.FileAgentSkillSnapshotRepository
import com.muyuchat.mca.agent.extensions.skills.FileAgentSkillStore
import com.muyuchat.mca.agent.inference.AgentCloudModelAdapter
import com.muyuchat.mca.agent.inference.AgentModelLeaseRetention
import com.muyuchat.mca.agent.inference.AgentRoutingModelPort
import com.muyuchat.mca.agent.inference.CloudApiStoreConfigSource
import com.muyuchat.mca.agent.inference.CloudStoreModelConfigResolver
import com.muyuchat.mca.agent.inference.EngineAgentStopProofSource
import com.muyuchat.mca.agent.inference.McaAgentLocalModelAdapter
import com.muyuchat.mca.agent.inference.McaInferenceServiceEngineSurface
import com.muyuchat.mca.agent.inference.McaInferenceServiceStopSettlementSurface
import com.muyuchat.mca.agent.inference.OpenAiChatCompletionsBoundedTransport
import com.muyuchat.mca.agent.inference.OpenAiResponsesBoundedTransport
import com.muyuchat.mca.agent.inference.RuntimeProfileStorePendingProfileGuard
import com.muyuchat.mca.agent.memory.AgentMemorySelectionService
import com.muyuchat.mca.agent.memory.AgentMemoryStore
import com.muyuchat.mca.agent.memory.AgentMemoryToolArgumentNormalizer
import com.muyuchat.mca.agent.memory.data.RoomAgentMemoryPersistence
import com.muyuchat.mca.agent.platform.AgentForegroundLeaseController
import com.muyuchat.mca.agent.platform.AgentRunNotificationProtocol
import com.muyuchat.mca.agent.platform.AgentRunProcessGuard
import com.muyuchat.mca.agent.platform.AgentRunStopActionCallback
import com.muyuchat.mca.agent.platform.AgentRunStopActionRouter
import com.muyuchat.mca.agent.platform.AgentRunStopCallbackOutcome
import com.muyuchat.mca.agent.platform.AgentRunStopCallbackResult
import com.muyuchat.mca.agent.security.AgentFrozenCommandFactory
import com.muyuchat.mca.agent.security.AgentPermissionBroker
import com.muyuchat.mca.agent.toolpack.RhinoToolPackageScriptEngine
import com.muyuchat.mca.agent.toolpack.ToolPackageInstallStore
import com.muyuchat.mca.agent.toolpack.ToolPackageLifecycleService
import com.muyuchat.mca.agent.toolpack.ToolPackageScriptRuntime
import com.muyuchat.mca.agent.toolpack.WorkspaceToolPackageHostHandler
import com.muyuchat.mca.agent.toolpack.augmentedWithPackageTools
import com.muyuchat.mca.agent.toolpack.defaultToolPackageRuntime
import com.muyuchat.mca.agent.toolpack.enabledToolPackageExecutors
import com.muyuchat.mca.agent.tools.OkHttpAgentHttpTextClient
import com.muyuchat.mca.agent.tools.AgentToolNormalizerRoutes
import com.muyuchat.mca.agent.tools.CatalogWorkflowToolRegistry
import com.muyuchat.mca.agent.tools.extendedAgentToolCatalog
import com.muyuchat.mca.agent.workflow.AgentOwnedWorkflowLauncher
import com.muyuchat.mca.agent.workflow.AgentWorkflowLauncher
import com.muyuchat.mca.agent.workflow.AgentWorkspaceCommandTaskGateway
import com.muyuchat.mca.agent.workflow.CompositeWorkflowDefinitionStore
import com.muyuchat.mca.agent.workflow.FileAgentWorkflowDefinitionStore
import com.muyuchat.mca.agent.workflow.FileAgentWorkflowRecipeStore
import com.muyuchat.mca.agent.workflow.RecipeWorkflowDefinitionStore
import com.muyuchat.mca.agent.workflow.FileWorkflowEntryReceiptStore
import com.muyuchat.mca.agent.workflow.FileWorkflowRunHistoryStore
import com.muyuchat.mca.agent.workflow.FileWorkflowScheduleStore
import com.muyuchat.mca.agent.workflow.RegisteredWorkflowToolGateway
import com.muyuchat.mca.agent.workflow.WorkflowEntryService
import com.muyuchat.mca.agent.workflow.WorkflowExecutionService
import com.muyuchat.mca.agent.workflow.WorkflowScheduleService
import com.muyuchat.mca.agent.workflow.WorkflowTriggerFacade
import com.muyuchat.mca.WorkManagerWorkflowWakeScheduler
import com.muyuchat.mca.facade.ChatExecutionFacade
import com.muyuchat.mca.presentation.AgentMcpAdminService
import com.muyuchat.mca.presentation.AgentPackageService
import com.muyuchat.mca.presentation.AgentSkillAdminService
import com.muyuchat.mca.presentation.AgentWorkflowAdminService
import com.muyuchat.mca.presentation.AgentWorkflowScheduleService
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

private const val STARTUP_TAG = "AgentStartup"

/**
 * Composition boundary of the native Kotlin Agent (Agent V1).
 *
 * It owns the Agent data assembly, the one model route (local engine + cloud records), the run
 * approval/tool-catalog chain, the tool-package registry, Skills/MCP stores and the runtime itself,
 * plus the management services over those instances. MainRuntimeAssembly only creates it; DSH and
 * the native Agent stay independent runtimes and neither creates the other.
 *
 * Lifetime: [agentOwnerScope] survives page/tab changes and rotation (viewModelScope does not) and
 * is cancelled by [close] when the startup session ends. [close] is the single settlement point:
 * it refuses new work, closes the runtime, releases network resources this assembly created, then
 * cancels the scope. Order is preserved from the previous inline wiring.
 *
 * The chat facade and cloud store are passed as typed providers/singletons owned by the app
 * container; this assembly never creates a second facade, store or engine.
 */
internal class AgentHostAssembly(
    private val application: Application,
    parentScope: CoroutineScope,
    private val engine: McaInferenceService,
    private val chatExecutionFacade: () -> ChatExecutionFacade,
    private val runtimeProfileStore: ModelRuntimeProfileStore,
    private val cloudApiStore: CloudApiStore
) {
    /**
     * Dedicated owner scope for the Agent runtime. It survives page/tab changes and rotation
     * (viewModelScope does not), but is cancelled with the startup session when the ViewModel is
     * finally cleared. The Agent runtime is a process-level invariant enforced by the composition;
     * see ADR-0002 D3 for the engine-scope exception and its exit condition.
     */
    private val agentOwnerJob: Job by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SupervisorJob(parentScope.coroutineContext[Job])
    }

    private val agentOwnerScope: CoroutineScope by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CoroutineScope(parentScope.coroutineContext + agentOwnerJob)
    }

    private val agentDataAssembly: AgentDataAssembly by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AgentDataAssembly(
            database = McaRoomDatabase.get(application),
            filesDir = application.filesDir
        )
    }

    /**
     * One memory persistence/store/selection-service set per startup session, owned by
     * [AgentDataAssembly] over the shared `mca.db`. These accessors exist so the runtime wiring
     * below (and a future memory admin surface) uses the same instances instead of constructing a
     * second store or database handle.
     */
    internal val agentMemoryPersistence: RoomAgentMemoryPersistence
        get() = agentDataAssembly.memoryPersistence

    internal val agentMemoryStore: AgentMemoryStore
        get() = agentDataAssembly.memoryStore

    internal val agentMemorySelectionService: AgentMemorySelectionService
        get() = agentDataAssembly.memorySelectionService

    /**
     * Durable Skill manifests live under the same no-backup Agent root as task packages. Snapshots
     * are immutable file trees; the manager is the only owner that mutates the manifest store.
     */
    private val agentSkillManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val skillsRoot = File(
            application.filesDir,
            AgentDataAssembly.AGENT_ROOT_DIRECTORY + File.separator + "skills"
        )
        AgentSkillManager(
            store = FileAgentSkillStore(skillsRoot),
            snapshots = FileAgentSkillSnapshotRepository(File(skillsRoot, "snapshots")),
            revocations = AgentSkillRevocationPort { revocation ->
                // A disabled/uninstalled Skill invalidates every pending approval of every
                // non-terminal run: no already-approved command may execute against a newer
                // catalog snapshot. Runs themselves are not cancelled; the next step re-freezes.
                agentOwnerScope.launch(Dispatchers.IO) {
                    runCatching {
                        agentDataAssembly.journal.listNonTerminalRuns(50).forEach { snapshot ->
                            agentDataAssembly.approvalStore.invalidatePendingForRun(snapshot.run.runId)
                        }
                    }
                }
            }
        )
    }

    /** Durable MCP server configs; shared by the management page and the per-run catalog provider. */
    private val mcpServerStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        // The store appends its own agent/mcp segment; passing a pre-joined path would read and
        // write a second, invisible config file (the management page and runs would disagree).
        AgentMcpConfigMigration.migrateIfNeeded(application.filesDir)
        AgentMcpServerStore(application.filesDir)
    }

    private var agentRuntimeRef: AgentRuntimeAssembly? = null

    /**
     * Process-protection guard driven by committed run transitions (via ObservingAgentJournal). It
     * acquires only while RUNNING and releases on waiting/paused/terminal states.
     */
    private val agentRunProcessGuard by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AgentRunProcessGuard(AgentForegroundLeaseController(application))
    }

    /** Notification stop action -> exact scoped owner cancel; stale/different runs never reach it. */
    private val agentRunStopActionRouter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AgentRunStopActionRouter(
            activeRunProvider = agentRunProcessGuard::activeStopAction,
            callback = AgentRunStopActionCallback { action ->
                agentOwnerScope.launch {
                    agentRuntimeRef?.requestCancelFromNotification(action.runId, action.epoch)
                }
                AgentRunStopCallbackResult(
                    AgentRunStopCallbackOutcome.ACCEPTED,
                    "agent_stop_dispatched"
                )
            }
        )
    }

    /** N09/N10/R4: executable tool packages (real Rhino engine + workspace read-only host family). */
    private val toolPackageStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ToolPackageInstallStore(
            File(
                application.filesDir,
                AgentDataAssembly.AGENT_ROOT_DIRECTORY + File.separator + "toolpackages-v2"
            )
        )
    }

    private val toolPackageRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        // Memory/artifact writes remain denied until their real stores are wired; the catalog also
        // refuses write-capable packages, so a package can never assume a half-wired capability.
        ToolPackageScriptRuntime(
            RhinoToolPackageScriptEngine(),
            WorkspaceToolPackageHostHandler(agentDataAssembly.workspaceFilePort)
        )
    }

    private val packageExecutorSnapshot = com.muyuchat.mca.agent.toolpack.ToolPackageExecutorSnapshot()

    private val toolPackageLifecycle by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ToolPackageLifecycleService(
            store = toolPackageStore,
            runtime = toolPackageRuntime,
            onStateChanged = { refreshEnabledPackageTools() }
        )
    }

    private fun refreshEnabledPackageTools() {
        agentOwnerScope.launch(Dispatchers.IO) {
            packageExecutorSnapshot.refresh {
                enabledToolPackageExecutors(toolPackageStore, toolPackageRuntime)
            }
        }
    }

    /** One load shared by the background refresh and the first-run resolution barrier. */
    private suspend fun loadEnabledPackageExecutors(): List<AgentToolExecutor> =
        runCatching {
            enabledToolPackageExecutors(toolPackageStore, toolPackageRuntime)
        }.getOrElse { emptyList() }

    private var agentCloudHttpClientRef: OkHttpClient? = null
    private var agentToolHttpClientRef: OkHttpClient? = null

    /** Dedicated transport for the explicitly opted-in `http.get` tool; closed with the assembly. */
    private fun agentToolHttpClient(): OkHttpClient = synchronized(this) {
        agentToolHttpClientRef ?: OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
            .also { agentToolHttpClientRef = it }
    }

    private fun agentCloudHttpClient(): OkHttpClient = synchronized(this) {
        agentCloudHttpClientRef ?: OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
            .also { agentCloudHttpClientRef = it }
    }

    /**
     * True only because [runtime] routes every cloud binding through [AgentRoutingModelPort] into
     * [AgentCloudModelAdapter]. Production catalog visibility must be derived from this flag:
     * offering a cloud model while the runtime still had a local-only port would select a model
     * that can never execute through the adapter it was listed for.
     */
    internal val cloudModelRoutingWired: Boolean = true

    /**
     * The single Agent runtime for this startup session. Local bindings keep the existing
     * engine/facade path through [McaAgentLocalModelAdapter]; cloud bindings in the same runtime go
     * through [AgentCloudModelAdapter] with the exact record config, both behind
     * [AgentRoutingModelPort]. It never creates an engine, supervisor or second execution body.
     */
    /**
     * The one definition read surface used by the run executor, the launcher and the admin service.
     *
     * Graph drafts/published versions are immutable once published; legacy recipes keep their own
     * `recipe-` namespace. Both callers therefore resolve the exact definition version a run pinned
     * without a second editable source.
     */
    private val workflowDefinitionStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CompositeWorkflowDefinitionStore(
            recipes = RecipeWorkflowDefinitionStore(workflowRecipeStore),
            graphStore = FileAgentWorkflowDefinitionStore(File(workflowRoot, "definitions"))
        )
    }

    fun runtime(): AgentRuntimeAssembly = synchronized(this) {
        agentRuntimeRef?.let { return it }
        val facade = chatExecutionFacade()
        val data = agentDataAssembly
        // One retention instance is shared by the adapter (which releases) and the run-settlement
        // source (which reports whether the fence is really gone). It is never re-created per run.
        val agentLeaseRetention = AgentModelLeaseRetention()
        val stopSettlementSource = AgentLeaseRunStopSettlementSource(
            retention = agentLeaseRetention,
            scope = agentOwnerScope,
            // A stop-driven release can land after the owner's bounded settlement query; the
            // STOPPING run epoch binds the late receipt to the exact run.
            lookupStoppingRunEpoch = { runId -> agentRuntimeRef?.stoppingRunEpoch(runId) },
            reportLateSettlement = { receipt -> agentRuntimeRef?.reportStopSettlement(receipt) }
        )
        val localModelPort = McaAgentLocalModelAdapter(
            engine = McaInferenceServiceEngineSurface(engine),
            facade = facade,
            pendingProfiles = RuntimeProfileStorePendingProfileGuard(runtimeProfileStore),
            retention = agentLeaseRetention,
            stopProof = EngineAgentStopProofSource(
                McaInferenceServiceStopSettlementSurface(engine)
            ),
            scope = agentOwnerScope,
            leaseSettlements = stopSettlementSource
        )
        // Cloud bindings resolve the exact record config from `providerId == cloud:<recordId>`;
        // the legacy store config is only used when the binding carries no record id. A cloud
        // model with protocol NONE stays listed in the picker but is rejected by the adapter
        // before any credential is read or any HTTP call is created.
        val cloudModelPort = AgentCloudModelAdapter(
            configSource = CloudApiStoreConfigSource(cloudApiStore),
            transport = OpenAiResponsesBoundedTransport(agentCloudHttpClient()),
            requestConfig = CloudStoreModelConfigResolver(cloudApiStore),
            // Prompted wire for OPENAI_COMPATIBLE records: same strict terminal rules, no native
            // tool declarations. ANTHROPIC stays an explicit typed rejection.
            chatCompletionsTransport = OpenAiChatCompletionsBoundedTransport(agentCloudHttpClient())
        )
        // One model port for the runtime: the route is decided from the frozen binding only, and
        // exact stops are routed back to the adapter that owns the attempt.
        val modelPort = AgentRoutingModelPort(
            local = localModelPort,
            cloud = cloudModelPort
        )
        val broker = AgentPermissionBroker(data.approvalStore)
        // One persisted document: global ALLOW/ASK/FORBID default + per-tool exceptions. Absent or
        // corrupt values decode to "everything asks", which is the pre-policy behaviour.
        val approvalPolicyStore =
            com.muyuchat.mca.agent.security.AgentToolApprovalPolicyStore(application)
        val hook = AgentToolPermissionHook(
            // The default factory stays built-in-only for tests/other callers; production injects
            // the composite that also validates memory.search before an approval is created.
            factory = AgentFrozenCommandFactory(
                normalizer = AgentToolNormalizerRoutes.production()
            ),
            broker = broker,
            invocations = data.toolInvocationLedger,
            approvalPolicySource = approvalPolicyStore
        ).hook()
        // Base catalog (N04): built-ins + the Skills enabled when THIS run starts. The snapshot is
        // resolved per run, so enabling/disabling/updating a Skill affects the next run and never
        // mutates a run that already resolved its catalog.
        // N08: the write family only needs the existing file port; http/terminal stay unregistered
        // until a production egress gate / executable sandbox exists.
        refreshEnabledPackageTools()
        val builtIns = extendedAgentToolCatalog(
            filePort = data.workspaceFilePort,
            deviceStatusSource = com.muyuchat.mca.agent.tools.DeviceStatusSource.android(application),
            deviceNotificationSink = com.muyuchat.mca.agent.tools.DeviceNotificationSink.android(application),
            deviceUrlLauncher = com.muyuchat.mca.agent.tools.DeviceUrlLauncher.android(application),
            deviceClipboardSink = com.muyuchat.mca.agent.tools.DeviceClipboardSink.android(application),
            deviceSettingsLauncher =
                com.muyuchat.mca.agent.tools.DeviceAppSettingsLauncher.android(application),
            // `device.screen` rides the app's existing accessibility engine: the same
            // DshA11yController the DSH capability lane uses, so there is exactly one tapper and one
            // typer in the app. Registered unconditionally — with the service disabled every call
            // answers SCREEN_SERVICE_DISABLED instead of silently succeeding.
            deviceScreenPort = com.muyuchat.mca.agent.tools.AndroidAgentScreenPort(),
            // `terminal.exec` rides the app-private workspace as its cwd. The sandbox reports
            // isAvailable only after a real probe (workspace writable + a known binary actually runs),
            // so on a device/ABI where that fails the tool stays unregistered instead of advertising
            // a capability it cannot honour. The whitelist stays DEFAULT; EXTENDED is opt-in.
            terminalSandbox = com.muyuchat.mca.agent.tools.AndroidAgentTerminalSandbox(
                workspace = runCatching {
                    java.io.File(application.filesDir, "agent/terminal").also { it.mkdirs() }
                }.getOrElse { java.io.File(application.filesDir, "agent") }
            )
        )
            .augmentedWithPackageTools { packageExecutorSnapshot.current() }
        val skillCatalogProvider = RunScopedSkillCatalogProvider(
            builtInCatalog = builtIns,
            skillSnapshotSource = { availableKeys: Set<String> ->
                agentSkillManager.buildRunSnapshot(availableKeys)
            }
        )
        // Run-scoped HTTP catalog: http.get is opt-in via enabledToolKeys on a NETWORK_ENABLED,
        // non-offline policy. The installed executor is bound to the exact run id; absent opt-in
        // the catalog is returned unchanged and the tool never reaches a prompt.
        val httpCatalogProvider = AgentHttpToolCatalogProvider(
            baseCatalogFor = skillCatalogProvider::catalogFor,
            client = OkHttpAgentHttpTextClient(agentToolHttpClient())
        )
        // Run-scoped memory catalog: the per-run built-in + Skill catalog is the base, and
        // memory.search is installed only when that exact task has a live frozen selection and the
        // run policy is LOCAL_ONLY.
        val memoryCatalogProvider = AgentMemoryToolCatalogProvider(
            baseCatalogFor = httpCatalogProvider::catalogFor,
            store = data.memoryStore,
            selections = data.memorySelectionService
        )
        // MCP is the OUTERMOST per-run decorator: the Skill/Memory decorators have a no-op release(),
        // so only this position makes release() revoke the run grants and close the MCP session.
        // catalogFor delegates down to memory + Skills (their surface is kept) and only appends
        // granted MCP adapters; a server with no durable authorization never reaches the catalog.
        val mcpCredentialStore = com.muyuchat.mca.agent.extensions.mcp.KeystoreMcpCredentialStore(application)
        val mcpCatalogProvider = RunScopedAgentToolCatalogProvider(
            delegate = memoryCatalogProvider,
            serverStore = mcpServerStore,
            grantStore = data.egressGrantStore,
            grantBinder = AgentMcpRunGrantBinder(
                serverStore = mcpServerStore,
                grantStore = data.egressGrantStore
            ),
            credentialProvider = com.muyuchat.mca.agent.extensions.mcp.McpCredentialProvider { ref ->
                mcpCredentialStore.get(ref)
            }
        )
        // Cold-start barrier: a run that resolves its catalog before the async package refresh has
        // committed must wait for the first load instead of freezing an empty package surface.
        val packageReadyProvider = com.muyuchat.mca.agent.composition.PackageReadyToolCatalogProvider(
            delegate = mcpCatalogProvider,
            awaitReady = { packageExecutorSnapshot.awaitInitial(::loadEnabledPackageExecutors) }
        )
        // Outermost decorator: records the immutable catalogHash/tool surface this run executed
        // with, and drops it when the coordinator releases the run-scoped catalog.
        val catalogProvider = AgentRunCatalogSnapshotRecorder(packageReadyProvider)
        AgentRuntimeAssembly(
            data = data,
            modelPort = modelPort,
            toolCatalogProvider = catalogProvider,
            commandHook = hook,
            permissionBroker = broker,
            approvalPolicySource = approvalPolicyStore,
            scope = agentOwnerScope,
            stopSettlementSource = stopSettlementSource,
            // A cancel must reach the exact engine request before the owner cancels the job: a
            // native runner may ignore coroutine cancellation and keep its lease while STOPPING.
            stopLiveModelAttempts = { runId -> localModelPort.stopRun(runId) },
            runObserver = { run ->
                val outcome = agentRunProcessGuard.update(run)
                outcome.foregroundUnavailable?.let {
                    // Never abort the run; pause with the typed platform reason so the user can
                    // retry once foreground admission is available again.
                    agentOwnerScope.launch {
                        agentRuntimeRef?.pauseForForegroundUnavailable(run.runId)
                    }
                }
            },
            // N18/N19: a run policy that pins a workflow version executes its graph under the same
            // owner. Definitions and checkpoints are the exact files the workflow admin surface uses.
            workflowDefinitions = workflowDefinitionStore,
            workflowCheckpointRoot = File(workflowRoot, "checkpoints"),
            // S4: a park that remains waiting after cold-start recovery gets a platform wake hint.
            parkWakeScheduler = com.muyuchat.mca.WorkManagerAgentParkWakeScheduler(application)
        ).also { runtime ->
            agentRuntimeRef = runtime
            McaGenerationForegroundService.agentStopActionHandler = { intent ->
                agentRunStopActionRouter.route(AgentRunNotificationProtocol.decodeStopIntent(intent))
            }
        }
    }

    /** Executable-package lifecycle for the settings surface (N09/N10). */
    fun toolPackageLifecycle(): ToolPackageLifecycleService = toolPackageLifecycle

    /**
     * Production seam for graph workflows (N20/N22).
     *
     * The facade is the only caller entry: it de-duplicates manual/schedule receipts and delegates
     * to [WorkflowExecutionService], which runs pinned definitions through the existing catalog
     * (tool nodes) or the existing run owner (AGENT_TASK nodes). No second executor is created here.
     */
    /** One recipe source shared by the legacy admin surface and the graph facade view. */
    private val workflowRecipeStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        FileAgentWorkflowRecipeStore(File(application.filesDir, "agent/workflows"))
    }

    private val workflowRoot: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(application.filesDir, "agent/workflow")
    }

    private val workflowRunHistoryStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        FileWorkflowRunHistoryStore(workflowRoot)
    }

    fun workflowTriggerFacade(): WorkflowTriggerFacade = synchronized(this) {
        agentWorkflowFacadeRef?.let { return it }
        val root = workflowRoot
        val runtime = runtime()
        // The graph runs as one real Agent run: task/run creation, tool approvals, ledger, cancel
        // and recovery all belong to the one owner. The launcher never executes a node itself.
        val launcher = AgentOwnedWorkflowLauncher(
            gateway = AgentWorkspaceCommandTaskGateway(runtime),
            queries = runtime.queries,
            definitions = workflowDefinitionStore,
            checkpointRoot = File(root, "checkpoints")
        )
        val entry = WorkflowEntryService(
            executor = launcher::run,
            history = workflowRunHistoryStore
        )
        WorkflowTriggerFacade(
            entry = entry,
            receipts = FileWorkflowEntryReceiptStore(File(root, "receipts"))
        ).also { agentWorkflowFacadeRef = it }
    }

    /**
     * Durable schedule administration and the worker-facing runner.
     *
     * The schedule row is the only persistent trigger source; WorkManager only wakes a process.
     * A saved row therefore keeps its frozen model plan and last delivered slot across restarts.
     */
    fun workflowScheduleService(): WorkflowScheduleService = synchronized(this) {
        agentWorkflowScheduleRef?.let { return it }
        WorkflowScheduleService(
            store = FileWorkflowScheduleStore(workflowRoot),
            definitions = workflowDefinitionStore,
            facade = workflowTriggerFacade(),
            wakes = WorkManagerWorkflowWakeScheduler(application)
        ).also { agentWorkflowScheduleRef = it }
    }

    /**
     * Page-facing schedule management over the process-wide store writer.
     *
     * The adapter maps one recipe to its frozen `recipe-<id>` version and delegates every write to
     * [workflowScheduleService]; the page never owns a row, a wake or a second executor.
     */
    fun workflowScheduleAdminService(): AgentWorkflowScheduleService = synchronized(this) {
        agentWorkflowScheduleAdminRef?.let { return it }
        AgentWorkflowScheduleService(
            recipes = workflowRecipeStore,
            schedules = workflowScheduleService(),
            activeBinding = { activeLocalModelBinding() }
        ).also { agentWorkflowScheduleAdminRef = it }
    }

    /**
     * Foreground cold start: reconcile the one owner, then re-install durable schedule wakes.
     *
     * A force-stop clears WorkManager while the schedule rows remain; without this pass an
     * enabled row would stay asleep until the user re-saved it. The re-arm only installs wakes
     * for persisted rows and never delivers a slot itself.
     *
     * The two duties are independent: a record that cannot be settled must not leave every
     * persisted schedule without its wake. Both outcomes are logged because this runs inside a
     * swallowed launch at the call site, and a silent skip is indistinguishable from a healthy
     * start (seen on device as a cold start that installed no one-time wake at all).
     */
    suspend fun reconcileProcessStartup() {
        try {
            runtime().reconcileStartup()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(STARTUP_TAG, "agent startup reconciliation failed; schedules still re-arm", failure)
        }
        val rearmed = try {
            workflowScheduleService().rearm()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(STARTUP_TAG, "workflow schedule re-arm failed", failure)
            false
        }
        if (!rearmed) {
            Log.w(STARTUP_TAG, "schedules are durable but their wakes are not armed")
        }
    }

    /** Rejects new Agent work, settles in-flight records and cancels the owner scope. */
    fun close() {
        synchronized(this) {
            agentPackageServiceRef = null
            agentSkillAdminRef = null
            agentMcpAdminRef = null
            agentWorkflowAdminRef = null
            agentWorkflowFacadeRef = null
            agentWorkflowScheduleRef = null
            agentWorkflowScheduleAdminRef = null
            McaGenerationForegroundService.agentStopActionHandler = null
            agentRuntimeRef?.close()
            agentRuntimeRef = null
            agentCloudHttpClientRef?.let { client ->
                agentCloudHttpClientRef = null
                // The runtime close above already cancelled Agent work; release only the transport
                // resources this assembly created itself. No Agent cloud Call survives this point.
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
            agentToolHttpClientRef?.let { client ->
                agentToolHttpClientRef = null
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
        agentRunProcessGuard.releaseNow()
        agentOwnerJob.cancel()
    }

    private var agentPackageServiceRef: AgentPackageService? = null
    private var agentSkillAdminRef: AgentSkillAdminService? = null
    private var agentMcpAdminRef: AgentMcpAdminService? = null
    private var agentWorkflowAdminRef: AgentWorkflowAdminService? = null

    /** Graph workflow entry facade (N20/N22): manual/schedule receipts over the fixed-version service. */
    private var agentWorkflowFacadeRef: WorkflowTriggerFacade? = null

    /** The single schedule-store writer and WorkManager wake installer for this process root. */
    private var agentWorkflowScheduleRef: WorkflowScheduleService? = null

    /** Page-facing schedule adapter over [agentWorkflowScheduleRef]; never a second writer. */
    private var agentWorkflowScheduleAdminRef: AgentWorkflowScheduleService? = null

    /** K14 host adapter over the same runtime/data assembly; one instance per startup session. */
    fun packageService(): AgentPackageService = synchronized(this) {
        agentPackageServiceRef?.let { return it }
        AgentPackageService(application, runtime()).also { agentPackageServiceRef = it }
    }

    /** K12 Skills management adapter over the same manager/store used for the per-run catalog. */
    fun skillAdminService(): AgentSkillAdminService = synchronized(this) {
        agentSkillAdminRef?.let { return it }
        AgentSkillAdminService(application, agentSkillManager).also { agentSkillAdminRef = it }
    }

    /** MCP registry management adapter (config only; connections still require run-scoped egress). */
    fun mcpAdminService(): AgentMcpAdminService = synchronized(this) {
        agentMcpAdminRef?.let { return it }
        AgentMcpAdminService(application, mcpServerStore).also { agentMcpAdminRef = it }
    }

    /**
     * Manual task-recipe store + launcher over the one Agent owner.
     *
     * The recipe store is a private file (`files/agent/workflows`), so no Room migration is
     * involved. The gateway delegates only to the existing [AgentRuntimeAssembly] create/start
     * commands; it is not a second loop, executor or scheduler.
     */
    fun workflowAdminService(): AgentWorkflowAdminService = synchronized(this) {
        agentWorkflowAdminRef?.let { return it }
        val runtime = runtime()
        val store = workflowRecipeStore
        val gateway = AgentWorkspaceCommandTaskGateway(runtime)
        val launcher = AgentWorkflowLauncher(store, gateway)
        AgentWorkflowAdminService(
            store = store,
            launcher = launcher,
            graphFacade = workflowTriggerFacade(),
            historyStore = workflowRunHistoryStore,
            definitions = workflowDefinitionStore,
            activeBinding = { activeLocalModelBinding() }
        ).also { agentWorkflowAdminRef = it }
    }
    /** Binding of the model the user already loaded, or null. Never guesses a protocol. */
    private fun activeLocalModelBinding(): com.muyuchat.core.agent.contract.AgentModelBinding? {
        val profile = runCatching { engine.activeExecutionProfile() }.getOrNull()
            ?: return null
        return com.muyuchat.core.agent.contract.AgentModelBinding(
            providerId = "agent.local",
            modelId = profile.modelId,
            runtime = profile.runtimeIdentity.runtime.name,
            assetFingerprint = profile.runtimeIdentity.identityHash,
            protocolMode = com.muyuchat.core.agent.contract.AgentProtocolMode.PROMPTED_V1,
            modelDisplayName = profile.modelId
        )
    }

}
