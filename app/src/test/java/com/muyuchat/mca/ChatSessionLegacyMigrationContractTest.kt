package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionLegacyMigrationContractTest {
    @Test
    fun malformedLegacyJsonReturnsBeforeTheSourceFileIsRetired() {
        val source = sourceFile("ChatSessionStore.kt")
        val body = functionBody(source, "migrateLegacyJsonIfNeeded")

        val parseFailureReturn = body.indexOf("}.getOrElse { return emptyList() }")
        val databaseWrite = body.indexOf("database.chatSessionDao().replaceAll(boundedRecords)")
        val legacyRetirement = body.indexOf("legacyFile.delete()")

        assertTrue("Parse failures must return without retiring the source file", parseFailureReturn >= 0)
        assertTrue("Room persistence must happen after a successful parse", databaseWrite > parseFailureReturn)
        assertTrue("The source file must be retired only after Room persistence", legacyRetirement > databaseWrite)
        assertTrue(body.contains("ChatHistoryPersistenceBounds.bound(records)"))
        assertFalse(body.contains("}.getOrElse {\n            emptyList()\n        }"))
    }

    private fun functionBody(source: String, functionName: String): String {
        val declaration = source.indexOf("suspend fun $functionName")
        require(declaration >= 0) { "Missing function $functionName" }
        val openingBrace = source.indexOf('{', declaration)
        require(openingBrace >= 0) { "Missing function body for $functionName" }
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
        error("Unterminated function body for $functionName")
    }

    private fun sourceFile(fileName: String): String {
        var root = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(6) {
            listOf(
                File(root, "src/main/java/com/muyuchat/mca/$fileName"),
                File(root, "app/src/main/java/com/muyuchat/mca/$fileName")
            ).firstOrNull(File::isFile)?.let { return it.readText(Charsets.UTF_8) }
            val parent = root.parentFile ?: return@repeat
            root = parent
        }
        error("Unable to locate $fileName from ${System.getProperty("user.dir")}")
    }
}
