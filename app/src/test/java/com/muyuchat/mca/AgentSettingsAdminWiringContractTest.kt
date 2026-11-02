package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static wiring contract for the Agent settings admin adapters.
 *
 * It pins that the activity passes the real tool-package/memory adapters and the host document
 * picker into `AgentWorkspaceRoute`, and that the view model exposes those adapters over the app
 * stores, so the feature ports no longer silently fall back to `Unavailable`. Runtime behavior
 * (Room query, file index read, SAF picker, Compose rendering) still needs a build/device run and is
 * not claimed here.
 */
class AgentSettingsAdminWiringContractTest {

    @Test
    fun `main activity passes the real tool package and memory adapters`() {
        val activity = source("app/src/main/java/com/muyuchat/mca/MainActivity.kt")
        val routeCall = activity.substringAfter("AgentWorkspaceRoute(").substringBefore(")")

        assertTrue(routeCall.contains("toolPackageAdmin = viewModel.agentWorkspacePorts.toolPackageAdmin"))
        assertTrue(routeCall.contains("memoryAdmin = viewModel.agentWorkspacePorts.memoryAdmin"))
    }

    @Test
    fun `the tool package preview uses the host document picker`() {
        val activity = source("app/src/main/java/com/muyuchat/mca/MainActivity.kt")

        assertTrue(activity.contains("documentLauncher = rememberAgentDocumentLauncher()"))
    }

    @Test
    fun `main activity passes the real recipe schedule adapter`() {
        val activity = source("app/src/main/java/com/muyuchat/mca/MainActivity.kt")
        val routeCall = activity.substringAfter("AgentWorkspaceRoute(").substringBefore(")")

        assertTrue(routeCall.contains("workflowSchedule = viewModel.agentWorkspacePorts.workflowSchedule"))

        val ports = source("app/src/main/java/com/muyuchat/mca/MainViewModelAgentPorts.kt")
        assertTrue(ports.contains("host.workflowScheduleAdminService()"))

        // The adapter is built over the one schedule writer and freezes the loaded model.
        val assembly = source("app/src/main/java/com/muyuchat/mca/composition/AgentHostAssembly.kt")
        assertTrue(assembly.contains("fun workflowScheduleAdminService(): AgentWorkflowScheduleService"))
        assertTrue(assembly.contains("schedules = workflowScheduleService()"))
        assertTrue(assembly.contains("activeBinding = { activeLocalModelBinding() }"))
    }

    @Test
    fun `main view model exposes adapters over the app-owned stores`() {
        val viewModel = source("app/src/main/java/com/muyuchat/mca/MainViewModelAgentPorts.kt")

        assertTrue(viewModel.contains("val memoryAdmin by lazy"))
        assertTrue(viewModel.contains("AgentMemoryAdminService.create(application)"))
        assertTrue(viewModel.contains("val toolPackageAdmin by lazy"))
        assertTrue(
            viewModel.contains(
                "AgentToolPackageAdminService.create(application, host.toolPackageLifecycle())"
            )
        )
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
}
