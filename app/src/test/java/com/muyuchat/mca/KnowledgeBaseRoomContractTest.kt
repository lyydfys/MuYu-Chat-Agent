package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeBaseRoomContractTest {
    @Test
    fun knowledgeBaseDeletionIsOneTransactionWithParentDeletedLast() {
        val source = chatSessionStoreSource()
        val body = transactionalFunctionBody(source, "deleteKnowledgeBaseCompletely")

        assertCallsInOrder(
            body,
            "deleteKnowledgeBindingsForBase(knowledgeBaseId)",
            "deleteKnowledgeChunksForBase(knowledgeBaseId)",
            "deleteKnowledgeDocumentsForBase(knowledgeBaseId)",
            "deleteKnowledgeBase(knowledgeBaseId)"
        )
    }

    @Test
    fun documentReplacementIsOneTransactionAndInvalidatesItsParentIndex() {
        val source = chatSessionStoreSource()
        val body = transactionalFunctionBody(source, "replaceKnowledgeDocument")

        assertCallsInOrder(
            body,
            "knowledgeBaseExists(document.knowledgeBaseId)",
            "deleteKnowledgeChunksForDocument(document.id)",
            "deleteKnowledgeDocument(document.id)",
            "insertKnowledgeDocuments(listOf(document))",
            "insertKnowledgeChunks(chunks)",
            "invalidateKnowledgeBaseIndex(document.knowledgeBaseId, document.updatedAt)"
        )
    }

    @Test
    fun sessionReplacementPrunesBindingsInsideTheSnapshotTransaction() {
        val source = chatSessionStoreSource()
        val body = transactionalFunctionBody(source, "replaceAll")

        assertCallsInOrder(body, "clearMessages()", "clearSessions()")
        assertTrue(body.contains("if (records.isEmpty())"))
        assertTrue(body.contains("clearKnowledgeBindings()"))
        assertCallsInOrder(body, "insertSessions(records.map { it.toEntity() })", "pruneKnowledgeBindings(records.map { it.id })")

        val clearQuery = queryForFunction(source, "clearKnowledgeBindings")
        assertTrue(clearQuery.contains("DELETE FROM chat_knowledge_base_bindings"))
        assertFalse(clearQuery.contains("WHERE"))

        val pruneQuery = queryForFunction(source, "pruneKnowledgeBindings")
        assertTrue(pruneQuery.contains("DELETE FROM chat_knowledge_base_bindings"))
        assertTrue(pruneQuery.contains("WHERE chatSessionId NOT IN (:chatSessionIds)"))
    }

    @Test
    fun knowledgeRetrievalJoinsLiveBasesAndAppliesHardBoundsBeforeRanking() {
        val roomSource = chatSessionStoreSource()
        val query = queryForFunction(roomSource, "knowledgeChunksForBases")

        assertTrue(query.contains("INNER JOIN knowledge_bases"))
        assertTrue(query.contains("knowledge_bases.id = knowledge_chunks.knowledgeBaseId"))
        assertTrue(query.contains("LIMIT :limit"))

        val storeSource = knowledgeBaseStoreSource()
        val retrieveBody = functionBody(storeSource, "retrieve")
        assertTrue(retrieveBody.contains(".take(MAX_SELECTED_KNOWLEDGE_BASES)"))
        assertTrue(retrieveBody.contains("limit = MAX_RETRIEVAL_CANDIDATE_CHUNKS"))

        val selectedBaseLimit = constantValue(storeSource, "MAX_SELECTED_KNOWLEDGE_BASES")
        val candidateChunkLimit = constantValue(storeSource, "MAX_RETRIEVAL_CANDIDATE_CHUNKS")
        assertTrue(selectedBaseLimit in 1..64)
        assertTrue(candidateChunkLimit in 1..4_096)
    }

    @Test
    fun migrationChainFrom16To18CreatesKnowledgeTablesWithoutReplacingExistingData() {
        val source = chatSessionStoreSource()

        assertTrue(Regex("""version\s*=\s*18""").containsMatchIn(source))
        val builder = source.substring(
            source.indexOf("Room.databaseBuilder"),
            source.indexOf(".build()", source.indexOf("Room.databaseBuilder"))
        )
        assertTrue(builder.contains("MIGRATION_16_17"))
        assertTrue(builder.contains("MIGRATION_17_18"))

        val migration16To17 = region(source, "private val MIGRATION_16_17", "private val MIGRATION_17_18")
        assertTrue(migration16To17.contains("createKnowledgeBaseTablesIfMissing(db)"))
        assertNonDestructive(migration16To17)

        val knowledgeSchema = functionBody(source, "createKnowledgeBaseTablesIfMissing")
        listOf(
            "knowledge_bases",
            "knowledge_documents",
            "knowledge_chunks",
            "chat_knowledge_base_bindings"
        ).forEach { table ->
            assertTrue(knowledgeSchema.contains("CREATE TABLE IF NOT EXISTS $table"))
        }
        assertEquals(6, Regex("CREATE INDEX IF NOT EXISTS").findAll(knowledgeSchema).count())
        assertNonDestructive(knowledgeSchema)

        val migration17To18 = region(source, "private val MIGRATION_17_18", "private fun addProjectIdColumnIfMissing")
        assertTrue(migration17To18.contains("addAssistantCharacterCardJsonColumnIfMissing(db)"))
        assertNonDestructive(migration17To18)

        val assistantUpgrade = functionBody(source, "addAssistantCharacterCardJsonColumnIfMissing")
        assertTrue(assistantUpgrade.contains("ALTER TABLE assistants ADD COLUMN characterCardJson TEXT"))
        assertNonDestructive(assistantUpgrade)
    }

    private fun transactionalFunctionBody(source: String, functionName: String): String {
        val declaration = source.indexOf("suspend fun $functionName")
        assertTrue("Missing DAO function $functionName", declaration >= 0)
        val annotationWindow = source.substring((declaration - 80).coerceAtLeast(0), declaration)
        assertTrue("$functionName must remain a Room transaction", annotationWindow.contains("@Transaction"))
        return blockAt(source, source.indexOf('{', declaration))
    }

    private fun functionBody(source: String, functionName: String): String {
        val declaration = source.indexOf("fun $functionName")
        assertTrue("Missing function $functionName", declaration >= 0)
        return blockAt(source, source.indexOf('{', declaration))
    }

    private fun queryForFunction(source: String, functionName: String): String {
        val declaration = source.indexOf("suspend fun $functionName")
        assertTrue("Missing DAO function $functionName", declaration >= 0)
        val queryStart = source.lastIndexOf("@Query", declaration)
        assertTrue("Missing @Query for $functionName", queryStart >= 0)
        return source.substring(queryStart, declaration)
    }

    private fun assertCallsInOrder(body: String, vararg calls: String) {
        var previous = -1
        calls.forEach { call ->
            val index = body.indexOf(call)
            assertTrue("Missing call: $call", index >= 0)
            assertTrue("Call is out of order: $call", index > previous)
            previous = index
        }
    }

    private fun assertNonDestructive(source: String) {
        val normalized = source.uppercase()
        assertFalse(normalized.contains("DROP TABLE"))
        assertFalse(normalized.contains("DELETE FROM"))
        assertFalse(normalized.contains("UPDATE CHAT_SESSIONS"))
        assertFalse(normalized.contains("UPDATE ASSISTANTS"))
    }

    private fun blockAt(source: String, openingBrace: Int): String {
        assertTrue("Missing function body", openingBrace >= 0)
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
        error("Unterminated function body")
    }

    private fun constantValue(source: String, name: String): Int {
        val match = Regex("""const val $name\s*=\s*([0-9_]+)""").find(source)
            ?: error("Missing constant $name")
        return match.groupValues[1].replace("_", "").toInt()
    }

    private fun region(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertTrue("Missing region start: $startMarker", start >= 0)
        assertTrue("Missing region end: $endMarker", end > start)
        return source.substring(start, end)
    }

    private fun chatSessionStoreSource(): String = sourceFile("ChatSessionStore.kt")

    private fun knowledgeBaseStoreSource(): String = sourceFile("KnowledgeBaseStore.kt")

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
