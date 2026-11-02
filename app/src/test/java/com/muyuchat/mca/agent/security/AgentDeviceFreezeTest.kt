package com.muyuchat.mca.agent.security

import com.muyuchat.mca.agent.tools.AgentDeviceInfoArgumentNormalizer
import com.muyuchat.mca.agent.tools.AgentDeviceInfoTool
import com.muyuchat.mca.agent.tools.AgentDeviceNotificationArgumentNormalizer
import com.muyuchat.mca.agent.tools.AgentDeviceNotificationTool
import com.muyuchat.mca.agent.tools.AgentDeviceScopes
import com.muyuchat.mca.agent.tools.AgentDispatchToolArgumentNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end freeze for device.info: normalizer (empty args) + typed Device scope only.
 *
 * This pins the production path that the executor tests could not cover: raw model `{}` becomes a
 * normalized `{}`, the typed scope supplies the resource identity, and the frozen command carries a
 * canonical device scope instead of a legacy workspace pseudo-path.
 */
class AgentDeviceFreezeTest {

    private val factory = AgentFrozenCommandFactory(
        AgentDispatchToolArgumentNormalizer(
            routes = listOf(
                setOf(AgentDeviceInfoTool.TOOL_KEY) to AgentDeviceInfoArgumentNormalizer()
            )
        )
    )

    private fun request(
        typedScope: com.muyuchat.core.agent.contract.AgentScopePrecondition? = AgentDeviceScopes.info()
    ) = AgentFreezeRequest(
        descriptor = AgentDeviceInfoTool.DESCRIPTOR,
        rawArgumentsJson = "{}",
        invocationId = "inv-device-freeze",
        runId = "run-device-freeze",
        stepId = "step-device-freeze",
        callOrdinal = 0,
        workspaceId = "workspace-device-freeze",
        taskId = "task-device-freeze",
        runEpoch = 1L,
        policyVersion = 1L,
        nowMillis = 1_000L,
        typedScope = typedScope
    )

    @Test
    fun deviceInfoFreezesWithItsTypedScope() {
        val frozen = factory.freeze(request()) as AgentFreezeResult.Frozen
        val command = frozen.command
        assertEquals(AgentDeviceInfoTool.TOOL_KEY, command.toolKey)
        assertEquals("{}", command.normalizedArgumentsJson)
        assertTrue(command.resourcePreconditionsJson.contains("\"device\""))
        assertTrue(command.resourcePreconditionsJson.contains("UserVisibleSummary") ||
            command.resourcePreconditionsJson.contains("userVisibleSummary"))
        assertTrue(frozen.scope.typedPreconditionJson != null)
        assertTrue(frozen.hashPreimageHex.isNotBlank())
    }

    @Test
    fun withoutATypedScopeTheDeviceCallIsRejected() {
        val rejected = factory.freeze(request(typedScope = null))
        assertTrue("unexpected: $rejected", rejected is AgentFreezeResult.Rejected)
    }
    @Test
    fun deviceNotifyFreezesWithArgumentBoundScope() {
        val notifyFactory = AgentFrozenCommandFactory(
            AgentDispatchToolArgumentNormalizer(
                routes = listOf(
                    setOf(AgentDeviceNotificationTool.TOOL_KEY) to
                        AgentDeviceNotificationArgumentNormalizer()
                )
            )
        )
        val raw = """{"title":"Cloud","body":"test passed"}"""
        val normalized = (
            AgentDeviceNotificationArgumentNormalizer()
                .normalize(AgentDeviceNotificationTool.DESCRIPTOR, raw)
            as com.muyuchat.mca.agent.security.AgentArgumentNormalization.Normalized
            ).normalizedArgumentsJson
        val frozen = notifyFactory.freeze(
            AgentFreezeRequest(
                descriptor = AgentDeviceNotificationTool.DESCRIPTOR,
                rawArgumentsJson = raw,
                invocationId = "inv-notify-freeze",
                runId = "run-notify-freeze",
                stepId = "step-notify-freeze",
                callOrdinal = 0,
                workspaceId = "workspace-notify-freeze",
                taskId = "task-notify-freeze",
                runEpoch = 1L,
                policyVersion = 1L,
                nowMillis = 1_000L,
                typedScope = requireNotNull(AgentDeviceScopes.notification(normalized))
            )
        )
        assertTrue("unexpected: $frozen", frozen is AgentFreezeResult.Frozen)
    }

}
