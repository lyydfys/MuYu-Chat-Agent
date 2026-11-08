package com.muyuchat.mca.agent.security

import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Approval tiers: global ALLOW/ASK/FORBID plus per-tool exceptions.
 *
 * The load-bearing promises are:
 *  1. an exact override wins over the global default, in both directions;
 *  2. only `ASK` is relaxed for a side-effect-free (`READ_ONLY`) tool — `FORBID` still forbids it;
 *  3. an unknown tool key inherits the default and is never implicitly allowed;
 *  4. on the real broker, FORBID creates no PENDING approval and ALLOW creates none either, so the
 *     run is never paused and the approval outbox keeps its existing meaning.
 */
class AgentToolApprovalPolicyTest {

    private val readText = AgentBuiltInToolArgumentNormalizer.READ_TEXT
    private val writeText = AgentBuiltInToolArgumentNormalizer.WRITE_TEXT
    private val writeArguments =
        """{"path":"artifacts/report.md","content":"hello","expectedOldHash":null}"""

    // ---------------------------------------------------------------- pure policy

    @Test
    fun `per tool override beats the global default in both directions`() {
        val policy = ToolApprovalPolicy(
            defaultMode = ToolApprovalMode.ASK,
            perToolOverrides = mapOf(
                writeText to ToolApprovalMode.ALLOW,
                readText to ToolApprovalMode.FORBID
            )
        )

        assertEquals(ToolApprovalMode.ALLOW, policy.decide(writeText, AgentToolSideEffect.WORKSPACE_WRITE))
        assertEquals(ToolApprovalMode.FORBID, policy.decide(readText, AgentToolSideEffect.READ_ONLY))
    }

    @Test
    fun `a forbidding default still forbids a read only tool`() {
        val policy = ToolApprovalPolicy(defaultMode = ToolApprovalMode.FORBID)
        assertEquals(ToolApprovalMode.FORBID, policy.decide(readText, AgentToolSideEffect.READ_ONLY))
    }

    @Test
    fun `ask lets a read only tool run without a prompt`() {
        val policy = ToolApprovalPolicy(defaultMode = ToolApprovalMode.ASK)
        assertEquals(ToolApprovalMode.ALLOW, policy.decide(readText, AgentToolSideEffect.READ_ONLY))
    }

    @Test
    fun `ask still asks for every side effecting tool`() {
        val policy = ToolApprovalPolicy(defaultMode = ToolApprovalMode.ASK)
        listOf(
            AgentToolSideEffect.WORKSPACE_WRITE,
            AgentToolSideEffect.EXTERNAL_SIDE_EFFECT,
            AgentToolSideEffect.NETWORK
        ).forEach { sideEffect ->
            assertEquals(
                "ASK must not relax $sideEffect",
                ToolApprovalMode.ASK,
                policy.decide("device.notify", sideEffect)
            )
        }
    }

    @Test
    fun `an unknown tool key follows the default`() {
        assertEquals(
            ToolApprovalMode.ASK,
            ToolApprovalPolicy().decide("mcp:server-1:remote_tool", AgentToolSideEffect.EXTERNAL_SIDE_EFFECT)
        )
        assertEquals(
            ToolApprovalMode.ALLOW,
            ToolApprovalPolicy(defaultMode = ToolApprovalMode.ALLOW)
                .decide("mcp:server-1:remote_tool", AgentToolSideEffect.EXTERNAL_SIDE_EFFECT)
        )
        assertEquals(
            ToolApprovalMode.FORBID,
            ToolApprovalPolicy(defaultMode = ToolApprovalMode.FORBID)
                .decide("mcp:server-1:remote_tool", AgentToolSideEffect.NETWORK)
        )
    }

    @Test
    fun `overrides are editable and removable`() {
        val base = ToolApprovalPolicy(defaultMode = ToolApprovalMode.ASK)
        val added = base.withOverride(writeText, ToolApprovalMode.FORBID)
        assertEquals(ToolApprovalMode.FORBID, added.modeFor(writeText))
        assertTrue(added.hasOverride(writeText))

        val removed = added.withOverride(writeText, null)
        assertEquals(ToolApprovalMode.ASK, removed.modeFor(writeText))
        assertFalse(removed.hasOverride(writeText))
        assertEquals(ToolApprovalMode.ASK, base.modeFor(writeText))
    }

    // ---------------------------------------------------------------- codec

    @Test
    fun `codec round trips the default and every override`() {
        val policy = ToolApprovalPolicy(
            defaultMode = ToolApprovalMode.FORBID,
            perToolOverrides = mapOf(
                readText to ToolApprovalMode.ALLOW,
                "mcp:server-1:remote_tool" to ToolApprovalMode.ASK
            )
        )
        assertEquals(policy, ToolApprovalPolicyCodec.decode(ToolApprovalPolicyCodec.encode(policy)))
    }

    @Test
    fun `a corrupt document falls back to everything asks`() {
        assertEquals(ToolApprovalPolicy.DEFAULT, ToolApprovalPolicyCodec.decode("{not json"))
        assertEquals(ToolApprovalPolicy.DEFAULT, ToolApprovalPolicyCodec.decode(null))
        assertEquals(
            ToolApprovalPolicy.DEFAULT,
            ToolApprovalPolicyCodec.decode("""{"version":99,"default":"ALLOW","overrides":{}}""")
        )
    }

    @Test
    fun `unknown modes and keys are dropped instead of widening access`() {
        val decoded = ToolApprovalPolicyCodec.decode(
            """{"version":1,"default":"ASK","overrides":{"workspace.write_text":"YOLO","bad key":"ALLOW"}}"""
        )
        assertEquals(ToolApprovalMode.ASK, decoded.defaultMode)
        assertTrue("a bad mode must not be stored", decoded.perToolOverrides.isEmpty())
    }

    // ---------------------------------------------------------------- broker integration

    @Test
    fun `forbid denies on the broker without creating a pending approval`() = runBlocking {
        val store = InMemoryAgentApprovalStore()
        val broker = AgentPermissionBroker(store, approvalIdFactory = { "approval-forbid" })
        val descriptor = AgentSecurityFixtures.descriptor(
            toolKey = writeText,
            sideEffect = AgentToolSideEffect.WORKSPACE_WRITE
        )
        val frozen = AgentSecurityFixtures.freeze(descriptor, writeArguments)
        val policy = ToolApprovalPolicy(
            defaultMode = ToolApprovalMode.ASK,
            perToolOverrides = mapOf(writeText to ToolApprovalMode.FORBID)
        )

        val decision = broker.decide(
            AgentSecurityFixtures.candidate(frozen, descriptor),
            AgentSecurityFixtures.policy(),
            AgentSecurityFixtures.grant(),
            NOW,
            policy
        )

        assertTrue("expected a denial, got $decision", decision is AgentPermissionDecision.Denied)
        val denied = decision as AgentPermissionDecision.Denied
        assertEquals("PERMISSION_DENIED", denied.error.code)
        assertEquals(AgentToolResultStatus.NOT_EXECUTED, denied.toolStatus)
        assertEquals("FORBID must not queue a prompt", 0, store.size())
    }

    @Test
    fun `allow returns a ready command and consumes nothing`() = runBlocking {
        val store = InMemoryAgentApprovalStore()
        val broker = AgentPermissionBroker(store)
        val descriptor = AgentSecurityFixtures.descriptor(
            toolKey = "device.notify",
            sideEffect = AgentToolSideEffect.EXTERNAL_SIDE_EFFECT
        )
        val frozen = AgentSecurityFixtures.freezePassthrough(descriptor)
        val policy = ToolApprovalPolicy(
            defaultMode = ToolApprovalMode.ASK,
            perToolOverrides = mapOf("device.notify" to ToolApprovalMode.ALLOW)
        )

        val decision = broker.decide(
            AgentSecurityFixtures.candidate(frozen, descriptor),
            AgentSecurityFixtures.policy(networkPolicy = com.muyuchat.core.agent.contract.AgentNetworkPolicy.NETWORK_ENABLED),
            AgentSecurityFixtures.grant(),
            NOW,
            policy
        )

        assertTrue("expected ALLOW_FOR_TASK, got $decision", decision is AgentPermissionDecision.AllowForTask)
        assertEquals(
            AgentPermissionBasis.POLICY_ALLOW,
            (decision as AgentPermissionDecision.AllowForTask).basis
        )
        assertEquals("ALLOW must not queue a prompt", 0, store.size())
    }

    @Test
    fun `missing policy keeps the historical ask behaviour`() = runBlocking {
        val store = InMemoryAgentApprovalStore()
        val broker = AgentPermissionBroker(store, approvalIdFactory = { "approval-ask" })
        val descriptor = AgentSecurityFixtures.descriptor(
            toolKey = writeText,
            sideEffect = AgentToolSideEffect.WORKSPACE_WRITE
        )
        val frozen = AgentSecurityFixtures.freeze(descriptor, writeArguments)

        // The 4-argument overload is what every existing caller/test uses: no policy, always ask.
        val decision = broker.decide(
            AgentSecurityFixtures.candidate(frozen, descriptor),
            AgentSecurityFixtures.policy(),
            AgentSecurityFixtures.grant(),
            NOW
        )

        assertTrue("expected a pending approval, got $decision", decision is AgentPermissionDecision.NeedsApproval)
        assertEquals(1, store.size())
    }

    @Test
    fun `a failing policy source is treated as ask`() {
        val source = AgentToolApprovalPolicySource { error("preference store unavailable") }
        val policy = runCatching { source.current() }.getOrElse { ToolApprovalPolicy.DEFAULT }
        assertEquals(ToolApprovalMode.ASK, policy.decide(writeText, AgentToolSideEffect.WORKSPACE_WRITE))
    }

    @Test
    fun `the stored policy uses the shared preference contract`() {
        assertEquals("mca_agent_tool_approval", AgentToolApprovalPolicyStore.PREFERENCES_NAME)
        assertEquals("policy", AgentToolApprovalPolicyStore.KEY_POLICY)
        assertNull(ToolApprovalPolicy.DEFAULT.perToolOverrides[readText])
    }
    // ---------------------------------------------------------------- presets

    @Test
    fun `ask money only still asks for device screen`() {
        assertEquals(
            ToolApprovalMode.ASK,
            ToolApprovalPolicy.PRESET_ASK_MONEY_ONLY.decide("device.screen", AgentToolSideEffect.EXTERNAL_SIDE_EFFECT)
        )
    }

    @Test
    fun `ask money only asks for every external effect and network call by class`() {
        val policy = ToolApprovalPolicy.PRESET_ASK_MONEY_ONLY
        assertEquals(
            ToolApprovalMode.ASK,
            policy.decide("device.notify", AgentToolSideEffect.EXTERNAL_SIDE_EFFECT)
        )
        assertEquals(
            ToolApprovalMode.ASK,
            policy.decide("http.get", AgentToolSideEffect.NETWORK)
        )
        assertEquals(
            ToolApprovalMode.ASK,
            policy.decide("mcp:server-1:unknown_spend", AgentToolSideEffect.NETWORK)
        )
    }

    @Test
    fun `ask money only runs everything else without asking`() {
        val policy = ToolApprovalPolicy.PRESET_ASK_MONEY_ONLY
        assertEquals(
            ToolApprovalMode.ALLOW,
            policy.decide(AgentBuiltInToolArgumentNormalizer.READ_TEXT, AgentToolSideEffect.READ_ONLY)
        )
        assertEquals(
            ToolApprovalMode.ALLOW,
            policy.decide(AgentBuiltInToolArgumentNormalizer.WRITE_TEXT, AgentToolSideEffect.WORKSPACE_WRITE)
        )
        assertEquals(
            ToolApprovalMode.ALLOW,
            policy.decide("memory.search", AgentToolSideEffect.READ_ONLY)
        )
    }

    @Test
    fun `autonomous allows the same spend-like tool`() {
        assertEquals(
            ToolApprovalMode.ALLOW,
            ToolApprovalPolicy.PRESET_AUTONOMOUS.decide(
                "device.screen",
                AgentToolSideEffect.EXTERNAL_SIDE_EFFECT
            )
        )
        assertEquals(
            ToolApprovalMode.ALLOW,
            ToolApprovalPolicy.PRESET_AUTONOMOUS.decide("http.get", AgentToolSideEffect.NETWORK)
        )
        assertTrue(ToolApprovalPolicy.PRESET_AUTONOMOUS.perToolOverrides.isEmpty())
        assertTrue(ToolApprovalPolicy.PRESET_AUTONOMOUS.askOnSideEffects.isEmpty())
    }

    @Test
    fun `cautious allows read only tools and asks for the rest`() {
        val policy = ToolApprovalPolicy.PRESET_CAUTIOUS
        assertEquals(
            ToolApprovalMode.ALLOW,
            policy.decide(AgentBuiltInToolArgumentNormalizer.READ_TEXT, AgentToolSideEffect.READ_ONLY)
        )
        assertEquals(
            ToolApprovalMode.ASK,
            policy.decide(AgentBuiltInToolArgumentNormalizer.WRITE_TEXT, AgentToolSideEffect.WORKSPACE_WRITE)
        )
        assertEquals(
            ToolApprovalMode.ASK,
            policy.decide("device.screen", AgentToolSideEffect.EXTERNAL_SIDE_EFFECT)
        )
    }

    @Test
    fun `ask money only decides device screen from its own override`() {
        val policy = ToolApprovalPolicy.PRESET_ASK_MONEY_ONLY
        assertTrue(
            "device.screen must be an explicit override, so the decision survives a default change",
            policy.hasOverride("device.screen")
        )
        assertEquals(ToolApprovalMode.ASK, policy.modeFor("device.screen"))
        assertEquals(
            ToolApprovalMode.ASK,
            policy.decide("device.screen", AgentToolSideEffect.READ_ONLY)
        )
    }

    @Test
    fun `every preset is listed once with a non blank chinese description`() {
        val presets = ToolApprovalPolicy.PRESETS
        assertEquals(3, presets.size)
        assertEquals(presets.size, presets.map { it.id }.distinct().size)
        presets.forEach { preset ->
            assertTrue("preset ${preset.id} needs a description", preset.description.isNotBlank())
            assertTrue(
                "preset ${preset.id} description must be Chinese",
                preset.description.any { it in '\u4E00'..'\u9FFF' }
            )
            assertEquals(preset, ToolApprovalPolicy.preset(preset.id))
        }
        assertNull(ToolApprovalPolicy.preset("no_such_preset"))
    }

    @Test
    fun `presets round trip through the codec without losing the side effect guard`() {
        val decoded = ToolApprovalPolicyCodec.decode(
            ToolApprovalPolicyCodec.encode(ToolApprovalPolicy.PRESET_ASK_MONEY_ONLY)
        )
        assertEquals(ToolApprovalPolicy.PRESET_ASK_MONEY_ONLY, decoded)
        assertEquals(
            ToolApprovalMode.ASK,
            decoded.decide("device.notify", AgentToolSideEffect.EXTERNAL_SIDE_EFFECT)
        )
    }

    @Test
    fun `a version one document still decodes to its exact old behaviour`() {
        val decoded = ToolApprovalPolicyCodec.decode(
            """{"version":1,"default":"ASK","overrides":{"artifact.write_text":"ALLOW"}}"""
        )
        assertEquals(ToolApprovalMode.ASK, decoded.defaultMode)
        assertEquals(ToolApprovalMode.ALLOW, decoded.modeFor(AgentBuiltInToolArgumentNormalizer.WRITE_TEXT))
        assertTrue(decoded.askOnSideEffects.isEmpty())
        assertEquals(
            ToolApprovalMode.ALLOW,
            decoded.decide(AgentBuiltInToolArgumentNormalizer.READ_TEXT, AgentToolSideEffect.READ_ONLY)
        )
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}