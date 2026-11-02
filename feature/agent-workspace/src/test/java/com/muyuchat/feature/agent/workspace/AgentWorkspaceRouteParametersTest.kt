package com.muyuchat.feature.agent.workspace

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level contract for the two Agent settings admin ports that the host now supplies.
 *
 * `src/test` has no Compose host, so these assertions pin the route -> factory -> view model ->
 * settings-binding parameter path as text. Runtime wiring is covered by the app-side adapter tests
 * and remains a device/build item, not a claim made here.
 */
class AgentWorkspaceRouteParametersTest {

    @Test
    fun `route accepts both admin ports and forwards them to the view model factory`() {
        val route = source(ROUTE)

        assertTrue(route.contains("toolPackageAdmin: AgentToolPackageAdminPort"))
        assertTrue(route.contains("memoryAdmin: AgentMemoryAdminPort"))
        assertTrue(route.contains("AgentToolPackageAdminPort.Unavailable"))
        assertTrue(route.contains("AgentMemoryAdminPort.Unavailable"))
        assertTrue(route.contains("toolPackageAdmin = toolPackageAdmin"))
        assertTrue(route.contains("memoryAdmin = memoryAdmin"))
    }

    @Test
    fun `factory hands both ports to the view model`() {
        val factory = source(FACTORY)

        assertTrue(factory.contains("toolPackageAdmin: AgentToolPackageAdminPort"))
        assertTrue(factory.contains("memoryAdmin: AgentMemoryAdminPort"))
        assertTrue(factory.contains("toolPackageAdmin = toolPackageAdmin"))
        assertTrue(factory.contains("memoryAdmin = memoryAdmin"))
    }

    @Test
    fun `view model binds both ports into the settings surface`() {
        val viewModel = source(VIEW_MODEL)

        assertTrue(viewModel.contains("toolPackageAdmin: AgentToolPackageAdminPort"))
        assertTrue(viewModel.contains("memoryAdmin: AgentMemoryAdminPort"))
        assertTrue(viewModel.contains("toolPackages = toolPackageAdmin"))
        assertTrue(viewModel.contains("memory = memoryAdmin"))
    }

    private fun source(relativePath: String): String =
        File(projectRoot(), relativePath).readText(Charsets.UTF_8)

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("project root not found from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val BASE = "feature/agent-workspace/src/main/java/com/muyuchat/feature/agent/workspace"
        const val ROUTE = "$BASE/AgentWorkspaceRoute.kt"
        const val FACTORY = "$BASE/AgentWorkspaceViewModelFactory.kt"
        const val VIEW_MODEL = "$BASE/AgentWorkspaceViewModel.kt"
    }
}
