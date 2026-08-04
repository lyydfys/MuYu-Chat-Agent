package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelSource
import com.muyuchat.core.modelstore.QairtExecutionAdmission
import com.muyuchat.core.modelstore.QairtExecutionAdmissionMode
import com.muyuchat.core.modelstore.QairtGraphRiskLevel
import com.muyuchat.core.telemetry.SocFamily
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeIdentityFactoryTest {
    @Test
    fun validManifestShaIsPreferredWithoutRehashingTheMainArtifact() {
        val root = tempDirectory()
        try {
            val modelFile = File(root, "model.gguf").apply { writeText("AAAA") }
            val manifestSha = "a".repeat(64)
            val model = manifest(modelFile, sha256 = manifestSha)
            val first = build(model).identity

            // A persisted, verified manifest SHA remains the artifact identity;
            // changing the file forces the model-store verifier to repair the
            // manifest, but this factory must not scan a multi-GB GGUF again.
            modelFile.writeText("BBBB")
            val second = build(model).identity
            assertEquals(manifestSha, first.artifactFingerprint)
            assertEquals(first.identityHash, second.identityHash)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fallbackDirectoryMerkleUsesRelativePathAndContentNotMtimeOrLength() {
        val root = tempDirectory()
        try {
            val bundle = File(root, "bundle").apply { mkdirs() }
            val component = File(bundle, "nested/a.bin").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("AAAA")
            }
            val model = manifest(bundle, sha256 = "")
            val first = build(model).identity
            component.setLastModified(component.lastModified() + 60_000L)
            val touched = build(model).identity
            assertEquals(first.artifactFingerprint, touched.artifactFingerprint)

            component.writeText("BBBB") // same length, different content
            val changedContent = build(model).identity
            assertNotEquals(first.artifactFingerprint, changedContent.artifactFingerprint)

            val renamed = File(component.parentFile, "z.bin")
            assertTrue(component.renameTo(renamed))
            val changedPath = build(model).identity
            assertNotEquals(changedContent.artifactFingerprint, changedPath.artifactFingerprint)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun tokenizerTemplateAndConfigFingerprintsAreIndependentMaterial() {
        val root = tempDirectory()
        try {
            val bundle = File(root, "mnn").apply { mkdirs() }
            File(bundle, "config.json").writeText("{\"model\":\"mnn\"}")
            File(bundle, "tokenizer.json").writeText("{\"vocab\":1}")
            File(bundle, "chat_template.jinja").writeText("{{ messages }}")
            File(bundle, "llm.mnn").writeText("weights")
            val model = manifest(bundle, runtime = ChatModelRuntime.MNN)

            val first = build(model)
            assertTrue(first.configFingerprint.isNotBlank())
            assertTrue(first.identity.tokenizerFingerprint.isNotBlank())
            assertTrue(first.identity.templateFingerprint.isNotBlank())
            assertNotEquals(first.configFingerprint, first.identity.tokenizerFingerprint)
            assertNotEquals(first.configFingerprint, first.identity.templateFingerprint)

            File(bundle, "tokenizer.json").writeText("{\"vocab\":2}")
            val tokenizerChanged = build(model)
            assertNotEquals(first.identity.tokenizerFingerprint, tokenizerChanged.identity.tokenizerFingerprint)
            assertEquals(first.configFingerprint, tokenizerChanged.configFingerprint)
            assertEquals(first.identity.templateFingerprint, tokenizerChanged.identity.templateFingerprint)

            File(bundle, "config.json").writeText("{\"model\":\"mnn-v2\"}")
            val configChanged = build(model)
            assertNotEquals(first.configFingerprint, configChanged.configFingerprint)
            assertNotEquals(first.identity.backendFingerprint, configChanged.identity.backendFingerprint)
            assertEquals(tokenizerChanged.identity.templateFingerprint, configChanged.identity.templateFingerprint)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun generatedMnnRuntimeConfigDoesNotChangeIdentityAfterFirstLoad() {
        val root = tempDirectory()
        try {
            val bundle = File(root, "mnn").apply { mkdirs() }
            File(bundle, "config.json").writeText(
                "{\"llm_config\":\"llm_config.json\",\"prompt_template\":" +
                    "\"<|im_start|>user\\n%s<|im_end|>\\n<|im_start|>assistant\\n\"}"
            )
            File(bundle, "llm_config.json").writeText("""{"hidden_size":896}""")
            File(bundle, "llm.mnn").writeText("weights")
            val model = manifest(
                path = bundle,
                sha256 = "a".repeat(64),
                runtime = ChatModelRuntime.MNN
            )

            val beforeFirstLoad = build(model)
            File(bundle, "mca_runtime_config.json").writeText(
                "{\"jinja\":{\"chat_template\":\"generated compatibility template\"}}"
            )
            val afterFirstLoad = build(model)

            assertEquals(beforeFirstLoad.configFingerprint, afterFirstLoad.configFingerprint)
            assertEquals(beforeFirstLoad.identity.identityHash, afterFirstLoad.identity.identityHash)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deviceIdentityIgnoresVolatileMeasurementsButTracksStableCapabilities() {
        val root = tempDirectory()
        try {
            val modelFile = File(root, "model.gguf").apply { writeText("model") }
            val model = manifest(modelFile, sha256 = "")
            val stable = device()
            val volatileOnly = stable.copy(
                availableRamBytes = 1L,
                storageFreeBytes = 1L,
                thermalStatus = com.muyuchat.core.deviceprofile.ThermalStatus.Critical,
                batteryPercent = 2,
                isCharging = false,
                batteryTemperatureC = 49f,
                procMemAvailableBytes = 1L,
                modelMemoryBudgetBytes = 1L
            )
            assertEquals(
                build(model, device = stable).identity.identityHash,
                build(model, device = volatileOnly).identity.identityHash
            )

            val differentRam = stable.copy(totalRamBytes = stable.totalRamBytes + 1L)
            assertNotEquals(
                build(model, device = stable).identity.identityHash,
                build(model, device = differentRam).identity.identityHash
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nativeLibraryFingerprintChangesWhenTheLoadedSoChanges() {
        val root = tempDirectory()
        try {
            val modelFile = File(root, "model.gguf").apply { writeText("model") }
            val native = File(root, "native").apply { mkdirs() }
            File(native, "libmca_native.so").writeBytes(byteArrayOf(1, 2, 3, 4))
            val model = manifest(modelFile, sha256 = "")
            val platform = RuntimeIdentityFactory.PlatformSnapshot("mca.test", "1", 1, native)
            val first = RuntimeIdentityFactory.buildForTesting(model, LocalChatRuntime.LLAMA_CPP, device(), "scope", platform)
            File(native, "libmca_native.so").writeBytes(byteArrayOf(4, 3, 2, 1))
            val second = RuntimeIdentityFactory.buildForTesting(model, LocalChatRuntime.LLAMA_CPP, device(), "scope", platform)
            assertNotEquals(first.identity.nativeLibrarySha256, second.identity.nativeLibrarySha256)
            assertNotEquals(first.identity.identityHash, second.identity.identityHash)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun runtimePolicyEvaluatorCapabilitiesAndProjectorAreExplicit() {
        val root = tempDirectory()
        try {
            val modelFile = File(root, "vision.gguf").apply { writeText("model") }
            val projector = File(root, "vision-mmproj.gguf").apply { writeText("projector") }
            val model = manifest(modelFile, sha256 = "").copy(
                visionProjectorPath = projector.absolutePath,
                visionProjectorSha256 = "b".repeat(64)
            )
            val result = RuntimeIdentityFactory.buildForTesting(
                model = model,
                runtime = LocalChatRuntime.LLAMA_CPP,
                device = device(),
                installationScopeId = "scope"
            )
            assertEquals("b".repeat(64), result.identity.projectorFingerprint)
            assertTrue(result.identity.runtimeVersion.contains("llama.cpp@f26efa02a77dc3660f94ac90efee59394f3bc74d"))
            assertEquals("runtime-parameters-v2-sparse-moe-mmap", result.identity.parameterPolicyVersion)
            assertTrue(result.identity.evaluatorFingerprint.length == 64)
            assertTrue("local_chat" in result.identity.capabilities)
            assertTrue("projector" in result.identity.capabilities)

            val qairtModel = manifest(File(root, "qairt").apply {
                mkdirs()
                File(this, "genie_config.json").writeText("{}")
                File(this, "context.bin").writeText("context")
            }, runtime = ChatModelRuntime.GENIEX_QAIRT)
            val unverified = RuntimeIdentityFactory.buildForTesting(
                model = qairtModel,
                runtime = LocalChatRuntime.GENIEX_QAIRT,
                device = device(),
                installationScopeId = "scope",
                qairtAdmissionPassed = false
            )
            val verified = RuntimeIdentityFactory.buildForTesting(
                model = qairtModel,
                runtime = LocalChatRuntime.GENIEX_QAIRT,
                device = device(),
                installationScopeId = "scope",
                qairtAdmission = QairtExecutionAdmission(
                    mode = QairtExecutionAdmissionMode.VERIFIED_ALLOW,
                    graphRisk = QairtGraphRiskLevel.LOW,
                    memoryAdvisory = null,
                    message = "verified"
                )
            )
            assertNotEquals(unverified.identity.identityHash, verified.identity.identityHash)
            assertTrue(verified.identity.capabilities.any { it.startsWith("qairt_admission:verified") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun verifiedSparseMoeOnTwelveGigabytesCarriesMmapAndMtpCapabilities() {
        val root = tempDirectory()
        try {
            val modelFile = File(root, "qwen36.gguf").apply { writeText("model") }
            val model = manifest(
                modelFile,
                sha256 = "1fb8a998362ebb5f7f3c8ece6d4803a74ba32211c751de2e76b81e3379fbf050"
            ).copy(
                displayName = "Qwen3.6-35B-A3B-APEX-MTP-I-Nano.gguf",
                fileName = "Qwen3.6-35B-A3B-APEX-MTP-I-Nano.gguf",
                architecture = "qwen35moe",
                sizeBytes = 11_686_646_144L
            )

            val result = build(model, device = device())

            assertTrue("sparse_moe" in result.identity.capabilities)
            assertTrue("sparse_moe_16gb_tier" in result.identity.capabilities)
            assertTrue("draft_mtp" in result.identity.capabilities)
            assertTrue("verified_q4_kv_cache" in result.identity.capabilities)

            val unverified = build(
                model.copy(sha256 = "2".repeat(64)),
                device = device(totalRamGiB = 16)
            )
            assertTrue("sparse_moe_16gb_tier" in unverified.identity.capabilities)
            assertFalse("draft_mtp" in unverified.identity.capabilities)
            assertFalse("verified_q4_kv_cache" in unverified.identity.capabilities)

            val aboveTier = build(
                model.copy(sha256 = "3".repeat(64)),
                device = device(totalRamGiB = 17)
            )
            assertFalse("sparse_moe_16gb_tier" in aboveTier.identity.capabilities)
            assertFalse("verified_q4_kv_cache" in aboveTier.identity.capabilities)

            val unknownRam = build(
                model.copy(sha256 = "4".repeat(64)),
                device = device(totalRamGiB = 0)
            )
            assertTrue("sparse_moe_16gb_tier" in unknownRam.identity.capabilities)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun build(
        model: ModelManifest,
        device: DeviceProfile = device()
    ): RuntimeIdentityFactory.BuildResult = RuntimeIdentityFactory.buildForTesting(
        model = model,
        runtime = when (model.runtime) {
            ChatModelRuntime.MNN -> LocalChatRuntime.MNN_CPU
            ChatModelRuntime.GENIEX_QAIRT -> LocalChatRuntime.GENIEX_QAIRT
            ChatModelRuntime.LLAMA_CPP -> LocalChatRuntime.LLAMA_CPP
        },
        device = device,
        installationScopeId = "test-installation"
    )

    private fun manifest(
        path: File,
        sha256: String = "",
        runtime: ChatModelRuntime = ChatModelRuntime.LLAMA_CPP
    ): ModelManifest = ModelManifest(
        id = "model-id",
        displayName = "Test model",
        path = path.absolutePath,
        runtime = runtime,
        source = ModelSource.LOCAL,
        fileName = path.name,
        sizeBytes = if (path.isFile) path.length() else 0L,
        sha256 = sha256
    )

    private fun device(totalRamGiB: Long = 12L): DeviceProfile = DeviceProfile(
        socManufacturer = "Qualcomm",
        socModel = "SM8750P",
        socFamily = SocFamily.Snapdragon,
        cpuCores = 8,
        estimatedBigCores = 4,
        totalRamBytes = totalRamGiB * GB,
        availableRamBytes = 8L * GB,
        storageFreeBytes = 64L * GB,
        androidApi = 35,
        thermalStatus = com.muyuchat.core.deviceprofile.ThermalStatus.None,
        batteryPercent = 90,
        isCharging = true,
        supportedAbis = listOf("arm64-v8a"),
        primaryAbi = "arm64-v8a"
    )

    private fun tempDirectory(): File = Files.createTempDirectory("runtime-identity-test").toFile()

    private companion object {
        const val GB = 1024L * 1024L * 1024L
    }
}
