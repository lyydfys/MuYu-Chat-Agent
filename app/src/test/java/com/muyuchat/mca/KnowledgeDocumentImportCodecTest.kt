package com.muyuchat.mca

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeDocumentImportCodecTest {
    @Test
    fun sourceLineEndingsNormalizeForStableDocumentIdentity() {
        val crlf = KnowledgeDocumentImportCodec.prepare("base-a", "one.txt", "\uFEFFAlpha\r\nBeta")
        val lf = KnowledgeDocumentImportCodec.prepare("base-a", "two.txt", "Alpha\nBeta")

        assertEquals(lf.document.id, crlf.document.id)
        assertEquals(lf.chunks.map { it.content }, crlf.chunks.map { it.content })
        assertEquals("\uFEFFAlpha\r\nBeta", crlf.originalText)
        assertEquals("base-a", crlf.chunks.single().knowledgeBaseId)
    }

    @Test
    fun boundedReaderRejectsOversizedAndMalformedUtf8Sources() {
        val oversized = ByteArray(KnowledgeDocumentImportCodec.MAX_SOURCE_BYTES + 1) { 'A'.code.toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeDocumentImportCodec.readText(ByteArrayInputStream(oversized))
        }
        val malformed = byteArrayOf(0xC3.toByte(), 0x28)
        val error = assertThrows(IllegalArgumentException::class.java) {
            KnowledgeDocumentImportCodec.readText(ByteArrayInputStream(malformed))
        }
        assertTrue(error.message.orEmpty().contains("UTF-8"))
    }

    @Test
    fun binaryNullAndWhitespaceOnlySourceNeverBecomeSearchableChunks() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeDocumentImportCodec.prepare("base-a", "binary", "valid\u0000invalid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeDocumentImportCodec.prepare("base-a", "empty", " \r\n ")
        }
    }
}
