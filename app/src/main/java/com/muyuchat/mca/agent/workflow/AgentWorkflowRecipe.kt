package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentProtocolMode

/** Hard bounds for one persisted recipe, its records and the file-backed store. */
object AgentWorkflowRecipeLimits {
    const val SCHEMA_VERSION: Int = 1
    const val MAX_RECIPE_BYTES: Int = 32 * 1024
    const val MAX_STORE_BYTES: Int = 256 * 1024
    const val MAX_RECIPES: Int = 64
    const val MAX_VERSIONS_PER_RECIPE: Int = 8
    const val MAX_ID_CHARS: Int = 64
    const val MAX_TITLE_CHARS: Int = 120
    const val MAX_DESCRIPTION_BYTES: Int = 1_024
    const val MAX_GOAL_TEMPLATE_BYTES: Int = 8 * 1024
    const val MAX_INPUTS: Int = 8
    const val MAX_STEPS: Int = 12
    const val MAX_TOOLS: Int = 8
    const val MAX_PROVIDER_IDS: Int = 8
    const val MAX_INPUT_VALUE_CHARS: Int = 2_000
    const val MAX_INPUT_VALUE_BYTES: Int = 2 * 1024
    const val MAX_INPUT_LABEL_CHARS: Int = 40
    const val MAX_STEP_TITLE_CHARS: Int = 80
    const val MAX_INPUT_ID_CHARS: Int = 32
    const val MAX_STEP_ID_CHARS: Int = 32
    const val MAX_PROVIDER_ID_CHARS: Int = 120
}

enum class AgentWorkflowRecipeState { ACTIVE, ARCHIVED, REVOKED }

/** Where a template input value comes from; both kinds stay bounded and task-relative. */
enum class AgentWorkflowInputKind {
    /** The user types the value in the manual create sheet. */
    USER_TEXT,

    /**
     * A declared logical path below the task's `inputs/` root. Launch resolves the placeholder to
     * the path string only; the workflow layer never reads or writes that file itself.
     */
    WORKSPACE_INPUT
}

/**
 * V1 approval policy is frozen to the normal one-shot approval sheet. A recipe cannot declare
 * ALWAYS_ALLOW/auto-approve/session approval because no such enum value exists.
 */
enum class AgentWorkflowApprovalPolicy { STANDARD }

/** Declarative checks rendered in the preview and evaluated by the preflight. No executor exists. */
enum class AgentWorkflowCheck {
    INPUTS_RESOLVED,
    GOAL_BOUNDED,
    TOOLS_READ_ONLY,
    NETWORK_LOCAL_ONLY,
    APPROVAL_STANDARD,
    MODEL_BOUND
}

/** One declared input source of the recipe. */
data class AgentWorkflowInputSource(
    val id: String,
    val kind: AgentWorkflowInputKind,
    val label: String,
    val required: Boolean = true,
    val maxChars: Int? = null,
    val path: String? = null
) {
    init {
        require(id.matches(INPUT_ID_PATTERN)) { "workflow input id is not a stable lowercase id" }
        require(label.isNotBlank()) { "workflow input label must not be blank" }
        require(label == label.trim()) { "workflow input label must not start or end with whitespace" }
        require(label.codePointCount(0, label.length) <= AgentWorkflowRecipeLimits.MAX_INPUT_LABEL_CHARS) {
            "workflow input label is too long"
        }
        require(label.none { it == '\u0000' || it.isISOControl() }) {
            "workflow input label must be a single line without control characters"
        }
        when (kind) {
            AgentWorkflowInputKind.USER_TEXT -> {
                require(path == null) { "USER_TEXT input must not declare a workspace path" }
                require(maxChars != null && maxChars in 1..AgentWorkflowRecipeLimits.MAX_INPUT_VALUE_CHARS) {
                    "USER_TEXT input maxChars must be in 1.." +
                        AgentWorkflowRecipeLimits.MAX_INPUT_VALUE_CHARS
                }
            }

            AgentWorkflowInputKind.WORKSPACE_INPUT -> {
                require(maxChars == null) { "WORKSPACE_INPUT input must not declare maxChars" }
                require(path != null && isNormalizedInputPath(path)) {
                    "WORKSPACE_INPUT path must be a normalized 'inputs/...' logical path"
                }
            }
        }
    }

    companion object {
        private val INPUT_ID_PATTERN = Regex("^[a-z][a-z0-9_]{0,31}$")

        /** Same grammar as task-relative logical paths; the workflow never touches a device path. */
        fun isNormalizedInputPath(path: String): Boolean {
            if (path.isBlank() || path.length > 512) return false
            if (path != path.trim()) return false
            if (path.any { it < ' ' }) return false
            if (path.contains('\\') || path.startsWith('/')) return false
            if (!path.startsWith("inputs/")) return false
            val segments = path.split('/')
            if (segments.size < 2 || segments.size > 17) return false
            return segments.none { it.isEmpty() || it.isBlank() || it == "." || it == ".." }
        }
    }
}

/** One declarative step. [inputId] is only meaningful for [AgentWorkflowCheck.INPUTS_RESOLVED]. */
data class AgentWorkflowStep(
    val id: String,
    val check: AgentWorkflowCheck,
    val title: String,
    val inputId: String? = null
) {
    init {
        require(id.matches(STEP_ID_PATTERN)) { "workflow step id is not a stable lowercase id" }
        require(title.isNotBlank()) { "workflow step title must not be blank" }
        require(title == title.trim()) { "workflow step title must not start or end with whitespace" }
        require(title.codePointCount(0, title.length) <= AgentWorkflowRecipeLimits.MAX_STEP_TITLE_CHARS) {
            "workflow step title is too long"
        }
        require(title.none { it == '\u0000' || it.isISOControl() }) {
            "workflow step title must be a single line without control characters"
        }
        when (check) {
            AgentWorkflowCheck.INPUTS_RESOLVED -> require(inputId != null) {
                "INPUTS_RESOLVED step must name the input it checks"
            }

            else -> require(inputId == null) {
                "only INPUTS_RESOLVED steps may name an input"
            }
        }
    }

    companion object {
        private val STEP_ID_PATTERN = Regex("^[a-z][a-z0-9_]{0,31}$")
    }
}

/**
 * Model/network policy of the recipe. The model itself is chosen explicitly by the caller of the
 * manual launch and validated against this policy; the recipe never stores a credential or a
 * provider request body. V1 accepts only [AgentNetworkPolicy.LOCAL_ONLY].
 */
data class AgentWorkflowModelPolicy(
    val networkPolicy: AgentNetworkPolicy = AgentNetworkPolicy.LOCAL_ONLY,
    val allowedProviderIds: List<String> = emptyList()
) {
    init {
        require(allowedProviderIds.size <= AgentWorkflowRecipeLimits.MAX_PROVIDER_IDS) {
            "too many allowed provider ids"
        }
        require(allowedProviderIds == allowedProviderIds.distinct().sorted()) {
            "allowedProviderIds must be unique and sorted"
        }
        allowedProviderIds.forEach { providerId ->
            require(providerId.isNotBlank()) { "allowed provider id must not be blank" }
            require(providerId.length <= AgentWorkflowRecipeLimits.MAX_PROVIDER_ID_CHARS) {
                "allowed provider id is too long"
            }
            require(providerId.none { it == '\u0000' || it.isISOControl() }) {
                "allowed provider id must not contain control characters"
            }
        }
    }
}

/**
 * Immutable, versioned task recipe v1.
 *
 * It is declarative data only: a goal template, bounded input sources, a model/network policy, a
 * one-shot approval policy and declarative checks. There is deliberately no node, script, entry
 * point, executor, trigger or schedule field, and every place that consumes it is read-only.
 */
data class AgentWorkflowRecipe(
    val id: String,
    val version: Long,
    val title: String,
    val goalTemplate: String,
    val description: String = "",
    val inputs: List<AgentWorkflowInputSource> = emptyList(),
    val modelPolicy: AgentWorkflowModelPolicy = AgentWorkflowModelPolicy(),
    val approvalPolicy: AgentWorkflowApprovalPolicy = AgentWorkflowApprovalPolicy.STANDARD,
    val enabledToolKeys: List<String> = emptyList(),
    val steps: List<AgentWorkflowStep> = emptyList(),
    val schemaVersion: Int = AgentWorkflowRecipeLimits.SCHEMA_VERSION
) {
    init {
        require(schemaVersion == AgentWorkflowRecipeLimits.SCHEMA_VERSION) {
            "unsupported workflow recipe schema version: $schemaVersion"
        }
        require(id.matches(ID_PATTERN)) { "workflow id is not a stable lowercase identifier" }
        require(version >= 1L) { "workflow version must be positive" }
        requireValidTitle(title)
        require(description.toByteArray(Charsets.UTF_8).size <= AgentWorkflowRecipeLimits.MAX_DESCRIPTION_BYTES) {
            "workflow description is too long"
        }
        require(!description.contains('\u0000')) { "workflow description must not contain NUL" }
        require(goalTemplate.isNotBlank()) { "workflow goal template must not be blank" }
        require(
            goalTemplate.toByteArray(Charsets.UTF_8).size <= AgentWorkflowRecipeLimits.MAX_GOAL_TEMPLATE_BYTES
        ) { "workflow goal template is too long" }
        require(goalTemplate.none { it == '\u0000' || (it.isISOControl() && it != '\n' && it != '\t') }) {
            "workflow goal template must not contain prohibited control characters"
        }
        require(inputs.size <= AgentWorkflowRecipeLimits.MAX_INPUTS) { "too many workflow inputs" }
        require(inputs.map { it.id }.distinct().size == inputs.size) {
            "workflow input ids must be unique"
        }
        require(steps.size in 1..AgentWorkflowRecipeLimits.MAX_STEPS) {
            "a workflow recipe must declare 1..${AgentWorkflowRecipeLimits.MAX_STEPS} steps"
        }
        require(steps.map { it.id }.distinct().size == steps.size) {
            "workflow step ids must be unique"
        }
        require(enabledToolKeys.size <= AgentWorkflowRecipeLimits.MAX_TOOLS) {
            "too many workflow tool keys"
        }
        require(enabledToolKeys == enabledToolKeys.distinct().sorted()) {
            "workflow tool keys must be unique and sorted"
        }
        requireGoalTemplateReferencesInputs(goalTemplate, inputs)
        val inputIds = inputs.mapTo(HashSet()) { it.id }
        steps.forEach { step ->
            val inputId = step.inputId
            if (step.check == AgentWorkflowCheck.INPUTS_RESOLVED && inputId != null) {
                require(inputId in inputIds) {
                    "workflow step '${step.id}' references an undeclared input '$inputId'"
                }
            }
        }
    }

    val requiresNetwork: Boolean
        get() = modelPolicy.networkPolicy == AgentNetworkPolicy.NETWORK_ENABLED

    companion object {
        private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]{1,63}$")
        private val PLACEHOLDER_PATTERN = Regex("\\{\\{input\\.([a-z][a-z0-9_]{0,31})\\}\\}")

        fun requireValidTitle(title: String) {
            require(title.isNotBlank()) { "workflow title must not be blank" }
            require(title == title.trim()) { "workflow title must not start or end with whitespace" }
            require(title.codePointCount(0, title.length) <= AgentWorkflowRecipeLimits.MAX_TITLE_CHARS) {
                "workflow title exceeds ${AgentWorkflowRecipeLimits.MAX_TITLE_CHARS} characters"
            }
            require(title.none { it == '\u0000' || it.isISOControl() }) {
                "workflow title must be a single line without control characters"
            }
        }

        /**
         * Every `{{input.<id>}}` placeholder must name a declared input and no other double-brace
         * placeholder is accepted. The check is shared by construction and strict parsing.
         */
        fun requireGoalTemplateReferencesInputs(
            goalTemplate: String,
            inputs: List<AgentWorkflowInputSource>
        ) {
            val declared = inputs.mapTo(HashSet()) { it.id }
            PLACEHOLDER_PATTERN.findAll(goalTemplate).forEach { match ->
                require(match.groupValues[1] in declared) {
                    "workflow goal template references an undeclared input '${match.groupValues[1]}'"
                }
            }
            val withoutValidPlaceholders = PLACEHOLDER_PATTERN.replace(goalTemplate, "")
            require(!withoutValidPlaceholders.contains("{{") && !withoutValidPlaceholders.contains("}}")) {
                "workflow goal template contains an unsupported placeholder"
            }
        }

        /** Bounded view used by previews; never mutates a recipe. */
        fun boundGoalTemplatePreview(goalTemplate: String, maxChars: Int = 160): String {
            val cap = maxChars.coerceIn(1, AgentWorkflowRecipeLimits.MAX_GOAL_TEMPLATE_BYTES)
            return if (goalTemplate.length <= cap) goalTemplate else goalTemplate.take(cap)
        }
    }
}

/** Durable record: one concrete version plus its archive/revocation state. */
data class AgentWorkflowRecipeRecord(
    val recipe: AgentWorkflowRecipe,
    val state: AgentWorkflowRecipeState,
    val revision: Long,
    val updatedAtMillis: Long
) {
    init {
        require(revision >= 1L) { "workflow record revision must be positive" }
        require(updatedAtMillis >= 0L) { "workflow record timestamp must not be negative" }
    }

    val active: Boolean get() = state == AgentWorkflowRecipeState.ACTIVE
}

internal fun AgentProtocolMode.isRunnableProtocol(): Boolean = this != AgentProtocolMode.NONE
