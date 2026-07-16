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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVisionNpuRunnerTest {
    @Test
    fun discoveredRuntimeOnUnknownDeviceFamilyStillReachesRealSmoke() {
        val bundle = qnnVisionBundle()
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

        val report = LiteRtQnnVisionRunner(runnerReady = true).health(device, bundle)

        assertEquals(LocalVisionNpuState.SMOKE_REQUIRED, report.state)
        assertFalse(report.npuActive)
    }

    @Test
    fun qnnVisionBundleDoesNotBecomeActiveWhenRuntimeIsMissing() {
        val bundle = qnnVisionBundle()
        val report = LiteRtQnnVisionRunner(runnerReady = true).health(
            device = snapdragonElite(qnnReady = false),
            bundleRoot = bundle
        )

        assertEquals(LocalVisionNpuState.QNN_RUNTIME_MISSING, report.state)
        assertFalse(report.npuActive)
    }

    @Test
    fun qnnVisionBundleDoesNotBecomeActiveWhenRuntimeLoadProbeFails() {
        val bundle = qnnVisionBundle()
        val report = LiteRtQnnVisionRunner(runnerReady = true).health(
            device = snapdragonElite(failedRuntime("mock load failure")),
            bundleRoot = bundle
        )

        assertEquals(LocalVisionNpuState.QNN_RUNTIME_MISSING, report.state)
        assertFalse(report.npuActive)
    }

    @Test
    fun qnnVisionBundleCanEnterSmokeWhenCdspRpcDiagnosticFails() {
        val bundle = qnnVisionBundle()
        val report = LiteRtQnnVisionRunner(runnerReady = true).health(
            device = snapdragonElite(
                blockedTransportRuntime("library \"libhidlbase.so\" not found")
            ),
            bundleRoot = bundle
        )

        assertEquals(LocalVisionNpuState.SMOKE_REQUIRED, report.state)
        assertFalse(report.npuActive)
        assertTrue(report.message.contains("smoke"))
    }

    @Test
    fun qnnVisionBundleDoesNotBecomeActiveWhenRunnerIsNotPackaged() {
        val bundle = qnnVisionBundle()
        val report = LiteRtQnnVisionRunner(runnerReady = false).health(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalVisionNpuState.RUNNER_NOT_PACKAGED, report.state)
        assertFalse(report.npuActive)
    }

    @Test
    fun qnnVisionBundleRequiresSmokeBeforeActive() {
        val bundle = qnnVisionBundle()
        val report = LiteRtQnnVisionRunner(runnerReady = true).health(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalVisionNpuState.SMOKE_REQUIRED, report.state)
        assertFalse(report.npuActive)
    }

    @Test
    fun failedSmokeKeepsQnnVisionInactive() {
        val bundle = qnnVisionBundle()
        val report = LiteRtQnnVisionRunner(
            runnerReady = true,
            forcedSmokePassed = false
        ).runSmoke(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalVisionNpuState.SMOKE_FAILED, report.state)
        assertFalse(report.npuActive)
    }

    @Test
    fun invalidQnnVisionSmokeMetadataStaysInactiveBeforeNativeBridge() {
        val bundle = qnnVisionBundle(smokeOverrides = """"graphName": """"")
        val report = LiteRtQnnVisionRunner(runnerReady = true).runSmoke(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalVisionNpuState.SMOKE_METADATA_INVALID, report.state)
        assertFalse(report.npuActive)
        assertTrue(report.message.contains("graphName"))
    }

    @Test
    fun missingQnnVisionContextBinaryStaysInactiveBeforeNativeBridge() {
        val bundle = qnnVisionBundle(contextBinary = "missing-fastvlm-context.litertlm")
        val report = LiteRtQnnVisionRunner(runnerReady = true).runSmoke(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalVisionNpuState.SMOKE_METADATA_INVALID, report.state)
        assertFalse(report.npuActive)
        assertTrue(report.message.contains("contextBinary is missing"))
        assertTrue(report.message.contains("missing-fastvlm-context.litertlm"))
    }

    @Test
    fun emptyQnnVisionContextBinaryStaysInactiveBeforeNativeBridge() {
        val bundle = qnnVisionBundle(contextBinaryContent = "")
        val report = LiteRtQnnVisionRunner(runnerReady = true).runSmoke(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalVisionNpuState.SMOKE_METADATA_INVALID, report.state)
        assertFalse(report.npuActive)
        assertTrue(report.message.contains("contextBinary is missing"))
    }

    @Test
    fun nativeBridgeWithoutGraphExecutionKeepsQnnVisionInactive() {
        val bundle = qnnVisionBundle()
        val report = LiteRtQnnVisionRunner(
            runnerReady = true,
            smokeBridge = FakeVisionSmokeBridge(
                NativeQnnSmokeResult(
                    message = "graph runner pending",
                    runnerReady = true,
                    graphRunnerReady = false,
                    graphExecute = false,
                    npuActive = false,
                    smokePassed = false,
                    elapsedMs = 33,
                    graphMetadataReady = true,
                    qnnInterfacePresent = true,
                    sdkHeadersPresent = true,
                    typedGraphBindingsCompiled = true,
                    smokeInputCount = 1,
                    smokeOutputCount = 1,
                    tensorBufferPlanReady = true,
                    inputBufferBytes = 338_688,
                    outputBufferBytes = 256,
                    executionStage = "graph_execution_unimplemented",
                    runtimeLoaded = true,
                    qnnInterfaceFound = true,
                    bundleManifestFound = true,
                    bundleGraphArtifactFound = true,
                    smokeMetadataComplete = true,
                    sdkHeadersCompiled = true,
                    inputTensors = listOf(
                        QnnTensorBufferDiagnostics(
                            name = "image",
                            role = "input",
                            dataType = "uint8",
                            shape = listOf(1, 336, 336, 3),
                            elementCount = 338_688,
                            bytesPerElement = 1,
                            byteSize = 338_688,
                            supported = true
                        )
                    ),
                    outputTensors = listOf(
                        QnnTensorBufferDiagnostics(
                            name = "tokens",
                            role = "output",
                            dataType = "int32",
                            shape = listOf(1, 64),
                            elementCount = 64,
                            bytesPerElement = 4,
                            byteSize = 256,
                            supported = true
                        )
                    )
                )
            )
        ).runSmoke(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalVisionNpuState.SMOKE_FAILED, report.state)
        assertFalse(report.npuActive)
        assertFalse(report.graphExecute)
        assertEquals(33, report.smokeElapsedMs)
        assertEquals("graph_execution_unimplemented", report.qnnDiagnostics.executionStage)
        assertTrue(report.qnnDiagnostics.graphMetadataReady)
        assertTrue(report.qnnDiagnostics.tensorBufferPlanReady)
        assertEquals(1, report.qnnDiagnostics.smokeInputCount)
        assertEquals(1, report.qnnDiagnostics.smokeOutputCount)
        assertTrue(report.qnnDiagnostics.allTensorsBindable)
        assertEquals("image", report.qnnDiagnostics.inputTensors.first().name)
        assertEquals(338_688L, report.qnnDiagnostics.inputTensors.first().byteSize)
        assertFalse(report.qnnDiagnostics.graphExecuted)
        assertEquals(
            "graph_execution_unimplemented",
            report.toJson().getJSONObject("qnnDiagnostics").getString("executionStage")
        )
        assertEquals(
            "tokens",
            report.toJson()
                .getJSONObject("qnnDiagnostics")
                .getJSONArray("outputTensors")
                .getJSONObject(0)
                .getString("name")
        )
    }

    @Test
    fun nativeContextLoadFailureIsReportedWithoutADeviceAdmissionDecision() {
        val bundle = qnnVisionBundle()
        val report = LiteRtQnnVisionRunner(
            runnerReady = true,
            smokeBridge = FakeVisionSmokeBridge(
                NativeQnnSmokeResult(
                    message = "QNN contextCreateFromBinary failed: invalid config",
                    runnerReady = true,
                    graphRunnerReady = false,
                    graphExecute = false,
                    npuActive = false,
                    smokePassed = false,
                    elapsedMs = 88,
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
                        graphNames = listOf("fastvlm"),
                        message = "QNN context binary metadata parsed; socModel=43"
                    )
                )
            )
        ).runSmoke(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalVisionNpuState.SMOKE_FAILED, report.state)
        assertFalse(report.npuActive)
        assertTrue(report.message.contains("invalid config"))
        assertEquals(43, report.qnnDiagnostics.binaryMetadata.socModel)
    }

    @Test
    fun realVisionGraphExecutionWinsOverStaticSocMetadata() {
        val bundle = qnnVisionBundle()
        val report = LiteRtQnnVisionRunner(
            runnerReady = true,
            smokeBridge = FakeVisionSmokeBridge(
                NativeQnnSmokeResult(
                    message = "graph execute ok",
                    runnerReady = true,
                    graphRunnerReady = true,
                    graphExecute = true,
                    npuActive = true,
                    smokePassed = true,
                    elapsedMs = 690,
                    executionStage = "graph_execute_passed",
                    binaryMetadata = QnnBinaryMetadataDiagnostics(
                        attempted = true,
                        parsed = true,
                        version = 3,
                        socModel = 43,
                        graphCount = 1,
                        graphNames = listOf("fastvlm")
                    )
                )
            )
        ).runSmoke(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalVisionNpuState.NPU_ACTIVE, report.state)
        assertTrue(report.npuActive)
        assertTrue(report.smokePassed)
        assertTrue(report.graphExecute)
    }

    @Test
    fun nativeBridgeMustProveGraphExecutionBeforeVisionNpuActive() {
        val bundle = qnnVisionBundle()
        val report = LiteRtQnnVisionRunner(
            runnerReady = true,
            smokeBridge = FakeVisionSmokeBridge(
                NativeQnnSmokeResult(
                    message = "graph execute ok",
                    runnerReady = true,
                    graphRunnerReady = true,
                    graphExecute = true,
                    npuActive = true,
                    smokePassed = true,
                    elapsedMs = 690
                )
            )
        ).runSmoke(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalVisionNpuState.NPU_ACTIVE, report.state)
        assertTrue(report.npuActive)
        assertTrue(report.graphExecute)
        assertEquals(690, report.smokeElapsedMs)
    }

    @Test
    fun passedSmokeIsTheOnlyPathToNpuActive() {
        val bundle = qnnVisionBundle()
        val report = LiteRtQnnVisionRunner(
            runnerReady = true,
            forcedSmokePassed = true,
            forcedSmokeElapsedMs = 812
        ).runSmoke(
            device = snapdragonElite(qnnReady = true),
            bundleRoot = bundle
        )

        assertEquals(LocalVisionNpuState.NPU_ACTIVE, report.state)
        assertTrue(report.npuActive)
        assertTrue(report.smokePassed)
        assertTrue(report.graphExecute)
        assertEquals(812, report.smokeElapsedMs)
    }

    private fun qnnVisionBundle(
        smokeOverrides: String? = null,
        contextBinary: String = "FastVLM-0.5B.qualcomm.sm8750.litertlm",
        contextBinaryContent: String = "x"
    ): File {
        val root = Files.createTempDirectory("qnn-vision-bundle").toFile()
        root.touch("FastVLM-0.5B.qualcomm.sm8750.litertlm", contextBinaryContent)
        File(root, "manifest.json").writeText(
            """
            {
              "schema": "mca.vision_engine.bundle.v1",
              "id": "fastvlm-sm8750",
              "title": "FastVLM SM8750",
              "runtime": "LITERT_QNN",
              "accelerator": "QNN_HTP",
              "minDeviceTier": "SNAPDRAGON_8_ELITE",
              "requiresQnnRuntime": true,
              "requiresSmokeTest": true,
              "components": [
                {"role": "MAIN_MODEL", "path": "FastVLM-0.5B.qualcomm.sm8750.litertlm", "required": true}
              ],
              "smoke": {
                "imageWidth": 336,
                "imageHeight": 336,
                "prompt": "描述图片",
                "timeoutSeconds": 30,
                ${smokeOverrides ?: """"graphName": "fastvlm""""},
                "contextBinary": "$contextBinary",
                "inputs": [
                  {"name": "image", "dataType": "uint8", "shape": [1, 336, 336, 3]}
                ],
                "outputs": [
                  {"name": "tokens", "dataType": "int32", "shape": [1, 64]}
                ]
              }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )
        return root
    }

    private fun snapdragonElite(qnnReady: Boolean): DeviceProfile {
        val runtime = if (qnnReady) readyRuntime() else QnnRuntimeStatus.Missing
        return snapdragonElite(runtime)
    }

    private fun snapdragonElite(qnnRuntime: QnnRuntimeStatus): DeviceProfile {
        val acceleration = DeviceAccelerationAnalyzer.assess(
            soc = SocInfo("Qualcomm", "SM8750", SocFamily.Snapdragon),
            totalRamBytes = 16.gb,
            qnnRuntime = qnnRuntime
        )
        return DeviceProfile(
            socManufacturer = "Qualcomm",
            socModel = "SM8750",
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
            // NPU graph smoke requires an internally coherent DSP profile,
            // including its matching HTP stub.
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

    private class FakeVisionSmokeBridge(
        private val result: NativeQnnSmokeResult
    ) : LocalVisionNpuSmokeBridge {
        override val runnerReady: Boolean = true
        override fun runVisionSmoke(
            bundleRoot: File,
            runtimeDirs: List<String>,
            smokeSpec: QnnSmokeSpec
        ): NativeQnnSmokeResult = result
    }

    private val Int.gb: Long
        get() = this * 1024L * 1024L * 1024L
}
