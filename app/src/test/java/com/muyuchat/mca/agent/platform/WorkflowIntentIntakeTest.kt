package com.muyuchat.mca.agent.platform

import com.muyuchat.mca.agent.workflow.WorkflowEntryReceipt
import com.muyuchat.mca.agent.workflow.WorkflowEntryStatus
import com.muyuchat.mca.agent.workflow.WorkflowIntentDispatchResult
import com.muyuchat.mca.agent.workflow.WorkflowIntentEnvelope
import com.muyuchat.mca.agent.workflow.WorkflowIntentRegistration
import com.muyuchat.mca.agent.workflow.WorkflowIntentRequest
import com.muyuchat.mca.agent.workflow.WorkflowIntentTriggerErrorCodes
import com.muyuchat.mca.agent.workflow.WorkflowIntentTriggerRegistry
import com.muyuchat.mca.agent.workflow.WorkflowTriggerKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security boundary of the external workflow trigger entry.
 *
 * These cases drive the production decision path ([WorkflowIntentIntake.handle], which
 * [WorkflowIntentReceiver.onReceive] calls) without an `Intent` or `android.jar`: every refusal must
 * leave the dispatcher untouched, and no input may escape as an exception.
 *
 * The negative cases carry the weight. A receiver that silently accepted an unregistered action, or
 * forwarded a foreign broadcast, would hand a third-party app the ability to start a workflow.
 */
class WorkflowIntentIntakeTest {

    private val action = "com.muyuchat.mca.action.RUN_WORKFLOW"

    private fun registry(enabled: Boolean = true) = WorkflowIntentTriggerRegistry(
        registrations = listOf(
            WorkflowIntentRegistration(
                action = action,
                definitionId = "wf-1",
                version = 1L,
                enabled = enabled
            )
        )
    )

    // ------------------------------------------------------------------ classify (pure)

    @Test
    fun `an action outside the MCA prefix is ignored`() {
        val result = WorkflowIntentReception.classify(
            action = "android.intent.action.BOOT_COMPLETED",
            registry = registry()
        )

        assertTrue(result is WorkflowIntentReceptionResult.Ignored)
    }

    @Test
    fun `a missing action is ignored`() {
        assertTrue(
            WorkflowIntentReception.classify(action = null, registry = registry())
                is WorkflowIntentReceptionResult.Ignored
        )
    }

    @Test
    fun `an MCA-prefixed but unregistered action is rejected`() {
        val result = WorkflowIntentReception.classify(
            action = "com.muyuchat.mca.action.SOMETHING_ELSE",
            registry = registry()
        )

        assertTrue(result is WorkflowIntentReceptionResult.Rejected)
        assertEquals(
            WorkflowIntentTriggerErrorCodes.NOT_REGISTERED,
            (result as WorkflowIntentReceptionResult.Rejected).code
        )
    }

    @Test
    fun `a registered but disabled action is rejected`() {
        val result = WorkflowIntentReception.classify(
            action = action,
            registry = registry(enabled = false)
        )

        assertTrue(result is WorkflowIntentReceptionResult.Rejected)
        assertEquals(
            WorkflowIntentTriggerErrorCodes.DISABLED,
            (result as WorkflowIntentReceptionResult.Rejected).code
        )
    }

    @Test
    fun `a registered action with a matching envelope is accepted`() {
        val result = WorkflowIntentReception.classify(
            action = action,
            registry = registry(),
            extras = mapOf("definitionId" to "wf-1", "version" to "1"),
            senderPackage = "com.example.caller"
        )

        assertTrue(result is WorkflowIntentReceptionResult.Accepted)
        result as WorkflowIntentReceptionResult.Accepted
        assertEquals("wf-1", result.request.definitionId)
        assertEquals(1L, result.request.version)
        assertEquals("com.example.caller", result.envelope.senderPackage)
    }

    @Test
    fun `an unregistered extra is rejected by the strict parser`() {
        val result = WorkflowIntentReception.classify(
            action = action,
            registry = registry(),
            extras = mapOf("shell" to "rm -rf /")
        )

        assertTrue(result is WorkflowIntentReceptionResult.Rejected)
        assertEquals(
            WorkflowIntentTriggerErrorCodes.EXTRA_NOT_ALLOWED,
            (result as WorkflowIntentReceptionResult.Rejected).code
        )
    }

    @Test
    fun `an envelope naming another definition is rejected`() {
        val result = WorkflowIntentReception.classify(
            action = action,
            registry = registry(),
            extras = mapOf("definitionId" to "wf-other")
        )

        assertTrue(result is WorkflowIntentReceptionResult.Rejected)
        assertEquals(
            WorkflowIntentTriggerErrorCodes.MISSING_DEFINITION,
            (result as WorkflowIntentReceptionResult.Rejected).code
        )
    }

    @Test
    fun `a non mca-workflow data uri is rejected`() {
        val result = WorkflowIntentReception.classify(
            action = action,
            registry = registry(),
            dataUri = "file:///data/data/com.muyuchat.mca/secrets"
        )

        assertTrue(result is WorkflowIntentReceptionResult.Rejected)
        assertEquals(
            WorkflowIntentTriggerErrorCodes.MALFORMED_URI,
            (result as WorkflowIntentReceptionResult.Rejected).code
        )
    }

    @Test
    fun `too many extras are refused before parsing`() {
        val result = WorkflowIntentReception.classify(
            action = action,
            registry = registry(),
            extras = (1..33).associate { "key$it" to "value" }
        )

        assertTrue(result is WorkflowIntentReceptionResult.Rejected)
        assertEquals(
            WorkflowIntentTriggerErrorCodes.EXTRA_NOT_ALLOWED,
            (result as WorkflowIntentReceptionResult.Rejected).code
        )
    }

    // ------------------------------------------------------------------ intake (production path)

    @Test
    fun `an accepted broadcast dispatches exactly one envelope`() {
        val dispatched = mutableListOf<WorkflowIntentEnvelope>()
        val logged = mutableListOf<String>()
        val intake = WorkflowIntentIntake(
            dependencies(dispatched::add, logged) { envelope ->
                dispatched += envelope
                WorkflowIntentDispatchResult.Started(
                    request = WorkflowIntentRequest(
                        commandId = "cmd-1",
                        definitionId = "wf-1",
                        version = 1L,
                        action = action
                    ),
                    receipt = receipt()
                )
            }
        )

        intake.handle(action = action, dataUri = null, extras = emptyMap(), senderPackage = null)

        assertEquals(1, dispatched.size)
        assertEquals(action, dispatched.single().action)
        assertTrue("an accepted dispatch must be logged", logged.any { it.contains("started") })
    }

    @Test
    fun `a refused broadcast dispatches nothing`() {
        val dispatched = mutableListOf<WorkflowIntentEnvelope>()
        val logged = mutableListOf<String>()
        val intake = WorkflowIntentIntake(
            dependencies(dispatched::add, logged) { envelope ->
                dispatched += envelope
                error("a refused broadcast must never reach the dispatcher")
            }
        )

        intake.handle(
            action = "com.muyuchat.mca.action.UNKNOWN",
            dataUri = null,
            extras = emptyMap(),
            senderPackage = null
        )

        assertTrue(dispatched.isEmpty())
        assertTrue("a refusal must be logged", logged.any { it.contains("refused") })
    }

    @Test
    fun `a foreign broadcast is dropped without dispatching`() {
        val dispatched = mutableListOf<WorkflowIntentEnvelope>()
        val intake = WorkflowIntentIntake(
            dependencies(dispatched::add, mutableListOf()) { envelope ->
                dispatched += envelope
                error("a foreign broadcast must never reach the dispatcher")
            }
        )

        intake.handle(
            action = "android.intent.action.BOOT_COMPLETED",
            dataUri = null,
            extras = emptyMap(),
            senderPackage = null
        )

        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun `a failing dispatcher never escapes the receiver`() {
        val intake = WorkflowIntentIntake(
            dependencies({}, mutableListOf()) { error("boom") }
        )

        // The assertion is simply that this returns: a throw here would crash the host process.
        intake.handle(action = action, dataUri = null, extras = emptyMap(), senderPackage = null)
        assertTrue(true)
    }

    private fun dependencies(
        dispatched: (WorkflowIntentEnvelope) -> Unit,
        logged: MutableList<String>,
        dispatch: suspend (WorkflowIntentEnvelope) -> WorkflowIntentDispatchResult
    ) = WorkflowIntentReceiverDependencies(
        registry = registry(),
        // Unconfined so a launched dispatch runs inline and the test needs no scheduler.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        dispatch = dispatch,
        log = { _, message, _ -> logged += message }
    )

    private fun receipt(): WorkflowEntryReceipt = WorkflowEntryReceipt(
        commandId = "cmd-1",
        definitionId = "wf-1",
        version = 1L,
        trigger = WorkflowTriggerKind.INTENT,
        runId = "run-1",
        status = WorkflowEntryStatus.COMPLETED,
        completedAtMillis = 2_000L
    )
}
