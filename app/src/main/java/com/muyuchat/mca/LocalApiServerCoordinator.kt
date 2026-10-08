package com.muyuchat.mca

import android.app.Application
import com.muyuchat.api.local.LocalApiRuntime
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 集中管理 Local API 服务与监听器的完整生命周期。
 *
 * 承担进程独占所有权、前台服务提升等待、监听器发布/停止、REST 网络暴露、
 * 以及生命周期互斥门控与错误回滚。
 */
internal class LocalApiServerCoordinator(
    private val application: Application,
    private val scope: CoroutineScope,
    private val store: UiStateStore,
    private val localApiRuntimeOwner: LocalApiRuntimeOwner,
    private val apiKey: String,
    private val cancelActiveLocalApiImageGeneration: (String) -> Boolean,
    private val persistApiPreferences: (Boolean, Boolean) -> Unit,
    private val apiUrl: (String) -> String,
    private val currentOpenApiAddress: () -> String,
    private val onInterruptForegroundRecovery: () -> Unit
) {
    companion object {
        private val localApiProcessLifecycleLock = Any()
        private var localApiProcessOwnerToken: Any? = null
        private const val LOCAL_API_FOREGROUND_READY_TIMEOUT_MS = 3_000L
        private const val LOCAL_API_FOREGROUND_READY_ATTEMPTS = 2
    }

    /** Listener, notification, persisted intent, and UI state share this single lifecycle gate. */
    val apiLifecycleMutex = Mutex()
    val apiLifecycleSequence = AtomicLong(0L)
    val apiLifecycleClosed = AtomicBoolean(false)
    var apiLifecycleRequestJob: Job? = null

    fun retireLocalApiListener(
        stopForegroundService: Boolean,
        forceServerStop: Boolean
    ) {
        apiLifecycleClosed.set(true)
        apiLifecycleSequence.incrementAndGet()
        onInterruptForegroundRecovery()
        apiLifecycleRequestJob?.cancel()
        if (forceServerStop) stopApiServer()
        if (stopForegroundService) {
            releaseLocalApiProcessOwnership()
        }
    }

    fun requestLocalApiState(
        enabled: Boolean,
        restEnabled: Boolean,
        failurePrefix: String
    ) {
        if (apiLifecycleClosed.get()) return
        onInterruptForegroundRecovery()
        val operation = apiLifecycleSequence.incrementAndGet()
        apiLifecycleRequestJob?.cancel()
        apiLifecycleRequestJob = scope.launch(Dispatchers.IO) {
            applyLocalApiState(operation, enabled, restEnabled, failurePrefix)
        }
    }

    suspend fun applyLocalApiState(
        operation: Long,
        enabled: Boolean,
        restEnabled: Boolean,
        failurePrefix: String
    ): LocalApiApplyResult? = apiLifecycleMutex.withLock {
        fun operationIsCurrent(): Boolean =
            !apiLifecycleClosed.get() && apiLifecycleSequence.get() == operation

        if (!operationIsCurrent()) return@withLock null
        var committed = false
        try {
            if (enabled) {
                // Android may return from startForegroundService before
                // onCreate/onStartCommand has promoted the service.  Starting
                // the socket first creates a window where the API advertises
                // itself but is not protected from process/background limits;
                // on some cold starts that race left port 11435 unbound after
                // the service request was lost.  Promote and wait first, then
                // publish the listener.
                check(ensureLocalApiForegroundServiceReady(restEnabled)) {
                    "本机 API 后台服务未就绪，请回到应用前台后重试。"
                }
                startApiServer(if (restEnabled) "0.0.0.0" else "127.0.0.1")
            } else {
                stopApiServer()
                setLocalApiForegroundService(false, false)
            }
            currentCoroutineContext().ensureActive()
            if (!operationIsCurrent()) return@withLock null

            persistApiPreferences(enabled, restEnabled)
            store.update { state ->
                if (!operationIsCurrent()) {
                    state
                } else {
                    state.copy(
                        apiEnabled = enabled,
                        restEnabled = enabled && restEnabled,
                        localApiAddress = apiUrl("127.0.0.1"),
                        openApiAddress = currentOpenApiAddress()
                    )
                }
            }
            if (!operationIsCurrent()) return@withLock null
            committed = true
            LocalApiApplyResult(
                running = enabled,
                restEnabled = enabled && restEnabled
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!operationIsCurrent()) return@withLock null
            stopApiServer()
            runCatching { setLocalApiForegroundService(false, false) }
            store.update { state ->
                if (!operationIsCurrent()) {
                    state
                } else {
                    state.copy(
                        apiEnabled = false,
                        restEnabled = false,
                        statusMessage = "${failurePrefix}：${error.message ?: "端口不可用"}"
                    )
                }
            }
            committed = true
            LocalApiApplyResult(running = false, restEnabled = false, failure = error)
        } finally {
            // A superseded operation must not tear down a replacement operation's
            // listener or foreground service. The replacement owns cleanup after
            // it acquires the same mutex; onCleared() handles process teardown.
            if (!committed && operationIsCurrent()) {
                stopApiServer()
                runCatching { setLocalApiForegroundService(false, false) }
            }
        }
    }

    suspend fun awaitLocalApiForegroundServiceReady(): Boolean {
        if (LocalApiForegroundService.isForegroundReady()) return true
        return withTimeoutOrNull(LOCAL_API_FOREGROUND_READY_TIMEOUT_MS) {
            while (!LocalApiForegroundService.isForegroundReady()) {
                currentCoroutineContext().ensureActive()
                delay(25L)
            }
            true
        } == true
    }

    suspend fun ensureLocalApiForegroundServiceReady(restEnabled: Boolean): Boolean {
        repeat(LOCAL_API_FOREGROUND_READY_ATTEMPTS) { attempt ->
            if (LocalApiForegroundService.isForegroundReady()) return true
            if (attempt > 0) delay(100L)
            runCatching {
                setLocalApiForegroundService(true, restEnabled)
            }.getOrElse { error ->
                if (attempt == LOCAL_API_FOREGROUND_READY_ATTEMPTS - 1) throw error
            }
            if (awaitLocalApiForegroundServiceReady()) return true
            // A timed-out start can leave an OEM service request queued. Clear
            // that request before the bounded retry so the next attempt is a
            // fresh startForegroundService call instead of an idempotent no-op.
            runCatching { LocalApiForegroundService.stop(application) }
        }
        return false
    }

    fun setLocalApiForegroundService(enabled: Boolean, restEnabled: Boolean) {
        synchronized(localApiProcessLifecycleLock) {
            // A cancelled operation from a replaced ViewModel may reach its finally block late.
            // Only the current process owner may mutate the shared notification service.
            if (localApiProcessOwnerToken !== localApiRuntimeOwner || apiLifecycleClosed.get()) return
            if (enabled) {
                check(LocalApiForegroundService.start(application, restEnabled)) {
                    "系统拒绝启动 API 后台服务，请回到应用前台后重新开启 API。"
                }
            } else {
                LocalApiForegroundService.stop(application)
            }
        }
    }

    fun claimLocalApiProcessOwnership() {
        synchronized(localApiProcessLifecycleLock) {
            localApiProcessOwnerToken = localApiRuntimeOwner
        }
    }

    fun releaseLocalApiProcessOwnership() {
        synchronized(localApiProcessLifecycleLock) {
            if (localApiProcessOwnerToken !== localApiRuntimeOwner) return
            localApiProcessOwnerToken = null
            LocalApiForegroundService.stop(application)
        }
    }

    fun startApiServer(bindHost: String) {
        synchronized(localApiProcessLifecycleLock) {
            if (localApiProcessOwnerToken !== localApiRuntimeOwner || apiLifecycleClosed.get()) {
                throw CancellationException("Local API process ownership changed before server start")
            }
            if (!LocalApiForegroundService.isListenerRunning(bindHost)) {
                cancelActiveLocalApiImageGeneration("Local API server is restarting.")
            }
            LocalApiForegroundService.ensureListener(bindHost, apiKey)
        }
    }

    fun stopApiServer() {
        cancelActiveLocalApiImageGeneration("Local API server stopped.")
        LocalApiForegroundService.stopListener()
    }
}
