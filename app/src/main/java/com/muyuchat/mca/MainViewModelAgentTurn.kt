package com.muyuchat.mca

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.StartAgentRun
import com.muyuchat.core.engine.ChatMessage

/**
 * Hands one chat turn to the agent instead of the chat model.
 *
 * Lives outside [MainViewModel] because that class is at its 1,000-line ceiling (Rule 4) and this is a
 * self-contained decision, not chat state-machine logic. A null result means the turn was accepted by
 * the agent; a non-null one is the message to show the user.
 *
 * ## Why the command carries the conversation id
 *
 * The runtime has no notion of a conversation; `CreateAgentTask.chatSessionId` is the only way it
 * learns one. The app persists that as a task -> conversation binding, and the binding is what lets
 * the finished run's summary land back in this conversation. An unbound run's summary is silently
 * dropped, which is the failure this change exists to remove, so the id is passed explicitly rather
 * than recovered later from ambient state.
 *
 * ## Why the binding is resolved here
 *
 * [binding] comes from [com.muyuchat.mca.agent.inference.ChatAgentModelBinding], which maps the
 * conversation's selected model (local engine or cloud record) onto the agent contract. Resolving it in
 * the caller keeps this function a pure "create the task" step and makes the model decision testable on
 * its own.
 */
internal suspend fun MainViewModel.submitAgentTurn(
    input: String,
    sessionId: String?,
    displayTitle: String,
    binding: AgentModelBinding?,
    history: List<ChatMessage> = emptyList(),
    seedFileName: String = "chat-history.txt"
): String? {
    val goal = input.trim()
    if (goal.isEmpty()) return null
    if (binding == null) return "请先选择一个可用于 Agent 的模型。" + NO_AGENT_MODEL_HINT
    val command = CreateAgentTask(
        commandId = "chat-agent-" + stableAgentTurnId(goal, sessionId.orEmpty()),
        goal = goal,
        displayTitle = displayTitle.take(MAX_AGENT_TITLE_CHARS),
        modelBinding = binding,
        // The policy default is LOCAL_ONLY/offlineForced, which is right for the standalone Agent page
        // and wrong here: this turn uses the conversation's own model, and a cloud model cannot run
        // offline. The invariant in AgentRunPolicy requires the two to move together.
        policy = AgentRunPolicy(
            networkPolicy = if (binding.isCloud) {
                AgentNetworkPolicy.NETWORK_ENABLED
            } else {
                AgentNetworkPolicy.LOCAL_ONLY
            },
            offlineForced = !binding.isCloud
        ),
        budget = AgentBudget(),
        chatSessionId = sessionId
    )
    // The history travels beside the goal, not inside it: the goal is capped at 8 KiB and the runtime
    // has no context channel. The seed is written only after the task exists, because a workspace path
    // needs a task id.
    val created = agentWorkspacePorts.runtime.createTaskFromConversation(
        command = command,
        history = history,
        seedFileName = seedFileName
    )
    if (created is AgentCommandResult.Rejected) return created.error.safeMessage
    created as AgentCommandResult.Accepted

    // `CreateAgentTask` deliberately does not start a run (its contract says so), so a created task
    // sits idle until this second command. Without it the user's turn is accepted into the database
    // and nothing ever happens — the exact failure seen on device.
    val receipt = created.creationReceipt
        ?: return "Agent 任务已创建，但拿不到任务锚点，无法启动执行。"
    return when (
        val started = agentWorkspacePorts.runtime.startRun(
            StartAgentRun(
                commandId = "chat-agent-start-" + stableAgentTurnId(goal, sessionId.orEmpty()),
                taskId = receipt.taskId,
                expectedTaskRevision = receipt.appliedRevision
            )
        )
    ) {
        is AgentCommandResult.Accepted -> null
        is AgentCommandResult.Rejected -> started.error.safeMessage
    }
}

/**
 * Deterministic command id for one (input, session) pair.
 *
 * The command id is the runtime's idempotency key, so retrying the same send must not create a second
 * task. Hashing rather than counting means a process restart re-derives the same id from the same input,
 * which is what makes a retried send safe after a crash.
 */
internal fun stableAgentTurnId(goal: String, sessionId: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest("$sessionId\u0000$goal".toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }.take(24)
}

internal const val MAX_AGENT_TITLE_CHARS = 60

/** Narrows a model-selection failure to the one action that fixes it. */
internal const val NO_AGENT_MODEL_HINT = "已加载的本地模型或已配置的云端对话模型都可以使用。"

/**
 * True when this binding targets a cloud provider.
 *
 * The agent contract has no is-cloud bit; the provider id is the only signal, and it is the same
 * `cloud:` prefix the model picker writes. A local engine id never starts with that prefix, so the
 * test is exact rather than heuristic.
 */
internal val AgentModelBinding.isCloud: Boolean
    get() = providerId.startsWith("cloud:")
