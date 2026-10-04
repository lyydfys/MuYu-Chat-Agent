package com.muyuchat.mca

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.muyuchat.core.engine.GenerationParams
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural contract for [GenerationPreferences]: persisted Local API switches and credential
 * shape, the persistent-prefix-cache default, the runtime override field set, the model-scoped
 * generation profile keys, the cloud preset table and the credential-write failure wording.
 *
 * The store only touches `SharedPreferences`, so the test supplies an in-memory preferences
 * implementation through the same interface (`Proxy`) and the same context wrapper the existing
 * JVM tests use. No Robolectric, no mock framework, no real `Application`.
 */
class GenerationPreferencesContractTest {

    @Test
    fun `a stored or cleared credential reports no failure message`() {
        assertNull(credentialWriteFailureMessage(CredentialSaveResult.Stored))
        assertNull(credentialWriteFailureMessage(CredentialSaveResult.Cleared))
        assertTrue(CredentialSaveResult.Stored.isStored)
        assertTrue(CredentialSaveResult.Cleared.isStored)
    }

    @Test
    fun `an unavailable keystore reports no safe save and no plaintext write`() {
        val message = requireNotNull(
            credentialWriteFailureMessage(CredentialSaveResult.EncryptionUnavailable)
        )

        assertFalse(CredentialSaveResult.EncryptionUnavailable.isStored)
        assertTrue(message.contains("未能安全保存"))
        // The one fact the user must be able to trust: nothing was written in the clear.
        assertTrue(message.contains("未写入明文"))
        assertFalse(message.contains("已保存"))
    }

    @Test
    fun `a failed verification tells the user the old configuration was kept`() {
        val message = requireNotNull(
            credentialWriteFailureMessage(CredentialSaveResult.VerificationFailed)
        )

        assertFalse(CredentialSaveResult.VerificationFailed.isStored)
        assertTrue(message.contains("校验失败"))
        assertTrue(message.contains("已保留原有配置"))
        assertTrue(message.contains("未写入明文"))
        assertFalse(message.contains("已保存"))
    }

    @Test
    fun `the two credential failure modes stay distinguishable`() {
        assertNotEquals(
            credentialWriteFailureMessage(CredentialSaveResult.EncryptionUnavailable),
            credentialWriteFailureMessage(CredentialSaveResult.VerificationFailed)
        )
    }

    @Test
    fun `api switches default to disabled in the api preferences file`() {
        val context = FakePreferencesContext()

        assertEquals(LocalApiPreferences(apiEnabled = false, restEnabled = false), GenerationPreferences.loadApiPreferences(context))
        assertEquals(listOf("mca_api"), context.openedFileNames)
    }

    @Test
    fun `rest is stored and reported as enabled only while the api itself is enabled`() {
        val context = FakePreferencesContext()

        GenerationPreferences.persistApiPreferences(context, apiEnabled = false, restEnabled = true)
        assertEquals(false, context.value("mca_api", "api_enabled"))
        assertEquals(false, context.value("mca_api", "rest_enabled"))
        assertEquals(
            LocalApiPreferences(apiEnabled = false, restEnabled = false),
            GenerationPreferences.loadApiPreferences(context)
        )

        GenerationPreferences.persistApiPreferences(context, apiEnabled = true, restEnabled = true)
        assertEquals(true, context.value("mca_api", "rest_enabled"))
        assertEquals(
            LocalApiPreferences(apiEnabled = true, restEnabled = true),
            GenerationPreferences.loadApiPreferences(context)
        )

        GenerationPreferences.persistApiPreferences(context, apiEnabled = true, restEnabled = false)
        assertEquals(
            LocalApiPreferences(apiEnabled = true, restEnabled = false),
            GenerationPreferences.loadApiPreferences(context)
        )
    }

    @Test
    fun `api key is created once with the documented shape and then reused`() {
        val context = FakePreferencesContext()

        val created = GenerationPreferences.loadOrCreateApiKey(context)
        assertTrue(created.startsWith("mca-"))
        assertTrue(created.removePrefix("mca-").matches(Regex("[0-9a-f]{24}")))
        assertEquals(created, context.value("mca_api", "api_key"))
        assertEquals(created, GenerationPreferences.loadOrCreateApiKey(context))
    }

    @Test
    fun `stored api key is returned verbatim and a blank one is replaced`() {
        val kept = FakePreferencesContext().apply { seed("mca_api", "api_key", "mca-kept-by-the-user") }
        assertEquals("mca-kept-by-the-user", GenerationPreferences.loadOrCreateApiKey(kept))

        val blank = FakePreferencesContext().apply { seed("mca_api", "api_key", "   ") }
        val recreated = GenerationPreferences.loadOrCreateApiKey(blank)
        assertNotEquals("   ", recreated)
        assertTrue(recreated.startsWith("mca-"))
        assertEquals(recreated, blank.value("mca_api", "api_key"))
    }

    @Test
    fun `persistent prefix cache defaults to enabled and round trips`() {
        val context = FakePreferencesContext()

        assertTrue(GenerationPreferences.loadPersistentPrefixCacheEnabled(context))
        assertNull(context.value("mca_generation_params", "persistent_prefix_cache_enabled"))

        GenerationPreferences.persistPersistentPrefixCacheEnabled(context, false)
        assertFalse(GenerationPreferences.loadPersistentPrefixCacheEnabled(context))
        assertEquals(false, context.value("mca_generation_params", "persistent_prefix_cache_enabled"))

        GenerationPreferences.persistPersistentPrefixCacheEnabled(context, true)
        assertTrue(GenerationPreferences.loadPersistentPrefixCacheEnabled(context))
    }

    @Test
    fun `runtime override fields fail open on missing malformed or blank storage`() {
        val context = FakePreferencesContext()
        assertTrue(GenerationPreferences.loadRuntimeUserOverrideFields(context).isEmpty())
        assertEquals("mca_generation_params", context.openedFileNames.single())

        context.seed("mca_generation_params", "runtime_user_fields", "{not a json array")
        assertTrue(GenerationPreferences.loadRuntimeUserOverrideFields(context).isEmpty())

        context.seed("mca_generation_params", "runtime_user_fields", """["temperature","","   ","top_k"]""")
        assertEquals(
            setOf("temperature", "top_k"),
            GenerationPreferences.loadRuntimeUserOverrideFields(context)
        )
    }

    @Test
    fun `runtime override fields are persisted as a sorted json array`() {
        val context = FakePreferencesContext()

        GenerationPreferences.persistRuntimeUserOverrideFields(
            context,
            setOf("top_p", "temperature", "top_k")
        )

        assertEquals(
            """["temperature","top_k","top_p"]""",
            context.value("mca_generation_params", "runtime_user_fields")
        )
        assertEquals(
            setOf("temperature", "top_k", "top_p"),
            GenerationPreferences.loadRuntimeUserOverrideFields(context)
        )
    }

    @Test
    fun `model generation profile keys are backend scoped and reject a blank id`() {
        assertNull(GenerationPreferences.modelGenerationProfileKey(ChatBackend.LOCAL, null))
        assertNull(GenerationPreferences.modelGenerationProfileKey(ChatBackend.LOCAL, "   "))
        assertNull(GenerationPreferences.modelGenerationProfileKey(ChatBackend.CLOUD, ""))

        assertEquals(
            "local:qwen3",
            GenerationPreferences.modelGenerationProfileKey(ChatBackend.LOCAL, "  qwen3  ")
        )
        assertEquals(
            "cloud:gpt-5",
            GenerationPreferences.modelGenerationProfileKey(ChatBackend.CLOUD, "gpt-5")
        )
    }

    @Test
    fun `model generation params are stored per backend and model and a blank id writes nothing`() {
        val context = FakePreferencesContext()
        val store = ModelGenerationProfileStore(context)
        val defaults = GenerationParams()
        val custom = defaults.copy(temperature = 0.25f, nPredict = 111, topK = 7)

        // First load creates the model's own profile document from the defaults.
        assertEquals(defaults, GenerationPreferences.loadModelGenerationParams(store, ChatBackend.LOCAL, "m1", defaults))
        assertTrue(store.contains("local:m1"))

        GenerationPreferences.persistModelGenerationParams(store, ChatBackend.LOCAL, "m1", custom)
        val restored = GenerationPreferences.loadModelGenerationParams(store, ChatBackend.LOCAL, "m1", defaults)
        assertEquals(0.25f, restored.temperature, 0.0f)
        assertEquals(111, restored.nPredict)
        assertEquals(7, restored.topK)

        // The same id on the other backend is a different profile.
        assertEquals(
            defaults.temperature,
            GenerationPreferences.loadModelGenerationParams(store, ChatBackend.CLOUD, "m1", defaults).temperature,
            0.0f
        )

        val before = context.value("mca_model_generation_profiles_v1", "profiles_json")
        GenerationPreferences.persistModelGenerationParams(store, ChatBackend.LOCAL, "   ", custom)
        GenerationPreferences.persistModelGenerationParams(store, ChatBackend.LOCAL, null, custom)
        assertEquals(before, context.value("mca_model_generation_profiles_v1", "profiles_json"))
    }

    @Test
    fun `cloud presets name a wire format and leave the endpoint to the user`() {
        assertEquals(
            listOf("openai", "responses", "anthropic"),
            builtInCloudProviderPresets.map { it.key }
        )
        builtInCloudProviderPresets.forEach { preset ->
            assertEquals("", preset.baseUrl)
            assertEquals("", preset.chatModel)
            assertEquals("", preset.imageModel)
            assertEquals("1024x1024", preset.imageSize)
            assertEquals("自定义推理引擎", preset.displayName)
            assertTrue(preset.providerName.isNotBlank())
        }

        assertEquals(CloudApiFormat.OPENAI_COMPATIBLE, findCloudProviderPreset("openai")?.format)
        assertEquals(CloudApiFormat.OPENAI_RESPONSES, findCloudProviderPreset("responses")?.format)
        assertEquals(CloudApiFormat.ANTHROPIC, findCloudProviderPreset("anthropic")?.format)
        assertNull(findCloudProviderPreset("OpenAI"))
        assertNull(findCloudProviderPreset(""))
    }

    /** One in-memory `SharedPreferences` per file name, plus every file name the code asked for. */
    private class FakePreferencesContext : ContextWrapper(null) {
        private val files = linkedMapOf<String, MemoryPreferences>()
        val openedFileNames = mutableListOf<String>()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            openedFileNames += name
            return files.getOrPut(name) { MemoryPreferences() }.instance
        }

        fun seed(fileName: String, key: String, value: Any?) {
            getSharedPreferences(fileName, Context.MODE_PRIVATE)
            files.getValue(fileName).values[key] = value
        }

        fun value(fileName: String, key: String): Any? =
            files[fileName]?.values?.get(key)
    }

    private class MemoryPreferences {
        private val removed = Any()
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
                        pending[args!![0] as String] = removed
                        proxy
                    }
                    method.name == "clear" -> {
                        clear = true
                        proxy
                    }
                    method.name == "apply" || method.name == "commit" -> {
                        if (clear) values.clear()
                        pending.forEach { (key, value) ->
                            if (value == null || value === removed) values.remove(key) else values[key] = value
                        }
                        true
                    }
                    else -> null
                }
            } as SharedPreferences.Editor
        }
    }
}
