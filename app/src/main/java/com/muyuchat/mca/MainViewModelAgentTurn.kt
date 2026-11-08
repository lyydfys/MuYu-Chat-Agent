package com.muyuchat.mca

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.CreateAgentTask
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
        policy = AgentRunPolicy(),
        budget = AgentBudget(),
        chatSessionId = sessionId
    )
    // The history travels beside the goal, not inside it: the goal is capped at 8 KiB and the runtime
    // has no context channel. The seed is written only after the task exists, because a workspace path
    // needs a task id.
    return when (
        val result = agentWorkspacePorts.runtime.createTaskFromConversation(
            command = command,
            history = history,
            seedFileName = seedFileName
        )
    ) {
        is AgentCommandResult.Accepted -> null
        is AgentCommandResult.Rejected -> result.error.safeMessage
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
