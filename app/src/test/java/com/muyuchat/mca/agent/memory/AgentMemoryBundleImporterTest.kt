package com.muyuchat.mca.agent.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MEM-08: a bulk bundle is imported strictly, or rejected with a path and no partial trust. */
class AgentMemoryBundleImporterTest {

    private val persistence = InMemoryAgentMemoryPersistence()

    private fun importer() = AgentMemoryBundleImporter(persistence) { 99L }

    private fun item(
        memoryId: String,
        hash: String,
        extra: String = "",
        sourceKind: String = "USER_TEXT",
        text: String = "hello world"
    ): String = """{"memoryId":"$memoryId","spaceId":"space-1","title":"Title","text":"$text",""" +
        """"contentHash":"${hash.repeat(64)}","sourceKind":"$sourceKind","createdAt":10,""" +
        """"updatedAt":10$extra}"""

    private fun bundle(items: String): String =
        """{"schemaVersion":1,"space":{"spaceId":"space-1","displayName":"Imported",""" +
            """"createdAt":10,"updatedAt":10,"revision":1},"items":[$items]}"""

    private fun <T> rejected(result: T): AgentMemoryBundleImporter.Result.Rejected =
        result as? AgentMemoryBundleImporter.Result.Rejected
            ?: error("expected a rejection, got $result")

    @Test
    fun aCompleteBundleImportsWithItsOwnIdsAndDerivedCounts() = runBlocking {
        val result = importer().importBundle(
            bundle(item("mem-1", "a") + "," + item("mem-2", "b", text = "second note"))
        )

        val imported = result as AgentMemoryBundleImporter.Result.Imported
        assertEquals("space-1", imported.spaceId)
        assertEquals(2, imported.itemCount)
        val first = requireNotNull(persistence.findItem("mem-1"))
        assertEquals(AgentMemorySourceKind.USER_TEXT, first.sourceKind)
        assertTrue("counts are derived from the chunker", (first.tokenCount) > 0)
        assertTrue("at least one chunk is written", first.chunkCount >= 1)
        assertNotNull(persistence.findItem("mem-2"))
        assertEquals(1, persistence.transactionCount)
    }

    @Test
    fun aFieldTheImporterDoesNotKnowIsRejectedNotIgnored() = runBlocking {
        val result = importer().importBundle(
            bundle(item("mem-1", "a", extra = ""","network":true"""))
        )

        val rejection = rejected(result)
        assertEquals("MEMORY_BUNDLE_UNKNOWN_FIELD", rejection.code)
        assertEquals("bundle.items[0].network", rejection.fieldPath)
        assertNull("a rejected bundle writes nothing", persistence.findItem("mem-1"))
        assertNull(persistence.findSpace("space-1"))
    }

    @Test
    fun anUnknownSourceKindIsRejectedInsteadOfFallingBack() = runBlocking {
        val rejection = rejected(
            importer().importBundle(bundle(item("mem-1", "a", sourceKind = "LEGACY_FILE")))
        )
        assertEquals("MEMORY_BUNDLE_INVALID_ENUM", rejection.code)
        assertEquals("bundle.items[0].sourceKind", rejection.fieldPath)
    }

    @Test
    fun aRevokedItemMustDeclareWhenItWasRevoked() = runBlocking {
        val rejection = rejected(
            importer().importBundle(bundle(item("mem-1", "a", extra = ""","revoked":true""")))
        )
        assertEquals("MEMORY_BUNDLE_MISSING_FIELD", rejection.code)
        assertEquals("bundle.items[0].revokedAt", rejection.fieldPath)
    }

    @Test
    fun aDuplicateContentHashFailsTheBundleInsteadOfMerging() = runBlocking {
        val result = importer().importBundle(
            bundle(item("mem-1", "a") + "," + item("mem-2", "a"))
        )
        assertEquals("MEMORY_BUNDLE_WRITE_REJECTED", rejected(result).code)
    }
}
