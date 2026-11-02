package com.muyuchat.core.dsh.runtime.process

import com.muyuchat.core.dsh.contract.runtime.DshTerminalTransport
import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshTerminalAssemblyTest {

    private val root = tempDirectory("dsh-terminal-assembly")
    private val config = DshRuntimeConfig(dataRoot = root).validated()

    @After
    fun cleanUp() {
        root.walkBottomUp().forEach { it.delete() }
    }

    @Test
    fun itDerivesTheExecutionWorldFromTheSameRuntimeConfig() {
        val nativeDir = File(root, "native").apply { mkdirs() }
        val assembly = DshTerminalAssembly(config, nativeDir)

        assertEquals(config.payloadDir, assembly.environment.payloadDir)
        assertEquals(nativeDir, assembly.environment.nativeLibraryDir)
        assertEquals(config.runtimeLibraryDir(), assembly.environment.runtimeLibraryDir)
        assertEquals(config.payloadBinDir(), assembly.environment.payloadBinDir)
        assertEquals(config.homeDir, assembly.environment.homeDir)
        assertEquals(config.workspaceDir, assembly.environment.workspaceDir)
        assertEquals(config.workspaceDir.absolutePath, assembly.environment.workingDirectory())
        assertTrue(assembly.environment.childEnvironment().getValue("PATH").contains(config.payloadBinDir().absolutePath))
    }

    @Test
    fun itExposesTheFixedOfficialToolRoutesAndNoSecondRuntime() {
        val assembly = DshTerminalAssembly(config, File(root, "native").apply { mkdirs() })
        val table = assembly.routes()
        assertEquals(DshTerminalToolRoutes.PATHS.sorted(), table.paths())
        assertEquals(DshTerminalToolRoutes.PATHS.size, DshTerminalToolRoutes.PATHS.distinct().size)
        assertEquals("dsh", assembly.service.owner())
    }

    @Test
    fun aJvmWithoutTheNativeLibraryReportsAnHonestCapabilityAndNothingToStop() {
        val assembly = DshTerminalAssembly(config, File(root, "native").apply { mkdirs() })
        // The JVM cannot load libdshpty.so; the capability must be typed, never "real".
        val capability = assembly.service.capability()
        if (capability is com.muyuchat.core.dsh.contract.runtime.DshTerminalCapability.RealPty) {
            // A host JVM that really has the library may report it; transport must still be real.
            assertEquals(DshTerminalTransport.REAL_PTY, capability.transport)
        } else {
            assertTrue(capability.transport == DshTerminalTransport.PIPE_FALLBACK)
        }
        val shutdown = assembly.stop()
        assertTrue(shutdown.settled)
        assertTrue(shutdown.sessions.isEmpty())
        assertTrue(assembly.diagnostics().contains("transport="))
    }
}
