package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QairtDryRunWorkerProtocolTest {
    @Test
    fun textCertificationRequiresARecognizableArithmeticAnswer() {
        assertTrue(QairtDryRunPolicy.textAnswerPasses("42"))
        assertTrue(QairtDryRunPolicy.textAnswerPasses("答案是 42。"))
        assertTrue(QairtDryRunPolicy.textAnswerPasses("四十二"))
        assertFalse(QairtDryRunPolicy.textAnswerPasses("00000012345678901234567890123456"))
        assertFalse(QairtDryRunPolicy.textAnswerPasses("<|assistant|>42"))
        assertFalse(QairtDryRunPolicy.textAnswerPasses(""))
    }

    @Test
    fun startRequestClampsBoundsAndKeepsIdentity() {
        val parsed = QairtDryRunWorkerProtocol.parseStart(
            QairtDryRunWorkerProtocol.start("run-1", "model-1", 99_999, 0)
        )

        assertEquals("run-1", parsed.requestId)
        assertEquals("model-1", parsed.modelId)
        assertEquals(QairtDryRunWorkerProtocol.MAX_N_CTX, parsed.nCtx)
        assertEquals(QairtDryRunWorkerProtocol.MIN_THREADS, parsed.nThreads)
    }

    @Test
    fun visionPolicyUsesModelIdentityRatherThanRuntimeNameOnly() {
        val vl = model("Qwen3-VL-4B-Instruct QAIRT", "qwen3_vl")
        val text = model("Qwen3-4B-Instruct QAIRT", "qwen3")

        assertTrue(QairtDryRunPolicy.requiresVision(vl))
        assertFalse(QairtDryRunPolicy.requiresVision(text))
    }

    @Test
    fun fixedAnswerChecksRequireExpectedSignal() {
        assertTrue(QairtDryRunPolicy.textAnswerPasses("42"))
        assertTrue(QairtDryRunPolicy.textAnswerPasses("答案是四十二。"))
        assertFalse(QairtDryRunPolicy.textAnswerPasses("OK"))
        assertFalse(QairtDryRunPolicy.textAnswerPasses("我无法回答"))
        assertTrue(QairtDryRunPolicy.visionAnswerPasses("蓝色"))
        assertTrue(QairtDryRunPolicy.visionAnswerPasses("blue"))
        assertFalse(QairtDryRunPolicy.visionAnswerPasses("红色"))
    }

    @Test
    fun userInitiatedCertificationKeepsDisposableWorkerImportantOnlyWhileBound() {
        val flags = qairtDryRunBindingFlags()

        assertTrue(flags and Context.BIND_AUTO_CREATE != 0)
        assertTrue(flags and Context.BIND_IMPORTANT != 0)
    }

    private fun model(name: String, architecture: String) = ModelManifest(
        id = name,
        displayName = name,
        path = "/models/$architecture",
        runtime = ChatModelRuntime.GENIEX_QAIRT,
        source = ModelSource.HUGGING_FACE,
        fileName = "$architecture.bundle",
        sizeBytes = 1L,
        sha256 = "sha",
        architecture = architecture
    )
}
