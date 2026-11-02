package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshGatewayModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D06/D07/D09: provider routing, desired/applied revision ACK and honest per-backend capabilities.
 *
 * These tests pin the rules the console and the composition rely on: a busy host requires an
 * explicit user stop (never a timer guess), a stale/old ACK can never become the applied revision,
 * a timed-out command is queried instead of replayed, and a model's advertised capabilities are the
 * ones the wire really serves.
 */
class DshCatalogBindingPolicyTest {

    // ------------------------------------------------------------------ provider identity

    @Test
    fun stableIdsKeepTheThreeSourcesApartAndNeverRekeyOfficialAccountModels() {
        assertEquals(DshModelBackend.MCA_LOCAL, DshModelBackend.classify("mca-local:abc"))
        assertEquals(DshModelBackend.MCA_CLOUD, DshModelBackend.classify("mca-cloud:xyz"))
        assertEquals(
            "an opaque official model id belongs to the account provider",
            DshModelBackend.DEEPSEEK_ACCOUNT,
            DshModelBackend.classify("deepseek-chat")
        )
        assertTrue(DshModelBackend.MCA_LOCAL.networkRequired.not())
        assertTrue(DshModelBackend.MCA_CLOUD.networkRequired)
        assertTrue(DshModelBackend.DEEPSEEK_ACCOUNT.stableIdPrefix == null)
    }

    // ------------------------------------------------------------------ routing

    @Test
    fun offlineModeServesOnlyTheLocalModelAndSuspendsEveryNetworkRoute() {
        val policy = DshModelRoutingPolicy(offline = true, mixedRoutingApproved = false)
        val catalog = listOf(localModel(), cloudModel(), accountModel())

        val local = policy.routePrimary(LOCAL_ID, catalog, REV, 1)
        assertTrue(local is DshRouteDecision.Resolved)
        assertEquals(DshModelBackend.MCA_LOCAL, (local as DshRouteDecision.Resolved).snapshot.backend)

        val cloud = policy.routePrimary(CLOUD_ID, catalog, REV, 1)
        assertEquals(
            DshRouteSuspendReason.OFFLINE_NOT_LOCAL,
            (cloud as DshRouteDecision.Suspended).reason
        )
        val account = policy.routeAuxiliary(DshAuxiliaryRoute.COMPRESSION, ACCOUNT_ID, catalog, REV, 1)
        assertEquals(
            DshRouteSuspendReason.OFFLINE_NOT_LOCAL,
            (account as DshRouteDecision.Suspended).reason
        )
    }

    @Test
    fun aRemovedModelIsSuspendedInsteadOfBeingSilentlyReplaced() {
        val policy = DshModelRoutingPolicy(offline = false, mixedRoutingApproved = false)
        val suspended = policy.routePrimary("mca-cloud:gone", listOf(localModel()), REV, 2)
        assertEquals(
            DshRouteSuspendReason.MODEL_NOT_IN_CATALOG,
            (suspended as DshRouteDecision.Suspended).reason
        )
    }

    @Test
    fun anAuxiliaryRouteThatNeedsToolsAndHasNoneIsSuspended() {
        val policy = DshModelRoutingPolicy(offline = false, mixedRoutingApproved = false)
        val noTools = localModel(supportsTools = false)
        val suspended = policy.routeAuxiliary(
            DshAuxiliaryRoute.SUBAGENT,
            LOCAL_ID,
            listOf(noTools),
            REV,
            1,
            requireTools = true
        )
        assertEquals(
            DshRouteSuspendReason.CAPABILITY_MISSING,
            (suspended as DshRouteDecision.Suspended).reason
        )
    }

    @Test
    fun aLocalPrimaryWithACloudAuxiliaryRequiresAnExplicitUserChoice() {
        val strict = DshModelRoutingPolicy(offline = false, mixedRoutingApproved = false)
        assertNotNull(strict.mixedRoutingDecision(LOCAL_ID, CLOUD_ID))
        assertNull(strict.mixedRoutingDecision(LOCAL_ID, LOCAL_ID))
        val approved = DshModelRoutingPolicy(offline = false, mixedRoutingApproved = true)
        assertNull(approved.mixedRoutingDecision(LOCAL_ID, CLOUD_ID))
    }

    @Test
    fun theResolvedSnapshotPinsRevisionGenerationAndNetworkDependency() {
        val policy = DshModelRoutingPolicy(offline = false, mixedRoutingApproved = false)
        val resolved = policy.routePrimary(CLOUD_ID, listOf(cloudModel()), "rev-7", 3)
        val snapshot = (resolved as DshRouteDecision.Resolved).snapshot
        assertEquals("rev-7", snapshot.catalogRevision)
        assertEquals(3L, snapshot.bindingGeneration)
        assertTrue(snapshot.networkRequired)
        assertEquals("$CLOUD_ID@rev-7#3", snapshot.resolvedKey)
    }

    // ------------------------------------------------------------------ revision ACK

    @Test
    fun aBusyOperationRequiresAnExplicitUserStopInsteadOfATimerGuess() {
        val coordinator = DshCatalogBindingCoordinator(null, runtimeEpoch = 42L) { "cmd-1" }
        val busy = coordinator.requestRebind("rev-2", busyOperation = true, quiescenceProven = false)
        assertTrue(busy is DshCatalogAck.NeedsExplicitUserStop)
        assertNull("no command may be issued while busy", coordinator.pendingCommand())
        assertNull(coordinator.applied())
    }

    @Test
    fun aCleanRebindIssuesOneCommandAndAppliesItsAck() {
        val coordinator = DshCatalogBindingCoordinator(
            DshCatalogRevisionStamp("rev-1", 1),
            runtimeEpoch = 42L
        ) { "cmd-1" }
        val issued = coordinator.requestRebind("rev-2", busyOperation = false, quiescenceProven = false)
        val command = (issued as DshCatalogAck.CommandIssued).command
        assertEquals("cmd-1", command.commandId)
        assertEquals(42L, command.runtimeEpoch)
        assertEquals(DshCatalogRevisionStamp("rev-2", 2), command.desired)

        val applied = coordinator.acknowledge("cmd-1", 42L, command.desired)
        assertTrue(applied is DshCatalogAck.Applied)
        assertEquals(command.desired, coordinator.applied())
        assertNull(coordinator.pendingCommand())
    }

    @Test
    fun aStaleCatalogAckIsRejectedAndThePreviousAppliedRevisionStaysAuthoritative() {
        val previous = DshCatalogRevisionStamp("rev-1", 1)
        val coordinator = DshCatalogBindingCoordinator(previous, runtimeEpoch = 42L) { "cmd-1" }
        coordinator.requestRebind("rev-3", busyOperation = false, quiescenceProven = false)

        val stale = coordinator.acknowledge("cmd-1", 42L, DshCatalogRevisionStamp("rev-2", 2))
        assertEquals(DshAckRejection.STALE_REVISION, (stale as DshCatalogAck.Rejected).reason)
        assertEquals(previous, coordinator.applied())

        val wrongRevisionSameGeneration = coordinator.acknowledge(
            "cmd-1",
            42L,
            DshCatalogRevisionStamp("rev-other", 2)
        )
        assertEquals(
            DshAckRejection.STALE_REVISION,
            (wrongRevisionSameGeneration as DshCatalogAck.Rejected).reason
        )
        assertEquals(previous, coordinator.applied())
    }

    @Test
    fun anAckFromAnotherRuntimeGenerationIsAnIdentityMismatch() {
        val coordinator = DshCatalogBindingCoordinator(null, runtimeEpoch = 42L) { "cmd-1" }
        val command = (coordinator.requestRebind("rev-2", false, false) as DshCatalogAck.CommandIssued).command
        val mismatch = coordinator.acknowledge("cmd-1", 43L, command.desired)
        assertEquals(DshAckRejection.IDENTITY_MISMATCH, (mismatch as DshCatalogAck.Rejected).reason)
        assertNull(coordinator.applied())
    }

    @Test
    fun aLateOrDuplicateAckIsIgnoredAndCannotOverwriteANewerState() {
        val coordinator = DshCatalogBindingCoordinator(null, runtimeEpoch = 42L) { "cmd-1" }
        val command = (coordinator.requestRebind("rev-2", false, false) as DshCatalogAck.CommandIssued).command
        coordinator.acknowledge("cmd-1", 42L, command.desired)
        val duplicate = coordinator.acknowledge("cmd-1", 42L, command.desired)
        assertTrue(duplicate is DshCatalogAck.IgnoredLate)
        assertEquals(command.desired, coordinator.applied())

        val unknown = coordinator.acknowledge("cmd-old", 41L, DshCatalogRevisionStamp("rev-1", 1))
        assertTrue(unknown is DshCatalogAck.IgnoredLate)
    }

    @Test
    fun aSecondRebindWhileOneIsPendingIsIgnored() {
        val coordinator = DshCatalogBindingCoordinator(null, runtimeEpoch = 42L) { "cmd-1" }
        coordinator.requestRebind("rev-2", false, false)
        val second = coordinator.requestRebind("rev-3", false, false)
        assertTrue(second is DshCatalogAck.IgnoredLate)
        assertEquals("cmd-1", coordinator.pendingCommand()!!.commandId)
    }

    @Test
    fun aTimeoutAsksToQueryTheSameCommandAndNeverReplaysIt() {
        val coordinator = DshCatalogBindingCoordinator(null, runtimeEpoch = 42L) { "cmd-1" }
        val command = (coordinator.requestRebind("rev-2", false, false) as DshCatalogAck.CommandIssued).command
        val timeout = coordinator.timeout("cmd-1")
        assertTrue(timeout is DshCatalogAck.QueryRequired)
        assertEquals("cmd-1", (timeout as DshCatalogAck.QueryRequired).commandId)
        assertEquals("the command must stay pending for the query", command, coordinator.pendingCommand())

        val unknown = coordinator.resolveQuery("cmd-1", queried = null)
        assertEquals(DshAckRejection.COMMAND_UNKNOWN, (unknown as DshCatalogAck.Rejected).reason)
        assertNull("an unknown outcome never becomes applied", coordinator.applied())

        val confirmed = coordinator.resolveQuery("cmd-1", queried = command.desired)
        assertTrue(confirmed is DshCatalogAck.Applied)
        assertEquals(command.desired, coordinator.applied())
    }

    @Test
    fun anOutOfOrderCatalogEventOlderThanTheAppliedBindingIsRejected() {
        val coordinator = DshCatalogBindingCoordinator(
            DshCatalogRevisionStamp("rev-2", 2),
            runtimeEpoch = 42L
        )
        assertFalse(coordinator.acceptCatalogEvent(DshCatalogRevisionStamp("rev-1", 1)))
        assertTrue(coordinator.acceptCatalogEvent(DshCatalogRevisionStamp("rev-3", 3)))
        assertTrue(
            "the same generation is not an out-of-order event",
            coordinator.acceptCatalogEvent(DshCatalogRevisionStamp("rev-2", 2))
        )
    }

    // ------------------------------------------------------------------ capability matrix

    @Test
    fun aLocalPromptedModelIsLabeledAndNativeModelsCarryNoPromptLabel() {
        val prompted = DshBackendCapabilityMatrix.describe(localModel(native = false, label = "prompted-tool-v1"))
        assertEquals(DshModelBackend.MCA_LOCAL, prompted.backend)
        assertFalse(prompted.nativeToolCalling)
        assertTrue(prompted.promptedToolCalling)
        assertEquals("prompted-tool-v1", prompted.toolProtocolLabel)
        assertTrue(DshBackendCapabilityMatrix.violations(prompted).isEmpty())

        val native = DshBackendCapabilityMatrix.describe(
            accountModel(supportsTools = true, native = true, label = null)
        )
        assertEquals(DshModelBackend.DEEPSEEK_ACCOUNT, native.backend)
        assertTrue(native.nativeToolCalling)
        assertFalse(native.promptedToolCalling)
        assertTrue(DshBackendCapabilityMatrix.violations(native).isEmpty())
    }

    @Test
    fun dishonestToolLabelsAreReportedInsteadOfBeingAdvertised() {
        val nativeWithLabel = DshBackendCapabilityMatrix.describe(
            accountModel(supportsTools = true, native = true, label = "prompted-tool-v1")
        )
        assertTrue(
            DshBackendCapabilityMatrix.violations(nativeWithLabel)
                .any { it.contains("must not carry a prompt-compat label") }
        )
        val promptedWithoutLabel = DshBackendCapabilityMatrix.describe(
            localModel(native = false, label = null)
        )
        assertTrue(
            DshBackendCapabilityMatrix.violations(promptedWithoutLabel)
                .any { it.contains("must carry its compatibility label") }
        )
    }

    @Test
    fun visionAndReasoningAreNeverClaimedWhenUndeclared() {
        val row = DshBackendCapabilityMatrix.describe(localModel())
        assertEquals(DshCapabilitySupport.NOT_DECLARED, row.vision)
        assertEquals(DshCapabilitySupport.NOT_DECLARED, row.reasoning)
        assertEquals(
            DshCapabilitySupport.SUPPORTED,
            DshBackendCapabilityMatrix.describe(localModel(), vision = true).vision
        )
        assertEquals(
            DshCapabilitySupport.UNSUPPORTED,
            DshBackendCapabilityMatrix.describe(localModel(), vision = false).vision
        )
        assertFalse(row.parallelToolCalls)
        assertFalse(row.toolChoice.contains(DshToolChoiceKind.NAMED))
    }

    @Test
    fun aToolLessModelAdvertisesNoToolChoiceMode() {
        val row = DshBackendCapabilityMatrix.describe(cloudModel(supportsTools = false))
        assertTrue(row.toolChoice.isEmpty())
        assertFalse(row.nativeToolCalling)
        assertFalse(row.promptedToolCalling)
    }

    @Test
    fun theModelsWireCarriesTheSameCapabilityRowWithoutBreakingOpenAiFields() {
        val json = JSONObject(
            DshOpenAiWire.modelsJson(
                listOf(localModel(), accountModel(supportsTools = true, native = true, label = null)),
                "rev-1",
                1234L
            )
        )
        val data = json.getJSONArray("data")
        val local = data.getJSONObject(0)
        assertEquals(LOCAL_ID, local.getString("id"))
        val capabilities = local.getJSONObject("mca_capabilities")
        assertEquals("mca_local", capabilities.getString("backend"))
        assertTrue(capabilities.getBoolean("prompted_tools"))
        assertEquals("not_declared", capabilities.getString("vision"))
        assertFalse(capabilities.getBoolean("parallel_tool_calls"))
        assertEquals("rev-1", json.getString("mca_catalog_revision"))

        val account = data.getJSONObject(1)
        assertEquals("deepseek_account", account.getJSONObject("mca_capabilities").getString("backend"))
        assertTrue(account.getJSONObject("mca_capabilities").getBoolean("native_tools"))
    }

    // ------------------------------------------------------------------ fixtures

    private fun localModel(
        supportsTools: Boolean = true,
        native: Boolean = false,
        label: String? = "prompted-tool-v1"
    ) = DshGatewayModel(
        id = LOCAL_ID,
        displayName = "Local model",
        contextWindow = 8_192,
        maxOutputTokens = null,
        supportsTools = supportsTools,
        nativeToolCalling = native,
        toolProtocolLabel = if (supportsTools && !native) label else null
    )

    private fun cloudModel(
        supportsTools: Boolean = true,
        native: Boolean = false,
        label: String? = "prompted-tool-v1"
    ) = DshGatewayModel(
        id = CLOUD_ID,
        displayName = "Cloud model",
        contextWindow = null,
        maxOutputTokens = null,
        supportsTools = supportsTools,
        nativeToolCalling = native,
        toolProtocolLabel = if (supportsTools && !native) label else null
    )

    private fun accountModel(
        supportsTools: Boolean = true,
        native: Boolean = true,
        label: String? = null
    ) = DshGatewayModel(
        id = ACCOUNT_ID,
        displayName = "Official account model",
        contextWindow = null,
        maxOutputTokens = null,
        supportsTools = supportsTools,
        nativeToolCalling = native,
        toolProtocolLabel = label
    )

    private companion object {
        const val LOCAL_ID = "mca-local:local-1"
        const val CLOUD_ID = "mca-cloud:cloud-1"
        const val ACCOUNT_ID = "deepseek-chat"
        const val REV = "rev-1"
    }
}

