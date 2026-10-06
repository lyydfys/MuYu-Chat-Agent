package com.muyuchat.mca

import android.database.Cursor
import java.lang.reflect.Proxy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1/S2: 行为的保护网。纯表与游标解析直接执行；触碰 android.jar stub 的胶水
 * （ContentResolver.getType/query、Uri.scheme）在本仓库 JVM 测试惯例下用源码文本钉住
 * （参照 CreateLocalGeneratedImageAssetOperationsTest 的说明）。
 */
class AttachmentMetadataSupportTest {

    @Test
    fun `extension table maps every documented group and falls back to plain text`() {
        assertEquals("text/markdown", textAttachmentMimeForExtension("notes.md"))
        assertEquals("text/markdown", textAttachmentMimeForExtension("NOTES.MARKDOWN"))
        assertEquals("application/json", textAttachmentMimeForExtension("data.json"))
        assertEquals("application/json", textAttachmentMimeForExtension("dump.jsonl"))
        assertEquals("application/xml", textAttachmentMimeForExtension("feed.xml"))
        assertEquals("text/csv", textAttachmentMimeForExtension("table.csv"))
        for (code in listOf("a.kt", "A.JAVA", "s.py", "app.js", "types.ts")) {
            assertEquals("text/x-code", textAttachmentMimeForExtension(code))
        }
        assertEquals("text/plain", textAttachmentMimeForExtension("readme"))
        assertEquals("text/plain", textAttachmentMimeForExtension("archive.tar.gz"))
        assertEquals("text/plain", textAttachmentMimeForExtension(""))
    }

    @Test
    fun `size cursor parsing reads column when present`() {
        val calls = mutableMapOf<String, Int>()
        val cursor = fakeCursor(calls, columnIndex = 3, moveToFirst = true, isNull = false, size = 42L)
        assertEquals(42L, textAttachmentSizeFromCursor(cursor))
        assertEquals(1, calls["close"])
    }

    @Test
    fun `size cursor parsing degrades to zero on missing column empty cursor and null value`() {
        assertEquals(0L, textAttachmentSizeFromCursor(fakeCursor(mutableMapOf(), columnIndex = -1, moveToFirst = true, isNull = false, size = 7L)))
        assertEquals(0L, textAttachmentSizeFromCursor(fakeCursor(mutableMapOf(), columnIndex = 0, moveToFirst = false, isNull = false, size = 7L)))
        assertEquals(0L, textAttachmentSizeFromCursor(fakeCursor(mutableMapOf(), columnIndex = 0, moveToFirst = true, isNull = true, size = 7L)))
        assertEquals(0L, textAttachmentSizeFromCursor(null))
    }

    @Test
    fun `framework glue keeps resolver first order and file scheme branch`() {
        val source = File(projectRoot(), "app/src/main/java/com/muyuchat/mca/AttachmentMetadataSupport.kt").readText()
        val mime = source.substringAfter("fun mimeTypeForTextAttachment(").substringBefore("fun textAttachmentSizeFromCursor(")
        assertTrue(mime.contains("contentResolver.getType(uri)?.takeIf { it.isNotBlank() }?.let { return it }"))
        assertTrue(mime.contains("return textAttachmentMimeForExtension(name)"))
        val size = source.substringAfter("fun sizeForUri(")
        assertTrue(size.contains("uri.scheme.equals(\"file\", ignoreCase = true)"))
        assertTrue(size.contains("File(it).length()"))
        assertTrue(size.contains("runCatching {"))
        assertTrue(size.contains("contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)"))
        assertTrue(size.contains(".getOrDefault(0L)"))

        val attachmentOps = File(projectRoot(), "app/src/main/java/com/muyuchat/mca/ChatAttachmentOperations.kt").readText()
        assertTrue(attachmentOps.contains("mimeTypeForTextAttachment(contentResolver, uri, displayName)"))
        assertTrue(attachmentOps.contains("sizeForUri(contentResolver, uri)"))
        val viewModel = File(projectRoot(), "app/src/main/java/com/muyuchat/mca/MainViewModel.kt").readText()
        assertTrue(!viewModel.contains("private fun mimeTypeForTextAttachment("))
        assertTrue(!viewModel.contains("private fun sizeForUri("))
    }

    private fun fakeCursor(
        calls: MutableMap<String, Int>,
        columnIndex: Int,
        moveToFirst: Boolean,
        isNull: Boolean,
        size: Long
    ): Cursor = Proxy.newProxyInstance(
        Cursor::class.java.classLoader,
        arrayOf(Cursor::class.java)
    ) { _, method, _ ->
        calls[method.name] = (calls[method.name] ?: 0) + 1
        when (method.name) {
            "getColumnIndex" -> columnIndex
            "moveToFirst" -> moveToFirst
            "isNull" -> isNull
            "getLong" -> size
            "close" -> Unit
            else -> throw UnsupportedOperationException("unexpected Cursor method: ${method.name}")
        }
    } as Cursor

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Unable to locate the project root from ${System.getProperty("user.dir")}")
    }
}
