package com.muyuchat.feature.chat

import com.muyuchat.core.engine.RuntimeStats
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDeviceStatusTest {
    @Test
    fun exposesFiveStableRowsWhenRuntimeHasNoEvidence() {
        val rows = ChatUiState().deviceStatusItems()

        assertEquals(listOf("cpu", "gpu", "npu", "memory", "temperature"), rows.map { it.key })
        assertEquals("在线", rows.first { it.key == "cpu" }.value)
        assertEquals("待检测", rows.first { it.key == "gpu" }.value)
        assertEquals("未使用", rows.first { it.key == "npu" }.value)
        assertEquals("待检测", rows.first { it.key == "memory" }.value)
        assertEquals("不可用", rows.first { it.key == "temperature" }.value)
        assertFalse(rows.first { it.key == "gpu" }.active)
        assertFalse(rows.first { it.key == "temperature" }.known)
    }

    @Test
    fun verifiedGpuDoesNotClaimNpuWithoutNativeEvidence() {
        val stats = RuntimeStats(
            loaded = true,
            backend = "qnn_htp",
            gpuOffloadSupported = true,
            gpuOffloadActive = true,
            gpuOffloadAllocationObserved = true,
            gpuOffloadExecutionObserved = true,
            gpuOffloadLayers = 18,
            gpuOffloadLayersKnown = true,
            totalMemKb = 8L * 1024 * 1024,
            availMemKb = 2L * 1024 * 1024
        )

        val rows = ChatUiState(stats = stats).deviceStatusItems()

        assertEquals("运行中", rows.first { it.key == "gpu" }.value)
        assertTrue(rows.first { it.key == "gpu" }.detail.contains("18 层"))
        assertEquals("未使用", rows.first { it.key == "npu" }.value)
        assertEquals("6.0 GB / 8.0 GB", rows.first { it.key == "memory" }.value)
        assertTrue(rows.first { it.key == "memory" }.detail.contains("2.0 GB"))
    }

    @Test
    fun selectedLiteRtNpuIsNotPresentedAsRunningBeforeNativeProof() {
        val state = ChatUiState(
            selectedModelId = "litert",
            generationParams = com.muyuchat.core.engine.GenerationParams(
                advancedJson = "{\"backend\":\"npu\"}"
            ),
            localModels = listOf(
                ChatModelChoice(
                    id = "litert",
                    displayName = "LiteRT-LM",
                    selectedBackendId = "npu"
                )
            ),
            stats = RuntimeStats(
                loaded = true,
                backend = "litert_lm",
                requestedBackend = "npu",
                actualBackend = null,
                backendExecutionEvidence = "sdk_backend_telemetry_unavailable"
            )
        )

        val npu = state.deviceStatusItems().first { it.key == "npu" }

        assertEquals("已选择", npu.value)
        assertFalse(npu.active)
        assertTrue(npu.detail.contains("实际：未确认"))
    }

    @Test
    fun explicitNativeNpuExecutionEvidenceIsPresentedAsRunning() {
        val npu = ChatUiState(
            selectedModelId = "qnn",
            localModels = listOf(
                ChatModelChoice(
                    id = "qnn",
                    displayName = "QAIRT",
                    selectedBackendId = "npu"
                )
            ),
            stats = RuntimeStats(
                loaded = true,
                backend = "qairt",
                requestedBackend = "npu",
                actualBackend = "htp",
                backendExecutionObserved = true,
                npuExecutionObserved = true,
                backendExecutionEvidence = "worker_execution_metadata"
            )
        ).deviceStatusItems().first { it.key == "npu" }

        assertEquals("运行中", npu.value)
        assertTrue(npu.active)
        assertTrue(npu.detail.contains("原生证据"))
    }

    @Test
    fun cloudSelectionDoesNotClaimLocalAccelerators() {
        val rows = ChatUiState(
            selectedModelIsCloud = true,
            stats = RuntimeStats(
                loaded = true,
                backend = "gpu",
                gpuOffloadSupported = true,
                totalMemKb = 1024L * 1024,
                availMemKb = 512L * 1024
            )
        ).deviceStatusItems()

        assertEquals("未使用", rows.first { it.key == "gpu" }.value)
        assertEquals("未使用", rows.first { it.key == "npu" }.value)
        assertFalse(rows.first { it.key == "gpu" }.active)
        assertFalse(rows.first { it.key == "npu" }.active)
        assertTrue(rows.first { it.key == "gpu" }.detail.contains("云端"))
    }

    @Test
    fun temperatureEvidenceIsDisplayedAndHighTemperatureIsMarkedActive() {
        val row = ChatUiState().deviceStatusItems(
            temperatureSample = ChatDeviceTemperatureSample(42.5f, "cpu-thermal")
        ).first { it.key == "temperature" }
        assertEquals("42.5°C", row.value)
        assertTrue(row.detail.startsWith("传感器：cpu-thermal"))
        assertTrue(row.known)
        assertFalse(row.active)
        assertEquals(ChatDeviceStatusTone.NEUTRAL, row.tone)

        val hot = ChatUiState().deviceStatusItems(
            temperatureSample = ChatDeviceTemperatureSample(45f, "soc")
        ).first { it.key == "temperature" }
        assertTrue(hot.active)
        assertEquals(ChatDeviceStatusTone.WARN, hot.tone)
    }

    @Test
    fun freshSystemMemoryReplacesStaleRuntimeMemoryAndClampsAvailableBytes() {
        val state = ChatUiState(stats = RuntimeStats(
            totalMemKb = 8L * 1024 * 1024,
            availMemKb = 2L * 1024 * 1024
        ))
        val current = state.deviceStatusItems(ChatDeviceMemorySample(
            totalKb = 12L * 1024 * 1024,
            availableKb = 5L * 1024 * 1024,
            lowMemory = false
        )).first { it.key == "memory" }

        assertEquals("7.0 GB / 12.0 GB", current.value)
        assertEquals("可用 5.0 GB", current.detail)
        assertEquals(0f, memoryUsedFraction(12, 20)!!, 0.001f)
        assertEquals(1f, memoryUsedFraction(12, -3)!!, 0.001f)
    }

    @Test
    fun absoluteBallPresetsSurviveEveryHostSizeWithoutADeadZone() {
        // The old screen-fraction model clamped the first eighth of its range to a constant
        // 18dp on a 360dp phone, so dragging produced no visible change.  An absolute preset
        // must resolve to the same size on every host that can hold it.
        FloatingDeviceStatusSize.entries.forEach { preset ->
            assertEquals(preset.dp, floatingDeviceStatusBallSize(preset.dp, 360f).value, 0.001f)
            assertEquals(preset.dp, floatingDeviceStatusBallSize(preset.dp, 412f).value, 0.001f)
            assertEquals(preset.dp, floatingDeviceStatusBallSize(preset.dp, 448f).value, 0.001f)
        }
        // Neighbouring presets are far enough apart that no step is a visual no-op.
        FloatingDeviceStatusSize.entries.map { it.dp }.zipWithNext().forEach { (small, large) ->
            assertTrue("$small -> $large must be a visible step", large - small >= 8f)
        }
    }

    @Test
    fun ballShrinksOnlyWhenTheHostCannotHoldTheChosenPreset() {
        assertEquals(28f, floatingDeviceStatusBallSize(28f, 320f).value, 0.001f)
        assertEquals(36f, floatingDeviceStatusBallSize(36f, 320f).value, 0.001f)
        assertEquals(44f, floatingDeviceStatusBallSize(44f, 320f).value, 0.001f)
        // 320dp * 0.16 = 51.2dp, so only the largest preset is trimmed on a tiny host.
        assertTrue(floatingDeviceStatusBallSize(52f, 320f).value <= 51.2f)
        // A degenerate host still yields a usable ball rather than a zero-sized one.
        assertTrue(floatingDeviceStatusBallSize(44f, 0f).value >= 16f)
    }

    @Test
    fun ballContentTiersProgressWithThePresetInsteadOfJumpingMidRange() {
        assertEquals(FloatingDeviceStatusBallTier.COMPACT, floatingDeviceStatusBallTier(28f))
        assertEquals(FloatingDeviceStatusBallTier.COMPACT, floatingDeviceStatusBallTier(36f))
        assertEquals(FloatingDeviceStatusBallTier.STANDARD, floatingDeviceStatusBallTier(44f))
        assertEquals(FloatingDeviceStatusBallTier.FULL, floatingDeviceStatusBallTier(52f))
    }

    @Test
    fun compactMemoryLabelFitsTheSmallPresets() {
        assertEquals("4.7G", compactMemoryLabel("4.7 GB"))
        assertEquals("512M", compactMemoryLabel("512 MB"))
        assertEquals("--", compactMemoryLabel("--"))
    }

    @Test
    fun ringStrokeScalesWithTheBall() {
        assertEquals(2.5f, floatingDeviceStatusRingStrokeDp(28f), 0.001f)
        assertEquals(3.74f, floatingDeviceStatusRingStrokeDp(44f), 0.001f)
        assertEquals(4.42f, floatingDeviceStatusRingStrokeDp(52f), 0.001f)
        assertTrue(floatingDeviceStatusRingStrokeDp(52f) > floatingDeviceStatusRingStrokeDp(28f))
    }

    @Test
    fun storedSizeResolvesToTheNearestPreset() {
        assertEquals(FloatingDeviceStatusSize.LARGE, FloatingDeviceStatusSize.nearest(44f))
        assertEquals(FloatingDeviceStatusSize.SMALL, FloatingDeviceStatusSize.nearest(10f))
        assertEquals(FloatingDeviceStatusSize.EXTRA_LARGE, FloatingDeviceStatusSize.nearest(999f))
    }

    @Test
    fun unprovenAcceleratorsAreTonedUnknownRatherThanHealthy() {
        // No probe and no execution proof: the tone has to say "not known", not "fine".
        val rows = ChatUiState().deviceStatusItems()
        assertEquals(ChatDeviceStatusTone.UNKNOWN, rows.first { it.key == "gpu" }.tone)
        assertEquals(ChatDeviceStatusTone.UNKNOWN, rows.first { it.key == "npu" }.tone)
        assertEquals(ChatDeviceStatusTone.UNKNOWN, rows.first { it.key == "memory" }.tone)
        assertEquals(ChatDeviceStatusTone.UNKNOWN, rows.first { it.key == "temperature" }.tone)
        // CPU has no probe to miss, so it reports the weaker but honest neutral tone.
        assertEquals(ChatDeviceStatusTone.NEUTRAL, rows.first { it.key == "cpu" }.tone)
    }

    @Test
    fun verifiedExecutionIsTonedActiveAndConfiguredOnlyStaysNeutral() {
        val running = ChatUiState(
            selectedModelId = "qnn",
            localModels = listOf(
                ChatModelChoice(id = "qnn", displayName = "QAIRT", selectedBackendId = "npu")
            ),
            stats = RuntimeStats(
                loaded = true,
                backend = "qairt",
                requestedBackend = "npu",
                actualBackend = "htp",
                backendExecutionObserved = true,
                npuExecutionObserved = true
            )
        ).deviceStatusItems().first { it.key == "npu" }
        assertEquals(ChatDeviceStatusTone.ACTIVE, running.tone)

        val configured = ChatUiState(
            selectedModelId = "litert",
            generationParams = com.muyuchat.core.engine.GenerationParams(
                advancedJson = "{\"backend\":\"npu\"}"
            ),
            localModels = listOf(
                ChatModelChoice(id = "litert", displayName = "LiteRT-LM", selectedBackendId = "npu")
            ),
            stats = RuntimeStats(
                loaded = true,
                backend = "litert_lm",
                requestedBackend = "npu",
                actualBackend = null
            )
        ).deviceStatusItems().first { it.key == "npu" }
        assertEquals("已选择", configured.value)
        assertFalse(configured.active)
        // A configured delegate must not be painted like a running one.
        assertEquals(ChatDeviceStatusTone.NEUTRAL, configured.tone)
    }

    @Test
    fun proofIsTheFirstFragmentOfTheEvidenceLineSoTruncationCannotHideIt() {
        val npu = ChatUiState(
            selectedModelId = "litert",
            generationParams = com.muyuchat.core.engine.GenerationParams(
                advancedJson = "{\"backend\":\"npu\"}"
            ),
            localModels = listOf(
                ChatModelChoice(id = "litert", displayName = "LiteRT-LM", selectedBackendId = "npu")
            ),
            stats = RuntimeStats(
                loaded = true,
                backend = "litert_lm",
                requestedBackend = "npu",
                actualBackend = null
            )
        ).deviceStatusItems().first { it.key == "npu" }
        assertTrue(
            "the execution proof must precede support/selection: ${npu.detail}",
            npu.detail.indexOf("实际") < npu.detail.indexOf("支持")
        )
    }

    @Test
    fun cpuRowCarriesAProofStatementLikeTheOtherAccelerators() {
        val cpu = ChatUiState(
            stats = RuntimeStats(loaded = true, backend = "mnn_cpu")
        ).deviceStatusItems().first { it.key == "cpu" }
        assertEquals("运行中", cpu.value)
        assertTrue(cpu.detail.contains("实际"))
        assertEquals(ChatDeviceStatusTone.ACTIVE, cpu.tone)
    }

    @Test
    fun temperatureToneEscalatesAcrossTheBands() {
        assertEquals(ChatDeviceStatusTone.NEUTRAL, chatTemperatureTone(41.1f))
        assertEquals(ChatDeviceStatusTone.WARN, chatTemperatureTone(54.8f))
        assertEquals(ChatDeviceStatusTone.CRITICAL, chatTemperatureTone(74.9f))
        assertEquals("正常", chatTemperatureBandLabel(41.1f))
        assertEquals("偏热", chatTemperatureBandLabel(54.8f))
        assertTrue(chatTemperatureBandLabel(74.9f).contains("降频"))
    }

    @Test
    fun floatingStatusDragIsClampedToItsAvailableArea() {
        assertEquals(1f, moveFloatingDeviceStatusFraction(0.8f, 50f, 100f), 0.001f)
        assertEquals(0f, moveFloatingDeviceStatusFraction(0.2f, -50f, 100f), 0.001f)
        assertEquals(0f, moveFloatingDeviceStatusFraction(0.8f, 50f, 0f), 0.001f)
    }

    @Test
    fun temperatureReaderUsesAnyReadableZoneAndConvertsMilliCelsius() {
        val root = Files.createTempDirectory("mca-thermal").toFile()
        try {
            val preferred = File(root, "thermal_zone9").apply { mkdirs() }
            File(preferred, "type").writeText("cpu-thermal")
            File(preferred, "temp").writeText("42000")
            val ignored = File(root, "thermal_zone0").apply { mkdirs() }
            File(ignored, "type").writeText("invalid")
            File(ignored, "temp").writeText("not-a-number")

            val sample = readChatDeviceTemperatureSample(root)

            assertTrue(sample != null)
            assertEquals(42f, sample!!.celsius, 0.001f)
            assertEquals("cpu-thermal", sample.source)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun temperatureReaderRejectsOutOfRangeOrMissingThermalData() {
        val root = Files.createTempDirectory("mca-thermal-invalid").toFile()
        try {
            val invalid = File(root, "thermal_zone7").apply { mkdirs() }
            File(invalid, "type").writeText("soc")
            File(invalid, "temp").writeText("200000")
            assertEquals(null, readChatDeviceTemperatureSample(root))
            assertEquals(null, readChatDeviceTemperatureSample(File(root, "does-not-exist")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun batteryTemperatureFallbackConvertsTenthsCelsiusAndRejectsInvalidValues() {
        val sample = batteryTemperatureSample(386)
        assertEquals(38.6f, sample!!.celsius, 0.001f)
        assertEquals("battery", sample.source)
        assertEquals(null, batteryTemperatureSample(Int.MIN_VALUE))
        assertEquals(null, batteryTemperatureSample(1500))
    }
}
