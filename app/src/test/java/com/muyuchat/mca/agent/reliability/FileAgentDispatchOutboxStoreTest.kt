package com.muyuchat.mca.agent.reliability

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchClaimResult
import com.muyuchat.core.agent.runtime.journal.AgentDispatchEnqueueResult
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchUpdateResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * N03/N19 file outbox contract: the same store semantics as the in-memory adapter, on the real
 * atomic-file path the task requires as evidence. A second store instance over the same directory
 * models a process restart.
 */
class FileAgentDispatchOutboxStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var nowMillis: Long = 1_000L

    private fun store(retentionMillis: Long = 7L * 24L * 60L * 60L * 1_000L): FileAgentDispatchOutboxStore =
        FileAgentDispatchOutboxStore(
            rootDirectory = folder.root,
            clock = { nowMillis },
            retentionMillis = retentionMillis
        )

    private fun ledger() = TestDispatchLedger()

    private fun outboxFile(): File = File(File(folder.root, "dispatch-outbox"), "outbox.json")

    @Test
    fun anEnqueuedRowSurvivesAStoreRestartAndDuplicateEnqueueIsIdempotent() {
        val first = store()
        val entry = ledger().seedApproved()
        val recorded = runBlockingOutbox { first.enqueue(entry) }
        assertTrue(recorded is AgentDispatchEnqueueResult.Recorded)

        val restarted = store()
        assertEquals(entry.idempotencyKey, runBlockingOutbox { restarted.read(entry.idempotencyKey)?.idempotencyKey })
        val replay = runBlockingOutbox { restarted.enqueue(entry) } as AgentDispatchEnqueueResult.Recorded
        assertEquals(AgentDispatchOutboxStatus.PENDING, replay.entry.status)
        assertEquals(1, runBlockingOutbox { restarted.listActive(10) }.size)
        assertTrue("the atomic replacement must not leave a temporary file", !File(folder.root, "dispatch-outbox/outbox.json.tmp").exists())
    }

    @Test
    fun aLeaseIsExclusiveAcrossInstancesAndExpiresForOneReclaim() {
        val first = store()
        val entry = ledger().seedApproved()
        runBlockingOutbox { first.enqueue(entry) }

        val claimed = runBlockingOutbox {
            first.claim(entry.idempotencyKey, "dispatcher-1", nowMillis, 1_000L)
        } as AgentDispatchClaimResult.Granted
        assertEquals(1, claimed.entry.dispatchAttempts)
        val second = store()
        assertEquals(
            AgentDispatchClaimResult.NotAvailable,
            runBlockingOutbox { second.claim(entry.idempotencyKey, "dispatcher-2", nowMillis, 1_000L) }
        )

        nowMillis += 1_001L
        val reclaimed = runBlockingOutbox {
            second.claim(entry.idempotencyKey, "dispatcher-2", nowMillis, 1_000L)
        } as AgentDispatchClaimResult.Granted
        assertEquals(2, reclaimed.entry.dispatchAttempts)
        assertEquals("dispatcher-2", reclaimed.entry.leaseOwner)
    }

    @Test
    fun dispatchingAndTerminalSettlementArePersistedAndFinal() {
        val first = store()
        val entry = ledger().seedApproved()
        runBlockingOutbox { first.enqueue(entry) }
        val claimed = runBlockingOutbox {
            first.claim(entry.idempotencyKey, "dispatcher-1", nowMillis, 1_000L)
        } as AgentDispatchClaimResult.Granted
        val dispatching = runBlockingOutbox {
            first.markDispatching(entry.idempotencyKey, claimed.entry.revision, "dispatcher-1", nowMillis)
        } as AgentDispatchUpdateResult.Updated
        runBlockingOutbox {
            first.settle(
                idempotencyKey = entry.idempotencyKey,
                expectedRevision = dispatching.entry.revision,
                status = AgentDispatchOutboxStatus.SETTLED,
                invocationStatus = ToolInvocationStatus.SUCCEEDED,
                errorCode = null,
                nowMillis = nowMillis + 1L
            )
        }

        val restarted = store()
        val settled = runBlockingOutbox { restarted.read(entry.idempotencyKey) }!!
        assertEquals(AgentDispatchOutboxStatus.SETTLED, settled.status)
        assertEquals(ToolInvocationStatus.SUCCEEDED, settled.settledInvocationStatus)
        assertEquals(0, runBlockingOutbox { restarted.listActive(10) }.size)
        val reopen = runBlockingOutbox {
            restarted.settle(
                idempotencyKey = entry.idempotencyKey,
                expectedRevision = settled.revision,
                status = AgentDispatchOutboxStatus.FAILED,
                invocationStatus = null,
                errorCode = "late",
                nowMillis = nowMillis + 2L
            )
        }
        assertTrue(reopen is AgentDispatchUpdateResult.Rejected)
    }

    @Test
    fun aCorruptDocumentIsNeverOverwrittenAndReadsFailClosed() {
        val file = outboxFile()
        file.parentFile.mkdirs()
        file.writeText("{\"entries\":\"not-an-array\"}", Charsets.UTF_8)
        val store = store()

        val enqueued = runBlockingOutbox { store.enqueue(ledger().seedApproved()) }
        assertTrue(enqueued is AgentDispatchEnqueueResult.Rejected)
        assertEquals(
            AgentErrorCodes.STORAGE_CORRUPTION,
            (enqueued as AgentDispatchEnqueueResult.Rejected).errorCode
        )
        assertEquals(
            "the damaged document must be preserved for review",
            "{\"entries\":\"not-an-array\"}",
            file.readText(Charsets.UTF_8)
        )
        try {
            runBlockingOutbox { store.read("obx-anything") }
            fail("a corrupt document must never be read as an absent outbox")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("corrupt"))
        }
    }

    @Test
    fun oldTerminalRowsArePrunedOnWriteWhileActiveRowsRemain() {
        val store = store(retentionMillis = 5_000L)
        val ledger = ledger()
        val settled = ledger.seedApproved(invocationId = "invocation-settled")
        val active = ledger.seedApproved(
            invocationId = "invocation-active",
            stepId = "step-2",
            callOrdinal = 1
        )
        runBlockingOutbox { store.enqueue(settled) }
        runBlockingOutbox { store.enqueue(active) }
        val claimed = runBlockingOutbox {
            store.claim(settled.idempotencyKey, "dispatcher-1", nowMillis, 1_000L)
        } as AgentDispatchClaimResult.Granted
        runBlockingOutbox {
            store.settle(
                idempotencyKey = settled.idempotencyKey,
                expectedRevision = claimed.entry.revision,
                status = AgentDispatchOutboxStatus.SETTLED,
                invocationStatus = ToolInvocationStatus.SUCCEEDED,
                errorCode = null,
                nowMillis = nowMillis
            )
        }

        nowMillis += 6_000L
        runBlockingOutbox { store.claim(active.idempotencyKey, "dispatcher-1", nowMillis, 1_000L) }

        assertNull(runBlockingOutbox { store.read(settled.idempotencyKey) })
        assertEquals(
            AgentDispatchOutboxStatus.CLAIMED,
            runBlockingOutbox { store.read(active.idempotencyKey) }!!.status
        )
    }

    @Test
    fun theFileNameCannotEscapeItsPrivateDirectory() {
        try {
            FileAgentDispatchOutboxStore(folder.root, fileName = "../escape.json")
            fail("a path-like file name must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "the containment check must explain the private-root boundary: ${expected.message}",
                expected.message!!.contains("private root") || expected.message!!.contains("name")
            )
        }
    }

    /** Small helper so each assertion is one suspending call without a coroutine-test builder. */
    private fun <T> runBlockingOutbox(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }
}
