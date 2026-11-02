package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentModelAttemptRef
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentModelPort
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentModelRequest
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentStopDispatch
import com.muyuchat.mca.CloudApiConfig
import com.muyuchat.mca.CloudApiFormat
import com.muyuchat.mca.CloudApiStore
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P05 record-scoped cloud config contract.
 *
 * The frozen `providerId == cloud:<recordId>` selects exactly one configured chat record; the
 * current store config is a fallback only when no record id exists. Unknown, non-chat or
 * unconfigured records must fail MODEL_NOT_LOADED without creating an HTTP call, and a protocol
 * NONE binding is rejected before any config (and therefore any credential) is read.
 */
class AgentCloudRecordConfigTest {

    @Test
    fun exactRecordConfigIsUsedForTheFrozenProviderId() = runBlocking {
        val store = store(
            records = listOf(
                recordJson("record-a", baseUrl = "https://record-a.test/v1", modelName = "model-a"),
                recordJson(
                    id = "record-b",
                    baseUrl = "https://record-b.test/v1",
                    modelName = "model-b",
                    apiKey = "key-b"
                )
            ),
            legacy = LegacyConfig(
                baseUrl = "https://legacy.test/v1",
                model = "legacy-model",
                apiKey = "legacy-key"
            )
        )
        val transport = CapturingTransport()
        val adapter = adapter(transport, CloudStoreModelConfigResolver(store))

        val result = adapter.infer(
            agentRequest(binding = cloudBinding("cloud:record-b", modelId = "model-b"))
        ) { }

        assertTrue(result is AgentModelResult.Success)
        val config = transport.requests.single().config
        assertEquals("https://record-b.test/v1", config.baseUrl)
        assertEquals("model-b", config.chatModel)
        assertEquals("key-b", config.apiKey)
        assertEquals(CloudApiFormat.OPENAI_RESPONSES, config.apiFormat)
        assertTrue(config.enabled)
    }

    @Test
    fun providerIdWithoutRecordIdFallsBackToTheCurrentStoreConfig() = runBlocking {
        val store = store(
            records = listOf(recordJson("record-a", baseUrl = "https://record-a.test/v1")),
            legacy = LegacyConfig(
                baseUrl = "https://legacy.test/v1",
                model = "legacy-model",
                apiKey = "legacy-key"
            )
        )
        val transport = CapturingTransport()
        val adapter = adapter(transport, CloudStoreModelConfigResolver(store))

        val result = adapter.infer(
            agentRequest(binding = cloudBinding("cloud", modelId = "legacy-model"))
        ) { }

        assertTrue(result is AgentModelResult.Success)
        val config = transport.requests.single().config
        assertEquals("https://legacy.test/v1", config.baseUrl)
        assertEquals("legacy-model", config.chatModel)
        assertEquals("legacy-key", config.apiKey)
    }

    @Test
    fun unknownRecordIdFailsModelNotLoadedInsteadOfBorrowingAnotherConfig() = runBlocking {
        val store = store(
            records = listOf(recordJson("record-a")),
            legacy = LegacyConfig(
                baseUrl = "https://legacy.test/v1",
                model = "legacy-model",
                apiKey = "legacy-key"
            )
        )
        val transport = CapturingTransport()

        val result = adapter(transport, CloudStoreModelConfigResolver(store)).infer(
            agentRequest(binding = cloudBinding("cloud:missing-record"))
        ) { }

        assertEquals("MODEL_NOT_LOADED", result.failureCode())
        assertEquals(0, transport.calls)
    }

    @Test
    fun unconfiguredRecordFailsModelNotLoaded() = runBlocking {
        val store = store(
            records = listOf(recordJson("record-empty", baseUrl = "")),
            legacy = LegacyConfig(
                baseUrl = "https://legacy.test/v1",
                model = "legacy-model",
                apiKey = "legacy-key"
            )
        )
        val transport = CapturingTransport()

        val result = adapter(transport, CloudStoreModelConfigResolver(store)).infer(
            agentRequest(binding = cloudBinding("cloud:record-empty"))
        ) { }

        assertEquals("MODEL_NOT_LOADED", result.failureCode())
        assertEquals(0, transport.calls)
    }

    @Test
    fun imageRecordIsNotUsableAsAChatBinding() = runBlocking {
        val store = store(
            records = listOf(recordJson("image-record", kind = "IMAGE")),
            legacy = null
        )
        val transport = CapturingTransport()

        val result = adapter(transport, CloudStoreModelConfigResolver(store)).infer(
            agentRequest(binding = cloudBinding("cloud:image-record"))
        ) { }

        assertEquals("MODEL_NOT_LOADED", result.failureCode())
        assertEquals(0, transport.calls)
    }

    @Test
    fun protocolNoneIsRejectedBeforeAnyConfigResolution() = runBlocking {
        val transport = CapturingTransport()
        var resolverCalls = 0
        val cloudAdapter = adapter(
            transport = transport,
            resolver = AgentCloudRequestConfigResolver {
                resolverCalls += 1
                CloudApiConfig(
                    enabled = true,
                    apiFormat = CloudApiFormat.OPENAI_RESPONSES,
                    baseUrl = "https://should-not-be-read.test/v1",
                    chatModel = "never"
                )
            }
        )

        val result = cloudAdapter.infer(
            agentRequest(
                binding = cloudBinding(
                    providerId = "cloud:record-a",
                    protocol = AgentProtocolMode.NONE
                )
            )
        ) { }

        assertEquals("MODEL_PROTOCOL_ERROR", result.failureCode())
        assertEquals(0, resolverCalls)
        assertEquals(0, transport.calls)
    }

    @Test
    fun nonResponsesRecordIsRejectedAsProtocolErrorNotAsMissingConfig() = runBlocking {
        val store = store(
            records = listOf(
                recordJson("compat-record", apiFormat = "OPENAI_COMPATIBLE")
            ),
            legacy = null
        )
        val transport = CapturingTransport()

        val result = adapter(transport, CloudStoreModelConfigResolver(store)).infer(
            agentRequest(binding = cloudBinding("cloud:compat-record"))
        ) { }

        assertEquals("MODEL_PROTOCOL_ERROR", result.failureCode())
        assertEquals(0, transport.calls)
    }

    @Test
    fun defaultAdapterWithoutResolverKeepsTheCurrentStoreConfigBehavior() = runBlocking {
        val transport = CapturingTransport()
        val legacy = CloudApiConfig(
            enabled = true,
            apiFormat = CloudApiFormat.OPENAI_RESPONSES,
            baseUrl = "https://legacy-default.test/v1",
            chatModel = "legacy-default",
            apiKey = "legacy-default-key"
        )
        val cloudAdapter = adapter(transport = transport, resolver = null, legacy = { legacy })

        val result = cloudAdapter.infer(
            agentRequest(binding = cloudBinding("cloud:any-record"))
        ) { }

        assertTrue(result is AgentModelResult.Success)
        val config = transport.requests.single().config
        assertEquals("https://legacy-default.test/v1", config.baseUrl)
        assertEquals("legacy-default", config.chatModel)
    }

    @Test
    fun routerDeliversTheFrozenCloudBindingToTheRecordConfigAdapter() = runBlocking {
        val store = store(
            records = listOf(
                recordJson(
                    id = "record-b",
                    baseUrl = "https://record-b.test/v1",
                    modelName = "model-b",
                    apiKey = "key-b"
                )
            ),
            legacy = null
        )
        val transport = CapturingTransport()
        val cloudAdapter = adapter(transport, CloudStoreModelConfigResolver(store))
        val localPort = CountingModelPort()
        val router = AgentRoutingModelPort(local = localPort, cloud = cloudAdapter)

        val result = router.infer(
            agentRequest(binding = cloudBinding("cloud:record-b", modelId = "model-b"))
        ) { }

        assertTrue(result is AgentModelResult.Success)
        assertEquals(0, localPort.calls)
        val config = transport.requests.single().config
        assertEquals("https://record-b.test/v1", config.baseUrl)
        assertEquals("model-b", config.chatModel)
        assertEquals("key-b", config.apiKey)
    }

    private fun adapter(
        transport: CapturingTransport,
        resolver: AgentCloudRequestConfigResolver?,
        legacy: () -> CloudApiConfig? = { null }
    ): AgentCloudModelAdapter = AgentCloudModelAdapter(
        configSource = AgentCloudConfigSource { legacy() },
        transport = transport,
        requestConfig = resolver
    )

    private fun cloudBinding(
        providerId: String,
        modelId: String = "model-a",
        protocol: AgentProtocolMode = AgentProtocolMode.NATIVE
    ): AgentModelBinding = AgentModelBinding(
        providerId = providerId,
        modelId = modelId,
        runtime = "CLOUD_OPENAI_RESPONSES",
        assetFingerprint = providerId + ":2",
        protocolMode = protocol,
        modelDisplayName = "Cloud model"
    )

    private fun recordJson(
        id: String,
        baseUrl: String = "https://$id.test/v1",
        modelName: String = "model-$id",
        apiKey: String = "key-$id",
        kind: String = "CHAT",
        apiFormat: String = "OPENAI_RESPONSES"
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind)
        .put("apiFormat", apiFormat)
        .put("providerName", "provider-$id")
        .put("displayName", "Record $id")
        .put("baseUrl", baseUrl)
        .put("apiKey", apiKey)
        .put("modelName", modelName)
        .put("supportsTools", true)
        .put("imageApiFormat", "OPENAI_IMAGES")
        .put("imageSize", "1024x1024")
        .put("createdAt", 1L)
        .put("updatedAt", 2L)

    private class CountingModelPort : AgentModelPort {
        var calls = 0

        override suspend fun infer(
            request: AgentModelRequest,
            progress: AgentModelProgressSink
        ): AgentModelResult {
            calls += 1
            return AgentModelResult.Success("local")
        }

        override suspend fun requestStop(attempt: AgentModelAttemptRef): AgentStopDispatch {
            return AgentStopDispatch(false, "test local port")
        }
    }

    /**
     * Seeds the real [CloudApiStore] persisted shape. Records are written as plaintext-JSON
     * fixtures (no `apiKeyCipher`), so no Android Keystore is touched on the JVM test.
     */
    private fun store(records: List<JSONObject>, legacy: LegacyConfig?): CloudApiStore {
        val values = mutableMapOf<String, Any?>()
        if (records.isNotEmpty()) {
            values[KEY_CLOUD_MODELS] = JSONArray(records).toString()
        }
        if (legacy != null) {
            values[KEY_ENABLED] = true
            values[KEY_API_FORMAT] = legacy.format
            values[KEY_BASE_URL] = legacy.baseUrl
            values[KEY_CHAT_MODEL] = legacy.model
            values[KEY_API_KEY_LEGACY] = legacy.apiKey
        }
        return CloudApiStore(MemoryPreferences(values).prefs)
    }

    private data class LegacyConfig(
        val baseUrl: String,
        val model: String,
        val apiKey: String,
        val format: String = "OPENAI_RESPONSES"
    )

    private class CapturingTransport : AgentCloudStreamTransport {
        val requests = CopyOnWriteArrayList<AgentCloudStreamRequest>()
        var calls = 0

        override suspend fun stream(
            request: AgentCloudStreamRequest,
            onCall: (AgentCloudCallHandle) -> Unit,
            onVisibleDelta: (String) -> Unit
        ): AgentCloudStreamTerminal {
            calls += 1
            requests += request
            onCall(AgentCloudCallHandle { })
            onVisibleDelta("hello")
            return AgentCloudStreamTerminal.Completed("hello", null)
        }
    }

    /** Minimal in-memory SharedPreferences; only the keys CloudApiStore touches are implemented. */
    private class MemoryPreferences(initial: Map<String, Any?> = emptyMap()) {
        private val values = initial.toMutableMap()

        private val editor: android.content.SharedPreferences.Editor = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(android.content.SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when {
                method.name.startsWith("put") -> {
                    values[args!![0] as String] = args[1]
                    proxy
                }

                method.name == "remove" -> {
                    values.remove(args!![0] as String)
                    proxy
                }

                method.name == "clear" -> {
                    values.clear()
                    proxy
                }

                method.name == "apply" -> null
                method.name == "commit" -> true
                else -> error("unexpected editor call ${method.name}")
            }
        } as android.content.SharedPreferences.Editor

        val prefs: android.content.SharedPreferences = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(android.content.SharedPreferences::class.java)
        ) { _, method, args ->
            when {
                method.name == "edit" -> editor
                method.name == "contains" -> values.containsKey(args!![0] as String)
                method.name.startsWith("get") -> values[args!![0] as String] ?: args[1]
                else -> error("unexpected preferences call ${method.name}")
            }
        } as android.content.SharedPreferences
    }

    private companion object {
        const val KEY_CLOUD_MODELS = "cloud_models_json"
        const val KEY_ENABLED = "cloud_enabled"
        const val KEY_API_FORMAT = "cloud_api_format"
        const val KEY_BASE_URL = "cloud_base_url"
        const val KEY_CHAT_MODEL = "cloud_chat_model"
        const val KEY_API_KEY_LEGACY = "cloud_api_key"
    }
}
