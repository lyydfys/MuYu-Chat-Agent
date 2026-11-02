package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentFileResult
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.mca.agent.data.AgentWorkspaceStore
import com.muyuchat.mca.agent.data.FakeArtifactLedger
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Closed loop for the third-party translation: a fixed third-party-style CommonJS package is translated,
 * installed into a real temp package store, enabled, then executed through the **real Rhino
 * engine**, the real [ToolPackageHostBridge]/[WorkspaceToolPackageHostHandler] and a real
 * [AgentWorkspaceStore] file port. No fake engine/executor is used.
 *
 * The nested artifact write is still gated by the production per-call
 * [ToolPackageHostApprovalPort]: the approving case records the exact [ToolPackageHostCallBinding],
 * and the denying case leaves zero file behind.
 */
class ThirdPartyTranslationClosedLoopTest {

    @get:Rule
    val temporary: TemporaryFolder = TemporaryFolder()

    private val workspaceId = "workspace-closed-loop"
    private val taskId = "task-closed-loop"
    private val runId = "run-closed-loop"

    private class RecordingApprovals(
        private val deny: Boolean = false
    ) : ToolPackageHostApprovalPort {
        val requests = mutableListOf<ToolPackageHostCallBinding>()

        override suspend fun approve(binding: ToolPackageHostCallBinding): ToolPackageHostApprovalDecision {
            requests += binding
            return if (deny) {
                ToolPackageHostApprovalDecision.Refused(
                    ToolPackageHostApprovalRefusal.DENIED,
                    "The test approval owner denied this host call."
                )
            } else {
                ToolPackageHostApprovalDecision.Approved(
                    approvalId = "approval-closed-loop",
                    bindingId = binding.bindingId,
                    revocation = binding.revocation
                )
            }
        }
    }

    private class Harness(
        val taskRoot: File,
        val workspace: AgentWorkspaceStore,
        val service: ToolPackageLifecycleService,
        val approvals: RecordingApprovals
    )

    private suspend fun harness(denyWrite: Boolean): Harness {
        val ledger = FakeArtifactLedger()
        val workspace = AgentWorkspaceStore(temporary.newFolder("agent-${System.nanoTime()}"), ledger)
        val taskRoot = workspace.ensureTaskRoots(workspaceId, taskId)
        File(taskRoot, "inputs").mkdirs()
        File(taskRoot, "inputs/source.txt").writeText(" seed \n")
        val approvals = RecordingApprovals(deny = denyWrite)
        val store = ToolPackageInstallStore(
            root = temporary.newFolder("packages-${System.nanoTime()}"),
            writeCapabilityPolicy = ToolPackageWriteCapabilityPolicy.ALLOW_WITH_PER_CALL_APPROVAL
        )
        val service = ToolPackageLifecycleService(
            store = store,
            runtime = ToolPackageScriptRuntime(
                RhinoToolPackageScriptEngine(),
                WorkspaceToolPackageHostHandler(workspace)
            ),
            approval = approvals,
            writeCapabilityPolicy = ToolPackageWriteCapabilityPolicy.ALLOW_WITH_PER_CALL_APPROVAL
        )
        return Harness(taskRoot, workspace, service, approvals)
    }

    private fun commandFor(descriptor: com.muyuchat.core.agent.contract.AgentToolDescriptor): FrozenToolCommand =
        FrozenToolCommand(
            invocationId = "inv-closed-loop",
            runId = runId,
            stepId = "step-closed-loop",
            callOrdinal = 0,
            toolKey = descriptor.toolKey,
            toolVersion = descriptor.version,
            schemaHash = descriptor.schemaHash,
            normalizedArgumentsJson = """{"content":"hello","outPath":"artifacts/report.txt","path":"inputs/source.txt"}""",
            argumentsHash = "arguments-hash",
            resourceFingerprint = "resource-fingerprint",
            runEpoch = 1L,
            policyVersion = 1L,
            expiresAtMillis = Long.MAX_VALUE
        )

    private fun context(): AgentToolContext = AgentToolContext(
        taskId = taskId,
        workspaceId = workspaceId,
        runId = runId,
        stepIndex = 0,
        runEpoch = 1L,
        policyVersion = 1L,
        workspaceReadGranted = true
    )

    @Test
    fun `translated third-party sample runs on the real Rhino engine and writes through approved host calls`() = runBlocking {
        val harness = harness(denyWrite = false)
        val installed = harness.service.installThirdPartyArchive(ThirdPartyTranslationFixtures.archive())
        assertTrue("expected install, was $installed", installed is ThirdPartyTranslationInstallResult.Installed)
        val translated = (installed as ThirdPartyTranslationInstallResult.Installed).translation
        assertEquals(listOf("write_report"), translated.toolNames)
        assertEquals(
            listOf(ToolPackageHostApis.ARTIFACT_WRITE_TEXT, ToolPackageHostApis.WORKSPACE_READ_TEXT),
            translated.mappedHostApis
        )
        assertTrue(harness.service.enable(translated.mcaPackageId) is ToolPackageLifecycleOutcome.Applied)

        val executor = harness.service.enabledExecutors().single()
        val result = executor.execute(commandFor(executor.descriptor), context())

        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        // Committed artifacts live at immutable `artifacts/.versions/...` blobs; the logical path
        // exists only in the artifact catalog. Read through the real port so this proves the
        // committed content and the physical backing, not a plain directory file.
        val readResult = harness.workspace.readText(workspaceId, taskId, "artifacts/report.txt", 0L, 64)
        assertTrue("expected a committed artifact, was $readResult", readResult is AgentFileResult.Ok)
        assertEquals("seed|HELLO", (readResult as AgentFileResult.Ok).value.content)
        assertEquals(
            1,
            File(harness.taskRoot, "artifacts/.versions").listFiles().orEmpty().count { it.isFile }
        )
        assertTrue(result.dataJson.orEmpty().contains("seed|HELLO"))
        assertTrue(result.dataJson.orEmpty().contains("artifactId"))
        assertEquals(1, harness.approvals.requests.size)
        val binding = harness.approvals.requests.single()
        assertEquals(ToolPackageHostApis.ARTIFACT_WRITE_TEXT, binding.method)
        assertEquals(ToolPackageHostAccess.WRITE, binding.access)
        assertEquals(translated.mcaPackageId, binding.packageId)
        assertEquals(runId, binding.runId)
        assertTrue(binding.stableCallOrdinal >= 1)
        assertEquals(
            "artifacts/report.txt",
            (binding.resource as ToolPackageHostResource.Workspace).relativePath
        )

        // The disabled package contributes no executor for the next run and uninstall leaves nothing.
        assertTrue(harness.service.disable(translated.mcaPackageId) is ToolPackageLifecycleOutcome.Applied)
        assertTrue(harness.service.enabledExecutors().isEmpty())
        harness.service.uninstall(translated.mcaPackageId)
        assertTrue(harness.service.list().isEmpty())
    }

    @Test
    fun `a denied nested write produces zero external effect`() = runBlocking {
        val harness = harness(denyWrite = true)
        val installed = harness.service.installThirdPartyArchive(ThirdPartyTranslationFixtures.archive())
        val translated = (installed as ThirdPartyTranslationInstallResult.Installed).translation
        assertTrue(harness.service.enable(translated.mcaPackageId) is ToolPackageLifecycleOutcome.Applied)
        val executor = harness.service.enabledExecutors().single()

        val result = executor.execute(commandFor(executor.descriptor), context())

        assertEquals(AgentToolResultStatus.DENIED, result.status)
        val readResult = harness.workspace.readText(workspaceId, taskId, "artifacts/report.txt", 0L, 64)
        assertTrue("denied write must not commit an artifact, was $readResult", readResult is AgentFileResult.Failure)
        assertEquals(0, File(harness.taskRoot, "artifacts/.versions").listFiles().orEmpty().size)
        assertEquals(1, harness.approvals.requests.size)
        assertEquals(ToolPackageHostAccess.WRITE, harness.approvals.requests.single().access)
    }

    @Test
    fun `the default REFUSE policy keeps a translated write package from being enabled`() = runBlocking {
        val store = ToolPackageInstallStore(temporary.newFolder("refuse-packages"))
        val service = ToolPackageLifecycleService(
            store = store,
            runtime = ToolPackageScriptRuntime(RhinoToolPackageScriptEngine(), denyToolPackageHostHandler()),
            approval = ToolPackageHostApprovalPort.DENY_ALL
        )
        val installed = service.installThirdPartyArchive(ThirdPartyTranslationFixtures.archive())
        assertTrue(installed is ThirdPartyTranslationInstallResult.Installed)
        val packageId = (installed as ThirdPartyTranslationInstallResult.Installed).translation.mcaPackageId
        val enabled = service.enable(packageId) as ToolPackageLifecycleOutcome.Rejected
        assertEquals("TOOLPACK_WRITE_UNSUPPORTED", enabled.code)
        assertTrue(service.enabledExecutors().isEmpty())
    }
}
