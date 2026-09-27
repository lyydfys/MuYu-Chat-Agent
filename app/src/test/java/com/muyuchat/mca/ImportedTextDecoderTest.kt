package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportedTextDecoderTest {
    @Test
    fun removesUtf8BomBeforeWorldBookOrKnowledgeJsonParsing() {
        val payload = "{\"name\":\"设定\"}".toByteArray(Charsets.UTF_8)
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + payload

        assertEquals("{\"name\":\"设定\"}", decodeImportedText(withBom))
    }

    @Test
    fun decodesUtf16ExportsWithEitherByteOrderMark() {
        val source = "知识库条目"
        val littleEndian = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + source.toByteArray(Charsets.UTF_16LE)
        val bigEndian = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + source.toByteArray(Charsets.UTF_16BE)

        assertEquals(source, decodeImportedText(littleEndian))
        assertEquals(source, decodeImportedText(bigEndian))
    }
}
