package com.muyuchat.mca.agent.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MEM-21 (bundle half): export produces the canonical bundle the importer reads, unchanged. */
class AgentMemoryBundleRoundTripTest {

    private fun item(
        memoryId: String,
        hash: String,
        extra: String = ""
    ): String = """{"memoryId":"$memoryId","spaceId":"space-1","title":"Title","text":"note $memoryId",""" +
        """"contentHash":"${hash.repeat(64)}","sourceKind":"USER_TEXT","createdAt":10,""" +
        """"updatedAt":10$extra}"""

    private fun bundle(items: String): String =
        """{"schemaVersion":1,"space":{"spaceId":"space-1","displayName":"Imported",""" +
            """"createdAt":10,"updatedAt":10,"revision":3},"items":[$items]}"""

    @Test
    fun anExportedSpaceImportsIntoAFreshStoreWithTheSameIdsAndTombstones() = runBlocking {
        val source = InMemoryAgentMemoryPersistence()
        val imported = AgentMemoryBundleImporter(source) { 99L }.importBundle(
            bundle(
                item("mem-live", "c") + "," +
                    item("mem-revoked", "d", extra = ""","revoked":true,"revokedAt":20""")
            )
        )
        assertTrue("seed import must succeed: $imported", imported is AgentMemoryBundleImporter.Result.Imported)

        val exported = AgentMemoryBundleExporter(source).exportSpace("space-1")
        val exportedBundle = exported as AgentMemoryBundleExporter.Result.Exported
        assertEquals("space-1", exportedBundle.spaceId)
        assertEquals(2, exportedBundle.itemCount)

        val target = InMemoryAgentMemoryPersistence()
        val reimported = AgentMemoryBundleImporter(target) { 99L }.importBundle(exportedBundle.bundleJson)
        assertEquals(
            "the exported bundle must import unchanged",
            2,
            (reimported as AgentMemoryBundleImporter.Result.Imported).itemCount
        )
        val live = requireNotNull(target.findItem("mem-live"))
        assertEquals("c".repeat(64), live.contentHash)
        assertEquals(AgentMemorySourceKind.USER_TEXT, live.sourceKind)
        val revoked = requireNotNull(target.findItem("mem-revoked"))
        assertTrue("the tombstone travels with the bundle", revoked.revoked)
        assertEquals(20L, revoked.revokedAt)
        val space = requireNotNull(target.findSpace("space-1"))
        assertEquals(3L, space.revision)
    }

    @Test
    fun anUnknownSpaceIsRejectedInsteadOfExportingAnEmptyBundle() = runBlocking {
        val rejected = AgentMemoryBundleExporter(InMemoryAgentMemoryPersistence()).exportSpace("missing")
        assertEquals(
            "MEMORY_BUNDLE_SPACE_NOT_FOUND",
            (rejected as AgentMemoryBundleExporter.Result.Rejected).code
        )
    }
}
