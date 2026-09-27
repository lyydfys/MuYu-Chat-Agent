package com.muyuchat.mca

import com.muyuchat.core.modelstore.ChatModelRuntime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LocalVisionLoadReadinessTest {
    @Test
    fun `text model without a bound projector is unaffected`() {
        assertNull(
            llamaCppVisionReadinessWarning(
                runtime = ChatModelRuntime.LLAMA_CPP,
                configuredProjectorPath = null,
                nativeStatsJson = "{}"
            )
        )
        assertNull(
            llamaCppVisionReadinessWarning(
                runtime = ChatModelRuntime.MNN,
                configuredProjectorPath = "unused.gguf",
                nativeStatsJson = "{}"
            )
        )
    }

    @Test
    fun `bound projector requires the active native runner and exact path`() {
        val projector = Files.createTempFile("mca-mmproj", ".gguf").toFile()
        val stats = JSONObject()
            .put("loaded", true)
            .put("visionReady", true)
            .put("mmprojPath", projector.canonicalPath)
            .toString()

        assertNull(
            llamaCppVisionReadinessWarning(
                ChatModelRuntime.LLAMA_CPP,
                projector.absolutePath,
                stats
            )
        )
        val wrongPath = Files.createTempFile("mca-other-mmproj", ".gguf").toFile()
        val mismatch = llamaCppVisionReadinessWarning(
            ChatModelRuntime.LLAMA_CPP,
            projector.absolutePath,
            JSONObject(stats).put("mmprojPath", wrongPath.absolutePath).toString()
        )
        assertTrue(mismatch.orEmpty().contains("不一致"))
    }

    @Test
    fun `bound projector with native negative readiness is reported as vision warning`() {
        val projector = Files.createTempFile("mca-mmproj", ".gguf").toFile()
        val stats = JSONObject()
            .put("loaded", true)
            .put("visionReady", false)
            .put("visionFailureReason", "native_capability_reports_no_vision")
            .put("mmprojPath", projector.absolutePath)
            .toString()

        val message = llamaCppVisionReadinessWarning(
            ChatModelRuntime.LLAMA_CPP,
            projector.absolutePath,
            stats
        )
        assertEquals(
            "视觉模型加载未完成：native runtime 拒绝了该主模型/投影器组合。请确认主模型和 mmproj 来自同一模型仓库、同一架构版本，然后重新绑定并加载。",
            message
        )
    }
}
