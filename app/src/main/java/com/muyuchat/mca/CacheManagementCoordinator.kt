package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.engine.McaInferenceService
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class CacheManagementCoordinator(
    private val application: Application,
    private val store: UiStateStore,
    private val scope: CoroutineScope,
    private val engine: McaInferenceService,
    private val chatRuns: ChatRunOwnership,
    val persistentPrefixCacheOperationSequence: AtomicLong = AtomicLong(0L),
    private val refreshPersistentPrefixCacheSummary: () -> Unit,
    private val markLocalConversationContextInvalid: () -> Unit
) {
    fun setPersistentPrefixCacheEnabled(enabled: Boolean): Job? {
        val current = store.current.persistentPrefixCacheEnabled
        if (current == enabled) {
            refreshPersistentPrefixCacheSummary()
            return null
        }
        val operation = persistentPrefixCacheOperationSequence.incrementAndGet()
        GenerationPreferences.persistPersistentPrefixCacheEnabled(application, enabled)
        engine.setPersistentPrefixCacheEnabled(enabled)
        // R3/S21: 前缀缓存族写入统一经 UiStateStore。
        store.update {
            it.copy(
                persistentPrefixCacheEnabled = enabled,
                statusMessage = if (enabled) {
                    "已启用持久化前缀缓存"
                } else {
                    "已关闭持久化前缀缓存，正在清理本机缓存"
                }
            )
        }
        if (enabled) {
            refreshPersistentPrefixCacheSummary()
            return null
        } else {
            // A disk-backed prefix must never outlive a user opt-out. The
            // normal edit barrier also clears the live text KV before another
            // local request can begin.
            markLocalConversationContextInvalid()
            return schedulePersistentPrefixCacheClear(
                operation = operation,
                successMessage = "已关闭持久化前缀缓存，并清空本机缓存",
                failureMessage = "已关闭持久化前缀缓存；缓存清理未完成，可稍后重试"
            )
        }
    }

    fun clearPersistentPrefixCache(): Job {
        val operation = persistentPrefixCacheOperationSequence.incrementAndGet()
        return schedulePersistentPrefixCacheClear(
            operation = operation,
            successMessage = "已清空持久化前缀缓存",
            failureMessage = "前缀缓存清理未完成，请在当前生成结束后重试"
        )
    }

    fun schedulePersistentPrefixCacheClear(
        operation: Long,
        successMessage: String,
        failureMessage: String
    ): Job {
        return scope.launch(Dispatchers.IO) {
            // A native state export owns the cache-store lock until prefill
            // finishes. Temporarily suppress new exports, then wait for the
            // in-flight UI turn so clear cannot race a late atomic commit.
            try {
                engine.setPersistentPrefixCacheEnabled(false)
                chatRuns.activePublishedJob()?.join()
                val cleared = engine.clearPersistentPrefixCache()
                val summary = engine.persistentPrefixCacheSummary()
                // R3/S21: 前缀缓存族写入统一经 UiStateStore。
                store.update { state ->
                    state.copy(
                        persistentPrefixCacheEntryCount = summary.entryCount,
                        persistentPrefixCacheBytes = summary.totalBytes,
                        statusMessage = if (operation == persistentPrefixCacheOperationSequence.get()) {
                            if (cleared) successMessage else failureMessage
                        } else {
                            state.statusMessage
                        }
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                store.update { state ->
                    if (operation == persistentPrefixCacheOperationSequence.get()) {
                        state.copy(statusMessage = failureMessage)
                    } else {
                        state
                    }
                }
            } finally {
                val enabledNow = store.current.persistentPrefixCacheEnabled
                runCatching { engine.setPersistentPrefixCacheEnabled(enabledNow) }
            }
        }
    }
}
