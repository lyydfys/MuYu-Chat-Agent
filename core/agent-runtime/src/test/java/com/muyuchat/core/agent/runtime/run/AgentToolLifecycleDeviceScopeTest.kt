package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentEffectKind
import com.muyuchat.core.agent.contract.AgentResourceScope
import com.muyuchat.core.agent.contract.AgentScopePrecondition
import com.muyuchat.core.agent.contract.AgentScopePreconditionCodec
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import org.junit.Assert.assertTrue
import org.junit.Test

/** The approval event must carry the human-readable Device scope for the sheet. */
class AgentToolLifecycleDeviceScopeTest {

    private val codec = StrictAgentJsonCodec()

    private val descriptor = AgentToolDescriptor(
        toolKey = "device.info",
        version = "1",
        displayName = "Device Information",
        description = "Read-only device facts.",
        schemaJson = """{"type":"object","additionalProperties":false,"properties":{}}""",
        schemaHash = "device-schema-hash",
        sideEffect = AgentToolSideEffect.READ_ONLY,
        dataClass = AgentDataClass.PRIVATE
    )

    @Test
    fun approvalSummaryCarriesTheDeviceScopeSummary() {
        val precondition = AgentScopePrecondition(
            scopes = listOf(
                AgentResourceScope.Device(
                    operation = "device.info",
                    userVisibleSummary = "读取设备型号、Android 版本与 ABI（只读）"
                )
            ),
            effects = setOf(AgentEffectKind.DEVICE_ACTION)
        )
        val command = FrozenToolCommand(
            invocationId = "inv-device-scope",
            runId = "run-device-scope",
            stepId = "step-device-scope",
            callOrdinal = 0,
            toolKey = descriptor.toolKey,
            toolVersion = descriptor.version,
            schemaHash = descriptor.schemaHash,
            normalizedArgumentsJson = "{}",
            argumentsHash = "args-device-scope",
            resourceFingerprint = "fingerprint-device-scope",
            runEpoch = 1L,
            policyVersion = 1L,
            expiresAtMillis = Long.MAX_VALUE,
            resourcePreconditionsJson = AgentScopePreconditionCodec.canonicalJson(precondition)
        )

        val summary = AgentToolLifecycle.approvalRequestedSummary(codec, command, descriptor, "{}")

        assertTrue(summary.contains("\"scope\""))
        assertTrue(summary.contains("读取设备型号"))
        assertTrue(summary.contains("device.info"))
    }
}
