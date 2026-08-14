package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.QnnRuntimeProbeState
import com.muyuchat.core.deviceprofile.QnnRuntimeStatus
import com.muyuchat.core.deviceprofile.ThermalStatus
import com.muyuchat.core.telemetry.SocFamily
import com.muyuchat.core.telemetry.SocInfo
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageQnnRunnerTest {
    @Test
    fun discoveredRuntimeOnUnknownDeviceFamilyStillReachesRealSmoke() {
        val bundle = qnnImageBundle()
        val runtime = readyRuntime()
        val acceleration = DeviceAccelerationAnalyzer.assess(
            soc = SocInfo("MediaTek", "Dimensity 9400", SocFamily.Dimensity),
            totalRamBytes = 16.gb,
            qnnRuntime = runtime
        )
        val device = DeviceProfile(
            socManufacturer = "MediaTek",
            socModel = "Dimensity 9400",
            socFamily = SocFamily.Dimensity,
            cpuCores = 8,
            estimatedBigCores = 4,
            totalRamBytes = 16.gb,
            availableRamBytes = 10.gb,
            storageFreeBytes = 64.gb,
            androidApi = 35,
            thermalStatus = ThermalStatus.None,
            batteryPercent = 80,
            isCharging = true,
            supportedAbis = listOf("arm64-v8a"),
            primaryAbi = "arm64-v8a",
            advertisedRamBytes = 16.gb,
            accelerationProfile = acceleration
        )

        val report = QnnHtpImageRunner(runnerReady = true).health(device, bundle)

        assertEquals(LocalImageQnnState.SMOKE_REQUIRED, report.state)
        assertFalse(report.npuActive)
    }

    @Test
    fun qnnImageBundleWithMissingRuntimeDiscoveryStillReachesRealSmoke() {
        val bundle = qnnImageBundle()
        val report = QnnHtpImageRunner(runnerReady = true).health(
            device = snapdragonElite(qnnReady = false),
            bundleRoot = bundle
        )

        assertEquals(LocalImageQnnState.SMOKE_REQUIRED, report.state)
        assertFalse(report.npuActive)
        assertTrue(report.message.contains("smoke", ignoreCase = true))
    }

    @Test
    fun qnnImageBundleWithFailedRuntimeDiscoveryStillReachesRealSmoke() {
        val bundle = qnnImageBundle()
        val report = QnnHtpImageRunner(runnerReady = true).health(
            device = snapdragonDevice("SM8750", failedRuntime("mock load failure")),
            bundleRoot = bundle
        )

        assertEquals(LocalImageQnnState.SMOKE_REQUIRED, report.state)
        assertFalse(report.npuActive)
        assertTrue(report.message.contains("smoke", ignoreCase = true))
    }

    @Test
    fun completeButUnprobedRuntimeStillReachesRealSmoke() {
        val bundle = qnnImageBundle()
        val report = QnnHtpImageRunner(runnerReady = true).health(
            device = snapdragonDevice("SM8750", unprobedCompleteRuntime()),
            bundleRoot = bundle
        )

        assertEquals(LocalImageQnnState.SMOKE_REQUIRED, report.state)
        assertFalse(report.npuActive)
    }

    @Test
    fun qnn228BundleRequiresExactV73RuntimeInsteadOfReadyGenericRuntime() {
        val bundle = qnnImageBundle(requiredRuntimeArch = 73)
        val report = QnnHtpImageRunner(runnerReady = true).health(
            device = snapdragonGen2(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalImageQnnState.QNN_RUNTIME_MISSING, report.state)
        assertFalse(report.npuActive)
        assertTrue(report.message.contains("骁龙 8 Gen 2 NPU 运行环境"))
        assertFalse(Regex("""(?i)SM\d{4}|HTP\s*V\d+|soc_model""").containsMatchIn(report.message))
    }

    @Test
    fun qnnImageBundleCanEnterSmokeWhenCdspRpcDiagnosticFails() {
        val bundle = qnnImageBundle()
        val report = QnnHtpImageRunner(runnerReady = true).health(
            device = snapdragonDevice(
                "SM8750",
                blockedTransportRuntime("library \"libhidlbase.so\" not found")
            ),
            bundleRoot = bundle
        )

        assertEquals(LocalImageQnnState.SMOKE_REQUIRED, report.state)
        assertFalse(report.npuActive)
        assertTrue(report.message.contains("smoke"))
    }

    @Test
    fun qnnImageBundleDoesNotBecomeActiveWhenRunnerIsNotPackaged() {
        val bundle = qnnImageBundle()
        val report = QnnHtpImageRunner(runnerReady = false).health(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalImageQnnState.RUNNER_NOT_PACKAGED, report.state)
        assertFalse(report.npuActive)
        assertTrue(report.message.contains("typed graph bindings"))
    }

    @Test
    fun qnnImageBundleRequiresSmokeBeforeActive() {
        val bundle = qnnImageBundle()
        val report = QnnHtpImageRunner(runnerReady = true).health(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalImageQnnState.SMOKE_REQUIRED, report.state)
        assertFalse(report.npuActive)
        assertEquals(384, report.smokeWidth)
        assertEquals(384, report.smokeHeight)
        assertEquals(1, report.smokeSteps)
    }

    @Test
    fun semanticQnnBundleWithoutManualGraphMetadataReachesRealSemanticGenerationGate() {
        val bundle = semanticQnnImageBundle()
        val bridge = RecordingImageSmokeBridge()
        val runner = QnnHtpImageRunner(runnerReady = true, smokeBridge = bridge)

        val health = runner.health(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )
        val smoke = runner.runSmoke(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalImageQnnState.SMOKE_REQUIRED, health.state)
        assertEquals(LocalImageQnnState.SMOKE_REQUIRED, smoke.state)
        assertFalse(health.npuActive)
        assertFalse(smoke.npuActive)
        assertTrue(health.message.contains("real QNN text encoder, UNet, and VAE graphs"))
        assertEquals(0, bridge.specs.size)
    }

    @Test
    fun semanticQnnBundleRequiresEveryNonEmptyNativeContext() {
        listOf("text_encoder.bin", "unet.bin", "vae.bin").forEach { missingName ->
            val missingBundle = semanticQnnImageBundle(missingName = missingName)
            val missingReport = QnnHtpImageRunner(runnerReady = true).health(
                device = snapdragonElite(qnnReady = true),
                bundleRoot = missingBundle
            )

            assertEquals(missingName, LocalImageQnnState.BUNDLE_INCOMPLETE, missingReport.state)
            assertFalse(missingName, missingReport.npuActive)
            assertTrue(missingName, missingReport.message.contains(missingName))

            val emptyBundle = semanticQnnImageBundle(emptyName = missingName)
            val emptyReport = QnnHtpImageRunner(runnerReady = true).health(
                device = snapdragonElite(qnnReady = true),
                bundleRoot = emptyBundle
            )

            assertEquals(missingName, LocalImageQnnState.BUNDLE_INCOMPLETE, emptyReport.state)
            assertFalse(missingName, emptyReport.npuActive)
            assertTrue(missingName, emptyReport.message.contains(missingName))
        }
    }

    @Test
    fun controlSemanticBundleRequiresConcreteControlNetGraph() {
        val root = Files.createTempDirectory("mca-qnn-control-contract").toFile()
        try {
            listOf("text_encoder.bin", "unet.bin", "vae_decoder.bin").forEach { name ->
                File(root, name).writeBytes(byteArrayOf(1))
            }

            assertEquals(
                listOf("controlnet.bin"),
                qnnSemanticGraphBundleMissingComponents(root, requiresControlNet = true)
            )
            File(root, "controlnet.bin").writeBytes(byteArrayOf(1))
            assertTrue(hasCompleteQnnSemanticGraphBundle(root, requiresControlNet = true))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun validSmokeSuiteOverridesLegacyPipelineSmokeWithoutGraphMetadata() {
        val bundle = qnnImageBundle()
        val manifestFile = File(bundle, "manifest.json")
        val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
        manifest.put(
            "smoke",
            JSONObject()
                .put("width", 512)
                .put("height", 512)
                .put("steps", 4)
                .put("timeoutSeconds", 240)
                .put("prompt", "a small ceramic cup on a bright wooden desk")
        )
        manifest.put(
            "smokes",
            JSONArray()
                .put(
                    JSONObject()
                        .put("graphName", "model")
                        .put("contextBinary", "diffusion/unet_context.bin")
                        .put(
                            "inputs",
                            JSONArray()
                                .put(JSONObject().put("name", "sample").put("dataType", "uint16").put("shape", JSONArray(listOf(1, 4, 64, 64))))
                                .put(JSONObject().put("name", "timestamp").put("dataType", "int32").put("shape", JSONArray(listOf(1))))
                                .put(JSONObject().put("name", "text_embedding").put("dataType", "uint16").put("shape", JSONArray(listOf(1, 77, 768))))
                        )
                        .put(
                            "outputs",
                            JSONArray().put(
                                JSONObject().put("name", "output").put("dataType", "uint16").put("shape", JSONArray(listOf(1, 4, 64, 64)))
                            )
                        )
                )
                .put(
                    JSONObject()
                        .put("graphName", "model")
                        .put("contextBinary", "vae/vae_decoder_context.bin")
                        .put(
                            "inputs",
                            JSONArray().put(
                                JSONObject().put("name", "input").put("dataType", "uint16").put("shape", JSONArray(listOf(1, 4, 64, 64)))
                            )
                        )
                        .put(
                            "outputs",
                            JSONArray().put(
                                JSONObject().put("name", "output").put("dataType", "uint16").put("shape", JSONArray(listOf(1, 3, 512, 512)))
                            )
                        )
                )
        )
        manifestFile.writeText(manifest.toString(), Charsets.UTF_8)

        val harnessSpec = qnnGraphSmokeSpecForHarness(bundle)
        val health = QnnHtpImageRunner(runnerReady = true).health(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )
        val smoke = QnnHtpImageRunner(
            runnerReady = true,
            forcedSmokePassed = true
        ).runSmoke(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals("model", harnessSpec.graphName)
        assertEquals("diffusion/unet_context.bin", harnessSpec.contextBinary)
        assertEquals(LocalImageQnnState.SMOKE_REQUIRED, health.state)
        assertEquals(512, health.smokeWidth)
        assertEquals(512, health.smokeHeight)
        assertEquals(4, health.smokeSteps)
        assertEquals(LocalImageQnnState.NPU_ACTIVE, smoke.state)
        assertTrue(smoke.npuActive)
    }

    @Test
    fun failedSmokeKeepsQnnImageInactive() {
        val bundle = qnnImageBundle()
        val report = QnnHtpImageRunner(
            runnerReady = true,
            forcedSmokePassed = false
        ).runSmoke(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalImageQnnState.SMOKE_FAILED, report.state)
        assertFalse(report.npuActive)
    }

    @Test
    fun invalidQnnImageSmokeMetadataStaysInactiveBeforeNativeBridge() {
        val bundle = qnnImageBundle(smokeOverrides = """"contextBinary": "../bad_context.bin"""")
        val report = QnnHtpImageRunner(runnerReady = true).runSmoke(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalImageQnnState.SMOKE_METADATA_INVALID, report.state)
        assertFalse(report.npuActive)
        assertTrue(report.message.contains("escapes the bundle root"))
        assertTrue(report.message.contains("../bad_context.bin"))
    }

    @Test
    fun missingQnnImageContextBinaryStaysInactiveBeforeNativeBridge() {
        val bundle = qnnImageBundle(contextBinary = "diffusion/missing_context.bin")
        val report = QnnHtpImageRunner(runnerReady = true).runSmoke(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalImageQnnState.BUNDLE_INCOMPLETE, report.state)
        assertFalse(report.npuActive)
        assertTrue(report.message.contains("diffusion/missing_context.bin"))
    }

    @Test
    fun emptyQnnImageContextBinaryStaysInactiveBeforeNativeBridge() {
        val bundle = qnnImageBundle(contextBinaryContent = "")
        val report = QnnHtpImageRunner(runnerReady = true).runSmoke(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalImageQnnState.BUNDLE_INCOMPLETE, report.state)
        assertFalse(report.npuActive)
        assertTrue(report.message.contains("diffusion/unet_context.bin"))
    }

    @Test
    fun nativeBridgeWithoutGraphExecutionKeepsQnnImageInactive() {
        val bundle = qnnImageBundle()
        val report = QnnHtpImageRunner(
            runnerReady = true,
            smokeBridge = FakeImageSmokeBridge(
                NativeQnnSmokeResult(
                    message = "graph runner pending",
                    runnerReady = true,
                    graphRunnerReady = false,
                    graphExecute = false,
                    npuActive = false,
                    smokePassed = false,
                    elapsedMs = 42,
                    graphMetadataReady = true,
                    qnnInterfacePresent = true,
                    sdkHeadersPresent = true,
                    typedGraphBindingsCompiled = true,
                    smokeInputCount = 2,
                    smokeOutputCount = 1,
                    tensorBufferPlanReady = true,
                    inputBufferBytes = 65_540,
                    outputBufferBytes = 65_536,
                    executionStage = "graph_execution_unimplemented",
                    runtimeLoaded = true,
                    qnnInterfaceFound = true,
                    bundleManifestFound = true,
                    bundleGraphArtifactFound = true,
                    smokeMetadataComplete = true,
                    sdkHeadersCompiled = true,
                    inputTensors = listOf(
                        QnnTensorBufferDiagnostics(
                            name = "latent",
                            role = "input",
                            dataType = "float32",
                            shape = listOf(1, 4, 64, 64),
                            elementCount = 16_384,
                            bytesPerElement = 4,
                            byteSize = 65_536,
                            supported = true
                        ),
                        QnnTensorBufferDiagnostics(
                            name = "timestep",
                            role = "input",
                            dataType = "int32",
                            shape = listOf(1),
                            elementCount = 1,
                            bytesPerElement = 4,
                            byteSize = 4,
                            supported = true
                        )
                    ),
                    outputTensors = listOf(
                        QnnTensorBufferDiagnostics(
                            name = "noise_pred",
                            role = "output",
                            dataType = "float32",
                            shape = listOf(1, 4, 64, 64),
                            elementCount = 16_384,
                            bytesPerElement = 4,
                            byteSize = 65_536,
                            supported = true
                        )
                    )
                )
            )
        ).runSmoke(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalImageQnnState.SMOKE_FAILED, report.state)
        assertFalse(report.npuActive)
        assertFalse(report.graphExecute)
        assertEquals(42, report.smokeElapsedMs)
        assertEquals("graph_execution_unimplemented", report.qnnDiagnostics.executionStage)
        assertTrue(report.qnnDiagnostics.graphMetadataReady)
        assertTrue(report.qnnDiagnostics.tensorBufferPlanReady)
        assertEquals(2, report.qnnDiagnostics.smokeInputCount)
        assertEquals(1, report.qnnDiagnostics.smokeOutputCount)
        assertTrue(report.qnnDiagnostics.allTensorsBindable)
        assertEquals("latent", report.qnnDiagnostics.inputTensors.first().name)
        assertEquals(65_536L, report.qnnDiagnostics.inputTensors.first().byteSize)
        assertFalse(report.qnnDiagnostics.graphExecuted)
        assertEquals(
            "graph_execution_unimplemented",
            report.toJson().getJSONObject("qnnDiagnostics").getString("executionStage")
        )
        assertEquals(
            "noise_pred",
            report.toJson()
                .getJSONObject("qnnDiagnostics")
                .getJSONArray("outputTensors")
                .getJSONObject(0)
                .getString("name")
        )
    }

    @Test
    fun forwardCompatibleSm8550ContextStillReportsNativeFailureOnSm8750() {
        val bundle = qnnImageBundle()
        val report = QnnHtpImageRunner(
            runnerReady = true,
            smokeBridge = FakeImageSmokeBridge(
                NativeQnnSmokeResult(
                    message = "QNN contextCreateFromBinary failed: invalid config",
                    runnerReady = true,
                    graphRunnerReady = false,
                    graphExecute = false,
                    npuActive = false,
                    smokePassed = false,
                    elapsedMs = 121,
                    graphMetadataReady = true,
                    executionStage = "context_load_failed",
                    runtimeLoaded = true,
                    qnnInterfaceFound = true,
                    backendCreated = true,
                    contextLoaded = false,
                    binaryMetadata = QnnBinaryMetadataDiagnostics(
                        attempted = true,
                        parsed = true,
                        version = 3,
                        buildId = "v2.28.0.241029232508_102474",
                        socModel = 43,
                        graphCount = 1,
                        graphNames = listOf("sd15_unet"),
                        message = "QNN context binary metadata parsed; socModel=43"
                    )
                )
            )
        ).runSmoke(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalImageQnnState.SMOKE_FAILED, report.state)
        assertFalse(report.npuActive)
        assertTrue(report.message.contains("invalid config"))
        assertEquals(43, report.qnnDiagnostics.binaryMetadata.socModel)
        assertEquals(
            43,
            report.toJson()
                .getJSONObject("qnnDiagnostics")
                .getJSONObject("binaryMetadata")
                .getInt("socModel")
        )
    }

    @Test
    fun forwardCompatibleSm8550ContextCanBecomeActiveOnSm8750AfterGraphExecution() {
        val bundle = qnnImageBundle()
        val report = QnnHtpImageRunner(
            runnerReady = true,
            smokeBridge = FakeImageSmokeBridge(
                NativeQnnSmokeResult(
                    message = "graph execute ok",
                    runnerReady = true,
                    graphRunnerReady = true,
                    graphExecute = true,
                    npuActive = true,
                    smokePassed = true,
                    elapsedMs = 7_100,
                    executionStage = "graph_execute_passed",
                    binaryMetadata = QnnBinaryMetadataDiagnostics(
                        attempted = true,
                        parsed = true,
                        version = 3,
                        socModel = 43,
                        graphCount = 1,
                        graphNames = listOf("sd15_unet")
                    )
                )
            )
        ).runSmoke(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalImageQnnState.NPU_ACTIVE, report.state)
        assertTrue(report.npuActive)
        assertTrue(report.smokePassed)
        assertTrue(report.graphExecute)
        assertTrue(report.qnnDiagnostics.graphExecuted)
        assertTrue(report.message.contains("通过"))
        assertEquals("qnn_graph", report.toJson().getString("executionMode"))
        assertFalse(report.toJson().getBoolean("fallback"))
    }

    @Test
    fun realGraphExecutionWinsOverStaticSocMetadataOnAnyDevice() {
        val bundle = qnnImageBundle()
        val report = QnnHtpImageRunner(
            runnerReady = true,
            smokeBridge = FakeImageSmokeBridge(
                NativeQnnSmokeResult(
                    message = "graph execute ok",
                    runnerReady = true,
                    graphRunnerReady = true,
                    graphExecute = true,
                    npuActive = true,
                    smokePassed = true,
                    elapsedMs = 7_100,
                    executionStage = "graph_execute_passed",
                    binaryMetadata = QnnBinaryMetadataDiagnostics(
                        attempted = true,
                        parsed = true,
                        version = 3,
                        socModel = 69,
                        graphCount = 1,
                        graphNames = listOf("sd15_unet")
                    )
                )
            )
        ).runSmoke(
            device = snapdragonGen2(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalImageQnnState.NPU_ACTIVE, report.state)
        assertTrue(report.npuActive)
        assertTrue(report.smokePassed)
        assertTrue(report.graphExecute)
        assertEquals("graph_execute_passed", report.qnnDiagnostics.executionStage)
    }

    @Test
    fun nativeBridgeMustProveGraphExecutionBeforeImageNpuActive() {
        val bundle = qnnImageBundle()
        val report = QnnHtpImageRunner(
            runnerReady = true,
            smokeBridge = FakeImageSmokeBridge(
                NativeQnnSmokeResult(
                    message = "graph execute ok",
                    runnerReady = true,
                    graphRunnerReady = true,
                    graphExecute = true,
                    npuActive = true,
                    smokePassed = true,
                    elapsedMs = 7_100
                )
            )
        ).runSmoke(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalImageQnnState.NPU_ACTIVE, report.state)
        assertTrue(report.npuActive)
        assertTrue(report.graphExecute)
        assertEquals(7_100, report.smokeElapsedMs)
    }

    @Test
    fun passedSmokeIsTheOnlyPathToQnnImageNpuActive() {
        val bundle = qnnImageBundle()
        val report = QnnHtpImageRunner(
            runnerReady = true,
            forcedSmokePassed = true,
            forcedSmokeElapsedMs = 12_500
        ).runSmoke(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalImageQnnState.NPU_ACTIVE, report.state)
        assertTrue(report.npuActive)
        assertTrue(report.smokePassed)
        assertTrue(report.graphExecute)
        assertEquals(12_500, report.smokeElapsedMs)
    }

    @Test
    fun incompleteQnnImageBundleStaysInactiveBeforeRunnerCheck() {
        val bundle = qnnImageBundle(includeVae = false)
        val report = QnnHtpImageRunner(runnerReady = true).health(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalImageQnnState.BUNDLE_INCOMPLETE, report.state)
        assertFalse(report.npuActive)
        assertTrue(report.message.contains("vae/vae_decoder_context.bin"))
    }

    @Test
    fun malformedOwnedManifestSchemaFailsInsteadOfFallingBackToLegacyDiscovery() {
        val cases = listOf(
            "execution-profile-type" to JSONObject()
                .put("executionProfile", "not-an-object"),
            "execution-profile-escape" to JSONObject()
                .put(
                    "executionProfile",
                    JSONObject().put(
                        "graph",
                        JSONObject()
                            .put("textEncoder", graphArtifact("clip_v2.mnn"))
                            .put("unet", graphArtifact("../unet.bin"))
                            .put("vae", graphArtifact("vae_decoder.bin"))
                    )
                ),
            "runtime-profile" to JSONObject()
                .put(
                    "requiredRuntimeProfile",
                    JSONObject()
                        .put("qnnSdk", "")
                        .put("htpArch", 73)
                ),
            "required-flag" to JSONObject()
                .put(
                    "components",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "DIFFUSION")
                            .put("path", "unet.bin")
                            .put("required", "true")
                    )
                )
        )
        cases.forEach { (name, mutation) ->
            val root = completeExtractedQnnBundle("malformed-$name")
            try {
                val manifest = baseSemanticManifest()
                mutation.keys().forEach { key -> manifest.put(key, mutation.get(key)) }
                File(root, "manifest.json").writeText(manifest.toString(2), Charsets.UTF_8)

                val report = QnnHtpImageRunner(runnerReady = true).health(
                    device = snapdragonElite(qnnReady = true),
                    bundleRoot = root
                )

                assertEquals(name, LocalImageQnnState.SMOKE_METADATA_INVALID, report.state)
                assertFalse(name, report.npuActive)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun deletedArchiveIsAcceptedOnlyWithACompleteExtractedRuntimeContract() {
        val complete = completeExtractedQnnBundle("archive-complete")
        try {
            File(complete, "manifest.json").writeText(
                baseSemanticManifest()
                    .put("components", archiveComponentArray())
                    .put("executionProfile", exactCommunityExecutionProfile())
                    .toString(2),
                Charsets.UTF_8
            )

            val ready = QnnHtpImageRunner(runnerReady = true).health(
                device = snapdragonElite(qnnReady = true),
                bundleRoot = complete
            )

            assertEquals(LocalImageQnnState.SMOKE_REQUIRED, ready.state)
            assertFalse(ready.npuActive)
        } finally {
            complete.deleteRecursively()
        }

        val incomplete = completeExtractedQnnBundle("archive-incomplete")
        try {
            File(incomplete, "pos_emb.bin").delete()
            File(incomplete, "manifest.json").writeText(
                baseSemanticManifest()
                    .put("components", archiveComponentArray())
                    .put("executionProfile", exactCommunityExecutionProfile())
                    .toString(2),
                Charsets.UTF_8
            )

            val rejected = QnnHtpImageRunner(runnerReady = true).health(
                device = snapdragonElite(qnnReady = true),
                bundleRoot = incomplete
            )

            assertEquals(LocalImageQnnState.SMOKE_METADATA_INVALID, rejected.state)
            assertTrue(rejected.message.contains("pos_emb.bin"))
        } finally {
            incomplete.deleteRecursively()
        }
    }

    @Test
    fun legacyCommunityClipBundleWithoutNewProfileMetadataStillReachesRealSmoke() {
        val root = completeExtractedQnnBundle("legacy-community-clip")
        try {
            File(root, "manifest.json").writeText(
                baseSemanticManifest()
                    .put(
                        "components",
                        JSONArray()
                            .put(requiredComponent("DIFFUSION", "unet.bin"))
                            .put(requiredComponent("VAE", "vae_decoder.bin"))
                            .put(requiredComponent("TEXT_ENCODER", "clip_v2.mnn"))
                    )
                    .toString(2),
                Charsets.UTF_8
            )

            val report = QnnHtpImageRunner(runnerReady = true).health(
                device = snapdragonElite(qnnReady = true),
                bundleRoot = root
            )

            assertEquals(LocalImageQnnState.SMOKE_REQUIRED, report.state)
            assertFalse(report.npuActive)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun undeclaredAndThirdPartyManifestsKeepLegacySemanticDiscoveryOpen() {
        listOf("absent", "third-party").forEach { mode ->
            val root = completeExtractedQnnBundle("legacy-$mode")
            try {
                if (mode == "third-party") {
                    File(root, "manifest.json").writeText(
                        JSONObject()
                            .put("schema", "publisher.image.bundle.v9")
                            .put(
                                "executionProfile",
                                JSONObject().put(
                                    "graph",
                                    JSONObject().put("unet", graphArtifact("../ignored.bin"))
                                )
                            )
                            .toString(2),
                        Charsets.UTF_8
                    )
                }

                val report = QnnHtpImageRunner(runnerReady = true).health(
                    device = snapdragonElite(qnnReady = true),
                    bundleRoot = root
                )

                assertEquals(mode, LocalImageQnnState.SMOKE_REQUIRED, report.state)
                assertFalse(mode, report.npuActive)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun minimumDeviceTierIsAdvisoryAndNeverBlocksTheRunPath() {
        val bundle = qnnImageBundle(minDeviceTier = "SNAPDRAGON_8_ELITE")
        val report = QnnHtpImageRunner(runnerReady = true).health(
            device = snapdragonGen2(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalImageQnnState.SMOKE_REQUIRED, report.state)
        assertFalse(report.npuActive)
    }

    private fun qnnImageBundle(
        includeVae: Boolean = true,
        minDeviceTier: String = "SNAPDRAGON_8_GEN2",
        smokeOverrides: String? = null,
        contextBinary: String = "diffusion/unet_context.bin",
        contextBinaryContent: String = "x",
        requiredRuntimeArch: Int? = null
    ): File {
        val root = Files.createTempDirectory("qnn-image-bundle").toFile()
        root.touch("diffusion/unet_context.bin", contextBinaryContent)
        if (includeVae) root.touch("vae/vae_decoder_context.bin")
        root.touch("text_encoder/clip_context.bin")
        root.touch("tokenizer/tokenizer.json")
        File(root, "manifest.json").writeText(
            """
            {
              "schema": "mca.image_engine.bundle.v1",
              "id": "sd15-qnn-min",
              "title": "SD1.5 QNN Min",
              "runtime": "QNN_HTP",
              "minDeviceTier": "$minDeviceTier",
              "family": "SD15",
              "requiresQnnRuntime": true,
              ${requiredRuntimeArch?.let { arch ->
                  """"requiredRuntimeProfile": {"qnnSdk": "2.28", "htpArch": $arch, "completeBundleRuntime": true},"""
              }.orEmpty()}
              "requiresSmokeTest": true,
              "components": [
                {"role": "DIFFUSION", "path": "diffusion/unet_context.bin"},
                {"role": "VAE", "path": "vae/vae_decoder_context.bin"},
                {"role": "TEXT_ENCODER", "path": "text_encoder/clip_context.bin"},
                {"role": "TOKENIZER", "path": "tokenizer/tokenizer.json"}
              ],
              "smoke": {
                "width": 384,
                "height": 384,
                "steps": 1,
                "timeoutSeconds": 180,
                "graphName": "sd15_unet",
                ${smokeOverrides ?: """"contextBinary": "$contextBinary""""},
                "inputs": [
                  {"name": "latent", "dataType": "float32", "shape": [1, 4, 48, 48]},
                  {"name": "timestep", "dataType": "int32", "shape": [1]},
                  {"name": "text_embeddings", "dataType": "float32", "shape": [1, 77, 768]}
                ],
                "outputs": [
                  {"name": "noise_pred", "dataType": "float32", "shape": [1, 4, 48, 48]}
                ]
              }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )
        return root
    }

    private fun semanticQnnImageBundle(
        missingName: String? = null,
        emptyName: String? = null
    ): File {
        val root = Files.createTempDirectory("qnn-semantic-image-bundle").toFile()
        listOf("text_encoder.bin", "unet.bin", "vae.bin").forEach { name ->
            if (name != missingName) {
                root.touch("graphs/$name", if (name == emptyName) "" else "context-$name")
            }
        }
        root.touch("tokenizer/vocab.json", "{}")
        root.touch("tokenizer/merges.txt", "#version: 0.2")
        File(root, "manifest.json").writeText(
            """
            {
              "schema": "mca.image_engine.bundle.v1",
              "id": "official-semantic-qnn",
              "title": "Official semantic QNN",
              "runtime": "QNN_HTP",
              "family": "SD15",
              "requiresQnnRuntime": true,
              "requiresSmokeTest": true,
              "components": [
                {"role": "DIFFUSION", "path": "graphs/unet.bin"},
                {"role": "VAE", "path": "graphs/vae.bin"},
                {"role": "TEXT_ENCODER", "path": "graphs/text_encoder.bin"}
              ],
              "smoke": {
                "width": 512,
                "height": 512,
                "steps": 1,
                "timeoutSeconds": 300
              }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )
        return root
    }

    private fun completeExtractedQnnBundle(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().apply {
            listOf(
                "unet.bin",
                "vae_decoder.bin",
                "vae_encoder.bin",
                "clip_v2.mnn",
                "tokenizer.json",
                "token_emb.bin",
                "pos_emb.bin"
            ).forEach { name -> touch(name) }
        }

    private fun baseSemanticManifest(): JSONObject = JSONObject()
        .put("schema", "mca.image_engine.bundle.v1")
        .put("id", "community-qnn-test")
        .put("runtime", "QNN_HTP")
        .put("family", "SD15")
        .put("requiresQnnRuntime", false)
        .put("requiresSmokeTest", true)

    private fun exactCommunityExecutionProfile(): JSONObject = JSONObject()
        .put(
            "graph",
            JSONObject()
                .put("textEncoder", graphArtifact("clip_v2.mnn"))
                .put("unet", graphArtifact("unet.bin"))
                .put("vae", graphArtifact("vae_decoder.bin"))
                .put("vaeEncoder", graphArtifact("vae_encoder.bin"))
                .put(
                    "configSidecars",
                    JSONArray(listOf("tokenizer.json", "token_emb.bin", "pos_emb.bin"))
                )
        )

    private fun graphArtifact(path: String): JSONObject =
        JSONObject().put("relativePath", path)

    private fun archiveComponentArray(): JSONArray = JSONArray().put(
        requiredComponent("DIFFUSION", "publisher-qnn-package.zip")
    )

    private fun requiredComponent(role: String, path: String): JSONObject = JSONObject()
        .put("role", role)
        .put("path", path)
        .put("required", true)

    private fun snapdragonElite(qnnReady: Boolean): DeviceProfile =
        snapdragonDevice("SM8750", qnnReady)

    private fun snapdragonGen2(qnnReady: Boolean): DeviceProfile =
        snapdragonDevice("SM8550", qnnReady)

    private fun snapdragonDevice(chipsetCode: String, qnnReady: Boolean): DeviceProfile {
        val runtime = if (qnnReady) readyRuntime() else QnnRuntimeStatus.Missing
        return snapdragonDevice(chipsetCode, runtime)
    }

    private fun snapdragonDevice(chipsetCode: String, qnnRuntime: QnnRuntimeStatus): DeviceProfile {
        val acceleration = DeviceAccelerationAnalyzer.assess(
            soc = SocInfo("Qualcomm", chipsetCode, SocFamily.Snapdragon),
            totalRamBytes = 16.gb,
            qnnRuntime = qnnRuntime
        )
        return DeviceProfile(
            socManufacturer = "Qualcomm",
            socModel = chipsetCode,
            socFamily = SocFamily.Snapdragon,
            cpuCores = 8,
            estimatedBigCores = 4,
            totalRamBytes = 16.gb,
            availableRamBytes = 10.gb,
            storageFreeBytes = 64.gb,
            androidApi = 35,
            thermalStatus = ThermalStatus.None,
            batteryPercent = 80,
            isCharging = true,
            supportedAbis = listOf("arm64-v8a"),
            primaryAbi = "arm64-v8a",
            advertisedRamBytes = 16.gb,
            accelerationProfile = acceleration
        )
    }

    private fun readyRuntime(): QnnRuntimeStatus =
        QnnRuntimeStatus(
            qnnSystemLibraryPresent = true,
            qnnHtpLibraryPresent = true,
            htpSkelLibraryPresent = true,
            // A smoke-ready profile must include the matching DSP stub as well
            // as System/HTP/Skel.  Keep this fixture representative of a real
            // packaged profile so it reaches the bundle/graph gates below.
            htpStubLibraryPresent = true,
            searchDirectories = listOf("/data/local/tmp/qnn"),
            probeState = QnnRuntimeProbeState.LOADABLE,
            probeMessage = "mock load ok"
        )

    private fun failedRuntime(message: String): QnnRuntimeStatus =
        QnnRuntimeStatus(
            qnnSystemLibraryPresent = true,
            qnnHtpLibraryPresent = true,
            htpSkelLibraryPresent = true,
            searchDirectories = listOf("/data/local/tmp/qnn"),
            probeState = QnnRuntimeProbeState.LOAD_FAILED,
            probeMessage = message
        )

    private fun unprobedCompleteRuntime(): QnnRuntimeStatus =
        QnnRuntimeStatus(
            qnnSystemLibraryPresent = true,
            qnnHtpLibraryPresent = true,
            htpSkelLibraryPresent = true,
            htpStubLibraryPresent = true,
            searchDirectories = listOf("/data/local/tmp/qnn"),
            probeState = QnnRuntimeProbeState.NOT_REQUESTED,
            probeMessage = ""
        )

    private fun blockedTransportRuntime(message: String): QnnRuntimeStatus =
        QnnRuntimeStatus(
            qnnSystemLibraryPresent = true,
            qnnHtpLibraryPresent = true,
            htpSkelLibraryPresent = true,
            htpStubLibraryPresent = true,
            cdspRpcLibraryPresent = true,
            cdspRpcLibraryLoadable = false,
            cdspRpcMessage = message,
            searchDirectories = listOf("/data/app/lib/arm64"),
            probeState = QnnRuntimeProbeState.LOADABLE,
            probeMessage = "host runtime ok"
        )

    private fun File.touch(name: String, content: String = "x"): File =
        File(this, name).also {
            it.parentFile?.mkdirs()
            it.writeText(content)
        }

    private class FakeImageSmokeBridge(
        private val result: NativeQnnSmokeResult
    ) : LocalImageQnnSmokeBridge {
        override val runnerReady: Boolean = true
        override fun runImageSmoke(
            bundleRoot: File,
            runtimeDirs: List<String>,
            smokeSpec: QnnSmokeSpec
        ): NativeQnnSmokeResult = result
    }

    private class RecordingImageSmokeBridge : LocalImageQnnSmokeBridge {
        override val runnerReady: Boolean = true
        val specs = mutableListOf<QnnSmokeSpec>()

        override fun runImageSmoke(
            bundleRoot: File,
            runtimeDirs: List<String>,
            smokeSpec: QnnSmokeSpec
        ): NativeQnnSmokeResult {
            specs += smokeSpec
            error("Static graph smoke must not run for a semantic-discovery bundle.")
        }
    }

    private val Int.gb: Long
        get() = this * 1024L * 1024L * 1024L
}
