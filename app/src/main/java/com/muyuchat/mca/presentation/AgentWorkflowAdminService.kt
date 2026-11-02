package com.muyuchat.mca.presentation

import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.feature.agent.workspace.AgentWorkflowAdminOutcome as FeatureAdminOutcome
import com.muyuchat.feature.agent.workspace.AgentWorkflowAdminPort
import com.muyuchat.feature.agent.workspace.AgentWorkflowDefinitionItem
import com.muyuchat.feature.agent.workspace.AgentWorkflowDefinitionsResult
import com.muyuchat.feature.agent.workspace.AgentWorkflowDraftResult
import com.muyuchat.feature.agent.workspace.AgentWorkflowDraftSnapshot
import com.muyuchat.feature.agent.workspace.AgentWorkflowLaunchCheckUi
import com.muyuchat.feature.agent.workspace.AgentWorkflowPublishOutcome
import com.muyuchat.feature.agent.workspace.AgentWorkflowLaunchInputUi
import com.muyuchat.feature.agent.workspace.AgentWorkflowLaunchOutcome as FeatureLaunchOutcome
import com.muyuchat.feature.agent.workspace.AgentWorkflowLaunchPort
import com.muyuchat.feature.agent.workspace.AgentWorkflowLaunchPreviewResult as FeaturePreviewResult
import com.muyuchat.feature.agent.workspace.AgentWorkflowLaunchPreviewUi
import com.muyuchat.feature.agent.workspace.AgentWorkflowLimits
import com.muyuchat.feature.agent.workspace.AgentWorkflowListResult
import com.muyuchat.feature.agent.workspace.AgentWorkflowRecipeItem
import com.muyuchat.mca.agent.workflow.AgentWorkflowApprovalPolicy
import com.muyuchat.mca.agent.workflow.AgentWorkflowCheck
import com.muyuchat.mca.agent.workflow.AgentWorkflowDefinitionStore
import com.muyuchat.mca.agent.workflow.AgentWorkflowErrorCodes
import com.muyuchat.mca.agent.workflow.AgentWorkflowException
import com.muyuchat.mca.agent.workflow.AgentWorkflowInputKind
import com.muyuchat.mca.agent.workflow.AgentWorkflowLaunchOutcome
import com.muyuchat.mca.agent.workflow.AgentWorkflowLaunchRequest
import com.muyuchat.mca.agent.workflow.AgentWorkflowLauncher
import com.muyuchat.mca.agent.workflow.AgentWorkflowModelPolicy
import com.muyuchat.mca.agent.workflow.AgentWorkflowPreflight
import com.muyuchat.mca.agent.workflow.AgentWorkflowRecipe
import com.muyuchat.mca.agent.workflow.AgentWorkflowRecipeLimits
import com.muyuchat.mca.agent.workflow.AgentWorkflowRecipeRecord
import com.muyuchat.mca.agent.workflow.AgentWorkflowRecipeState
import com.muyuchat.mca.agent.workflow.AgentWorkflowRecipeStore
import com.muyuchat.mca.agent.workflow.AgentWorkflowRecipeToDefinition
import com.muyuchat.core.agent.runtime.workflow.WorkflowDefinitionValidator
import com.muyuchat.core.agent.runtime.workflow.WorkflowValidationResult
import com.muyuchat.mca.agent.workflow.AgentWorkflowSafeMessages
import com.muyuchat.mca.agent.workflow.RecipeWorkflowDefinitionStore
import com.muyuchat.mca.agent.workflow.WorkflowAgentTaskPlan
import com.muyuchat.mca.agent.workflow.WorkflowEntryReceipt
import com.muyuchat.mca.agent.workflow.WorkflowDefinitionJson
import com.muyuchat.mca.agent.workflow.WorkflowEntryStatus
import com.muyuchat.mca.agent.workflow.WorkflowManualRunCommand
import com.muyuchat.mca.agent.workflow.WorkflowRunHistoryEntry
import com.muyuchat.mca.agent.workflow.WorkflowRunHistoryStore
import com.muyuchat.mca.agent.workflow.WorkflowRunStatus
import com.muyuchat.mca.agent.workflow.WorkflowTriggerFacade
import com.muyuchat.mca.agent.workflow.WorkflowTriggerKind
import com.muyuchat.mca.agent.workflow.AgentWorkflowStep
import com.muyuchat.mca.agent.workflow.AgentWorkflowToolAllowlist
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App-side adapter that binds the file-backed recipe store and the manual launcher to the
 * feature-local workflow ports.
 *
 * It implements both halves of the page surface: the existing manual recipe list/create port and
 * the explicit preview/manual-launch port. Every method is a bounded port call; the class holds no
 * ViewModel, worker, scheduler, tool executor or UI type.
 */
class AgentWorkflowAdminService(
    private val store: AgentWorkflowRecipeStore,
    private val launcher: AgentWorkflowLauncher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val updatedLabelProvider: (Long) -> String? = ::defaultUpdatedLabel,
    /** Graph entry facade over the same recipe-backed definitions; null keeps run typed unavailable. */
    private val graphFacade: WorkflowTriggerFacade? = null,
    private val historyStore: WorkflowRunHistoryStore? = null,
    /** Draft/publish definition repository; null keeps the definition surface typed unavailable. */
    private val definitions: AgentWorkflowDefinitionStore? = null,
    /** Binding of the model the user already loaded; null refuses the run instead of guessing. */
    private val activeBinding: (() -> AgentModelBinding?)? = null
) : AgentWorkflowAdminPort, AgentWorkflowLaunchPort {

    // ------------------------------------------------------------------ recipe list/create

    override suspend fun list(): AgentWorkflowListResult = withContext(ioDispatcher) {
        try {
            AgentWorkflowListResult.Loaded(store.list().map(::toAdminItem))
        } catch (failure: AgentWorkflowException) {
            AgentWorkflowListResult.Rejected(failure.code, failure.safeMessage)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            AgentWorkflowListResult.Rejected(
                AgentWorkflowErrorCodes.STORE_FAILURE,
                AgentWorkflowSafeMessages.forCode(AgentWorkflowErrorCodes.STORE_FAILURE)
            )
        }
    }

    override suspend fun create(
        name: String,
        goalTemplate: String
    ): FeatureAdminOutcome = withContext(ioDispatcher) {
        val normalizedName = name.trim()
        val normalizedGoal = goalTemplate.trim()
        val validation = manualRecipeValidation(normalizedName, normalizedGoal)
        if (validation != null) {
            return@withContext FeatureAdminOutcome.Rejected(
                AgentWorkflowErrorCodes.RECIPE_INVALID,
                validation
            )
        }
        try {
            val record = store.publish(
                defaultManualRecipe(normalizedName, normalizedGoal),
                clock()
            )
            FeatureAdminOutcome.Created(toAdminItem(record))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: AgentWorkflowException) {
            FeatureAdminOutcome.Rejected(failure.code, failure.safeMessage)
        } catch (_: Throwable) {
            FeatureAdminOutcome.Rejected(
                AgentWorkflowErrorCodes.STORE_FAILURE,
                AgentWorkflowSafeMessages.forCode(AgentWorkflowErrorCodes.STORE_FAILURE)
            )
        }
    }

    // ------------------------------------------------------------------ preview / manual launch

    override suspend fun preview(
        recipeId: String,
        inputValues: Map<String, String>,
        model: AgentModelBinding
    ): FeaturePreviewResult = withContext(ioDispatcher) {
        val record = try {
            store.latest(recipeId)
        } catch (failure: AgentWorkflowException) {
            return@withContext FeaturePreviewResult.Rejected(failure.code, failure.safeMessage)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return@withContext FeaturePreviewResult.Rejected(
                AgentWorkflowErrorCodes.STORE_FAILURE,
                AgentWorkflowSafeMessages.forCode(AgentWorkflowErrorCodes.STORE_FAILURE)
            )
        }
        if (record == null) {
            return@withContext FeaturePreviewResult.Rejected(
                AgentWorkflowErrorCodes.NOT_FOUND,
                AgentWorkflowSafeMessages.forCode(AgentWorkflowErrorCodes.NOT_FOUND)
            )
        }

        val preflight = AgentWorkflowPreflight.evaluate(
            recipe = record.recipe,
            inputValues = inputValues,
            modelBinding = model
        )
        // N17 conversion check: a legacy recipe must still produce a valid graph definition. The
        // preview is a real production caller of the converter + validator; an impossible graph is
        // rejected instead of silently launching under an unrepresentable definition.
        val definition = runCatching {
            AgentWorkflowRecipeToDefinition.convert(record.recipe)
        }.getOrNull() ?: return@withContext FeaturePreviewResult.Rejected(
            AgentWorkflowErrorCodes.RECIPE_INVALID,
            AgentWorkflowSafeMessages.forCode(AgentWorkflowErrorCodes.RECIPE_INVALID)
        )
        when (val validation = WorkflowDefinitionValidator().validate(definition)) {
            is WorkflowValidationResult.Valid -> Unit
            is WorkflowValidationResult.Invalid -> return@withContext FeaturePreviewResult.Rejected(
                AgentWorkflowErrorCodes.RECIPE_INVALID,
                "legacy recipe graph is invalid: " +
                    validation.issues.joinToString { it.code }.take(160)
            )
        }
        val stateBlock = when (record.state) {
            AgentWorkflowRecipeState.ACTIVE -> null
            AgentWorkflowRecipeState.ARCHIVED ->
                AgentWorkflowSafeMessages.forCode(AgentWorkflowErrorCodes.ARCHIVED)

            AgentWorkflowRecipeState.REVOKED ->
                AgentWorkflowSafeMessages.forCode(AgentWorkflowErrorCodes.REVOKED)
        }
        FeaturePreviewResult.Loaded(
            AgentWorkflowLaunchPreviewUi(
                recipeId = record.recipe.id,
                versionLabel = "v${record.recipe.version}",
                title = record.recipe.title,
                goal = preflight.resolvedGoal.take(AgentWorkflowLimits.MAX_GOAL_TEMPLATE_CHARS),
                modelLabel = model.modelDisplayName,
                inputs = record.recipe.inputs.map { source ->
                    AgentWorkflowLaunchInputUi(
                        inputId = source.id,
                        label = source.label,
                        editable = source.kind == AgentWorkflowInputKind.USER_TEXT,
                        required = source.required,
                        maxChars = source.maxChars,
                        pathHint = source.path
                    )
                },
                checks = preflight.checks.map { check ->
                    AgentWorkflowLaunchCheckUi(
                        title = check.title.take(AgentWorkflowRecipeLimits.MAX_STEP_TITLE_CHARS),
                        passed = check.passed,
                        detail = check.detail?.take(MAX_CHECK_DETAIL_CHARS)
                    )
                },
                canCreate = record.active && preflight.canLaunch,
                blockedMessage = stateBlock ?: preflight.blockedMessage
            )
        )
    }

    override suspend fun createManualTask(
        recipeId: String,
        inputValues: Map<String, String>,
        model: AgentModelBinding
    ): FeatureLaunchOutcome = withContext(ioDispatcher) {
        val outcome = try {
            launcher.launch(
                AgentWorkflowLaunchRequest(
                    recipeId = recipeId,
                    inputValues = inputValues,
                    modelBinding = model
                )
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: AgentWorkflowException) {
            return@withContext FeatureLaunchOutcome.Rejected(failure.code, failure.safeMessage)
        } catch (_: Throwable) {
            return@withContext FeatureLaunchOutcome.Rejected(
                AgentWorkflowErrorCodes.LAUNCH_FAILED,
                AgentWorkflowSafeMessages.forCode(AgentWorkflowErrorCodes.LAUNCH_FAILED)
            )
        }
        when (outcome) {
            is AgentWorkflowLaunchOutcome.Started -> FeatureLaunchOutcome.Started(
                taskId = outcome.taskId,
                runId = outcome.runId
            )

            is AgentWorkflowLaunchOutcome.TaskCreatedRunRejected ->
                FeatureLaunchOutcome.TaskCreatedRunRejected(
                    taskId = outcome.taskId,
                    code = outcome.code,
                    safeMessage = outcome.safeMessage
                )

            is AgentWorkflowLaunchOutcome.Rejected -> FeatureLaunchOutcome.Rejected(
                code = outcome.code,
                safeMessage = outcome.safeMessage
            )
        }
    }

    // ------------------------------------------------------------------ mapping

    override suspend fun runWorkflow(
        recipeId: String,
        version: Long
    ): com.muyuchat.feature.agent.workspace.workflow.WorkflowRunReceipt = withContext(ioDispatcher) {
        val facade = graphFacade
            ?: return@withContext rejectedReceipt(
                recipeId,
                version,
                "WORKFLOW_RUN_UNAVAILABLE",
                "工作流运行尚未接入。"
            )
        val record = try {
            store.latest(recipeId)
        } catch (_: Throwable) {
            null
        } ?: return@withContext rejectedReceipt(
            recipeId,
            version,
            "WORKFLOW_RECIPE_NOT_FOUND",
            "找不到要运行的工作流。"
        )
        if (!record.active || record.recipe.version != version) {
            return@withContext rejectedReceipt(
                recipeId,
                version,
                "WORKFLOW_RECIPE_VERSION_CHANGED",
                "工作流版本已变化，请刷新后重试。"
            )
        }
        val binding = activeBinding?.invoke()
            ?: return@withContext rejectedReceipt(
                recipeId,
                version,
                "WORKFLOW_MODEL_NOT_LOADED",
                "请先在聊天页加载一个本地模型。"
            )
        val receipt = try {
            facade.runManual(
                WorkflowManualRunCommand(
                    commandId = idFactory(),
                    definitionId = RecipeWorkflowDefinitionStore.definitionId(recipeId),
                    version = version,
                    agentTask = WorkflowAgentTaskPlan(
                        modelBinding = binding,
                        policy = AgentRunPolicy(),
                        displayTitle = record.recipe.title
                    )
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return@withContext rejectedReceipt(
                recipeId,
                version,
                "WORKFLOW_RUN_FAILED",
                "工作流运行失败。"
            )
        }
        receipt.toFeatureReceipt()
    }

    override suspend fun workflowRunHistory(
        limit: Int
    ): List<com.muyuchat.feature.agent.workspace.workflow.WorkflowRunHistoryEntry> =
        withContext(ioDispatcher) {
            val store = historyStore ?: return@withContext emptyList()
            try {
                store.list(limit).map(::toFeatureHistory)
            } catch (_: Throwable) {
                emptyList()
            }
        }

    // ------------------------------------------------------------------ graph definitions

    override suspend fun workflowDefinitions(): AgentWorkflowDefinitionsResult =
        withContext(ioDispatcher) {
            val repository = definitions
                ?: return@withContext AgentWorkflowDefinitionsResult.Rejected(
                    AgentWorkflowErrorCodes.ADMIN_UNAVAILABLE,
                    AgentWorkflowSafeMessages.forCode(AgentWorkflowErrorCodes.ADMIN_UNAVAILABLE)
                )
            try {
                AgentWorkflowDefinitionsResult.Loaded(
                    repository.list()
                        .filter { RecipeWorkflowDefinitionStore.recipeId(it.definitionId) == null }
                        .map { summary ->
                            AgentWorkflowDefinitionItem(
                                definitionId = summary.definitionId,
                                displayName = summary.displayName,
                                latestPublishedVersion = summary.latestPublishedVersion,
                                draftRevision = summary.draftRevision,
                                updatedLabel = updatedLabelProvider(summary.updatedAtMillis)
                            )
                        }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AgentWorkflowException) {
                AgentWorkflowDefinitionsResult.Rejected(failure.code, failure.safeMessage)
            } catch (_: Throwable) {
                AgentWorkflowDefinitionsResult.Rejected(
                    AgentWorkflowErrorCodes.STORE_FAILURE,
                    AgentWorkflowSafeMessages.forCode(AgentWorkflowErrorCodes.STORE_FAILURE)
                )
            }
        }

    override suspend fun workflowDraft(definitionId: String): AgentWorkflowDraftResult =
        withContext(ioDispatcher) {
            val repository = definitions
                ?: return@withContext AgentWorkflowDraftResult.Rejected(
                    AgentWorkflowErrorCodes.ADMIN_UNAVAILABLE,
                    AgentWorkflowSafeMessages.forCode(AgentWorkflowErrorCodes.ADMIN_UNAVAILABLE)
                )
            if (RecipeWorkflowDefinitionStore.recipeId(definitionId) != null) {
                return@withContext AgentWorkflowDraftResult.Absent
            }
            try {
                val draft = repository.draft(definitionId)
                    ?: return@withContext AgentWorkflowDraftResult.Absent
                AgentWorkflowDraftResult.Loaded(
                    AgentWorkflowDraftSnapshot(
                        definitionId = definitionId,
                        revision = draft.revision,
                        definitionJson = WorkflowDefinitionJson.encode(draft.definition).toString()
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AgentWorkflowException) {
                AgentWorkflowDraftResult.Rejected(failure.code, failure.safeMessage)
            } catch (_: Throwable) {
                AgentWorkflowDraftResult.Rejected(
                    AgentWorkflowErrorCodes.STORE_FAILURE,
                    AgentWorkflowSafeMessages.forCode(AgentWorkflowErrorCodes.STORE_FAILURE)
                )
            }
        }

    override suspend fun saveWorkflowDraft(
        definitionJson: String,
        expectedRevision: Long?
    ): AgentWorkflowDraftResult = withContext(ioDispatcher) {
        val repository = definitions
            ?: return@withContext AgentWorkflowDraftResult.Rejected(
                AgentWorkflowErrorCodes.ADMIN_UNAVAILABLE,
                AgentWorkflowSafeMessages.forCode(AgentWorkflowErrorCodes.ADMIN_UNAVAILABLE)
            )
        if (expectedRevision != null && expectedRevision < 0L) {
            return@withContext AgentWorkflowDraftResult.Rejected(
                AgentWorkflowErrorCodes.RECIPE_INVALID,
                "草稿修订号无效。"
            )
        }
        if (definitionJson.toByteArray(Charsets.UTF_8).size > MAX_DEFINITION_JSON_BYTES) {
            return@withContext AgentWorkflowDraftResult.Rejected(
                AgentWorkflowErrorCodes.LIMIT_EXCEEDED,
                AgentWorkflowSafeMessages.forCode(AgentWorkflowErrorCodes.LIMIT_EXCEEDED)
            )
        }
        val parsed = try {
            JSONObject(definitionJson)
        } catch (_: Throwable) {
            return@withContext AgentWorkflowDraftResult.Rejected(
                AgentWorkflowErrorCodes.RECIPE_INVALID,
                "工作流定义不是合法 JSON。"
            )
        }
        val definition = WorkflowDefinitionJson.decode(parsed)
            ?: return@withContext AgentWorkflowDraftResult.Rejected(
                AgentWorkflowErrorCodes.RECIPE_INVALID,
                "工作流定义字段不完整或类型不正确。"
            )
        if (definition.definitionId.isBlank() ||
            RecipeWorkflowDefinitionStore.recipeId(definition.definitionId) != null
        ) {
            return@withContext AgentWorkflowDraftResult.Rejected(
                AgentWorkflowErrorCodes.RECIPE_INVALID,
                "工作流定义 ID 不能为空，也不能占用 legacy recipe 前缀。"
            )
        }
        try {
            val saved = repository.saveDraft(definition, expectedRevision, clock())
            AgentWorkflowDraftResult.Saved(saved.definition.definitionId, saved.revision)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AgentWorkflowException) {
            AgentWorkflowDraftResult.Rejected(failure.code, failure.safeMessage)
        } catch (_: Throwable) {
            AgentWorkflowDraftResult.Rejected(
                AgentWorkflowErrorCodes.STORE_FAILURE,
                AgentWorkflowSafeMessages.forCode(AgentWorkflowErrorCodes.STORE_FAILURE)
            )
        }
    }

    override suspend fun publishWorkflowDefinition(
        definitionId: String,
        expectedDraftRevision: Long
    ): AgentWorkflowPublishOutcome = withContext(ioDispatcher) {
        val repository = definitions
            ?: return@withContext AgentWorkflowPublishOutcome.Rejected(
                AgentWorkflowErrorCodes.ADMIN_UNAVAILABLE,
                AgentWorkflowSafeMessages.forCode(AgentWorkflowErrorCodes.ADMIN_UNAVAILABLE)
            )
        if (expectedDraftRevision < 0L ||
            RecipeWorkflowDefinitionStore.recipeId(definitionId) != null
        ) {
            return@withContext AgentWorkflowPublishOutcome.Rejected(
                AgentWorkflowErrorCodes.RECIPE_INVALID,
                "只能发布 graph 定义的有效草稿修订。"
            )
        }
        try {
            val draft = repository.draft(definitionId)
                ?: return@withContext AgentWorkflowPublishOutcome.Rejected(
                    AgentWorkflowErrorCodes.NOT_FOUND,
                    AgentWorkflowSafeMessages.forCode(AgentWorkflowErrorCodes.NOT_FOUND)
                )
            if (draft.revision != expectedDraftRevision) {
                return@withContext AgentWorkflowPublishOutcome.Rejected(
                    AgentWorkflowErrorCodes.VERSION_CONFLICT,
                    AgentWorkflowSafeMessages.forCode(AgentWorkflowErrorCodes.VERSION_CONFLICT)
                )
            }
            val published = repository.publish(
                definitionId = definitionId,
                expectedDraftRevision = expectedDraftRevision,
                nowMillis = clock(),
                referencedDefinitions = resolveReferences(draft.definition, repository)
            )
            AgentWorkflowPublishOutcome.Published(
                definitionId = published.definitionId,
                version = published.version,
                definitionHash = published.definitionHash
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AgentWorkflowException) {
            AgentWorkflowPublishOutcome.Rejected(failure.code, failure.safeMessage)
        } catch (_: Throwable) {
            AgentWorkflowPublishOutcome.Rejected(
                AgentWorkflowErrorCodes.STORE_FAILURE,
                AgentWorkflowSafeMessages.forCode(AgentWorkflowErrorCodes.STORE_FAILURE)
            )
        }
    }

    /**
     * Resolves every pinned subworkflow/foreach reference transitively so publish can validate the
     * nested graph. A missing version stays absent; the validator then rejects the publish with
     * WF_SUBWORKFLOW_UNRESOLVED instead of publishing a dangling reference.
     */
    private fun resolveReferences(
        definition: WorkflowDefinition,
        repository: AgentWorkflowDefinitionStore
    ): Map<
        WorkflowDefinitionValidator.SubworkflowRef,
        WorkflowDefinition
        > {
        val resolved = LinkedHashMap<
            WorkflowDefinitionValidator.SubworkflowRef,
            WorkflowDefinition
            >()
        val pending = ArrayDeque<WorkflowDefinition>()
        pending.add(definition)
        while (pending.isNotEmpty() && resolved.size < MAX_REFERENCED_DEFINITIONS) {
            val current = pending.removeFirst()
            subworkflowRefs(current).forEach { ref ->
                if (resolved.containsKey(ref)) return@forEach
                val referenced = try {
                    repository.version(ref.definitionId, ref.version)
                } catch (_: Throwable) {
                    null
                } ?: return@forEach
                if (resolved.size >= MAX_REFERENCED_DEFINITIONS) return@forEach
                if (resolved.put(ref, referenced) == null) pending.add(referenced)
            }
        }
        return resolved
    }

    private fun subworkflowRefs(
        definition: WorkflowDefinition
    ): List<WorkflowDefinitionValidator.SubworkflowRef> =
        definition.nodes.mapNotNull { node ->
            when (node.type) {
                WorkflowNodeType.SUBWORKFLOW -> WorkflowDefinitionValidator.SubworkflowRef(
                    definitionId = node.subworkflowDefinitionId.orEmpty(),
                    version = node.subworkflowVersion ?: 0L
                )
                WorkflowNodeType.FOREACH -> {
                    val config = try {
                        JSONObject(node.configJson)
                    } catch (_: Throwable) {
                        null
                    }
                    WorkflowDefinitionValidator.SubworkflowRef(
                        definitionId = config?.optString("subworkflowDefinitionId").orEmpty(),
                        version = config?.optLong("subworkflowVersion", 0L) ?: 0L
                    )
                }
                else -> null
            }
        }.filter { it.definitionId.isNotBlank() && it.version > 0L }

    private fun WorkflowEntryReceipt.toFeatureReceipt() =
        com.muyuchat.feature.agent.workspace.workflow.WorkflowRunReceipt(
            commandId = commandId,
            definitionId = RecipeWorkflowDefinitionStore.recipeId(definitionId)
                ?.let { "recipe-$it" } ?: definitionId,
            version = version,
            trigger = com.muyuchat.feature.agent.workspace.workflow.WorkflowRunTrigger.MANUAL,
            runId = runId,
            status = when (status) {
                WorkflowEntryStatus.COMPLETED ->
                    com.muyuchat.feature.agent.workspace.workflow.WorkflowRunReceiptStatus.COMPLETED
                WorkflowEntryStatus.FAILED ->
                    com.muyuchat.feature.agent.workspace.workflow.WorkflowRunReceiptStatus.FAILED
                WorkflowEntryStatus.REJECTED ->
                    com.muyuchat.feature.agent.workspace.workflow.WorkflowRunReceiptStatus.REJECTED
                WorkflowEntryStatus.PAUSED ->
                    com.muyuchat.feature.agent.workspace.workflow.WorkflowRunReceiptStatus.PAUSED
            },
            errorCode = errorCode,
            safeMessage = safeMessage,
            completedAtMillis = completedAtMillis.coerceAtLeast(1L)
        )

    private fun toFeatureHistory(
        entry: WorkflowRunHistoryEntry
    ) = com.muyuchat.feature.agent.workspace.workflow.WorkflowRunHistoryEntry(
        runId = entry.runId,
        definitionId = RecipeWorkflowDefinitionStore.recipeId(entry.definitionId)
            ?.let { "recipe-$it" } ?: entry.definitionId,
        version = entry.version,
        trigger = if (entry.trigger == WorkflowTriggerKind.SCHEDULE) {
            com.muyuchat.feature.agent.workspace.workflow.WorkflowRunTrigger.SCHEDULE
        } else {
            com.muyuchat.feature.agent.workspace.workflow.WorkflowRunTrigger.MANUAL
        },
        slotMillis = entry.slotMillis,
        startedAtMillis = entry.startedAtMillis,
        finishedAtMillis = entry.finishedAtMillis,
        status = when (entry.status) {
            WorkflowRunStatus.STARTED ->
                com.muyuchat.feature.agent.workspace.workflow.WorkflowRunStatus.STARTED
            WorkflowRunStatus.COMPLETED ->
                com.muyuchat.feature.agent.workspace.workflow.WorkflowRunStatus.COMPLETED
            WorkflowRunStatus.FAILED ->
                com.muyuchat.feature.agent.workspace.workflow.WorkflowRunStatus.FAILED
            WorkflowRunStatus.REJECTED ->
                com.muyuchat.feature.agent.workspace.workflow.WorkflowRunStatus.REJECTED
        },
        errorCode = entry.errorCode
    )

    private fun rejectedReceipt(
        recipeId: String,
        version: Long,
        code: String,
        message: String
    ) = com.muyuchat.feature.agent.workspace.workflow.WorkflowRunReceipt(
        commandId = idFactory(),
        definitionId = "recipe-$recipeId",
        version = version.coerceAtLeast(1L),
        trigger = com.muyuchat.feature.agent.workspace.workflow.WorkflowRunTrigger.MANUAL,
        runId = null,
        status = com.muyuchat.feature.agent.workspace.workflow.WorkflowRunReceiptStatus.REJECTED,
        errorCode = code,
        safeMessage = message,
        completedAtMillis = clock().coerceAtLeast(1L)
    )

    private fun toAdminItem(record: AgentWorkflowRecipeRecord): AgentWorkflowRecipeItem =
        AgentWorkflowRecipeItem(
            recipeId = record.recipe.id,
            name = record.recipe.title,
            goalTemplate = record.recipe.goalTemplate,
            versionLabel = "v${record.recipe.version}",
            updatedLabel = updatedLabelProvider(record.updatedAtMillis)
        )

    private fun defaultManualRecipe(name: String, goal: String): AgentWorkflowRecipe =
        AgentWorkflowRecipe(
            id = "wf-" + idFactory(),
            version = 1L,
            title = name,
            goalTemplate = goal,
            description = "",
            inputs = emptyList(),
            modelPolicy = AgentWorkflowModelPolicy(
                networkPolicy = AgentNetworkPolicy.LOCAL_ONLY,
                allowedProviderIds = emptyList()
            ),
            approvalPolicy = AgentWorkflowApprovalPolicy.STANDARD,
            enabledToolKeys = AgentWorkflowToolAllowlist.READ_ONLY_TOOL_KEYS,
            steps = DEFAULT_MANUAL_STEPS
        )

    private fun manualRecipeValidation(name: String, goal: String): String? {
        if (name.isEmpty()) return "请输入配方名称。"
        if (name.codePointCount(0, name.length) > AgentWorkflowLimits.MAX_NAME_CHARS) {
            return "配方名称不能超过 ${AgentWorkflowLimits.MAX_NAME_CHARS} 个字符。"
        }
        if (name.any { it == '\u0000' || it.isISOControl() }) {
            return "配方名称只能是一行，不能包含控制字符。"
        }
        if (goal.isEmpty()) return "请输入任务目标。"
        if (goal.codePointCount(0, goal.length) > AgentWorkflowLimits.MAX_GOAL_TEMPLATE_CHARS) {
            return "任务目标不能超过 ${AgentWorkflowLimits.MAX_GOAL_TEMPLATE_CHARS} 个字符。"
        }
        if (goal.toByteArray(Charsets.UTF_8).size >
            AgentWorkflowRecipeLimits.MAX_GOAL_TEMPLATE_BYTES
        ) {
            return "任务目标超过长度限制。"
        }
        if (goal.any { it == '\u0000' || (it.isISOControl() && it != '\n' && it != '\t') }) {
            return "任务目标不能包含控制字符。"
        }
        if (goal.contains("{{") || goal.contains("}}")) {
            return "手动创建暂不支持 {{input...}} 占位符。"
        }
        return null
    }

    companion object {
        private const val MAX_CHECK_DETAIL_CHARS = 160
        internal const val MAX_DEFINITION_JSON_BYTES = 256 * 1024
        internal const val MAX_REFERENCED_DEFINITIONS = 64

        /**
         * Five declarative checks for a name+goal manual recipe. They are evaluated by the
         * preflight; none of them executes anything.
         */
        private val DEFAULT_MANUAL_STEPS: List<AgentWorkflowStep> = listOf(
            AgentWorkflowStep("goal", AgentWorkflowCheck.GOAL_BOUNDED, "任务目标检查"),
            AgentWorkflowStep("tools", AgentWorkflowCheck.TOOLS_READ_ONLY, "只读工具检查"),
            AgentWorkflowStep("network", AgentWorkflowCheck.NETWORK_LOCAL_ONLY, "本地模式检查"),
            AgentWorkflowStep("approval", AgentWorkflowCheck.APPROVAL_STANDARD, "单次审批检查"),
            AgentWorkflowStep("model", AgentWorkflowCheck.MODEL_BOUND, "模型检查")
        )
    }
}

private fun defaultUpdatedLabel(updatedAtMillis: Long): String? {
    if (updatedAtMillis <= 0L) return null
    return try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(updatedAtMillis))
    } catch (_: Throwable) {
        null
    }
}
