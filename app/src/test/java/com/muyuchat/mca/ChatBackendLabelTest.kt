package com.muyuchat.mca

import com.muyuchat.feature.chat.ChatBackendFamily
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the transport label shown to the user, and proves the P2 extraction changed nothing.
 *
 * The label is interpolated into status messages such as "正在重新加载 …（GPU / OpenCL）", so
 * it is user-visible text. It had no test, and the families disagree with each other on
 * purpose, which is exactly the kind of mapping a tidy-up would flatten.
 */
class ChatBackendLabelTest {

    @Test
    fun `mnn shows opencl as gpu and everything else as cpu`() {
        assertEquals("GPU / OpenCL", backendLabel(ChatBackendFamily.MNN, "opencl"))
        assertEquals("CPU", backendLabel(ChatBackendFamily.MNN, "cpu"))
        // An npu id still reads CPU for this family. Recorded because it looks wrong at a
        // glance and is the pre-existing mapping.
        assertEquals("CPU", backendLabel(ChatBackendFamily.MNN, "npu"))
        assertEquals("CPU", backendLabel(ChatBackendFamily.MNN, ""))
    }

    @Test
    fun `litert lm names its three transports`() {
        assertEquals("NPU", backendLabel(ChatBackendFamily.LITERT_LM, "npu"))
        assertEquals("GPU", backendLabel(ChatBackendFamily.LITERT_LM, "gpu"))
        assertEquals("CPU", backendLabel(ChatBackendFamily.LITERT_LM, "cpu"))
        assertEquals("CPU", backendLabel(ChatBackendFamily.LITERT_LM, "anything"))
    }

    @Test
    fun `qairt always reads npu regardless of the id`() {
        listOf("npu", "htp", "cpu", "", "something-else").forEach { id ->
            assertEquals("NPU", backendLabel(ChatBackendFamily.QAIRT, id))
        }
    }

    @Test
    fun `llama cpp names its fixed transports`() {
        assertEquals("GPU（全量）", backendLabel(ChatBackendFamily.LLAMA_CPP, "gpu"))
        assertEquals("自动调配", backendLabel(ChatBackendFamily.LLAMA_CPP, "auto"))
        assertEquals("自定义 GPU 层数", backendLabel(ChatBackendFamily.LLAMA_CPP, "custom"))
        assertEquals("CPU", backendLabel(ChatBackendFamily.LLAMA_CPP, "cpu"))
        assertEquals("CPU", backendLabel(ChatBackendFamily.LLAMA_CPP, ""))
    }

    @Test
    fun `llama cpp reports a custom layer count when the id carries one`() {
        assertEquals("自定义 GPU 层数（32）", backendLabel(ChatBackendFamily.LLAMA_CPP, "custom:32"))
        assertEquals("自定义 GPU 层数（0）", backendLabel(ChatBackendFamily.LLAMA_CPP, "custom:0"))
        assertEquals("自定义 GPU 层数（4096）", backendLabel(ChatBackendFamily.LLAMA_CPP, "custom:4096"))
    }

    @Test
    fun `a custom layer count outside the accepted range falls back to cpu`() {
        // customGpuLayerCountFromBackendId only accepts 0..4096.
        assertEquals("CPU", backendLabel(ChatBackendFamily.LLAMA_CPP, "custom:4097"))
        assertEquals("CPU", backendLabel(ChatBackendFamily.LLAMA_CPP, "custom:-1"))
        assertEquals("CPU", backendLabel(ChatBackendFamily.LLAMA_CPP, "custom:notanumber"))
        assertEquals("CPU", backendLabel(ChatBackendFamily.LLAMA_CPP, "custom:"))
    }

    @Test
    fun `geniex names gpu npu and hybrid`() {
        assertEquals("GPU", backendLabel(ChatBackendFamily.GENIEX_LLAMA_CPP, "gpu"))
        assertEquals("NPU / HTP", backendLabel(ChatBackendFamily.GENIEX_LLAMA_CPP, "npu"))
        assertEquals("NPU + CPU", backendLabel(ChatBackendFamily.GENIEX_LLAMA_CPP, "hybrid"))
        assertEquals("CPU", backendLabel(ChatBackendFamily.GENIEX_LLAMA_CPP, "cpu"))
        assertEquals("CPU", backendLabel(ChatBackendFamily.GENIEX_LLAMA_CPP, ""))
    }

    @Test
    fun `an unknown family echoes the runtime id and never returns an empty label`() {
        assertEquals("some-transport", backendLabel(ChatBackendFamily.UNKNOWN, "some-transport"))
        assertEquals("默认", backendLabel(ChatBackendFamily.UNKNOWN, ""))
        assertEquals("默认", backendLabel(ChatBackendFamily.UNKNOWN, "   "))
    }

    @Test
    fun `every family produces a non blank label for a blank id`() {
        // The label is interpolated into a status message, so an empty string would leave
        // the user with brackets and nothing inside them.
        ChatBackendFamily.entries.forEach { family ->
            val label = backendLabel(family, "")
            assertEquals(
                "$family must not produce a blank label for a blank id, got '$label'",
                true,
                label.isNotBlank()
            )
        }
    }

    @Test
    fun `the extraction matches the original mapping on every sample`() {
        val ids = listOf(
            "", "   ", "opencl", "cpu", "gpu", "npu", "htp", "hybrid", "auto", "custom",
            "custom:0", "custom:32", "custom:4096", "custom:4097", "custom:-1",
            "custom:notanumber", "custom:", "CUSTOM:32", "Custom:32", "unknown-id"
        )

        ChatBackendFamily.entries.forEach { family ->
            ids.forEach { id ->
                assertEquals(
                    "label diverged for $family / '$id'",
                    originalBackendLabel(family, id),
                    backendLabel(family, id)
                )
            }
        }
    }

    /** Verbatim copy of the pre-extraction `MainViewModel.backendLabel`. */
    private fun originalBackendLabel(family: ChatBackendFamily, backendId: String): String =
        when (family) {
            ChatBackendFamily.MNN -> if (backendId == "opencl") "GPU / OpenCL" else "CPU"
            ChatBackendFamily.LITERT_LM -> when (backendId) {
                "npu" -> "NPU"
                "gpu" -> "GPU"
                else -> "CPU"
            }
            ChatBackendFamily.QAIRT -> "NPU"
            ChatBackendFamily.LLAMA_CPP -> when (backendId) {
                "gpu" -> "GPU（全量）"
                "auto" -> "自动调配"
                "custom" -> "自定义 GPU 层数"
                else -> com.muyuchat.feature.chat.customGpuLayerCountFromBackendId(backendId)
                    ?.let { "自定义 GPU 层数（$it）" }
                    ?: "CPU"
            }
            ChatBackendFamily.GENIEX_LLAMA_CPP -> when (backendId) {
                "gpu" -> "GPU"
                "npu" -> "NPU / HTP"
                "hybrid" -> "NPU + CPU"
                else -> "CPU"
            }
            ChatBackendFamily.UNKNOWN -> backendId.ifBlank { "默认" }
        }
}
