package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentToolResultStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Task-scoped workflow tool receipts, legacy run-key fallback and fail-closed corruption. */
class WorkflowToolReceiptStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun receipt(
        callHash: String = "wfc-1",
        summary: String = "read ok"
    ) = WorkflowToolReceipt(
        invocationId = "invocation-1",
        toolKey = "workspace.readText",
        callHash = callHash,
        status = AgentToolResultStatus.SUCCEEDED,
        summary = summary,
        dataJson = """{"text":"hello"}""",
        artifactIds = emptyList(),
        errorCode = null,
        possibleSideEffects = false
    )

    @Test
    fun aTaskScopedReceiptRoundTripsAcrossStoreInstances() {
        val store = WorkflowToolReceiptStore(folder.root)
        assertTrue(store.saveTask("task-1", "wftask-abc", "read-node", receipt()))

        val loaded = WorkflowToolReceiptStore(folder.root)
            .loadTask("task-1", "wftask-abc", "read-node") as WorkflowToolReceiptLoad.Loaded
        assertEquals(receipt(), loaded.receipt)
    }

    @Test
    fun distinctFramesForTheSameNodeDoNotShareAReceipt() {
        val store = WorkflowToolReceiptStore(folder.root)
        store.saveTask("task-1", "frame-a", "read-node", receipt(callHash = "wfc-a"))

        assertEquals(
            WorkflowToolReceiptLoad.Absent,
            store.loadTask("task-1", "frame-b", "read-node")
        )
    }

    @Test
    fun aLegacyRunScopedReceiptIsStillReadWhenTheTaskKeyIsAbsent() {
        val legacy = WorkflowToolReceiptStore(folder.root)
        assertTrue(legacy.save("run-1", "wfnode-abc", receipt()))

        val loaded = WorkflowToolReceiptStore(folder.root).loadNode(
            taskId = "task-1",
            frameKey = "wftask-abc",
            nodeId = "read-node",
            legacyRunId = "run-1",
            legacyStepId = "wfnode-abc"
        ) as WorkflowToolReceiptLoad.Loaded

        assertEquals(receipt(), loaded.receipt)
    }

    @Test
    fun aCorruptTaskReceiptNeverFallsBackToTheLegacyRunFile() {
        val store = WorkflowToolReceiptStore(folder.root)
        store.save("run-1", "wfnode-abc", receipt())
        store.saveTask("task-1", "wftask-abc", "read-node", receipt())
        val taskFile = folder.root.walkTopDown()
            .first { it.isFile && it.parentFile.name.startsWith("task-") }
        taskFile.writeText("{broken")

        val loaded = store.loadNode(
            taskId = "task-1",
            frameKey = "wftask-abc",
            nodeId = "read-node",
            legacyRunId = "run-1",
            legacyStepId = "wfnode-abc"
        )

        assertTrue(loaded is WorkflowToolReceiptLoad.Corrupt)
    }

    @Test
    fun aLegacySchemaVersionFailsClosed() {
        val store = WorkflowToolReceiptStore(folder.root)
        store.saveTask("task-1", "wftask-abc", "read-node", receipt())
        val taskFile = folder.root.walkTopDown()
            .first { it.isFile && it.parentFile.name.startsWith("task-") }
        taskFile.writeText(
            """{"schemaVersion":1,"invocationId":"invocation-1","toolKey":"workspace.readText",""" +
                """"status":"SUCCEEDED","summary":"read ok","dataJson":null,""" +
                """"artifactIds":[],"errorCode":null,"possibleSideEffects":false}"""
        )

        val loaded = store.loadTask("task-1", "wftask-abc", "read-node")

        assertTrue(loaded is WorkflowToolReceiptLoad.Corrupt)
    }

    @Test
    fun anUnsafeLegacyKeyIsRefusedWithoutThrowing() {
        val store = WorkflowToolReceiptStore(folder.root)
        assertEquals(WorkflowToolReceiptLoad.Absent, store.load("../escape", "wfnode-abc"))
        assertEquals(
            false,
            store.save("run-1", "../escape", receipt())
        )
        assertEquals(WorkflowToolReceiptLoad.Absent, store.loadTask(" ", "frame", "node"))
        assertTrue(!File(folder.root, "escape.json").exists())
    }
}
