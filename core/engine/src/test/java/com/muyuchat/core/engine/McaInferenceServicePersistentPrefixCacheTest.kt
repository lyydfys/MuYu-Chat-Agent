package com.muyuchat.core.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McaInferenceServicePersistentPrefixCacheTest {
    @Test
    fun coldPrefixIsPublishedAndNextRequestRestoresTheVerifiedState() = runBlocking {
        val root = temporaryRoot("round-trip")
        val store = PersistentPrefixCacheStore(File(root, "prefix"), maxBytes = 64 * 1024L)
        val runner = PrefixRunner()
        val service = loadedService(root, runner, store)

        runner.enqueue("cold")
        val coldEvents = service.streamChat(request("first")).toList()

        assertTrue(coldEvents.last() is GenerateEvent.Done)
        assertEquals(1, runner.prefixRequests.size)
        assertNull(runner.prefixRequests.first().restoreStatePath)
        assertNotNull(runner.prefixRequests.first().writeStatePath)
        assertEquals(1, store.entries().size)

        runner.enqueue("warm")
        runner.reportRestoreHit = true
        val warmEvents = service.streamChat(request("second")).toList()

        assertTrue(warmEvents.last() is GenerateEvent.Done)
        assertEquals(2, runner.prefixRequests.size)
        assertNotNull(runner.prefixRequests.last().restoreStatePath)
        assertEquals(1, store.entries().size)
        val warmStats = (warmEvents.last() as GenerateEvent.Done).stats
        assertTrue(warmStats.persistentPrefixCacheHit)
        assertEquals(17, warmStats.persistentPrefixCacheTokens)
        assertEquals("state_loaded", warmStats.persistentPrefixCacheReason)
    }

    @Test
    fun fullSessionStateIsPublishedAfterTheVisibleReplyAndRestoredOnReturn() = runBlocking {
        val root = temporaryRoot("session-round-trip")
        val store = PersistentPrefixCacheStore(File(root, "prefix"), maxBytes = 64 * 1024L)
        val runner = PrefixRunner()
        val service = loadedService(root, runner, store)
        val first = request("first").copy(persistentSessionId = "conversation-a")

        runner.enqueue("first reply")
        val coldEvents = mutableListOf<GenerateEvent>()
        service.streamChat(first).collect { event ->
            coldEvents += event
            if (event is GenerateEvent.Chunk) assertTrue(store.entries().isEmpty())
        }

        assertTrue(coldEvents.last() is GenerateEvent.Done)
        assertTrue(runner.prefixRequests.first().fullSessionState)
        assertEquals(1, store.entries().size)

        runner.enqueue("second reply")
        runner.reportRestoreHit = true
        val warmEvents = service.streamChat(
            request("second").copy(persistentSessionId = "conversation-a")
        ).toList()

        assertTrue(warmEvents.last() is GenerateEvent.Done)
        assertNotNull(runner.prefixRequests.last().restoreStatePath)
        val warmStats = (warmEvents.last() as GenerateEvent.Done).stats
        assertTrue(warmStats.persistentPrefixCacheHit)
        assertEquals("session_state_saved", warmStats.persistentPrefixCacheReason)
    }

    @Test
    fun fullSessionStateRequiresTheSameRoleCardMemoryAndContextVersions() = runBlocking {
        val root = temporaryRoot("role-session-isolation")
        val store = PersistentPrefixCacheStore(File(root, "prefix"), maxBytes = 64 * 1024L)
        val runner = PrefixRunner()
        val service = loadedService(root, runner, store)
        val first = request("first").copy(
            persistentSessionId = "conversation-a",
            persistentRoleId = "role-a",
            persistentRoleCardVersion = "card-v1",
            persistentMemoryVersion = "memory-v1",
            conversationContextRevision = "summary-v1"
        )
        val variants = listOf(
            first,
            first.copy(persistentRoleId = "role-b"),
            first.copy(persistentRoleCardVersion = "card-v2"),
            first.copy(persistentMemoryVersion = "memory-v2"),
            first.copy(conversationContextRevision = "summary-v2")
        )
        variants.forEachIndexed { index, variant ->
            runner.enqueue("reply-$index")
            assertTrue(service.streamChat(variant).toList().last() is GenerateEvent.Done)
            assertNull(runner.prefixRequests.last().restoreStatePath)
        }

        runner.enqueue("same state again")
        assertTrue(service.streamChat(variants.last()).toList().last() is GenerateEvent.Done)
        assertNotNull(runner.prefixRequests.last().restoreStatePath)
        assertNotEquals(runner.runtimeScopes[0].first, runner.runtimeScopes[1].first)
        assertNotEquals(runner.runtimeScopes[0].second, runner.runtimeScopes[2].second)
        assertNotEquals(runner.runtimeScopes[0].second, runner.runtimeScopes[3].second)
        assertNotEquals(runner.runtimeScopes[0].second, runner.runtimeScopes[4].second)
        assertEquals(runner.runtimeScopes[4], runner.runtimeScopes[5])

        assertTrue(service.clearPersistentSessionStates(listOf("conversation-a")))
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun returningToRoleRestoresItsOwnSessionState() = runBlocking {
        val root = temporaryRoot("role-return")
        val store = PersistentPrefixCacheStore(File(root, "prefix"), maxBytes = 64 * 1024L)
        val runner = PrefixRunner()
        val service = loadedService(root, runner, store)
        val roleA = request("first").copy(
            persistentSessionId = "conversation-a",
            persistentRoleId = "role-a",
            persistentRoleCardVersion = "card-a",
            persistentMemoryVersion = "memory-a",
            conversationContextRevision = "summary-a"
        )
        val roleB = roleA.copy(
            persistentSessionId = "conversation-b",
            persistentRoleId = "role-b",
            persistentRoleCardVersion = "card-b",
            persistentMemoryVersion = "memory-b",
            conversationContextRevision = "summary-b"
        )

        runner.enqueue("A reply")
        service.streamChat(roleA).toList()
        runner.enqueue("B reply")
        service.streamChat(roleB).toList()
        assertNull(runner.prefixRequests.last().restoreStatePath)

        runner.enqueue("A resumed")
        assertTrue(service.streamChat(roleA).toList().last() is GenerateEvent.Done)
        assertNotNull(runner.prefixRequests.last().restoreStatePath)
    }

    @Test
    fun fixedPrefixStateDoesNotCrossRoleCardIdentity() = runBlocking {
        val root = temporaryRoot("role-prefix-isolation")
        val store = PersistentPrefixCacheStore(File(root, "prefix"), maxBytes = 64 * 1024L)
        val runner = PrefixRunner()
        val service = loadedService(root, runner, store)
        val first = request("first").copy(
            persistentRoleId = "role-a",
            persistentRoleCardVersion = "card-v1"
        )
        runner.enqueue("first reply")
        service.streamChat(first).toList()
        assertNull(runner.prefixRequests.last().restoreStatePath)

        runner.enqueue("other role")
        service.streamChat(first.copy(persistentRoleId = "role-b")).toList()
        assertNull(runner.prefixRequests.last().restoreStatePath)

        runner.enqueue("edited card")
        service.streamChat(first.copy(persistentRoleCardVersion = "card-v2")).toList()
        assertNull(runner.prefixRequests.last().restoreStatePath)

        runner.enqueue("original role")
        service.streamChat(first).toList()
        assertNotNull(runner.prefixRequests.last().restoreStatePath)
    }

    @Test
    fun purgingOneSessionPreservesAnotherConversation() = runBlocking {
        val root = temporaryRoot("session-purge")
        val store = PersistentPrefixCacheStore(File(root, "prefix"), maxBytes = 64 * 1024L)
        val runner = PrefixRunner()
        val service = loadedService(root, runner, store)
        runner.enqueue("first reply")
        service.streamChat(request("first").copy(persistentSessionId = "conversation-a")).toList()
        runner.enqueue("other reply")
        service.streamChat(request("other").copy(persistentSessionId = "conversation-b")).toList()
        assertEquals(2, store.entries().size)

        assertTrue(service.clearPersistentSessionStates(setOf(" conversation-a ")))
        assertEquals(1, store.entries().size)
        runner.enqueue("return")
        service.streamChat(request("return").copy(persistentSessionId = "conversation-b")).toList()
        assertNotNull(runner.prefixRequests.last().restoreStatePath)
    }

    @Test
    fun failedReplacementExportEvictsAnUnrestorableFullSessionState() = runBlocking {
        val root = temporaryRoot("session-failed-replacement")
        val store = PersistentPrefixCacheStore(File(root, "prefix"), maxBytes = 64 * 1024L)
        val runner = PrefixRunner()
        val service = loadedService(root, runner, store)
        val session = request("first").copy(persistentSessionId = "conversation-a")
        runner.enqueue("first reply")
        service.streamChat(session).toList()
        assertEquals(1, store.entries().size)

        runner.writeState = false
        runner.enqueue("second reply")
        val result = service.streamChat(request("second").copy(persistentSessionId = "conversation-a")).toList()
        assertTrue(result.last() is GenerateEvent.Done)
        assertEquals("session_state_save_failed", (result.last() as GenerateEvent.Done).stats.persistentPrefixCacheReason)
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun failedStateExportDoesNotFailTheAnswerOrPublishAnEntry() = runBlocking {
        val root = temporaryRoot("save-failure")
        val store = PersistentPrefixCacheStore(File(root, "prefix"), maxBytes = 64 * 1024L)
        val runner = PrefixRunner().apply { writeState = false }
        val service = loadedService(root, runner, store)

        runner.enqueue("answer")
        val events = service.streamChat(request("save failure")).toList()

        assertTrue(events.last() is GenerateEvent.Done)
        assertEquals(1, runner.prefixRequests.size)
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun managedCommitFailureStaysVisibleWithoutFailingGeneration() = runBlocking {
        val root = temporaryRoot("managed-commit-failure")
        val store = PersistentPrefixCacheStore(File(root, "prefix"), maxBytes = 64 * 1024L)
        val runner = PrefixRunner().apply { deleteStateBeforeCommit = true }
        val service = loadedService(root, runner, store)

        runner.enqueue("answer")
        val events = service.streamChat(request("managed commit failure")).toList()

        assertTrue(events.last() is GenerateEvent.Done)
        val stats = (events.last() as GenerateEvent.Done).stats
        assertFalse(stats.persistentPrefixCacheHit)
        assertEquals("managed_commit_failed", stats.persistentPrefixCacheReason)
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun dynamicSystemPromptDoesNotOptIntoPersistentPrefixCache() = runBlocking {
        val root = temporaryRoot("dynamic-opt-out")
        val store = PersistentPrefixCacheStore(File(root, "prefix"), maxBytes = 64 * 1024L)
        val runner = PrefixRunner().apply { enqueue("answer") }
        val service = loadedService(root, runner, store)
        val dynamicRequest = request("dynamic")
            .copy(
                params = request("dynamic").params.copy(systemPrompt = "retrieval evidence for this turn"),
                persistentPrefixSystemPrompt = null
            )

        val events = service.streamChat(dynamicRequest).toList()

        assertTrue(events.last() is GenerateEvent.Done)
        assertEquals("", dynamicRequest.fixedSystemPromptForPrefixCache())
        assertTrue(runner.prefixRequests.isEmpty())
        assertEquals(1, runner.ordinaryBeginCalls)
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun disabledServiceDoesNotStageOrPublishPersistentPrefixState() = runBlocking {
        val root = temporaryRoot("disabled")
        val store = PersistentPrefixCacheStore(File(root, "prefix"), maxBytes = 64 * 1024L)
        val runner = PrefixRunner().apply { enqueue("answer") }
        val service = loadedService(root, runner, store)

        service.setPersistentPrefixCacheEnabled(false)
        val events = service.streamChat(request("disabled")).toList()

        assertTrue(events.last() is GenerateEvent.Done)
        assertTrue(runner.prefixRequests.isEmpty())
        assertEquals(1, runner.ordinaryBeginCalls)
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun oversizedFixedPrefixFallsBackToOrdinaryGeneration() = runBlocking {
        val root = temporaryRoot("oversized-fixed-prefix")
        val store = PersistentPrefixCacheStore(File(root, "prefix"), maxBytes = 64 * 1024L)
        val runner = PrefixRunner().apply { enqueue("answer") }
        val service = McaInferenceService(
            context = FakeContext(root),
            runners = mapOf(LocalChatRuntime.LLAMA_CPP to runner),
            installationScopeId = "stable-test-installation",
            persistentPrefixCacheStoreOverride = store
        )
        service.loadModel(
            modelPath = File(root, "model.gguf").absolutePath,
            runtime = LocalChatRuntime.LLAMA_CPP,
            params = LoadParams(nCtx = 131_072, nThreads = 2)
        ).getOrThrow()
        val fixedPrompt = "a".repeat(128 * 1024 + 1)
        val request = ChatRequest(
            messages = listOf(ChatMessage(Role.USER, "continue")),
            params = GenerationParams(
                nCtx = 131_072,
                nPredict = 8,
                nThreads = 2,
                systemPrompt = fixedPrompt,
                reasoningMode = ReasoningMode.OFF,
                hideReasoning = true
            ),
            persistentPrefixSystemPrompt = fixedPrompt
        )

        val events = service.streamChat(request).toList()

        assertTrue(events.last() is GenerateEvent.Done)
        assertTrue(runner.prefixRequests.isEmpty())
        assertEquals(1, runner.ordinaryBeginCalls)
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun cancellationDiscardsABlockingPrefixWriteBeforeReturning() = runBlocking {
        val root = temporaryRoot("cancel-race")
        val store = PersistentPrefixCacheStore(File(root, "prefix"), maxBytes = 64 * 1024L)
        val runner = PrefixRunner().apply { blockBegin = true }
        val service = loadedService(root, runner, store)

        val job = launch(Dispatchers.Default) {
            service.streamChat(request("cancel")).collect { }
        }
        assertTrue(runner.beginEntered.await(5, TimeUnit.SECONDS))
        job.cancel()
        runner.releaseBegin.countDown()
        job.join()

        // A stranded PendingWrite would make the store reject this unrelated
        // preparation because it serializes native exports per root.
        val probe = store.prepareWrite(testPrefixKey())
        assertNotNull(probe)
        store.discard(requireNotNull(probe))
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun draftMtpUsesTheOrdinaryBeginPathAndNeverTouchesDiskPrefixState() = runBlocking {
        val root = temporaryRoot("mtp")
        val store = PersistentPrefixCacheStore(File(root, "prefix"), maxBytes = 64 * 1024L)
        val runner = PrefixRunner()
        val service = McaInferenceService(
            context = FakeContext(root),
            runners = mapOf(LocalChatRuntime.LLAMA_CPP to runner),
            installationScopeId = "stable-test-installation",
            persistentPrefixCacheStoreOverride = store
        )
        val identity = ModelRuntimeIdentity(
            modelId = "mtp.gguf",
            artifactFingerprint = PrefixCacheKey.sha256Utf8("mtp-model"),
            runtime = LocalChatRuntime.LLAMA_CPP,
            installationScopeId = "stable-test-installation",
            capabilities = setOf("draft_mtp")
        )
        service.loadModel(
            modelPath = File(root, "mtp.gguf").absolutePath,
            runtime = LocalChatRuntime.LLAMA_CPP,
            params = LoadParams(
                nCtx = 4096,
                nThreads = 2,
                advancedJson = "{\"spec_type\":\"draft-mtp\",\"spec_draft_n_max\":4}"
            ),
            runtimeIdentity = identity
        ).getOrThrow()

        runner.enqueue("mtp")
        val events = service.streamChat(
            request("mtp").copy(
                params = request("mtp").params.copy(
                    advancedJson = "{\"spec_type\":\"draft-mtp\",\"spec_draft_n_max\":4}"
                )
            )
        ).toList()

        assertTrue(events.last() is GenerateEvent.Done)
        assertEquals(1, runner.ordinaryBeginCalls)
        assertTrue(runner.prefixRequests.isEmpty())
        assertTrue(store.entries().isEmpty())
    }

    private suspend fun loadedService(
        root: File,
        runner: PrefixRunner,
        store: PersistentPrefixCacheStore
    ): McaInferenceService = McaInferenceService(
        context = FakeContext(root),
        runners = mapOf(LocalChatRuntime.LLAMA_CPP to runner),
        installationScopeId = "stable-test-installation",
        persistentPrefixCacheStoreOverride = store
    ).also { service ->
        service.loadModel(
            modelPath = File(root, "model.gguf").absolutePath,
            runtime = LocalChatRuntime.LLAMA_CPP,
            params = LoadParams(nCtx = 4096, nThreads = 2)
        ).getOrThrow()
    }

    private fun request(text: String): ChatRequest = ChatRequest(
        messages = listOf(ChatMessage(Role.USER, text)),
        params = GenerationParams(
            nCtx = 4096,
            nPredict = 8,
            nThreads = 2,
            systemPrompt = "stable persona",
            reasoningMode = ReasoningMode.OFF,
            hideReasoning = true
        ),
        persistentPrefixSystemPrompt = "stable persona"
    )

    private class PrefixRunner : LocalChatRunner {
        private var stats = loadedStats("{}")
        private val chunks = ArrayDeque<String>()
        private var pendingSessionState: PersistentPrefixCacheRequest? = null

        val prefixRequests = mutableListOf<PersistentPrefixCacheRequest>()
        val runtimeScopes = mutableListOf<Pair<String, String?>>()
        var ordinaryBeginCalls = 0
            private set
        var reportRestoreHit = false
        var writeState = true
        var deleteStateBeforeCommit = false
        var blockBegin = false
        val beginEntered = CountDownLatch(1)
        val releaseBegin = CountDownLatch(1)

        override val runtime: LocalChatRuntime = LocalChatRuntime.LLAMA_CPP
        override val isAvailable: Boolean = true
        override val loadError: Throwable? = null

        override fun initBackends(nativeLibDir: String) = Unit

        override fun loadModel(modelPath: String, paramsJson: String): Int {
            stats = loadedStats(paramsJson)
            return 0
        }

        override fun unloadModel() = Unit

        override fun beginCompletion(messagesJson: String, paramsJson: String): Int {
            ordinaryBeginCalls += 1
            recordRuntimeScope(paramsJson)
            return 0
        }

        override fun beginCompletionWithPrefixCache(
            messagesJson: String,
            paramsJson: String,
            prefixCache: PersistentPrefixCacheRequest?
        ): Int {
            val request = requireNotNull(prefixCache)
            prefixRequests += request
            recordRuntimeScope(paramsJson)
            val hit = reportRestoreHit && request.restoreStatePath != null
            pendingSessionState = request.takeIf { it.fullSessionState }
            val saved = !request.fullSessionState && !hit && writeState && request.writeStatePath != null
            if (saved) {
                val stateFile = File(requireNotNull(request.writeStatePath))
                stateFile.writeBytes("native-state".toByteArray())
                if (deleteStateBeforeCommit) stateFile.delete()
            }
            if (blockBegin) {
                beginEntered.countDown()
                releaseBegin.await(5, TimeUnit.SECONDS)
            }
            stats = JSONObject(stats)
                .put(
                    "persistentPrefixCache",
                    JSONObject()
                        .put("attempted", true)
                        .put("hit", hit)
                        .put("saved", saved)
                        .put("tokens", 17)
                        .put("reason", if (hit) "state_loaded" else if (saved) "state_saved" else "state_save_failed")
                )
                .toString()
            return 0
        }

        private fun recordRuntimeScope(paramsJson: String) {
            val params = JSONObject(paramsJson)
            runtimeScopes += params.optString("conversationOwnerId") to
                params.optString("conversationContextRevision").takeIf(String::isNotBlank)
        }

        override fun generateNextChunk(): String? {
            if (chunks.isNotEmpty()) return chunks.removeFirst()
            pendingSessionState?.let { request ->
                pendingSessionState = null
                val saved = writeState && request.writeStatePath != null
                if (saved) {
                    val stateFile = File(requireNotNull(request.writeStatePath))
                    stateFile.writeBytes("completed-turn-state".toByteArray())
                    if (deleteStateBeforeCommit) stateFile.delete()
                }
                stats = JSONObject(stats).put(
                    "persistentPrefixCache",
                    JSONObject()
                        .put("attempted", true)
                        .put("hit", reportRestoreHit && request.restoreStatePath != null)
                        .put("saved", saved)
                        .put("tokens", 24)
                        .put("reason", if (saved) "session_state_saved" else "session_state_save_failed")
                ).toString()
            }
            return null
        }

        override fun requestStop() = Unit
        override fun getRuntimeStatsJson(): String = stats
        override fun shutdown() = Unit

        fun enqueue(value: String) {
            chunks.clear()
            chunks.addLast(value)
        }

        private companion object {
            fun loadedStats(paramsJson: String): String {
                val params = runCatching { JSONObject(paramsJson) }.getOrElse { JSONObject() }
                val advanced = when (val raw = params.opt("advanced_json")) {
                    is JSONObject -> raw
                    is String -> runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
                    else -> JSONObject()
                }
                fun value(key: String, fallback: Any): Any = when {
                    params.has(key) && !params.isNull(key) -> params.opt(key)
                    advanced.has(key) && !advanced.isNull(key) -> advanced.opt(key)
                    else -> fallback
                }
                val nCtx = (value("n_ctx", 4096) as Number).toInt()
                val nThreads = (value("n_threads", 2) as Number).toInt()
                return JSONObject()
                    .put("backend", LocalChatRuntime.LLAMA_CPP.backendId)
                    .put("loaded", true)
                    .put("runnerReady", true)
                    .put("visionReady", false)
                    .put("nThreads", nThreads)
                    .put("nCtx", nCtx)
                    .put("maxAllTokens", nCtx)
                    .put("maxNewTokens", 8)
                    .put("backendDevices", JSONArray().put("cpu"))
                    .put(
                        "effectiveConfig",
                        JSONObject()
                            .put("n_ctx", nCtx)
                            .put("n_threads", nThreads)
                            .put("n_threads_batch", value("n_threads_batch", nThreads))
                            .put("n_batch", value("n_batch", 512))
                            .put("n_ubatch", value("n_ubatch", 512))
                            .put("n_gpu_layers", value("n_gpu_layers", 0))
                            .put("main_gpu", value("main_gpu", 0))
                            .put("split_mode", value("split_mode", "none"))
                            .put("n_cpu_moe", value("n_cpu_moe", 0))
                            .put("cache_type_k", value("cache_type_k", "f16"))
                            .put("cache_type_v", value("cache_type_v", "f16"))
                            .put("flash_attn", value("flash_attn", "auto"))
                            .put("perf", value("perf", false))
                            .put("n_parallel", value("n_parallel", 1))
                            .put("spec_type", value("spec_type", "none"))
                            .put("spec_draft_n_max", value("spec_draft_n_max", 0))
                            .put("mmap", value("mmap", true))
                            .put("mlock", value("mlock", false))
                    )
                    .toString()
            }
        }
    }

    private class FakeContext(private val root: File) : ContextWrapper(null) {
        private val files = File(root, "files").also(File::mkdirs)
        private val cache = File(root, "cache").also(File::mkdirs)
        private val noBackup = File(root, "no-backup").also(File::mkdirs)
        private val appInfo = ApplicationInfo().apply {
            nativeLibraryDir = File(root, "lib").also(File::mkdirs).absolutePath
        }

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getNoBackupFilesDir(): File = noBackup
        override fun getApplicationInfo(): ApplicationInfo = appInfo
    }

    private fun temporaryRoot(label: String): File = File(
        System.getProperty("java.io.tmpdir"),
        "mca-persistent-prefix-$label-${System.nanoTime()}"
    ).also(File::mkdirs)

    private fun testPrefixKey(): PrefixCacheKey {
        val digest = PrefixCacheKey.sha256Utf8("cancel-probe")
        return PrefixCacheKey(digest, digest, digest, digest, digest, digest)
    }
}
