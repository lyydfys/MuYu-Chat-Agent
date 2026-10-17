package com.muyuchat.core.engine

import kotlinx.coroutines.withContext
import org.json.JSONObject

    /**
     * Lists only aggregate usage of the app-private llama.cpp prefix cache.
     * Cache keys and persisted KV content intentionally stay private to the engine.
     */
internal fun McaInferenceService.persistentPrefixCacheSummaryImpl(): PersistentPrefixCacheSummary =
        runCatching { persistentPrefixCacheStore.summary() }
            .getOrDefault(PersistentPrefixCacheSummary(entryCount = 0, totalBytes = 0L))

    /**
     * Enables or disables future persisted llama.cpp prefix state. Disabling
     * also prevents an already-running prefill from publishing its staging file.
     */
internal fun McaInferenceService.setPersistentPrefixCacheEnabledImpl(enabled: Boolean) {
        persistentPrefixCacheEnabled = enabled
    }

    /** Clears persisted fixed-prefix state. The caller owns any live-context invalidation. */
internal suspend fun McaInferenceService.clearPersistentPrefixCacheImpl(): Boolean = withContext(io) {
        runCatching { persistentPrefixCacheStore.clear() }.getOrDefault(false)
    }

    /** Purges all model/configuration variants of deleted conversations' disk KV states. */
internal suspend fun McaInferenceService.clearPersistentSessionStatesImpl(sessionIds: Collection<String>): Boolean = withContext(io) {
        val fingerprints = sessionIds.map(String::trim).filter(String::isNotBlank)
            .mapTo(hashSetOf()) { sessionId ->
                PrefixCacheKey.sha256Utf8("full-session-prefix-v1\n$sessionId")
            }
        runCatching { persistentPrefixCacheStore.clearPrefixFingerprints(fingerprints) }
            .getOrDefault(false)
    }


internal data class PreparedPersistentPrefix(
        val key: PrefixCacheKey,
        val request: PersistentPrefixCacheRequest,
        val pending: PersistentPrefixCacheStore.PendingWrite,
        val existing: PersistentPrefixCacheEntry?
    )

internal data class ManagedPersistentPrefixFailure(
        val reason: String,
        val tokens: Int
    )

    /**
     * Prepares either a stable role prefix entry or a session-scoped llama.cpp
     * state entry. Session state is accepted only after native token-prefix
     * validation, so dynamic world-book/retrieval text cannot be misapplied to
     * a changed conversation.
     */
internal fun McaInferenceService.preparePersistentPrefix(
        request: ChatRequest,
        preparedParameters: CompletionPreflight.Ready?,
        paramsJson: String,
        hasImageAttachments: Boolean
    ): PreparedPersistentPrefix? {
        if (!persistentPrefixCacheEnabled ||
            activeRuntime != LocalChatRuntime.LLAMA_CPP ||
            hasImageAttachments ||
            preparedParameters == null
        ) return null
        val session = activeLoadSession ?: return null
        val identity = session.runtimeIdentity
        if (!session.params.visionProjectorPath.isNullOrBlank() ||
            identity.capabilities.any { capability ->
                capability.equals("vision", ignoreCase = true) ||
                    capability.equals("multimodal", ignoreCase = true)
            }
        ) return null
        // A process-local identity is deliberately advisory-only for runtime
        // admission, but it is not stable enough for a cross-process cache.
        if (identity.installationScopeId.startsWith("process-local:", ignoreCase = true)) return null

        val fixedSystemPrompt = request.fixedSystemPromptForPrefixCache()
        val sessionId = request.persistentSessionId?.trim()?.takeIf { it.isNotBlank() }
        if (fixedSystemPrompt.isBlank() && sessionId == null) return null
        // Prefix-cache metadata crosses the isolated worker boundary. A cache
        // optimization must never reject an otherwise admissible chat request.
        if (fixedSystemPrompt.toByteArray(Charsets.UTF_8).size >
            MAX_PERSISTENT_PREFIX_CACHE_PROMPT_BYTES
        ) return null

        val root = runCatching { JSONObject(paramsJson) }.getOrNull() ?: return null
        val advanced = when (val raw = root.opt("advanced_json")) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        }
        fun value(name: String): Any? = when {
            root.has(name) && !root.isNull(name) -> root.opt(name)
            advanced?.has(name) == true && !advanced.isNull(name) -> advanced.opt(name)
            else -> null
        }
        val profile = session.executionProfile
        val nParallel = (profile.resolvedLoadBoundValues.value("n_parallel") as? Number)?.toInt()
            ?: (value("n_parallel") as? Number)?.toInt()
            ?: 1
        val specType = profile.resolvedLoadBoundValues.value("spec_type")
            ?.toString()
            ?.trim()
            ?.lowercase()
            ?: value("spec_type")?.toString()?.trim()?.lowercase()
            ?: "none"
        val cacheReuse = (value("cache_reuse") as? Number)?.toInt()
            ?: (profile.hotExecutionValues.value("cache_reuse") as? Number)?.toInt()
            ?: -1
        if (nParallel != 1 || specType != "none" || cacheReuse == 0) return null

        val signatures = preparedParameters.signatures
        val key = persistentPrefixCacheKey(
            identity = identity,
            signatures = signatures,
            nativeParams = root,
            request = request,
            fixedSystemPrompt = fixedSystemPrompt,
            sessionId = sessionId
        )
        val store = persistentPrefixCacheStore
        val existing = runCatching { store.load(key) }.getOrNull()
        val pending = runCatching { store.prepareWrite(key) }.getOrNull() ?: return null
        return PreparedPersistentPrefix(
            key = key,
            request = PersistentPrefixCacheRequest(
                restoreStatePath = existing?.stateFile?.absolutePath,
                writeStatePath = pending.stateFile.absolutePath,
                fixedSystemPrompt = fixedSystemPrompt.ifBlank { "MCA_SESSION_STATE" },
                fullSessionState = sessionId != null
            ),
            pending = pending,
            existing = existing
        )
    }

internal fun McaInferenceService.finishPersistentPrefix(
        prepared: PreparedPersistentPrefix?,
        resultCode: Int,
        runner: LocalChatRunner
    ): ManagedPersistentPrefixFailure? {
        if (prepared == null) return null
        if (!persistentPrefixCacheEnabled) {
            runCatching { persistentPrefixCacheStore.discard(prepared.pending) }
            return null
        }
        val stats = runCatching { JSONObject(runner.getRuntimeStatsJson()) }.getOrNull()
        val persistent = stats?.optJSONObject("persistentPrefixCache")
        val saved = resultCode == 0 && persistent?.optBoolean("saved", false) == true
        if (saved) {
            val committed = runCatching { persistentPrefixCacheStore.commit(prepared.pending) }
                .getOrNull()
            if (committed == null) {
                // Native export succeeded, but the managed atomic publication did
                // not. Keep generation successful while making the cache miss
                // explicit so diagnostics never claim state_saved.
                return ManagedPersistentPrefixFailure(
                    reason = MANAGED_PREFIX_COMMIT_FAILED_REASON,
                    tokens = persistent.optInt("tokens", 0)
                )
            }
        } else {
            runCatching { persistentPrefixCacheStore.discard(prepared.pending) }
            val reason = persistent?.optString("reason").orEmpty()
            if (prepared.existing != null && (reason in setOf(
                    "state_load_failed",
                    "state_restore_failed",
                    "state_token_mismatch",
                    "state_prefix_mismatch",
                    "session_state_load_failed",
                    "session_state_token_mismatch",
                    "session_state_trim_failed"
                ) || (prepared.request.fullSessionState &&
                    persistent?.optBoolean("hit", false) != true &&
                    reason in setOf("session_state_save_failed", "session_state_unavailable")))
            ) {
                runCatching { persistentPrefixCacheStore.clear(prepared.key) }
            }
        }
        return null
    }

internal fun McaInferenceService.discardPersistentPrefix(prepared: PreparedPersistentPrefix?) {
        prepared ?: return
        runCatching { persistentPrefixCacheStore.discard(prepared.pending) }
    }

internal fun McaInferenceService.persistentPrefixCacheKey(
        identity: ModelRuntimeIdentity,
        signatures: ParameterSignatureSnapshot,
        nativeParams: JSONObject,
        request: ChatRequest,
        fixedSystemPrompt: String,
        sessionId: String?
    ): PrefixCacheKey {
        fun digest(value: String): String = PrefixCacheKey.sha256Utf8(value)
        val advanced = when (val raw = nativeParams.opt("advanced_json")) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        }
        fun stringValue(name: String, fallback: String = ""): String = when {
            nativeParams.has(name) && !nativeParams.isNull(name) -> nativeParams.optString(name, fallback)
            advanced?.has(name) == true && !advanced.isNull(name) -> advanced.optString(name, fallback)
            else -> fallback
        }
        val templateBinding = listOf(
            identity.templateFingerprint,
            request.params.chatTemplateMode,
            stringValue("use_jinja", "true"),
            stringValue("enable_thinking", "false"),
            stringValue("thinking_budget", "0")
        ).joinToString("\n")
        val runtimeBinding = listOf(
            PERSISTENT_PREFIX_CACHE_FORMAT,
            LLAMA_SEQUENCE_STATE_FORMAT,
            identity.runtimeVersion,
            identity.nativeLibrarySha256,
            identity.abi,
            identity.backendFingerprint,
            signatures.resolved.digest,
            signatures.committed.digest,
            stringValue("n_ctx", "0"),
            stringValue("cache_type_k", "f16"),
            stringValue("cache_type_v", "f16"),
            stringValue("n_parallel", "1")
        ).joinToString("\n")
        return PrefixCacheKey(
            modelFingerprint = digest("model\n${identity.identityHash}"),
            tokenizerFingerprint = digest("tokenizer\n${identity.tokenizerFingerprint}"),
            templateFingerprint = digest("template\n$templateBinding"),
            systemPromptFingerprint = digest(buildList {
                add("role-bound-prompt-v1")
                add(fixedSystemPrompt)
                add(request.persistentRoleId.orEmpty())
                add(request.persistentRoleCardVersion.orEmpty())
                if (sessionId != null) {
                    add(request.persistentMemoryVersion.orEmpty())
                    add(request.conversationContextRevision.orEmpty())
                }
            }.joinToString("\n")),
            runtimeFingerprint = digest("runtime\n$runtimeBinding"),
            prefixFingerprint = digest(
                if (sessionId != null) {
                    "full-session-prefix-v1\n$sessionId"
                } else {
                    "fixed-system-prefix\n$fixedSystemPrompt\n${request.persistentRoleId.orEmpty()}"
                }
            )
        )
    }
