package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteTable
import com.muyuchat.core.dsh.platform.DshCapabilityRouteRegistry
import com.muyuchat.core.dsh.platform.DshCapabilityRouteResolution
import com.muyuchat.core.dsh.runtime.process.DshTerminalToolRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mounting is the authorization decision, so it is asserted directly instead of through the HTTP
 * server: the terminal family must be POST-only, must be its own capability family, and must be
 * the real fixed route list the official terminal tools call.
 */
class DshCapabilityRouteMountingTest {

    @Test
    fun theTerminalTableMountsPostOnlyUnderItsOwnFamily() {
        val builder = DshCapabilityRouteRegistry.builder()

        val mounted = mountCapabilityRoutes(
            builder = builder,
            table = table(DshTerminalToolRoutes.PATHS),
            capabilityOf = { DshCapability.WORKSPACE_TERMINAL },
            methods = setOf(DshCapabilityHttpMethod.POST)
        )
        val registry = builder.build()

        assertEquals(DshTerminalToolRoutes.PATHS.sorted(), mounted.sorted())
        assertEquals(DshTerminalToolRoutes.PATHS.sorted(), registry.paths())
        for (path in DshTerminalToolRoutes.PATHS) {
            val definition = requireNotNull(registry.definitionFor(path)) { "missing $path" }
            assertEquals(DshCapability.WORKSPACE_TERMINAL, definition.capability)
            assertEquals(setOf(DshCapabilityHttpMethod.POST), definition.methods)
            assertNotEquals(
                "the terminal family must never ride the privileged shell grant",
                DshCapability.PRIVILEGED_SHELL,
                definition.capability
            )
            assertTrue(
                registry.resolve(path, DshCapabilityHttpMethod.POST) is DshCapabilityRouteResolution.Found
            )
            assertTrue(
                registry.resolve(path, DshCapabilityHttpMethod.GET) is DshCapabilityRouteResolution.MethodNotAllowed
            )
        }
        assertTrue(
            registry.resolve("/terminal/not-a-route", DshCapabilityHttpMethod.POST) is
                DshCapabilityRouteResolution.NotFound
        )
    }

    @Test
    fun onePathCannotBeOwnedTwice() {
        val builder = DshCapabilityRouteRegistry.builder()
        val paths = listOf(DshTerminalToolRoutes.OPEN)
        mountCapabilityRoutes(
            builder,
            table(paths),
            { DshCapability.WORKSPACE_TERMINAL },
            setOf(DshCapabilityHttpMethod.POST)
        )

        val failure = runCatching {
            mountCapabilityRoutes(
                builder,
                table(paths),
                { DshCapability.WORKSPACE_TERMINAL },
                setOf(DshCapabilityHttpMethod.POST)
            )
        }

        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun theMountHelperKeepsThePerPathCapabilityResolver() {
        val builder = DshCapabilityRouteRegistry.builder()
        mountCapabilityRoutes(
            builder,
            table(listOf("/shell", "/notify")),
            { path ->
                if (path == "/shell") DshCapability.PRIVILEGED_SHELL else DshCapability.ANDROID_SYSTEM
            },
            setOf(DshCapabilityHttpMethod.GET, DshCapabilityHttpMethod.POST)
        )
        val registry = builder.build()

        assertEquals(
            DshCapability.PRIVILEGED_SHELL,
            registry.definitionFor("/shell")?.capability
        )
        assertEquals(
            DshCapability.ANDROID_SYSTEM,
            registry.definitionFor("/notify")?.capability
        )
    }

    private fun table(paths: List<String>): DshCapabilityRouteTable {
        val builder = DshCapabilityRouteTable.builder()
        for (path in paths) {
            builder.route(path) { DshCapabilityRouteResponse.Json(200, """{"ok":true}""") }
        }
        return builder.build()
    }
}
