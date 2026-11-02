package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowEdge
import com.muyuchat.core.agent.contract.workflow.WorkflowNode
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.contract.workflow.WorkflowPort
import com.muyuchat.core.agent.contract.workflow.WorkflowPortType
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.workflow.WorkflowCheckpointLoad
import com.muyuchat.core.agent.runtime.workflow.WorkflowCheckpointSink
import com.muyuchat.core.agent.runtime.workflow.WorkflowGraphResult
import com.muyuchat.core.agent.runtime.workflow.WorkflowGraphRunner
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeExecution
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * N19 integration: the file checkpoint store drives a runner across two store instances (a process
 * restart stand-in). A saved node fact is never re-executed; an unsaved node is retried.
 */
class WorkflowCheckpointRunnerIntegrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val definition = WorkflowDefinition(
        definitionId = "wf-run",
        version = 1,
        displayName = "checkpoint-run",
        outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
        nodes = listOf(
            WorkflowNode("trigger", WorkflowNodeType.TRIGGER),
            WorkflowNode("gather", WorkflowNodeType.TOOL, configJson = """{"toolKey":"workspace.read_text"}"""),
            WorkflowNode("done", WorkflowNodeType.OUTPUT, configJson = """{"port":"result"}""")
        ),
        edges = listOf(WorkflowEdge("trigger", "gather"), WorkflowEdge("gather", "done"))
    )

    private fun objectOutput(value: String) =
        AgentJsonValue.Obj(listOf("result" to AgentJsonValue.Str(value)))

    private fun executor(calls: MutableList<String>): WorkflowNodeExecutor = WorkflowNodeExecutor { request ->
        calls += request.node.nodeId
        WorkflowNodeExecution.Succeeded(objectOutput("value-${request.node.nodeId}"))
    }

    @Test
    fun aRestartedRunnerResumesWithoutExecutingSavedNodesAgain() = runBlocking {
        val firstStore = FileWorkflowCheckpointStore(folder.root, "run-1")
        val firstCalls = ArrayList<String>()
        val first = WorkflowGraphRunner(executor(firstCalls)).run(
            definition,
            sink = firstStore
        ) as WorkflowGraphResult.Completed

        // Simulate a process restart: a fresh store instance reads the same file.
        val secondStore = FileWorkflowCheckpointStore(folder.root, "run-1")
        val loaded = secondStore.load(definition) as WorkflowCheckpointLoad.Loaded
        val secondCalls = ArrayList<String>()
        val second = WorkflowGraphRunner(
            WorkflowNodeExecutor { request ->
                secondCalls += request.node.nodeId
                error("a saved node must not execute after restart")
            }
        ).run(definition, checkpoint = loaded.checkpoint, sink = secondStore)

        assertEquals(listOf("gather", "done"), firstCalls)
        assertTrue("no node may re-execute after restart", secondCalls.isEmpty())
        assertEquals(first.outputs, (second as WorkflowGraphResult.Completed).outputs)
    }

    @Test
    fun aCrashBeforeTheLastCheckpointRetriesOnlyTheUnsavedNode() = runBlocking {
        val store = FileWorkflowCheckpointStore(folder.root, "run-2")
        var saves = 0
        val crashingSink = WorkflowCheckpointSink { checkpoint ->
            saves += 1
            if (saves == 3) throw IllegalStateException("crash after gather checkpoint")
            store.save(checkpoint)
        }
        val firstCalls = ArrayList<String>()
        val crashed = WorkflowGraphRunner(executor(firstCalls)).run(
            definition,
            sink = crashingSink
        )
        assertTrue(crashed is WorkflowGraphResult.Failed)
        assertEquals("WF_CHECKPOINT_FAILED", (crashed as WorkflowGraphResult.Failed).errorCode)
        assertEquals("gather and done executed before the crash", listOf("gather", "done"), firstCalls)

        val resumedStore = FileWorkflowCheckpointStore(folder.root, "run-2")
        val loaded = resumedStore.load(definition) as WorkflowCheckpointLoad.Loaded
        assertEquals("the gather fact was persisted before the crash", 2L, loaded.checkpoint.revision)

        val resumedCalls = ArrayList<String>()
        val resumed = WorkflowGraphRunner(executor(resumedCalls)).run(
            definition,
            checkpoint = loaded.checkpoint,
            sink = resumedStore
        )

        assertTrue(resumed is WorkflowGraphResult.Completed)
        assertEquals("only the unsaved output node retries", listOf("done"), resumedCalls)
    }
}
