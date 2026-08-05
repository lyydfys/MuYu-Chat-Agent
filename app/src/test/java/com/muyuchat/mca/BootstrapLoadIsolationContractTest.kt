package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapLoadIsolationContractTest {
    @Test
    fun bootstrapWorkerAllowsNullRollbackAndNeverUsesTheTuningPlanner() {
        val source = sourceFile("TuningProbeWorkerService.kt")
        val route = source.substring(
            source.indexOf("TuningProbeWorkerProtocol.ProbeKind.BOOTSTRAP_LOAD -> {")
                .also { assertTrue(it >= 0) },
            source.indexOf("profileStore.updateJournalStage(")
                .also { assertTrue(it >= 0) }
        )

        assertTrue(route.contains("rollbackTargetProfileId?.let"))
        assertFalse(route.contains("rollbackTargetProfileId ?:"))
        assertFalse(route.contains("TuningCandidateCanaryPlanner"))
        assertFalse(route.contains("CandidateIsolationPolicy"))
    }

    @Test
    fun bootstrapWorkerIsGenericToOrdinaryRuntimesAndFailsClosedOnLowMemory() {
        val source = sourceFile("TuningProbeWorkerService.kt")
        val bootstrap = source.substring(
            source.indexOf("if (request.probeKind == TuningProbeWorkerProtocol.ProbeKind.BOOTSTRAP_LOAD)")
                .also { assertTrue(it >= 0) },
            source.indexOf("val (candidate, plan) = requireNotNull(tuningProbe)")
                .also { assertTrue(it >= 0) }
        )

        assertTrue(bootstrap.contains("ChatModelRuntime.MNN"))
        assertTrue(bootstrap.contains("ChatModelRuntime.LLAMA_CPP"))
        assertTrue(bootstrap.contains("BootstrapLoadCanaryPolicy.matches"))
        assertTrue(bootstrap.contains("bootstrap.sequenceAfter <= bootstrap.sequenceBefore"))
        assertTrue(bootstrap.contains("\"low_memory\""))
        assertTrue(bootstrap.indexOf("\"low_memory\"") < bootstrap.indexOf("passed = violations.isEmpty()"))
        listOf("chipset", "deviceProfile", "certified", "allowlist", "whitelist").forEach { gate ->
            assertFalse(bootstrap.contains(gate, ignoreCase = true))
        }
    }

    @Test
    fun mainRunsNewBootstrapProofBeforeFormalLoadAndNeverRunsTheCanaryInProcess() {
        val source = sourceFile("MainViewModel.kt")
        val loadModel = source.substring(
            source.indexOf("fun loadModel(requestedModel: ModelManifest)")
                .also { assertTrue(it >= 0) },
            source.indexOf("fun verifyModel(model: ModelManifest)")
                .also { assertTrue(it >= 0) }
        )
        val worker = loadModel.indexOf(
            "probeKind = TuningProbeWorkerProtocol.ProbeKind.BOOTSTRAP_LOAD"
        )
        val workerGate = loadModel.indexOf("workerResult.passed &&")
        val formalLoad = loadModel.indexOf("val nativeLoad = engine.loadModel(")

        assertTrue(worker >= 0)
        assertTrue(workerGate > worker)
        assertTrue(formalLoad > workerGate)
        assertTrue(loadModel.contains("if (needsBootstrapCommit && ordinaryBootstrapRuntime)"))
        assertTrue(loadModel.contains("activeProfile.resolvedLoadSignature =="))
        assertTrue(loadModel.contains("activeProfile.committedExecutionSignature =="))
        assertTrue(loadModel.contains("PersistedProfileVerificationLevel.SAFE.name"))
        assertTrue(loadModel.contains("rejectStagedBootstrap(error, \"BOOTSTRAP_LOAD\")"))
        assertFalse(source.contains("runBootstrapCorrectnessCanary"))
    }

    @Test
    fun workerAndFormalLoadShareExactProfileMaterialization() {
        val worker = sourceFile("TuningProbeWorkerService.kt")
        val main = sourceFile("MainViewModel.kt")

        assertTrue(worker.contains("model.loadParamsForExecutionProfile(candidateProfile)"))
        assertTrue(main.contains("model.loadParamsForExecutionProfile(bootstrapProfile)"))
        assertTrue(worker.contains("profile.matchesExactParameterSignatures(snapshot)"))
        assertTrue(main.contains("bootstrapProfile.matchesExactParameterSignatures(formalSignatures)"))
    }

    @Test
    fun clientExactResponseMatchingIncludesProbeKind() {
        val client = sourceFile("TuningProbeWorkerClient.kt")

        assertTrue(client.contains("result.probeKind == request.probeKind"))
    }

    private fun sourceFile(name: String): String {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            sequenceOf(
                File(current, "app/src/main/java/com/muyuchat/mca/$name"),
                File(current, "src/main/java/com/muyuchat/mca/$name")
            ).firstOrNull(File::isFile)?.let { return it.readText(Charsets.UTF_8) }
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate $name from ${System.getProperty("user.dir")}")
    }
}
