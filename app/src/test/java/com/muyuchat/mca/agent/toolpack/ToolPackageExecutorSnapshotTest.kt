package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cold-start visibility for executable packages (priority 5).
 *
 * The assembly cannot block while it is built, but a run that starts immediately afterwards must
 * not freeze a catalog without the packages the user enabled. These tests pin the two properties
 * the fix relies on: the first resolution performs (or joins) exactly one load, and refresh order
 * is monotonic so a slow older refresh cannot resurrect a stale surface.
 */
class ToolPackageExecutorSnapshotTest {

    private fun executor(key: String): AgentToolExecutor = object : AgentToolExecutor {
        override val descriptor = AgentToolDescriptor(
            toolKey = key,
            version = "1",
            displayName = key,
            description = key,
            schemaJson = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
            schemaHash = "a".repeat(64),
            sideEffect = com.muyuchat.core.agent.contract.AgentToolSideEffect.READ_ONLY,
            dataClass = com.muyuchat.core.agent.contract.AgentDataClass.PRIVATE
        )

        override suspend fun execute(
            command: com.muyuchat.core.agent.contract.FrozenToolCommand,
            context: com.muyuchat.core.agent.contract.AgentToolContext
        ): com.muyuchat.core.agent.contract.AgentToolResult =
            com.muyuchat.core.agent.contract.AgentToolResult(
                status = com.muyuchat.core.agent.contract.AgentToolResultStatus.SUCCEEDED,
                summary = "ok"
            )
    }

    @Test
    fun `the first catalog resolution loads the surface instead of freezing an empty one`() = runBlocking {
        val snapshot = ToolPackageExecutorSnapshot()
        assertTrue(snapshot.current().isEmpty())

        snapshot.awaitInitial { listOf(executor("pkg.alpha")) }

        assertEquals(listOf("pkg.alpha"), snapshot.current().map { it.descriptor.toolKey })
        assertEquals(1L, snapshot.committed)
    }

    @Test
    fun `concurrent first resolutions share exactly one load`() = runBlocking {
        val snapshot = ToolPackageExecutorSnapshot()
        val loads = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val load: suspend () -> List<AgentToolExecutor> = {
            loads.incrementAndGet()
            gate.await()
            listOf(executor("pkg.alpha"))
        }

        val first = async { snapshot.awaitInitial(load) }
        val second = async { snapshot.awaitInitial(load) }
        yield()
        gate.complete(Unit)
        first.await()
        second.await()

        assertEquals("one shared load for concurrent resolutions", 1, loads.get())
        assertEquals(1L, snapshot.committed)
    }

    @Test
    fun `a background refresh in flight satisfies the first resolution without a second load`() = runBlocking {
        val snapshot = ToolPackageExecutorSnapshot()
        val loads = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val load: suspend () -> List<AgentToolExecutor> = {
            loads.incrementAndGet()
            gate.await()
            listOf(executor("pkg.alpha"))
        }

        val refresh = async { snapshot.refresh(load) }
        yield()
        val resolution = async { snapshot.awaitInitial(load) }
        yield()
        assertTrue("the resolution must wait for the committed surface", !resolution.isCompleted)
        gate.complete(Unit)
        refresh.await()
        resolution.await()

        assertEquals(1, loads.get())
        assertEquals(listOf("pkg.alpha"), snapshot.current().map { it.descriptor.toolKey })
    }

    @Test
    fun `an older slower refresh never overwrites a newer committed surface`() = runBlocking {
        val snapshot = ToolPackageExecutorSnapshot()
        val olderGate = CompletableDeferred<Unit>()
        snapshot.awaitInitial { listOf(executor("pkg.initial")) }

        val older = async {
            snapshot.refresh {
                olderGate.await()
                listOf(executor("pkg.older"))
            }
        }
        yield()
        val newer = async { snapshot.refresh { listOf(executor("pkg.newer")) } }
        newer.await()
        assertEquals(listOf("pkg.newer"), snapshot.current().map { it.descriptor.toolKey })

        olderGate.complete(Unit)
        assertNull("the stale refresh must not commit", older.await())

        assertEquals(
            listOf("pkg.newer"),
            snapshot.current().map { it.descriptor.toolKey }
        )
        assertEquals(3L, snapshot.committed)
    }

    @Test
    fun `a failing load commits an empty surface instead of hanging the first run`() = runBlocking {
        val snapshot = ToolPackageExecutorSnapshot()

        snapshot.awaitInitial { throw IllegalStateException("store unavailable") }

        assertTrue(snapshot.current().isEmpty())
        assertEquals(1L, snapshot.committed)
    }

    @Test
    fun `the assembled run catalog does not follow a later refresh`() = runBlocking {
        val snapshot = ToolPackageExecutorSnapshot()
        snapshot.awaitInitial { listOf(executor("pkg.alpha")) }
        val base = com.muyuchat.mca.agent.tools.builtInAgentToolCatalog(
            com.muyuchat.mca.agent.tools.FakeAgentWorkspaceFilePort()
        ).augmentedWithPackageTools { snapshot.current() }
        // Freezing happens in the per-run assembler, not in the base view: assemble once, exactly
        // as the run catalog provider does.
        val assembled = com.muyuchat.mca.agent.tools.AgentToolCatalogAssembler()
            .assemble(base, emptyList(), emptyList()) as
            com.muyuchat.mca.agent.tools.AgentToolCatalogAssemblyResult.Assembled

        val runSnapshot = assembled.catalog.snapshot().map { it.toolKey }.toSet()
        assertTrue("pkg.alpha" in runSnapshot)

        snapshot.refresh { listOf(executor("pkg.beta")) }

        assertEquals(
            "an assembled run catalog must not follow a later package change",
            runSnapshot,
            assembled.catalog.snapshot().map { it.toolKey }.toSet()
        )
    }
}
