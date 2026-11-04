package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.mca.secure.AndroidApiKeyVault
import com.muyuchat.mca.secure.ApiKeyVaultState
import com.muyuchat.mca.secure.ApiKeyVaultStatus
import com.muyuchat.mca.secure.ApiKeyVaultUnavailable
import com.muyuchat.mca.secure.planApiKeyCreate
import com.muyuchat.mca.secure.planApiKeyRead
import java.util.UUID
import org.json.JSONArray

/**
 * Persisted generation and Local API preferences: the `SharedPreferences` reads and writes that used
 * to live in `MainViewModel`, plus the model-scoped generation profile key.
 *
 * **Why an object and not a field on the ViewModel.** Every one of these operations is a pure
 * function of `(Context, value)` (or `(ModelGenerationProfileStore, value)`); none of them reads
 * ViewModel state. Making it a stateless object with the collaborators passed in keeps them that way
 * and -- importantly -- removes an initialisation-order hazard: several of these are called while the
 * ViewModel is still being constructed, where a `by lazy` store field would be reachable before the
 * stores it wraps.
 *
 * **Preferences contracts preserved verbatim**, including the ones that are easy to get wrong:
 *
 *  * `mca_api` holds `api_key`, `api_enabled` and `rest_enabled`; **REST is only ever stored as
 *    enabled when the API itself is enabled** (`apiEnabled && restEnabled`);
 *  * a generated API key is `mca-` plus 24 hex-ish characters from a UUID, and is written back on
 *    first read so it is stable across restarts;
 *  * `mca_generation_params` holds `params_json` (semantic document), `runtime_params_json` (runtime
 *    document), `persistent_prefix_cache_enabled` (**defaults to true**) and `runtime_user_fields`
 *    (a sorted JSON array).
 *
 * Nothing here changes a stored key name, a default, or a file name: an existing install must read
 * exactly the same values after this move as before it.
 */
internal object GenerationPreferences {

    /** SharedPreferences file for the Local API switch, its REST switch and the API key. */
    private const val API_PREFERENCES = "mca_api"

    internal const val GENERATION_PARAMETERS_PREFERENCES = "mca_generation_params"
    internal const val PERSISTENT_PREFIX_CACHE_ENABLED_KEY = "persistent_prefix_cache_enabled"
    internal const val CHAT_LOCAL_TOOLS_ENABLED_KEY = "chat_local_tools_enabled"
internal const val RICH_BLOCKS_ENABLED_KEY = "chat_rich_blocks_enabled"

    private const val KEY_API_KEY = "api_key"
    private const val KEY_API_ENABLED = "api_enabled"
    private const val KEY_REST_ENABLED = "rest_enabled"
    private const val KEY_PARAMS_JSON = "params_json"
    private const val KEY_RUNTIME_PARAMS_JSON = "runtime_params_json"
    private const val KEY_RUNTIME_USER_FIELDS = "runtime_user_fields"

    private fun apiPreferences(context: Context) =
        context.getSharedPreferences(API_PREFERENCES, Context.MODE_PRIVATE)

    private fun generationPreferences(context: Context) =
        context.getSharedPreferences(GENERATION_PARAMETERS_PREFERENCES, Context.MODE_PRIVATE)

    // ---- Local API switch and credential ------------------------------------------------

    /**
     * Returns the stored Local API key, creating and persisting one on first use.
     *
     * The slot is an Android-Keystore AES-GCM vault blob (`v1:` + base64). A value written by an
     * older build is plaintext: it is re-encrypted on read and the plaintext is replaced by the
     * blob. A blob that cannot be decrypted is **never cleared** — the caller gets a fail-closed
     * per-process key instead, and [ApiKeyVaultState] reports `UNDECRYPTABLE_KEPT` so the failure is
     * diagnosable and a later working keystore can still recover the original value.
     */
    fun loadOrCreateApiKey(context: Context): String {
        val prefs = apiPreferences(context)
        val stored = prefs.getString(KEY_API_KEY, null)
        val cipher = AndroidApiKeyVault.forApp(context)
        val plan = planApiKeyRead(stored, cipher)
        plan.write?.let { encrypted -> prefs.edit().putString(KEY_API_KEY, encrypted).apply() }
        ApiKeyVaultState.record(plan)
        plan.value?.takeIf(String::isNotBlank)?.let { return it }
        if (plan.status == ApiKeyVaultStatus.UNDECRYPTABLE_KEPT) {
            // Fail closed: blanking the key would make the loopback server accept every request.
            return ApiKeyVaultUnavailable.sessionKey
        }
        val created = "mca-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val create = planApiKeyCreate(created, cipher)
        val persisted = create.write ?: created
        prefs.edit().putString(KEY_API_KEY, persisted).apply()
        ApiKeyVaultState.record(create)
        return create.value ?: created
    }

    /**
     * Reads the two Local API switches. REST is reported as enabled only when the API is: a stored
     * `rest_enabled = true` with the API off must not resurrect the REST surface.
     */
    fun loadApiPreferences(context: Context): LocalApiPreferences {
        val prefs = apiPreferences(context)
        val apiEnabled = prefs.getBoolean(KEY_API_ENABLED, false)
        return LocalApiPreferences(
            apiEnabled = apiEnabled,
            restEnabled = apiEnabled && prefs.getBoolean(KEY_REST_ENABLED, false)
        )
    }

    /** Persists the two switches, collapsing REST to `false` whenever the API is off. */
    fun persistApiPreferences(context: Context, apiEnabled: Boolean, restEnabled: Boolean) {
        apiPreferences(context)
            .edit()
            .putBoolean(KEY_API_ENABLED, apiEnabled)
            .putBoolean(KEY_REST_ENABLED, apiEnabled && restEnabled)
            .apply()
    }

    // ---- Model-scoped generation profile ------------------------------------------------

    /** The profile key for a model, or null when no model is selected. */
    fun modelGenerationProfileKey(backend: ChatBackend, modelId: String?): String? {
        val id = modelId?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        return when (backend) {
            ChatBackend.LOCAL -> ModelGenerationProfileKey.local(id)
            ChatBackend.CLOUD -> ModelGenerationProfileKey.cloud(id)
        }
    }

    /**
     * Applies a model's stored semantic profile on top of [defaults], without replacing the selected
     * assistant/system prompt or the native load-bound settings. A model with no stored profile gets
     * [defaults] unchanged.
     */
    fun loadModelGenerationParams(
        profileStore: ModelGenerationProfileStore,
        backend: ChatBackend,
        modelId: String?,
        defaults: GenerationParams
    ): GenerationParams {
        val key = modelGenerationProfileKey(backend, modelId) ?: return defaults
        return profileStore.loadOrCreate(key, defaults)
    }

    /** Saves a model's semantic profile; a model with no key is left untouched. */
    fun persistModelGenerationParams(
        profileStore: ModelGenerationProfileStore,
        backend: ChatBackend,
        modelId: String?,
        params: GenerationParams
    ) {
        modelGenerationProfileKey(backend, modelId)?.let { key ->
            profileStore.save(key, params)
        }
    }

    // ---- Global generation parameters --------------------------------------------------

    /** Restores the global generation parameters, semantic and runtime documents together. */
    fun loadGenerationParams(context: Context): GenerationParams {
        val prefs = generationPreferences(context)
        return restoreGenerationParams(
            semanticJson = prefs.getString(KEY_PARAMS_JSON, null),
            runtimeJson = prefs.getString(KEY_RUNTIME_PARAMS_JSON, null)
        )
    }

    /** Writes both parameter documents; the JSON shapes are the ones the rest of the app reads. */
    fun persistGenerationParams(context: Context, params: GenerationParams) {
        generationPreferences(context)
            .edit()
            .putString(KEY_PARAMS_JSON, params.toAssistantGenerationJson())
            .putString(KEY_RUNTIME_PARAMS_JSON, runtimeParameterDocument(params).toString())
            .apply()
    }

    /** The persistent prefix cache toggle; **defaults to true** when never written. */
    fun loadPersistentPrefixCacheEnabled(context: Context): Boolean =
        generationPreferences(context).getBoolean(PERSISTENT_PREFIX_CACHE_ENABLED_KEY, true)

    fun persistPersistentPrefixCacheEnabled(context: Context, enabled: Boolean) {
        generationPreferences(context)
            .edit()
            .putBoolean(PERSISTENT_PREFIX_CACHE_ENABLED_KEY, enabled)
            .apply()
    }

    /**
     * The chat-page local tool toggle (device clock / arithmetic / dice); **defaults to true**.
     * A stored false is honoured: a model that ignores the prompted protocol, or a user who does
     * not want the extra system text, keeps a plain chat turn.
     */
    fun loadChatLocalToolsEnabled(context: Context): Boolean =
        generationPreferences(context).getBoolean(CHAT_LOCAL_TOOLS_ENABLED_KEY, true)

    fun persistChatLocalToolsEnabled(context: Context, enabled: Boolean) {
        generationPreferences(context)
            .edit()
            .putBoolean(CHAT_LOCAL_TOOLS_ENABLED_KEY, enabled)
            .apply()
    }

    /**
     * Structured display of Tavern-style HTML blocks (`<details>`, `<status>`, `<StatusBlock>`).
     *
     * **Defaults to true**: a preset that emits those blocks currently shows raw tags, which is worse
     * than the sanitized structure. The renderer keeps a per-message "查看原文" fallback, and scripts
     * are never executed, so the default is safe for existing installs too.
     */
    fun loadRichBlocksEnabled(context: Context): Boolean =
        generationPreferences(context).getBoolean(RICH_BLOCKS_ENABLED_KEY, true)

    fun persistRichBlocksEnabled(context: Context, enabled: Boolean) {
        generationPreferences(context)
            .edit()
            .putBoolean(RICH_BLOCKS_ENABLED_KEY, enabled)
            .apply()
    }

    /**
     * The fields the user overrode for the current runtime. A malformed stored array yields the empty
     * set rather than throwing: this value decides parameter authority, and failing open to "nothing
     * was overridden" is the behaviour the ViewModel had.
     */
    fun loadRuntimeUserOverrideFields(context: Context): Set<String> {
        val raw = generationPreferences(context).getString(KEY_RUNTIME_USER_FIELDS, null)
            ?: return emptySet()
        return runCatching {
            val values = JSONArray(raw)
            buildSet {
                for (index in 0 until values.length()) {
                    values.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.getOrDefault(emptySet())
    }

    /** Stores the overridden fields as a sorted JSON array, so the file is stable across writes. */
    fun persistRuntimeUserOverrideFields(context: Context, fields: Set<String>) {
        generationPreferences(context)
            .edit()
            .putString(KEY_RUNTIME_USER_FIELDS, JSONArray(fields.sorted()).toString())
            .apply()
    }
}

/** The two Local API switches as read from storage. */
internal data class LocalApiPreferences(
    val apiEnabled: Boolean,
    val restEnabled: Boolean
)

/**
 * A named cloud provider wire format offered when configuring a custom engine.
 *
 * `internal`, moved out of `MainViewModel` where it was a nested `private` class: the preset table
 * and its lookup live here now, so the type has to be reachable from this file's public surface.
 * The default base URL and chat model are empty on purpose -- a preset only names the format, and the
 * user supplies the endpoint.
 */
internal data class CloudProviderPreset(
    val key: String,
    val providerName: String,
    val displayName: String,
    val format: CloudApiFormat,
    val baseUrl: String,
    val chatModel: String,
    val imageModel: String = "",
    val imageSize: String = "1024x1024"
)

/**
 * The cloud provider presets offered when a user configures a custom engine.
 *
 * Base URLs and default models are intentionally empty: the preset only names the wire format, and
 * the user supplies the endpoint.
 */
internal val builtInCloudProviderPresets = listOf(
    CloudProviderPreset("openai", "OpenAI-compatible", "自定义推理引擎", CloudApiFormat.OPENAI_COMPATIBLE, "", ""),
    CloudProviderPreset("responses", "OpenAI Responses", "自定义推理引擎", CloudApiFormat.OPENAI_RESPONSES, "", ""),
    CloudProviderPreset("anthropic", "Anthropic Messages", "自定义推理引擎", CloudApiFormat.ANTHROPIC, "", "")
)

/**
 * Looks up a provider preset by its stable key.
 *
 * The three file-level helpers below are named differently from the ViewModel members they replace:
 * a same-named member shadows a top-level declaration inside the class, so a forwarder left behind
 * would call itself.
 */
internal fun findCloudProviderPreset(key: String): CloudProviderPreset? =
    builtInCloudProviderPresets.firstOrNull { it.key == key }

/**
 * User-facing message for a credential write that did not reach a durable state, or null when the
 * credential was stored (or intentionally cleared).
 *
 * A failed credential write must never be presented as a successful save, and the message must not
 * suggest that a plaintext copy was created.
 */
internal fun credentialWriteFailureMessage(result: CredentialSaveResult): String? = when (result) {
    CredentialSaveResult.Stored, CredentialSaveResult.Cleared -> null

    CredentialSaveResult.EncryptionUnavailable ->
        "连接成功，但 API Key 未能安全保存：系统密钥库当前不可用，本次未写入明文。" +
            "恢复系统密钥库后请重新保存。"

    CredentialSaveResult.VerificationFailed ->
        "连接成功，但 API Key 写入后校验失败，已保留原有配置且未写入明文。请重试保存。"
}
