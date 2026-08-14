package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeBaseImportUxContractTest {
    @Test
    fun knowledgeImportTracksProgressAndActivatesTheImportedBase() {
        val body = functionBody(mainViewModelSource(), "importKnowledgeDocument")

        assertTrue(body.contains("knowledgeBaseImportingIds"))
        assertTrue(body.contains("knowledgeDocumentCounts"))
        assertTrue(body.contains("selectedKnowledgeBaseIds = state.selectedKnowledgeBaseIds + knowledgeBaseId"))
        assertTrue(body.contains("persistKnowledgeBaseBindings(sessionId, selectedKnowledgeBaseIds)"))
    }

    @Test
    fun oversizedInlineFileGetsKnowledgeBaseRecoveryAdvice() {
        val body = functionBody(mainViewModelSource(), "sendMessage")

        assertTrue(body.contains("FILE_ATTACHMENT_MARKER"))
        assertTrue(body.contains("请将长文件导入知识库后再提问"))
    }

    private fun mainViewModelSource(): String {
        var root = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(6) {
            listOf(
                File(root, "src/main/java/com/muyuchat/mca/MainViewModel.kt"),
                File(root, "app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
            ).firstOrNull(File::isFile)?.let { return it.readText(Charsets.UTF_8) }
            root = root.parentFile ?: return@repeat
        }
        error("Unable to locate MainViewModel.kt")
    }

    private fun functionBody(source: String, functionName: String): String {
        val declaration = source.indexOf("fun $functionName")
        assertTrue("Missing $functionName", declaration >= 0)
        val openingBrace = source.indexOf('{', declaration)
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(openingBrace + 1, index)
                }
            }
        }
        error("Unterminated $functionName")
    }
}
