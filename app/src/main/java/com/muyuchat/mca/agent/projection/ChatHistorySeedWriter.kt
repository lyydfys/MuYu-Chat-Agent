package com.muyuchat.mca.agent.projection

import com.muyuchat.mca.agent.data.AgentWorkspacePaths
import java.io.File

/**
 * Writes a [ChatHistorySeed] into a task's read-only `inputs/` snapshot area.
 *
 * ## Why this is a plain file write and not `AgentWorkspaceFilePort.writeArtifact`
 *
 * `writeArtifact` is the *agent's* write path: it needs an invocation id, a policy version and an ASK
 * approval, because it represents the model deciding to change a file. This seed is not that — it is
 * the host handing the agent the conversation that preceded it, at task-creation time, before any run
 * exists. Routing it through the approval path would either stall the task on a prompt for something the
 * user never asked for, or require a synthetic invocation the ledger has no row for.
 *
 * `inputs/` is defined as the read-only snapshot area for exactly this: material the agent may read but
 * never modify. Writing here grants nothing; the agent still resolves its read grant from the task, and
 * writes to artifacts continue to need their own approval.
 *
 * ## Failure policy
 *
 * Best effort by design: a task whose history could not be written is still a usable task, and the goal
 * hint is only appended when the write succeeded. Silently attaching a hint to a file that does not
 * exist would make the agent read a missing path on its first step and waste a round.
 */
internal object ChatHistorySeedWriter {

    /**
     * Writes [seed] under the task's `inputs/` and returns the logical path the agent should read, or
     * null when there was nothing to write or the write failed.
     *
     * The returned value is a workspace-relative logical path (`inputs/<name>`), which is what the goal
     * hint quotes and what the agent's `read` tool accepts.
     */
    fun write(agentRoot: File, workspaceId: String, taskId: String, seed: ChatHistorySeed): String? {
        if (seed.isEmpty) return null
        return runCatching {
            val taskRoot = AgentWorkspacePaths.taskRoot(
                agentRoot = agentRoot,
                workspaceId = workspaceId,
                taskId = taskId,
                create = true
            )
            val inputsDir = File(taskRoot, AgentWorkspacePaths.INPUTS)
            if (!inputsDir.isDirectory && !inputsDir.mkdirs()) return null
            val target = File(inputsDir, seed.inputFileName)
            // The seed is UTF-8 text by construction; write bytes so the byte budget the builder
            // enforced is the byte count on disk.
            target.writeBytes(seed.seedText.toByteArray(Charsets.UTF_8))
            "${AgentWorkspacePaths.INPUTS}/${seed.inputFileName}"
        }.getOrNull()
    }
}
