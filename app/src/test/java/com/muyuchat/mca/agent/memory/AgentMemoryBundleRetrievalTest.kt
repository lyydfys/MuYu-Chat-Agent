package com.muyuchat.mca.agent.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MEM-08 integration: an imported bundle is readable and lexically retrievable through the
 * production store, so import feeds the same chunk index and read path as any other write.
 */
class AgentMemoryBundleRetrievalTest {

    @Test
    fun anImportedItemIsLexicallyRetrievableThroughTheProductionStore() = runBlocking {
        val persistence = InMemoryAgentMemoryPersistence()
        val importer = AgentMemoryBundleImporter(persistence) { 99L }
        val item = """{"memoryId":"mem-telescope","spaceId":"space-1","title":"Telescope setup",""" +
            """"text":"align the telescope mount before the first observing night",""" +
            """"contentHash":"${"c".repeat(64)}","sourceKind":"FILE_IMPORT","createdAt":10,"updatedAt":10}"""
        val bundle = """{"schemaVersion":1,"space":{"spaceId":"space-1","displayName":"Imported",""" +
            """"createdAt":10,"updatedAt":10,"revision":1},"items":[$item]}"""

        val imported = importer.importBundle(bundle)
        assertTrue("the bundle must import: $imported", imported is AgentMemoryBundleImporter.Result.Imported)

        val store = AgentMemoryStore(persistence, clock = { 99L })
        val outcome = store.search(
            grant = AgentMemoryReadGrant(taskId = "task-import", spaceIds = listOf("space-1")),
            query = "telescope",
            requestedSpaceId = "space-1",
            maxItems = 5
        )

        val found = outcome as AgentMemorySearchOutcome.Found
        assertEquals("the imported item must be retrievable", 1, found.result.citations.size)
        val citation = found.result.citations.single()
        assertEquals("mem-telescope", citation.memoryId)
        assertEquals("space-1", citation.spaceId)
        assertEquals(AgentMemoryRetrievalMode.LEXICAL, found.result.retrievalMode)
    }
}
