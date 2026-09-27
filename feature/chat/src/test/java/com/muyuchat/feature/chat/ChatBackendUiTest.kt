package com.muyuchat.feature.chat

import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.RuntimeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBackendUiTest {
    @Test
    fun runtimeFamiliesExposeOnlyTheirRealTransportChoices() {
        assertEquals(listOf("cpu", "opencl"), chatBackendOptionsFor(ChatBackendFamily.MNN).map { it.id })
        assertEquals(listOf("cpu", "gpu", "auto", "custom"), chatBackendOptionsFor(ChatBackendFamily.LLAMA_CPP).map { it.id })
        assertEquals(listOf("cpu", "gpu", "npu", "hybrid"), chatBackendOptionsFor(ChatBackendFamily.GENIEX_LLAMA_CPP).map { it.id })
        assertEquals(listOf("cpu", "gpu", "npu"), chatBackendOptionsFor(ChatBackendFamily.LITERT_LM).map { it.id })
        assertEquals(listOf("npu"), chatBackendOptionsFor(ChatBackendFamily.QAIRT).map { it.id })
        assertTrue(chatBackendOptionsFor(ChatBackendFamily.UNKNOWN).isEmpty())
    }

    @Test
    fun geniexLlamaAliasesUseTheirComputeUnitContract() {
        assertEquals(
            ChatBackendFamily.GENIEX_LLAMA_CPP,
            chatBackendFamilyForRuntime("geniex_llama_cpp")
        )
        assertEquals(
            ChatBackendFamily.GENIEX_LLAMA_CPP,
            chatBackendFamilyForRuntime("geniex_gguf")
        )
        assertEquals(
            ChatBackendFamily.QAIRT,
            chatBackendFamilyForRuntime("geniex_qairt")
        )
    }

    @Test
    fun unavailableNpuRemainsVisibleWithConcreteReason() {
        val npu = chatBackendOptionsFor(ChatBackendFamily.LITERT_LM, npuAvailable = false).last()
        assertEquals("npu", npu.id)
        assertTrue(npu.enabled)
        assertTrue(npu.availabilityNote.orEmpty().contains("仍可尝试"))
    }

    @Test
    fun llamaGpuOptionReflectsLoadedNativeBackendAvailability() {
        val unavailable = chatBackendOptionsFor(
            ChatBackendFamily.LLAMA_CPP,
            gpuAvailable = false
        )
        val unavailableGpu = unavailable.first { it.id == "gpu" }
        assertTrue(unavailableGpu.enabled)
        assertEquals("GPU（全量）", unavailableGpu.label)
        assertTrue(unavailable.first().availabilityNote.orEmpty().contains("没有可用"))

        val available = chatBackendOptionsFor(
            ChatBackendFamily.LLAMA_CPP,
            gpuAvailable = true
        )
        val availableGpu = available.first { it.id == "gpu" }
        assertTrue(availableGpu.enabled)
        assertTrue(availableGpu.detail.contains("OpenCL"))

        // A missing probe stays actionable: native load remains the authority.
        assertTrue(chatBackendOptionsFor(ChatBackendFamily.LLAMA_CPP).first { it.id == "gpu" }.enabled)
    }

    @Test
    fun unknownChipsetKeepsNpuActionableInsteadOfHardDisablingIt() {
        assertEquals(
            null,
            npuAvailabilityForChatBackend(
                family = ChatBackendFamily.LITERT_LM,
                chipsetCode = null,
                qnnRuntimeUsableForSmoke = false
            )
        )
        assertEquals(
            null,
            npuAvailabilityForChatBackend(
                family = ChatBackendFamily.QAIRT,
                chipsetCode = "",
                qnnRuntimeUsableForSmoke = false
            )
        )
    }

    @Test
    fun packagedLiteRtTransportEnablesKnownChipsetBeforeFirstNativeProbe() {
        assertEquals(
            true,
            npuAvailabilityForChatBackend(
                family = ChatBackendFamily.LITERT_LM,
                chipsetCode = "SM8750P",
                qnnRuntimeUsableForSmoke = false,
                packagedLiteRtTransportAvailable = true
            )
        )
        assertEquals(
            null,
            npuAvailabilityForChatBackend(
                family = ChatBackendFamily.QAIRT,
                chipsetCode = "SM8750P",
                qnnRuntimeUsableForSmoke = false,
                packagedLiteRtTransportAvailable = true
            )
        )
    }

    @Test
    fun knownChipsetWithoutProbeOrPackagedProfileStillAllowsNativeAttempt() {
        assertEquals(
            null,
            npuAvailabilityForChatBackend(
                family = ChatBackendFamily.LITERT_LM,
                chipsetCode = "SM9999",
                qnnRuntimeUsableForSmoke = false,
                packagedLiteRtTransportAvailable = false
            )
        )
        assertTrue(chatBackendOptionsFor(ChatBackendFamily.LITERT_LM, npuAvailable = false)
            .first { it.id == "npu" }.enabled)
        assertTrue(chatBackendOptionsFor(ChatBackendFamily.GENIEX_LLAMA_CPP, npuAvailable = false)
            .first { it.id == "npu" }.enabled)
    }

    @Test
    fun selectedBackendPrefersNativeReadbackOverRequestedProfile() {
        val requested = GenerationParams(advancedJson = "{\"backend\":\"opencl\"}")
        assertEquals(
            "cpu",
            selectedChatBackendId(
                ChatBackendFamily.MNN,
                requested,
                RuntimeStats(loaded = true, backend = "mnn_cpu")
            )
        )
    }

    @Test
    fun genericLoadedLiteRtIdentityPreservesRequestedTransport() {
        for (backend in listOf("cpu", "gpu", "npu")) {
            assertEquals(
                backend,
                selectedChatBackendId(
                    ChatBackendFamily.LITERT_LM,
                    GenerationParams(advancedJson = "{\"backend\":\"$backend\"}"),
                    RuntimeStats(loaded = true, backend = "litert_lm")
                )
            )
        }
    }

    @Test
    fun explicitLiteRtCpuReadbackCanOverrideRequestedTransport() {
        assertEquals(
            "cpu",
            selectedChatBackendId(
                ChatBackendFamily.LITERT_LM,
                GenerationParams(advancedJson = "{\"backend\":\"npu\"}"),
                RuntimeStats(loaded = true, backend = "litert_lm_cpu")
            )
        )
    }

    @Test
    fun registeredGpuPluginDoesNotClaimLlamaGpuExecution() {
        val stats = RuntimeStats(
            loaded = true,
            backend = "llama.cpp",
            backendDevices = "CPU, GPU plugin registered",
            gpuOffloadSupported = true,
            gpuOffloadActive = false,
            gpuOffloadAllocationObserved = false,
            gpuOffloadExecutionObserved = false
        )
        assertEquals(
            "auto",
            selectedChatBackendId(ChatBackendFamily.LLAMA_CPP, GenerationParams(), stats)
        )
    }




    @Test
    fun backendChangesStayInModelScopedAdvancedProfile() {
        val mnn = GenerationParams().withChatBackend(ChatBackendFamily.MNN, "opencl")
        assertEquals("opencl", org.json.JSONObject(mnn.advancedJson).optString("backend"))

        val llama = GenerationParams().withChatBackend(ChatBackendFamily.LLAMA_CPP, "gpu")
        assertEquals(-2, org.json.JSONObject(llama.advancedJson).optInt("n_gpu_layers"))
        val cpuWithMoe = GenerationParams(
            advancedJson = "{\"n_gpu_layers\":-2,\"n_cpu_moe\":4}"
        ).withChatBackend(ChatBackendFamily.LLAMA_CPP, "cpu")
        assertEquals(0, org.json.JSONObject(cpuWithMoe.advancedJson).optInt("n_gpu_layers"))
        assertEquals(0, org.json.JSONObject(cpuWithMoe.advancedJson).optInt("n_cpu_moe"))
        val custom = GenerationParams().withChatBackend(ChatBackendFamily.LLAMA_CPP, "custom")
        assertEquals(-1, org.json.JSONObject(custom.advancedJson).optInt("n_gpu_layers"))
        val auto = GenerationParams().withChatBackend(ChatBackendFamily.LLAMA_CPP, "auto")
        assertEquals(-1, org.json.JSONObject(auto.advancedJson).optInt("n_gpu_layers"))
        assertEquals("layer", org.json.JSONObject(auto.advancedJson).optString("split_mode"))

        val litert = GenerationParams().withChatBackend(ChatBackendFamily.LITERT_LM, "npu")
        assertEquals("npu", org.json.JSONObject(litert.advancedJson).optString("backend"))

        val geniexGpu = GenerationParams().withChatBackend(ChatBackendFamily.GENIEX_LLAMA_CPP, "gpu")
        val geniexGpuJson = org.json.JSONObject(geniexGpu.advancedJson)
        assertEquals("gpu", geniexGpuJson.optString("geniex_compute_unit"))
        assertFalse(geniexGpuJson.has("n_gpu_layers"))

        val geniexNpu = GenerationParams().withChatBackend(ChatBackendFamily.GENIEX_LLAMA_CPP, "npu")
        assertEquals("npu", org.json.JSONObject(geniexNpu.advancedJson).optString("geniex_compute_unit"))
    }

    @Test
    fun geniexSelectedBackendReadsComputeUnitInsteadOfLlamaGpuLayers() {
        assertEquals(
            "gpu",
            selectedChatBackendId(
                ChatBackendFamily.GENIEX_LLAMA_CPP,
                GenerationParams(advancedJson = "{\"geniex_compute_unit\":\"gpu\"}")
            )
        )
        assertEquals(
            "npu",
            selectedChatBackendId(
                ChatBackendFamily.GENIEX_LLAMA_CPP,
                GenerationParams(advancedJson = "{\"geniex_compute_unit\":\"npu\"}")
            )
        )
    }

    @Test
    fun llamaWithoutExplicitProfileUsesAutomaticTransport() {
        assertEquals(
            "auto",
            selectedChatBackendId(ChatBackendFamily.LLAMA_CPP, GenerationParams())
        )
        assertEquals(
            "cpu",
            selectedChatBackendId(
                ChatBackendFamily.LLAMA_CPP,
                GenerationParams(advancedJson = "{\"n_gpu_layers\":0}")
            )
        )
    }

    @Test
    fun customLlamaModeDoesNotReuseAllGpuOrCpuSentinels() {
        val fromAllGpu = GenerationParams(
            advancedJson = "{\"n_gpu_layers\":-2,\"split_mode\":\"layer\"}"
        ).withChatBackend(ChatBackendFamily.LLAMA_CPP, "custom")
        val allGpuJson = org.json.JSONObject(fromAllGpu.advancedJson)
        assertEquals(-1, allGpuJson.optInt("n_gpu_layers"))
        assertEquals("layer", allGpuJson.optString("split_mode"))

        val fromCpu = GenerationParams(
            advancedJson = "{\"n_gpu_layers\":0,\"split_mode\":\"none\"}"
        ).withChatBackend(ChatBackendFamily.LLAMA_CPP, "custom")
        val cpuJson = org.json.JSONObject(fromCpu.advancedJson)
        assertEquals(-1, cpuJson.optInt("n_gpu_layers"))
        assertEquals("layer", cpuJson.optString("split_mode"))

        val fromExplicitLayers = GenerationParams(
            advancedJson = "{\"n_gpu_layers\":12,\"split_mode\":\"row\"}"
        ).withChatBackend(ChatBackendFamily.LLAMA_CPP, "custom")
        val explicitJson = org.json.JSONObject(fromExplicitLayers.advancedJson)
        assertEquals(12, explicitJson.optInt("n_gpu_layers"))
        assertEquals("row", explicitJson.optString("split_mode"))
    }

    @Test
    fun customLlamaModePersistsTheRequestedLayerCount() {
        val custom = GenerationParams(
            advancedJson = "{\"n_gpu_layers\":-2,\"n_cpu_moe\":4}"
        ).withChatBackend(ChatBackendFamily.LLAMA_CPP, "custom:12")
        val json = org.json.JSONObject(custom.advancedJson)
        assertEquals(12, json.optInt("n_gpu_layers"))
        assertEquals("layer", json.optString("split_mode"))
        assertEquals(4, json.optInt("n_cpu_moe"))
        assertEquals(12, customGpuLayerCountFromBackendId("custom:12"))
        assertEquals(null, customGpuLayerCountFromBackendId("custom:4097"))
    }

    @Test
    fun customZeroLayersUsesTheCpuTopology() {
        val custom = GenerationParams().withChatBackend(ChatBackendFamily.LLAMA_CPP, "custom:0")
        val json = org.json.JSONObject(custom.advancedJson)
        assertEquals(0, json.optInt("n_gpu_layers"))
        assertEquals("none", json.optString("split_mode"))
        assertEquals(0, json.optInt("n_cpu_moe"))
    }
}
