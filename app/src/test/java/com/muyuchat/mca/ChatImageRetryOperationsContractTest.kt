package com.muyuchat.mca

import android.content.SharedPreferences
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural contract for the chat image retry snapshot and the commit-recovery decision.
 *
 * [ChatImageRetryPort] is an interface, so a hand-written port supplies the UI state, records the
 * status messages and state updates, and deliberately fails on every collaborator the operation
 * under test must not touch (the session store, the local image store, the cloud store). Assertions
 * are on the returned [ImageGenerationJobSpec], the recorded state, and the models the port was
 * asked for -- never on call order between private helpers.
 */
class ChatImageRetryOperationsContractTest {

    @Test
    fun `a local retry rebuilds the snapshot from the stored options and the selected model`() = runBlocking {
        withTemporaryBundle { root ->
            val primary = root.resolve("model.gguf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val model = localModel(primary, root)
            val port = FakeRetryPort(MainUiState(localImageModels = listOf(model)))
            val options = LocalImageGenerationOptions(
                width = 512,
                height = 768,
                steps = 8,
                negativePrompt = "blurry",
                seed = 7
            )

            val spec = requireNotNull(
                chatImageRetrySnapshot(
                    port = port,
                    request = request(backendId = "LOCAL", modelId = model.id, optionsJson = options.toJson().toString()),
                    chatSessionId = "session-1"
                )
            )

            assertEquals(ImageBackend.LOCAL, spec.backend)
            assertEquals("a red panda", spec.prompt)
            assertEquals(model.id, spec.modelId)
            assertEquals(model, spec.localModelSnapshot)
            assertNull(spec.cloudConfigSnapshot)
            assertEquals("session-1", spec.chatSessionId)
            assertEquals("request-1", spec.chatMessageId)
            assertEquals(512, spec.options.width)
            assertEquals(768, spec.options.height)
            assertEquals(8, spec.options.steps)
            assertEquals("blurry", spec.options.negativePrompt)
            assertEquals(7, spec.options.seed)
            // A retry is a fresh text-to-image request: prepared input paths never come back.
            assertEquals(LocalImageTaskMode.TEXT_TO_IMAGE, spec.options.taskMode)
            assertNull(spec.options.inputImage)
            assertNull(spec.options.maskImage)
            assertNull(spec.options.controlImage)
            assertEquals(LocalImageInputDraft(), spec.inputDraft)
        }
    }

    @Test
    fun `a local retry keeps the recorded model name and falls back to the model display name`() = runBlocking {
        withTemporaryBundle { root ->
            val primary = root.resolve("model.gguf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val model = localModel(primary, root, displayName = "Local SD 1.5")
            val optionsJson = LocalImageGenerationOptions(steps = 4).toJson().toString()

            val named = requireNotNull(
                chatImageRetrySnapshot(
                    FakeRetryPort(MainUiState(localImageModels = listOf(model))),
                    request(backendId = "LOCAL", modelId = model.id, optionsJson = optionsJson, modelName = "Recorded Name"),
                    "session-1"
                )
            )
            assertEquals("Recorded Name", named.modelName)

            val blank = requireNotNull(
                chatImageRetrySnapshot(
                    FakeRetryPort(MainUiState(localImageModels = listOf(model))),
                    request(backendId = "LOCAL", modelId = model.id, optionsJson = optionsJson, modelName = "   "),
                    "session-1"
                )
            )
            assertEquals("Local SD 1.5", blank.modelName)
        }
    }

    @Test
    fun `a retry without usable stored options or backend identity is refused`() {
        val port = FakeRetryPort(MainUiState())

        assertNull(
            chatImageRetrySnapshot(port, request(backendId = "LOCAL", modelId = "m1", optionsJson = null), "s")
        )
        assertNull(
            chatImageRetrySnapshot(port, request(backendId = "LOCAL", modelId = "m1", optionsJson = "not json"), "s")
        )
        assertNull(
            chatImageRetrySnapshot(
                port,
                request(backendId = null, modelId = "m1", optionsJson = """{"steps":4}"""),
                "s"
            )
        )
        assertNull(
            chatImageRetrySnapshot(
                port,
                request(backendId = "NOT_A_BACKEND", modelId = "m1", optionsJson = """{"steps":4}"""),
                "s"
            )
        )
        assertNull(
            chatImageRetrySnapshot(
                port,
                request(backendId = "LOCAL", modelId = null, optionsJson = """{"steps":4}"""),
                "s"
            )
        )
    }

    @Test
    fun `a local retry is refused when the model is gone unconfigured or the profile changed`() = runBlocking {
        withTemporaryBundle { root ->
            val primary = root.resolve("model.gguf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val configured = localModel(primary, root)
            val unconfigured = localModel(root.resolve("missing.gguf"), root, id = "missing-model")
            val optionsJson = LocalImageGenerationOptions(steps = 4).toJson().toString()

            // The recorded model is no longer in the catalogue.
            assertNull(
                chatImageRetrySnapshot(
                    FakeRetryPort(MainUiState(localImageModels = listOf(configured))),
                    request(backendId = "LOCAL", modelId = "other-model", optionsJson = optionsJson),
                    "s"
                )
            )
            // The recorded model exists but its files are not usable.
            assertNull(
                chatImageRetrySnapshot(
                    FakeRetryPort(MainUiState(localImageModels = listOf(unconfigured))),
                    request(backendId = "LOCAL", modelId = unconfigured.id, optionsJson = optionsJson),
                    "s"
                )
            )
            // The stored fingerprint no longer describes the selected profile.
            assertNull(
                chatImageRetrySnapshot(
                    FakeRetryPort(MainUiState(localImageModels = listOf(configured))),
                    request(
                        backendId = "LOCAL",
                        modelId = configured.id,
                        optionsJson = optionsJson,
                        modelFingerprint = "stale-fingerprint"
                    ),
                    "s"
                )
            )
            // The matching fingerprint is accepted.
            val accepted = chatImageRetrySnapshot(
                FakeRetryPort(MainUiState(localImageModels = listOf(configured))),
                request(
                    backendId = "LOCAL",
                    modelId = configured.id,
                    optionsJson = optionsJson,
                    modelFingerprint = configured.chatImageRetryFingerprint()
                ),
                "s"
            )
            assertEquals(configured.id, requireNotNull(accepted).modelId)
        }
    }

    @Test
    fun `a cloud retry requires a configured image model of that exact id`() {
        val image = cloudModel(id = "cloud-image", kind = CloudModelKind.IMAGE)
        val chat = cloudModel(id = "cloud-chat", kind = CloudModelKind.CHAT)
        val unconfigured = cloudModel(id = "cloud-broken", kind = CloudModelKind.IMAGE, baseUrl = "")
        val state = MainUiState(cloudModels = listOf(image, chat, unconfigured))
        val optionsJson = LocalImageGenerationOptions(steps = 4).toJson().toString()

        val spec = requireNotNull(
            chatImageRetrySnapshot(
                FakeRetryPort(state),
                request(backendId = "CLOUD", modelId = image.id, optionsJson = optionsJson),
                "session-2"
            )
        )
        assertEquals(ImageBackend.CLOUD, spec.backend)
        assertNull(spec.localModelSnapshot)
        assertEquals(image.id, spec.modelId)
        assertEquals(image.displayName, spec.modelName)
        val cloudConfig = requireNotNull(spec.cloudConfigSnapshot)
        assertEquals(image.modelName, cloudConfig.imageModel)
        assertEquals(image.baseUrl, cloudConfig.baseUrl)
        assertEquals(image.displayName, cloudConfig.displayName)
        assertEquals(LocalImageTaskMode.TEXT_TO_IMAGE, spec.options.taskMode)
        assertEquals("session-2", spec.chatSessionId)

        // A chat model, an unusable image model and a stale fingerprint are all refused.
        assertNull(
            chatImageRetrySnapshot(
                FakeRetryPort(state),
                request(backendId = "CLOUD", modelId = chat.id, optionsJson = optionsJson),
                "s"
            )
        )
        assertNull(
            chatImageRetrySnapshot(
                FakeRetryPort(state),
                request(backendId = "CLOUD", modelId = unconfigured.id, optionsJson = optionsJson),
                "s"
            )
        )
        assertNull(
            chatImageRetrySnapshot(
                FakeRetryPort(state),
                request(
                    backendId = "CLOUD",
                    modelId = image.id,
                    optionsJson = optionsJson,
                    modelFingerprint = "stale-fingerprint"
                ),
                "s"
            )
        )
    }

    @Test
    fun `recovery returns the inputs untouched when the journal cannot be opened`() = runBlocking {
        val sessions = emptyList<ChatSessionRecord>()
        val images = emptyList<ImageAssetRecord>()
        val port = FakeRetryPort(MainUiState(), journal = null)

        val recovered = recoverPendingImageCommits(port, sessions, images)

        assertSame(sessions, recovered.first)
        assertSame(images, recovered.second)
    }

    @Test
    fun `recovery with an empty journal changes nothing and touches no store`() = runBlocking {
        withTemporaryBundle { root ->
            val assets = root.resolve("assets").apply { mkdirs() }
            val journalRoot = root.resolve("journal").apply { mkdirs() }
            val journal = ChatImageCommitJournal(journalRoot, assets)
            assertEquals(0, journal.enumerate().validEntries.size)
            val port = FakeRetryPort(MainUiState(), journal = journal)
            val sessions = emptyList<ChatSessionRecord>()
            val images = emptyList<ImageAssetRecord>()

            val recovered = recoverPendingImageCommits(port, sessions, images)

            assertSame(sessions, recovered.first)
            assertSame(images, recovered.second)
            // The journal scanned nothing, so no session or image write was attempted: the fake
            // session store would have failed the test instead of silently accepting it.
            assertTrue(journal.enumerate().validEntries.isEmpty())
        }
    }

    @Test
    fun `an entry that cannot be durably saved keeps the journal and changes nothing`() = runBlocking {
        withTemporaryBundle { root ->
            val assets = root.resolve("assets").apply { mkdirs() }
            val journalRoot = root.resolve("journal").apply { mkdirs() }
            val journal = ChatImageCommitJournal(journalRoot, assets)
            val imageFile = assets.resolve("generated.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val asset = ImageAssetRecord(
                id = "asset-1",
                name = "generated.png",
                uriString = imageFile.toURI().toString(),
                source = "chat",
                prompt = "a red panda",
                sizeBytes = imageFile.length()
            )
            journal.stage(
                ChatImageCommitJournal.Record(
                    jobId = "job-1",
                    chatSessionId = "session-1",
                    requestId = "request-1",
                    imageAssetMetadataJson = ChatImageCommitJournal.serializeImageAssetRecord(asset)
                )
            )
            assertEquals(1, journal.enumerate().validEntries.size)
            val port = FakeRetryPort(MainUiState(), journal = journal)
            val sessions = emptyList<ChatSessionRecord>()
            val images = emptyList<ImageAssetRecord>()

            val recovered = recoverPendingImageCommits(port, sessions, images)

            assertSame(sessions, recovered.first)
            assertSame(images, recovered.second)
            // The session store did not accept the write, so nothing may be claimed: the entry
            // survives for the next recovery attempt.
            assertEquals(1, journal.enumerate().validEntries.size)
        }
    }

    @Test
    fun `deleting the selected chat model moves the selection to the remaining chat model`() = runBlocking {
        val first = cloudModel(id = "cloud-chat-1", kind = CloudModelKind.CHAT)
        val second = cloudModel(id = "cloud-chat-2", kind = CloudModelKind.CHAT)
        val port = FakeRetryPort(
            MainUiState(
                cloudModels = listOf(first, second),
                selectedCloudChatModelId = first.id,
                selectedChatBackend = ChatBackend.CLOUD
            )
        )

        deleteCloudModelRecord(port, first.id)

        val state = port.uiState()
        assertEquals(listOf(second.id), state.cloudModels.map { it.id })
        assertEquals(second.id, state.selectedCloudChatModelId)
        assertEquals(ChatBackend.CLOUD, state.selectedChatBackend)
        assertEquals(second.modelName, state.cloudApiConfig.chatModel)
        assertEquals(second.baseUrl, state.cloudApiConfig.baseUrl)
        assertEquals("已删除云端模型：${first.displayName}", state.statusMessage)
        assertEquals(listOf(second.id), port.cloudStore.loadModels().map { it.id })
        assertEquals(second.id, port.cloudStore.loadSelectedCloudChatModelId())
    }

    @Test
    fun `deleting the last chat model falls back to the local backend`() = runBlocking {
        val only = cloudModel(id = "cloud-chat-1", kind = CloudModelKind.CHAT)
        val port = FakeRetryPort(
            MainUiState(
                cloudModels = listOf(only),
                selectedCloudChatModelId = only.id,
                selectedChatBackend = ChatBackend.CLOUD
            )
        )

        deleteCloudModelRecord(port, only.id)

        val state = port.uiState()
        assertTrue(state.cloudModels.isEmpty())
        assertNull(state.selectedCloudChatModelId)
        assertEquals(ChatBackend.LOCAL, state.selectedChatBackend)
        assertNull(port.cloudStore.loadSelectedCloudChatModelId())
        assertEquals(ChatBackend.LOCAL, port.cloudStore.loadSelectedBackend())
    }

    @Test
    fun `deleting an unknown model only reports it and writes nothing`() = runBlocking {
        val only = cloudModel(id = "cloud-chat-1", kind = CloudModelKind.CHAT)
        val port = FakeRetryPort(MainUiState(cloudModels = listOf(only), selectedCloudChatModelId = only.id))
        port.cloudStore.saveModels(listOf(only))
        port.cloudStore.saveSelectedCloudChatModelId(only.id)

        deleteCloudModelRecord(port, "not-a-model")

        assertEquals(listOf("未找到要删除的云端模型"), port.statusMessages)
        assertEquals(listOf(only.id), port.uiState().cloudModels.map { it.id })
        assertEquals(only.id, port.uiState().selectedCloudChatModelId)
        assertEquals(listOf(only.id), port.cloudStore.loadModels().map { it.id })
    }

    private class FakeRetryPort(
        private var state: MainUiState = MainUiState(),
        private val journal: ChatImageCommitJournal? = null
    ) : ChatImageRetryPort {
        val statusMessages = mutableListOf<String?>()

        /** A real store over in-memory preferences: deletion effects stay observable. */
        val cloudStore = CloudApiStore(MemoryPreferences().instance)

        override fun uiState(): MainUiState = state

        override fun updateUiState(transform: (MainUiState) -> MainUiState) {
            state = transform(state)
        }

        override val cloudApiStore: CloudApiStore get() = cloudStore

        override val localImageModelStore: LocalImageModelStore
            get() = error("This operation must not touch the local image model store.")

        override val chatImageCommitJournal: ChatImageCommitJournal
            get() = journal ?: error("No image commit journal is available.")

        override val chatSessionStore: ChatSessionStore
            get() = error("This operation must not touch the chat session store.")

        override fun showStatus(message: String?) {
            statusMessages += message
        }

        override fun invalidatePendingChatImagePreparation() = Unit

        override fun sendChatImageRequest(
            visibleUserText: String,
            prompt: String,
            optionsOverride: LocalImageGenerationOptions?,
            promptEnvelope: PromptEnvelope?,
            contextTraceJson: String?
        ) = Unit

        override fun pendingImageTranslation(): PendingImageTranslation? = null

        override fun setPendingImageTranslation(value: PendingImageTranslation?) = Unit
    }

    private class MemoryPreferences {
        val values = linkedMapOf<String, Any?>()

        val instance: SharedPreferences = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when {
                method.name == "edit" -> editor()
                method.name == "contains" -> values.containsKey(args!![0])
                method.name == "getAll" -> values.toMap()
                method.name.startsWith("get") -> values[args!![0] as String] ?: args[1]
                method.name == "toString" -> "MemoryPreferences"
                else -> null
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = linkedMapOf<String, Any?>()
            var clear = false
            return Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)
            ) { proxy, method, args ->
                when {
                    method.name.startsWith("put") -> {
                        pending[args!![0] as String] = args[1]
                        proxy
                    }
                    method.name == "remove" -> {
                        pending[args!![0] as String] = null
                        proxy
                    }
                    method.name == "clear" -> {
                        clear = true
                        proxy
                    }
                    method.name == "apply" || method.name == "commit" -> {
                        if (clear) values.clear()
                        pending.forEach { (key, value) ->
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        true
                    }
                    else -> null
                }
            } as SharedPreferences.Editor
        }
    }

    private fun request(
        backendId: String?,
        modelId: String?,
        optionsJson: String?,
        modelName: String? = null,
        modelFingerprint: String? = null
    ): ChatGeneratedImageRequest = ChatGeneratedImageRequest(
        id = "request-1",
        prompt = "a red panda",
        status = ChatGeneratedImageStatus.DONE,
        backendId = backendId,
        modelId = modelId,
        modelName = modelName,
        modelFingerprint = modelFingerprint,
        generationOptionsJson = optionsJson
    )

    private fun localModel(
        primary: File,
        root: File,
        id: String = "local-image-1",
        displayName: String = "Local Image"
    ): LocalImageModelRecord = LocalImageModelRecord(
        id = id,
        displayName = displayName,
        path = primary.absolutePath,
        fileName = primary.name,
        sizeBytes = primary.length(),
        sha256 = "c".repeat(64),
        runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
        family = LocalImageModelFamily.SD15,
        bundleRoot = root.absolutePath,
        updatedAt = 7L
    )

    private fun cloudModel(
        id: String,
        kind: CloudModelKind,
        baseUrl: String = "https://example.invalid/v1"
    ): CloudModelRecord = CloudModelRecord(
        id = id,
        kind = kind,
        apiFormat = CloudApiFormat.OPENAI_COMPATIBLE,
        providerName = "test",
        displayName = "Cloud $id",
        baseUrl = baseUrl,
        apiKey = "",
        modelName = "model-$id"
    )

    private suspend fun <T> withTemporaryBundle(block: suspend (File) -> T): T {
        val root = Files.createTempDirectory("chat-image-retry").toFile().canonicalFile
        try {
            return block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
