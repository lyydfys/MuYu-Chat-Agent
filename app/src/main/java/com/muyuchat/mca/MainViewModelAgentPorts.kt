package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.mca.agent.inference.AgentNativeToolEvidenceStore
import com.muyuchat.mca.composition.AgentHostAssembly
import com.muyuchat.mca.presentation.AgentMemoryAdminService
import com.muyuchat.mca.presentation.AgentModelCatalogAdapter
import com.muyuchat.mca.presentation.AgentToolPackageAdminService
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/** Lazy app-facing adapters for the one process-owned Agent host. */
internal class MainViewModelAgentPorts(
    application: Application,
    private val host: AgentHostAssembly,
    engine: McaInferenceService,
    state: StateFlow<MainUiState>
) {
    val runtime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { host.runtime() }
    val packageActions by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { host.packageService() }
    val skillAdmin by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { host.skillAdminService() }
    val mcpAdmin by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { host.mcpAdminService() }
    val workflowAdmin by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { host.workflowAdminService() }
    val workflowSchedule by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { host.workflowScheduleAdminService() }
    val memoryAdmin by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AgentMemoryAdminService.create(application) }
    val toolPackageAdmin by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AgentToolPackageAdminService.create(application, host.toolPackageLifecycle())
    }
    val modelCatalog by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AgentModelCatalogAdapter(
            engine = engine,
            state = state,
            includeCloudModels = host.cloudModelRoutingWired,
            nativeToolEvidence = AgentNativeToolEvidenceStore(File(application.filesDir, "agent"))
        )
    }
}
