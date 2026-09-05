package com.muyuchat.core.modelstore

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelImportClassifierTest {
    @Test
    fun liteRtLmMagicWinsOverProviderExtension() {
        val magic = LITERT_LM_MAGIC.toByteArray(Charsets.US_ASCII)

        assertEquals(ModelImportKind.LITERT_LM, classifyModelImport("provider-document", magic))
        assertEquals(ModelImportKind.LITERT_LM, classifyModelImport("model.gguf", magic))
        assertEquals("provider-document.litertlm", normalizedLiteRtLmImportName(" provider-document "))
    }

    @Test
    fun liteRtLmExtensionIsOnlyAShortHeaderFallback() {
        assertEquals(ModelImportKind.LITERT_LM, classifyModelImport("MODEL.LITERTLM ", byteArrayOf()))
        assertEquals(ModelImportKind.UNKNOWN, classifyModelImport(
            "MODEL.LITERTLM",
            byteArrayOf('n'.code.toByte(), 'o'.code.toByte(), 'p'.code.toByte(), 'e'.code.toByte(),
                'x'.code.toByte(), 'x'.code.toByte(), 'x'.code.toByte(), 'x'.code.toByte())
        ))
    }

    @Test
    fun ggufMagicWinsWhenProviderOmitsOrMislabelsExtension() {
        val magic = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

        assertEquals(ModelImportKind.GGUF, classifyModelImport("provider-document", magic))
        assertEquals(ModelImportKind.GGUF, classifyModelImport("model.zip", magic))
        assertEquals("provider-document.gguf", normalizedGgufImportName(" provider-document "))
    }

    @Test
    fun zipMagicWinsWhenProviderOmitsOrMislabelsExtension() {
        val magic = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)

        assertEquals(ModelImportKind.MNN_ZIP, classifyModelImport(null, magic))
        assertEquals(ModelImportKind.MNN_ZIP, classifyModelImport("model.gguf", magic))
    }

    @Test
    fun namesRemainACompatibilityFallbackForShortOrUnavailableHeaders() {
        assertEquals(ModelImportKind.GGUF, classifyModelImport("MODEL.GGUF ", byteArrayOf()))
        assertEquals(ModelImportKind.MNN_ZIP, classifyModelImport("bundle.ZIP", byteArrayOf()))
        assertEquals(ModelImportKind.MNN_COMPONENT, classifyModelImport("llm.mnn.weight", byteArrayOf()))
        assertEquals(ModelImportKind.UNKNOWN, classifyModelImport("notes.txt", byteArrayOf()))
    }

    @Test
    fun completeNonModelHeaderCannotBeOverriddenByAFalseExtension() {
        val html = byteArrayOf('<'.code.toByte(), 'h'.code.toByte(), 't'.code.toByte(), 'm'.code.toByte())

        assertEquals(ModelImportKind.UNKNOWN, classifyModelImport("download.gguf", html))
        assertEquals(ModelImportKind.UNKNOWN, classifyModelImport("download.zip", html))
    }
}
