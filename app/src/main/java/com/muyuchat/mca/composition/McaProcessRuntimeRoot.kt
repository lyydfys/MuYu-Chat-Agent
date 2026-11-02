package com.muyuchat.mca.composition

import android.app.Application
import android.util.Log
import com.muyuchat.mca.LocalApiForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Process-scoped execution root for the native Agent runtime and its inference engine.
 *
 * The container and [MainRuntimeAssembly] are created once per process and shared by every page
 * reconstruction; the page ViewModel never constructs or closes them. [close] is the single
 * settlement point for the last finishing Activity (or a test/app-terminate path): it stops the
 * Local API surface, settles every process-owned host, releases the container-owned worker pools,
 * then cancels the process scope. Repeating [close] is a no-op.
 *
 * ADR-0008: the root must not hold Activity/View/ViewModel references. This class only keeps the
 * application, the application-only container and the assembly.
 */
internal class McaProcessRuntimeRoot(
    private val application: Application,
    val container: McaAppContainer,
    installationScopeId: String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val assembly: MainRuntimeAssembly = MainRuntimeAssembly(
        application = application,
        container = container,
        scope = scope,
        installationScopeId = installationScopeId
    )

    private val closeSequence = ProcessCloseSequence(
        "local-api-service" to { LocalApiForegroundService.stop(application) },
        "local-api-owner" to { container.localApiRuntimeOwner.release() },
        "agent-assembly" to { assembly.close() },
        "image-worker" to { container.localImageWorkerClient.close() },
        "isolated-runners" to { container.isolatedLocalChatRunners.close() },
        "scope" to { scope.cancel() }
    )

    @Volatile
    private var closed = false

    val isClosed: Boolean
        get() = closed

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        closeSequence.close().forEach { failure ->
            Log.e(TAG, "close step '${failure.step}' failed", failure.error)
        }
    }

    private companion object {
        const val TAG = "McaProcessRuntimeRoot"
    }
}
