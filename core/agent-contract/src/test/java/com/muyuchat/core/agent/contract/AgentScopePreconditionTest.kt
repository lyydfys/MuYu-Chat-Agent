package com.muyuchat.core.agent.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N02c contract invariants for typed resource scopes.
 *
 * These are pure logic checks: they do not claim that any production caller uses the v2 precondition
 * yet. They pin the canonical shape, the effect/scope consistency rule and the rejection of unsafe
 * path/origin values so the eventual wiring cannot silently change what an approval binds.
 */
class AgentScopePreconditionTest {

    private fun memoryScope(
        spaceIds: List<String> = listOf("space-a", "space-b"),
        access: AgentResourceAccess = AgentResourceAccess.READ
    ) = AgentResourceScope.Memory(spaceIds = spaceIds, access = access)

    private fun workspaceScope(
        paths: List<String> = listOf("artifacts/report.md"),
        access: AgentResourceAccess = AgentResourceAccess.WRITE
    ) = AgentResourceScope.Workspace(
        scopeId = "workspace-1",
        relativePaths = paths,
        access = access
    )

    @Test
    fun memoryScopeRequiresAReadEffect() {
        val precondition = AgentScopePrecondition(
            scopes = listOf(memoryScope()),
            effects = setOf(AgentEffectKind.LOCAL_READ)
        )

        assertEquals(2, precondition.schemaVersion)
        assertTrue(AgentScopePreconditionCodec.summary(precondition).startsWith("memory:space-a,space-b"))
    }

    @Test
    fun aScopeWithoutAMatchingEffectIsRejected() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            AgentScopePrecondition(
                scopes = listOf(memoryScope()),
                effects = setOf(AgentEffectKind.NETWORK_WRITE)
            )
        }
        assertTrue(failure.message.orEmpty().contains("no matching effect"))
    }

    @Test
    fun networkScopeRequiresANetworkEffect() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            AgentScopePrecondition(
                scopes = listOf(
                    AgentResourceScope.Network(
                        origins = listOf("https://example.com"),
                        methods = setOf("GET")
                    )
                ),
                effects = setOf(AgentEffectKind.LOCAL_READ)
            )
        }
        assertTrue(failure.message.orEmpty().contains("no matching effect"))
    }

    @Test
    fun effectsMustNotBeEmptyAndScopesMustNotBeEmpty() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentScopePrecondition(scopes = emptyList(), effects = setOf(AgentEffectKind.LOCAL_READ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentScopePrecondition(scopes = listOf(memoryScope()), effects = emptySet())
        }
    }

    @Test
    fun canonicalJsonIsStableAcrossInputOrder() {
        val first = AgentScopePrecondition(
            scopes = listOf(memoryScope(), workspaceScope()),
            effects = setOf(AgentEffectKind.LOCAL_READ, AgentEffectKind.LOCAL_WRITE)
        )
        val second = AgentScopePrecondition(
            scopes = listOf(workspaceScope(), memoryScope()),
            effects = setOf(AgentEffectKind.LOCAL_WRITE, AgentEffectKind.LOCAL_READ)
        )

        assertEquals(
            AgentScopePreconditionCodec.canonicalJson(first),
            AgentScopePreconditionCodec.canonicalJson(second)
        )
        assertEquals(
            AgentScopePreconditionCodec.fingerprint(first),
            AgentScopePreconditionCodec.fingerprint(second)
        )
    }

    @Test
    fun fingerprintChangesWithTheFrozenSpaceSet() {
        val narrow = AgentScopePrecondition(
            scopes = listOf(memoryScope(spaceIds = listOf("space-a"))),
            effects = setOf(AgentEffectKind.LOCAL_READ)
        )
        val wide = AgentScopePrecondition(
            scopes = listOf(memoryScope(spaceIds = listOf("space-a", "space-b"))),
            effects = setOf(AgentEffectKind.LOCAL_READ)
        )

        assertNotEquals(
            AgentScopePreconditionCodec.fingerprint(narrow),
            AgentScopePreconditionCodec.fingerprint(wide)
        )
    }

    @Test
    fun pathTraversalAndMalformedOriginsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            workspaceScope(paths = listOf("../../etc/passwd"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentResourceScope.Network(
                origins = listOf("https://example.com/path?q=1"),
                methods = setOf("GET")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentResourceScope.Memory(spaceIds = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentResourceScope.Memory(spaceIds = listOf("space-a"), revisions = mapOf("memory-1" to -1L))
        }
    }

    @Test
    fun compositeScopeRequiresEveryChildEffect() {
        val composite = AgentResourceScope.Composite(
            requiredScopes = listOf(memoryScope(), workspaceScope())
        )

        assertEquals(
            setOf(AgentEffectKind.LOCAL_READ, AgentEffectKind.LOCAL_WRITE),
            AgentScopePrecondition.requiredEffects(composite)
        )
        assertThrows(IllegalArgumentException::class.java) {
            AgentScopePrecondition(
                scopes = listOf(composite),
                effects = setOf(AgentEffectKind.LOCAL_READ)
            )
        }
    }
}
