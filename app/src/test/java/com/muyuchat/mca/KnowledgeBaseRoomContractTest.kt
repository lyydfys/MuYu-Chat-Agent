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
            "require(chunks.all",
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
        val replaceBody = transactionalFunctionBody(source, "replaceAll")
        assertTrue(replaceBody.contains("reconcileSnapshot(ChatHistoryPersistenceBounds.bound(records))"))

        val reconcileBody = functionBody(source, "reconcileSnapshot")
        assertTrue(reconcileBody.contains("if (records.isEmpty())"))
        assertCallsInOrder(
            reconcileBody,
            "clearMessages()",
            "clearKnowledgeBindings()",
            "clearSessions()"
        )
        assertCallsInOrder(
            reconcileBody,
            "upsertSessions(changedSessions)",
            "pruneKnowledgeBindings(liveSessionIds)",
            "pruneSessions(liveSessionIds)"
        )

        val clearQuery = queryForFunction(source, "clearKnowledgeBindings")
        assertTrue(clearQuery.contains("DELETE FROM chat_knowledge_base_bindings"))
        assertFalse(clearQuery.contains("WHERE"))

        val pruneQuery = queryForFunction(source, "pruneKnowledgeBindings")
        assertTrue(pruneQuery.contains("DELETE FROM chat_knowledge_base_bindings"))
        assertTrue(pruneQuery.contains("WHERE chatSessionId NOT IN (:chatSessionIds)"))
    }

    @Test
    fun knowledgeRetrievalUsesBoundedKeysetPagesAndKeepsGlobalTopRanking() {
        val roomSource = chatSessionStoreSource()
        val query = queryForFunction(roomSource, "knowledgeChunkPageForBases")

        assertTrue(query.contains("INNER JOIN knowledge_bases"))
        assertTrue(query.contains("knowledge_bases.id = knowledge_chunks.knowledgeBaseId"))
        assertTrue(query.contains("INNER JOIN knowledge_documents"))
        assertTrue(query.contains("knowledge_documents.id = knowledge_chunks.documentId"))
        assertTrue(query.contains("knowledge_documents.knowledgeBaseId = knowledge_chunks.knowledgeBaseId"))
        assertTrue(query.contains(":afterKnowledgeBaseId IS NULL"))
        assertTrue(query.contains("knowledge_chunks.knowledgeBaseId > :afterKnowledgeBaseId"))
        assertTrue(query.contains("knowledge_chunks.documentId > :afterDocumentId"))
        assertTrue(query.contains("knowledge_chunks.position > :afterPosition"))
        assertTrue(query.contains("knowledge_chunks.id > :afterId"))
        assertTrue(query.contains("ORDER BY"))
        assertTrue(query.contains("knowledge_chunks.knowledgeBaseId ASC"))
        assertTrue(query.contains("knowledge_chunks.documentId ASC"))
        assertTrue(query.contains("knowledge_chunks.position ASC"))
        assertTrue(query.contains("knowledge_chunks.id ASC"))
        assertTrue(query.contains("LIMIT :limit"))

        val roomRetrievalBody = transactionalFunctionBody(roomSource, "knowledgeChunkPageForBaseRecords")
        assertTrue(roomRetrievalBody.contains(".take(MAX_KNOWLEDGE_BASE_IDS_PER_ROOM_OPERATION)"))
        assertTrue(roomRetrievalBody.contains("limit.coerceAtMost(MAX_KNOWLEDGE_CHUNKS_PER_ROOM_PAGE)"))

        val storeSource = knowledgeBaseStoreSource()
        val retrieveBody = functionBody(storeSource, "retrieve")
        assertTrue(retrieveBody.contains(".take(MAX_SELECTED_KNOWLEDGE_BASES)"))
        assertTrue(retrieveBody.contains("while (true)"))
        assertTrue(retrieveBody.contains("afterKnowledgeBaseId = cursor?.knowledgeBaseId"))
        assertTrue(retrieveBody.contains("afterDocumentId = cursor?.documentId"))
        assertTrue(retrieveBody.contains("afterPosition = cursor?.position"))
        assertTrue(retrieveBody.contains("afterId = cursor?.id"))
        assertTrue(retrieveBody.contains("mergeTopRanked"))
        assertTrue(retrieveBody.contains("limit = RETRIEVAL_PAGE_SIZE"))

        val selectedBaseLimit = constantValue(storeSource, "MAX_SELECTED_KNOWLEDGE_BASES")
        val candidateChunkLimit = constantValue(storeSource, "RETRIEVAL_PAGE_SIZE")
        assertTrue(selectedBaseLimit in 1..64)
        assertTrue(candidateChunkLimit in 1..256)
    }

    @Test
    fun bindingReplacementValidatesLiveParentsBeforeMutatingBindings() {
        val source = chatSessionStoreSource()
        val body = transactionalFunctionBody(source, "replaceKnowledgeBindings")

        assertCallsInOrder(
            body,
            "require(knowledgeBaseIds.size <= MAX_KNOWLEDGE_BASE_IDS_PER_ROOM_OPERATION)",
            "chatSessionExists(chatSessionId)",
            "existingKnowledgeBaseIds(normalizedKnowledgeBaseIds)",
            "deleteKnowledgeBindingsForChat(chatSessionId)"
        )
        assertTrue(body.contains("knowledgeBaseIds.all { it.isNotBlank() }"))
        assertTrue(body.contains("knowledgeBaseIds.distinct().sorted()"))

        val sessionExistsQuery = queryForFunction(source, "chatSessionExists")
        assertTrue(sessionExistsQuery.contains("SELECT COUNT(*) > 0 FROM chat_sessions"))

        val existingBasesQuery = queryForFunction(source, "existingKnowledgeBaseIds")
        assertTrue(existingBasesQuery.contains("SELECT id FROM knowledge_bases WHERE id IN (:knowledgeBaseIds)"))
    }

    @Test
    fun knowledgeBaseUpdatesUseUpsertSoForeignKeysCannotCascadeDeleteDocuments() {
        val source = chatSessionStoreSource()
        val declaration = source.substring(
            source.indexOf("suspend fun insertKnowledgeBases") - 64,
            source.indexOf("suspend fun insertKnowledgeBases") + 96
        )

        assertTrue(declaration.contains("@Upsert"))
        assertFalse(declaration.contains("@Insert(onConflict = OnConflictStrategy.REPLACE)"))
    }

    @Test
    fun selectedBindingsAndDocumentDeletionUseLiveParents() {
        val roomSource = chatSessionStoreSource()
        val bindingQuery = queryForFunction(roomSource, "knowledgeBaseIdsForChat")
        assertTrue(bindingQuery.contains("INNER JOIN chat_sessions"))
        assertTrue(bindingQuery.contains("INNER JOIN knowledge_bases"))

        val deleteBody = transactionalFunctionBody(roomSource, "deleteKnowledgeDocumentCompletely")
        assertCallsInOrder(
            deleteBody,
            "knowledgeDocument(documentId) ?: return",
            "knowledgeBaseExists(document.knowledgeBaseId)",
            "deleteKnowledgeChunksForDocument(documentId)",
            "deleteKnowledgeDocument(documentId)",
            "invalidateKnowledgeBaseIndex(document.knowledgeBaseId, updatedAt)"
        )

        val storeSource = knowledgeBaseStoreSource()
        val setSelectedBody = functionBody(storeSource, "setSelectedKnowledgeBaseIds")
        assertCallsInOrder(
            setSelectedBody,
            "require(ids.size <= MAX_SELECTED_KNOWLEDGE_BASES)",
            ".take(MAX_SELECTED_KNOWLEDGE_BASES)",
            "replaceKnowledgeBindings"
        )
        assertTrue(setSelectedBody.contains("ids.all { it.isNotBlank() }"))

        val deleteDocumentBody = functionBody(storeSource, "deleteDocument")
        assertFalse(deleteDocumentBody.contains("knowledgeDocument(documentId)"))
    }

    @Test
    fun firstMessagePersistsSessionAndKnowledgeBindingsInOneRoomTransaction() {
        val roomSource = chatSessionStoreSource()
        val transactionBody = transactionalFunctionBody(roomSource, "replaceAllWithKnowledgeBindings")
        assertCallsInOrder(
            transactionBody,
            "reconcileSnapshot(boundedRecords)",
            "replaceKnowledgeBindings("
        )

        val source = sourceFile("MainViewModel.kt")
        val persistBody = functionBody(source, "persistChatSessions")
        assertTrue(persistBody.contains("pendingKnowledgeBindings[sessionId] = knowledgeBaseIds.toSet()"))
        assertTrue(persistBody.contains("chatSessionStore.save(snapshot, knowledgeBindingsForSave)"))
        assertFalse(persistBody.contains("knowledgeBaseStore.setSelectedKnowledgeBaseIds"))
        val sendBody = functionBody(source, "sendMessage")
        assertTrue(sendBody.contains("knowledgeBinding = chatSessionIdForKnowledgeBinding"))
        assertFalse(sendBody.contains("persistKnowledgeBaseBindings(sessionId, selectedKnowledgeBaseIdsForBinding)"))
    }

    @Test
    fun coalescedSnapshotsKeepPendingBindingsAndDiscardDeletedSessionBindings() {
        val body = functionBody(sourceFile("MainViewModel.kt"), "persistChatSessions")

        assertCallsInOrder(
            body,
            "pendingKnowledgeBindings[sessionId] = knowledgeBaseIds.toSet()",
            "if (sequence != chatSessionPersistenceSequence.get()) return@withLock",
            ".filterKeys { it in liveSessionIds }",
            "chatSessionStore.save(snapshot, knowledgeBindingsForSave)"
        )
        assertTrue(body.contains("pendingKnowledgeBindings.keys"))
        assertTrue(body.contains(".filterNot { it in liveSessionIds }"))
    }

    @Test
    fun sessionDeletionDelegatesBindingCleanupToSnapshotTransaction() {
        val source = sourceFile("MainViewModel.kt")
        val deleteBody = functionBody(source, "deleteChatSession")

        assertTrue(deleteBody.contains("persistConversationMutation(remaining, rollback)"))
        assertFalse(deleteBody.contains("setSelectedKnowledgeBaseIds(sessionId, emptySet())"))
    }

    @Test
    fun worldBookCleanupRunsOnlyAfterCanonicalOwnerPersistence() {
        val source = sourceFile("MainViewModel.kt")
        val persistBody = functionBody(source, "persistChatSessions")
        assertCallsInOrder(
            persistBody,
            "chatSessionStore.save(snapshot, knowledgeBindingsForSave)",
            "cleanupPendingWorldBooksAfterOwnerCommit("
        )

        listOf(
            "deleteChatSession",
            "clearChatHistory",
            "deleteMessageAt",
            "deleteLastConversationTurn"
        ).forEach { functionName ->
            val body = functionBody(source, functionName)
            assertFalse("$functionName must not delete world books before Room commit", body.contains("removeScoped("))
        }

        val deleteAssistantBody = functionBody(source, "deleteAssistant")
        assertCallsInOrder(
            deleteAssistantBody,
            "assistantStore.saveAssistants(remaining)",
            "queueWorldBookCleanup(WorldBookScope.ASSISTANT",
            "cleanupPendingWorldBooksAfterOwnerCommit("
        )
        assertTrue(deleteAssistantBody.contains("助手和关联世界书均未修改"))
    }

    @Test
    fun localUiKeepsSearchEvidenceRequestScopedAndOptsInStablePersonaOnly() {
        val source = sourceFile("MainViewModel.kt")
        val body = functionBody(source, "startGeneration")

        assertFalse(body.contains("systemPrompt = listOf(requestParams.systemPrompt, webSearchTurn.promptContext)"))
        assertTrue(body.contains("runtimeSystemContextForTurn"))
        assertTrue(body.contains("webSearchTurn.promptContext"))
        assertTrue(body.contains("activeRuntimeIdentity?.runtime == LocalChatRuntime.LLAMA_CPP"))
        assertTrue(body.contains("persistentPrefixSystemPrompt = persistentLlamaPrefix"))

        val cloudRequest = body.substring(
            body.indexOf("cloudChatProvider.streamChat("),
            body.indexOf("} else {", body.indexOf("cloudChatProvider.streamChat("))
        )
        assertFalse(cloudRequest.contains("persistentPrefixSystemPrompt"))
    }

    @Test
    fun migrationChainFrom16To20CreatesKnowledgeTablesWithoutReplacingExistingData() {
        val source = chatSessionStoreSource()

        assertTrue(Regex("""version\s*=\s*20""").containsMatchIn(source))
        val builder = source.substring(
            source.indexOf("Room.databaseBuilder"),
            source.indexOf(".build()", source.indexOf("Room.databaseBuilder"))
        )
        assertTrue(builder.contains("MIGRATION_16_17"))
        assertTrue(builder.contains("MIGRATION_17_18"))
        assertTrue(builder.contains("MIGRATION_18_19"))
        assertTrue(builder.contains("MIGRATION_19_20"))

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

        val migration17To18 = region(source, "private val MIGRATION_17_18", "private val MIGRATION_18_19")
        assertTrue(migration17To18.contains("addAssistantCharacterCardJsonColumnIfMissing(db)"))
        assertNonDestructive(migration17To18)

        val assistantUpgrade = functionBody(source, "addAssistantCharacterCardJsonColumnIfMissing")
        assertTrue(assistantUpgrade.contains("ALTER TABLE assistants ADD COLUMN characterCardJson TEXT"))
        assertNonDestructive(assistantUpgrade)

        val migration18To19 = region(source, "private val MIGRATION_18_19", "private val MIGRATION_19_20")
        assertTrue(migration18To19.contains("addChatSessionAssistantSnapshotColumnIfMissing(db)"))
        assertNonDestructive(migration18To19)

        val snapshotUpgrade = functionBody(source, "addChatSessionAssistantSnapshotColumnIfMissing")
        assertTrue(snapshotUpgrade.contains("ALTER TABLE chat_sessions ADD COLUMN assistantSnapshotJson TEXT"))
        assertNonDestructive(snapshotUpgrade)

        val migration19To20 = region(source, "private val MIGRATION_19_20", "private fun addProjectIdColumnIfMissing")
        assertTrue(migration19To20.contains("rebuildKnowledgeTablesWithForeignKeys(db)"))

        val foreignKeyRebuild = functionBody(source, "rebuildKnowledgeTablesWithForeignKeys")
        assertCallsInOrder(
            foreignKeyRebuild,
            "rebuildKnowledgeDocumentsWithForeignKeys(db)",
            "rebuildKnowledgeChunksWithForeignKeys(db)",
            "rebuildChatKnowledgeBaseBindingsWithForeignKeys(db)"
        )

        listOf(
            "createKnowledgeDocumentsTableWithForeignKeys",
            "createKnowledgeChunksTableWithForeignKeys",
            "createChatKnowledgeBaseBindingsTableWithForeignKeys"
        ).forEach { functionName ->
            assertTrue(functionBody(source, functionName).contains("ON DELETE CASCADE"))
        }
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
