package com.muyuchat.mca

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * App wiring of the automatic DSH binding refresh.
 *
 * There is exactly one production call site of `DshHostAssembly.refreshActiveBinding`, and it is
 * inside [DshBindingRefreshCoordinator]. This file only (a) builds that coordinator over the
 * composition seam and (b) watches the one state that can make the DSH model overlay stale: the
 * loaded local model and the configured cloud CHAT catalog.
 *
 * What must NOT happen here:
 *  - no DSH start. The readiness guard reads the runtime phase (and never constructs the `:dsh`
 *    binder for a user who never opened DSH); the owner's `refreshActiveBinding` returns null when
 *    the host is not READY;
 *  - no force. Only an explicit user action may cut a running turn, so a deferred answer stays
 *    deferred and is reported. The idle reconcile added for D07 does not change that: it only
 *    queues one normal retry after the typed observation proves `observedCalls == 0`, and an
 *    unobservable (`null`) probe leaves the deferral exactly as it was;
 *  - no global state write. The only UI effect is a status line through the existing channel, and
 *    only for outcomes the user must know about (deferred / rolled back / failed).
 */

/** Builds the single coordinator over the app runtime assembly and the ViewModel scope. */
internal fun MainViewModel.createDshBindingRefreshCoordinator(): DshBindingRefreshCoordinator =
    DshBindingRefreshCoordinator(
        scope = viewModelScope,
        ready = { runtimeAssembly.dshHost.runtimePhaseOrNull() == DshRuntimePhase.READY },
        refresh = { force -> runtimeAssembly.dshHost.refreshActiveBinding(force) },
        // Idle reconcile seam: the typed tri-state observation of the running generation. It reads
        // the live gateway and bridge ledgers, never starts either, and reports "cannot observe"
        // when the model channel cannot be asked — which the coordinator must not read as idle.
        observeInFlight = { runtimeAssembly.dshHost.observeInFlight() },
        onState = { state -> onDshBindingRefreshState(state) },
        log = { line -> Log.i(DSH_REFRESH_TAG, line) }
    )

/**
 * Watches the exact inputs of the DSH model catalog and coalesces them into one refresh.
 *
 * The signature is recomputed from the whole UI state but only reflects the loaded local model
 * (including the effective context window the gateway advertises) and the configured cloud CHAT
 * records, so unrelated state writes (streaming text, tabs, scroll) cannot trigger a host reload.
 * The initial emission is the state the app started with, not a change, so it is skipped.
 */
internal fun MainViewModel.startDshModelCatalogWatching(): Job = viewModelScope.launch {
    var previous: String? = null
    uiStateStore.state
        .map { state -> dshModelCatalogSignature(state) }
        .distinctUntilChanged()
        .collect { signature ->
            val before = previous
            previous = signature
            if (before == null) return@collect
            Log.i(DSH_REFRESH_TAG, "model catalog changed; scheduling a guarded DSH binding refresh")
            dshBindingRefresh.onModelCatalogChanged("model-catalog")
        }
}

/** Canonical signature of the inputs the DSH gateway advertises as its model list. */
internal fun MainViewModel.dshModelCatalogSignature(state: MainUiState): String =
    DshModelCatalogSignature.of(
        loadedModelId = state.loadedModelId,
        localModelName = state.loadedModelName,
        // The gateway advertises the window the adapter derives from the *loaded* execution
        // profile's `n_ctx`; `stats.nCtx` is that same effective value after a successful load
        // (native read-back with the load parameters as fallback). `params.nCtx` is the pending UI
        // parameter instead, so changing the slider without reloading must not signal a catalog
        // change: only a genuinely loaded window may enter the signature.
        localContextWindow = state.stats.nCtx.takeIf { it > 0 },
        cloudChatModels = state.cloudModels
            .asSequence()
            .filter { it.kind == CloudModelKind.CHAT }
            .map { record ->
                DshModelCatalogSignature.CloudChat(
                    id = record.id,
                    displayName = record.displayName,
                    modelName = record.modelName,
                    apiFormat = record.apiFormat.name,
                    supportsVision = record.supportsVision,
                    supportsTools = record.supportsTools,
                    configured = record.configured,
                    updatedAt = record.updatedAt
                )
            }
            .toList()
    )

/**
 * Shows only the outcomes the user must know about. A confirmed reload is logged; a deferred,
 * rolled-back or failed one is surfaced because the DSH model list the user sees is then *not* the
 * one MCA currently advertises.
 */
internal fun MainViewModel.onDshBindingRefreshState(state: DshBindingRefreshState) {
    val message = when (state) {
        is DshBindingRefreshState.Deferred -> when (state.reason) {
            DshBindingRefreshDeferReason.IN_FLIGHT ->
                "DSH 正在处理请求，模型目录将在下一轮切换时刷新（不会中断当前会话）"
            DshBindingRefreshDeferReason.UNOBSERVABLE ->
                "DSH 运行状态暂时无法观测，模型目录刷新已延后（不会中断当前会话）"
            DshBindingRefreshDeferReason.GATEWAY_UNAVAILABLE ->
                "DSH 模型通道暂不可用，模型目录刷新已延后"
        }

        is DshBindingRefreshState.RolledBack ->
            "DSH 未能应用新的模型目录，已恢复原配置继续运行"

        is DshBindingRefreshState.Failed ->
            "DSH 模型目录刷新失败，运行时保持刷新前的状态"

        else -> null
    }
    if (message != null) showStatus(message)
}

private const val DSH_REFRESH_TAG = "McaDshBindingRefresh"
