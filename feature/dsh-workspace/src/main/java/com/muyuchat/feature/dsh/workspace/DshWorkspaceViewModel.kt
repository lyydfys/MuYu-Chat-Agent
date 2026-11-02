package com.muyuchat.feature.dsh.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimeClient
import com.muyuchat.core.dsh.contract.DshRuntimeFailure
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What the DSH workspace surface currently shows. There is no fourth business surface: the
 * official DSH page owns sessions, settings, approvals and plugin state, and this model only
 * projects the *host* lifecycle plus the one WebView it drives.
 */
sealed interface DshWorkspaceUiState {

    /** Host is starting or attaching; no page exists yet. */
    data object Starting : DshWorkspaceUiState

    /** Start failed, or a previously ready host exited. [requiresRestart] forces a new generation. */
    data class Failure(
        val projection: DshFailureProjection,
        val requiresRestart: Boolean
    ) : DshWorkspaceUiState

    /** A live WebView generation. The token entry is only ever present in [loadUrl] in heap. */
    data class Web(
        val epoch: Long,
        val origin: DshOrigin,
        val loadUrl: String,
        val loadId: Long,
        val authenticatedEntry: Boolean,
        val webGeneration: Long,
        val restoredPath: String?,
        val restoreApplied: Boolean,
        val restoreRequest: DshRestoreRequest?
    ) : DshWorkspaceUiState
}

/**
 * Owner of the DSH workspace page lifecycle.
 *
 * Responsibilities:
 * - attach to the single runtime owner through [DshRuntimeClient] (never start a second host),
 * - take the authenticated entry exactly once per generation and hand it to the WebView,
 * - project host failures, renderer death and page errors into shells/banners,
 * - remember only the last official path (via [DshNavigationStore]) for the next entry.
 *
 * It never writes DSH session data, never executes a tool, never holds model state and never stops
 * the runtime when the user leaves the tab: leaving the page does not cancel DSH work.
 */
class DshWorkspaceViewModel(
    private val client: DshRuntimeClient,
    private val navigationStore: DshNavigationStore,
    private val firstFrameReporter: DshFirstFrameReporter = DshFirstFrameReporter.None,
    private val clock: () -> Long = System::currentTimeMillis
) : ViewModel() {

    private val _state = MutableStateFlow<DshWorkspaceUiState>(DshWorkspaceUiState.Starting)
    val state: StateFlow<DshWorkspaceUiState> = _state.asStateFlow()

    private val _page = MutableStateFlow(DshPageUiState())
    val page: StateFlow<DshPageUiState> = _page.asStateFlow()

    private val _notice = MutableStateFlow<DshNotice?>(null)
    val notice: StateFlow<DshNotice?> = _notice.asStateFlow()

    private var startJob: Job? = null
    private var generationJob: Job? = null
    private var generationEpoch: Long? = null
    private var startRequested = false
    private var loadSequence = 0L
    private var webGeneration = 0L
    private var restoreSequence = 0L
    private var noticeSequence = 0L
    private var currentCleanPath: String? = null
    private var lastPersistedPath: String? = null
    private var entryRetries = 0
    private var firstFrameReportedEpoch: Long? = null
    private var backgroundedAtMillis: Long? = null

    init {
        viewModelScope.launch {
            client.snapshot.collect(::onSnapshot)
        }
    }

    // -- lifecycle commands ------------------------------------------------

    /** First composition / re-entry. Idempotent and never starts a second runtime. */
    fun start() {
        if (generationJob?.isActive == true) return
        when (_state.value) {
            is DshWorkspaceUiState.Web, is DshWorkspaceUiState.Failure -> return
            DshWorkspaceUiState.Starting -> Unit
        }
        startInternal(forceRestart = false)
    }

    /** Retry from the failure shell. A stale auth cookie or exited process needs a new generation. */
    fun retry() {
        val forceRestart = (_state.value as? DshWorkspaceUiState.Failure)?.requiresRestart == true ||
            client.snapshot.value.phase == DshRuntimePhase.FAILED
        startInternal(forceRestart)
    }

    /** Restart semantics are explicit for renderer/host exits and are never implicit on tab exit. */
    fun restart() = startInternal(forceRestart = true)

    private fun startInternal(forceRestart: Boolean) {
        if (startJob?.isActive == true) return
        startRequested = true
        cancelGenerationLoad()
        _state.value = DshWorkspaceUiState.Starting
        _page.value = DshPageUiState()
        entryRetries = 0
        // Install the owner before executing: an immediate client snapshot must not launch a
        // second consumer for the same one-shot authenticated entry.
        startJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val savedPath = navigationStore.readLastPath()
                val snapshot = if (forceRestart || client.snapshot.value.phase == DshRuntimePhase.FAILED) {
                    client.restart()
                } else {
                    client.ensureStarted()
                }
                applyCurrentSnapshot(snapshot, savedPath)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                // The exception text may contain host paths/URLs; the UI only gets a stable code.
                fail(
                    DshFailureProjectionMapper.project(
                        DshRuntimeFailure(
                            code = DshFailureCode.INTERNAL,
                            message = "DSH workspace start failed",
                            retryable = true
                        )
                    ),
                    requiresRestart = false
                )
            } finally {
                if (startJob === currentCoroutineContext()[Job]) {
                    startJob = null
                    if (isActive) onSnapshot(client.snapshot.value)
                }
            }
        }
        startJob?.start()
    }

    private fun onSnapshot(snapshot: DshRuntimeSnapshot) {
        if (!startRequested) return
        // A service restart can reuse epoch 1 in the same process, so the epoch alone is not a
        // stable generation identity. Any non-READY phase starts a new observation window for the
        // first visible frame; otherwise the pre-restart latch would suppress the new generation.
        if (snapshot.phase != DshRuntimePhase.READY) firstFrameReportedEpoch = null
        when (snapshot.phase) {
            DshRuntimePhase.FAILED -> {
                cancelGenerationLoad()
                val current = _state.value
                if (current is DshWorkspaceUiState.Web ||
                    current == DshWorkspaceUiState.Starting && startJob?.isActive != true
                ) {
                    fail(
                        DshFailureProjectionMapper.project(snapshot.failure),
                        requiresRestart = true
                    )
                }
            }

            DshRuntimePhase.STOPPED -> {
                cancelGenerationLoad()
                if (_state.value is DshWorkspaceUiState.Web ||
                    _state.value == DshWorkspaceUiState.Starting && startJob?.isActive != true
                ) {
                    fail(DshFailureProjectionMapper.unexpectedStop(), requiresRestart = true)
                }
            }

            DshRuntimePhase.STARTING -> {
                cancelGenerationLoad()
                if (_state.value != DshWorkspaceUiState.Starting) {
                    // The host is being restarted under an attached page; the page is no longer valid.
                    _state.value = DshWorkspaceUiState.Starting
                    _page.value = DshPageUiState()
                    entryRetries = 0
                }
            }

            DshRuntimePhase.STOPPING -> Unit

            DshRuntimePhase.READY -> {
                if (startJob?.isActive == true) return
                val current = _state.value
                if (current == DshWorkspaceUiState.Starting ||
                    current is DshWorkspaceUiState.Web &&
                    (current.epoch != snapshot.epoch || current.origin.value != snapshot.origin)
                ) {
                    refreshForGeneration(snapshot)
                }
            }
        }
    }

    private fun refreshForGeneration(snapshot: DshRuntimeSnapshot) {
        if (generationJob?.isActive == true && generationEpoch == snapshot.epoch) return
        cancelGenerationLoad()
        generationEpoch = snapshot.epoch
        generationJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val savedPath = navigationStore.readLastPath()
                applyCurrentSnapshot(snapshot, savedPath)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (!isCurrentSnapshot(snapshot)) return@launch
                fail(
                    DshFailureProjectionMapper.project(
                        DshRuntimeFailure(
                            code = DshFailureCode.INTERNAL,
                            message = "DSH workspace generation refresh failed",
                            retryable = true
                        )
                    ),
                    requiresRestart = false
                )
            } finally {
                if (generationJob === currentCoroutineContext()[Job]) {
                    generationJob = null
                    generationEpoch = null
                    if (isActive) onSnapshot(client.snapshot.value)
                }
            }
        }
        generationJob?.start()
    }

    private fun cancelGenerationLoad() {
        generationJob?.cancel()
        generationJob = null
        generationEpoch = null
    }

    private suspend fun applyCurrentSnapshot(snapshot: DshRuntimeSnapshot, savedPath: String?) {
        if (!isCurrentSnapshot(snapshot)) return
        val entry = if (snapshot.isReady) client.webEntry() else null
        currentCoroutineContext().ensureActive()
        // A restart/exit while the entry IPC was suspended invalidates the old load, even if a
        // non-cooperative client returned its result after the load job had been cancelled.
        if (isCurrentSnapshot(snapshot)) {
            applyPlan(DshLoadPlanner.plan(snapshot, entry, savedPath), incrementGeneration = true)
        }
    }

    private fun isCurrentSnapshot(snapshot: DshRuntimeSnapshot): Boolean =
        client.snapshot.value.let { live ->
            live.phase == snapshot.phase && live.epoch == snapshot.epoch && live.origin == snapshot.origin
        }

    private fun applyPlan(plan: DshWebLoadPlan, incrementGeneration: Boolean) {
        when (plan) {
            is DshWebLoadPlan.Failure -> {
                fail(plan.projection, requiresRestart = plan.projection.runtimeExited)
            }

            is DshWebLoadPlan.TokenEntry -> {
                entryRetries = 0
                currentCleanPath = null
                loadSequence += 1
                if (incrementGeneration) webGeneration += 1
                _state.value = DshWorkspaceUiState.Web(
                    epoch = plan.epoch,
                    origin = plan.origin,
                    loadUrl = plan.url,
                    loadId = loadSequence,
                    authenticatedEntry = true,
                    webGeneration = webGeneration,
                    restoredPath = plan.restorePath,
                    restoreApplied = false,
                    restoreRequest = null
                )
                _page.value = DshPageUiState(loading = true)
            }

            is DshWebLoadPlan.CookieResume -> {
                entryRetries = 0
                currentCleanPath = DshNavigationPolicy.pathForRestore(plan.url, plan.origin)
                loadSequence += 1
                if (incrementGeneration) webGeneration += 1
                _state.value = DshWorkspaceUiState.Web(
                    epoch = plan.epoch,
                    origin = plan.origin,
                    loadUrl = plan.url,
                    loadId = loadSequence,
                    authenticatedEntry = false,
                    webGeneration = webGeneration,
                    restoredPath = null,
                    restoreApplied = true,
                    restoreRequest = null
                )
                _page.value = DshPageUiState(loading = true)
            }
        }
    }

    private fun fail(projection: DshFailureProjection, requiresRestart: Boolean) {
        _page.value = DshPageUiState(loading = false)
        _state.value = DshWorkspaceUiState.Failure(
            projection = projection,
            requiresRestart = requiresRestart || projection.runtimeExited
        )
    }

    // -- WebView events ----------------------------------------------------

    fun onPageStarted() {
        _page.update { it.copy(loading = true, progress = 0, error = null) }
    }

    fun onPageCommitVisible() {
        _page.update { it.copy(loading = false, error = null) }
    }

    /**
     * The WebView host observed the first visible frame of the page for [epoch].
     *
     * Forwarded only when the live frozen snapshot is READY for exactly that epoch (READY is
     * published after the readiness gate verified the authenticated index and client plugins)
     * and only once per generation. It is an additive observation: page state and the runtime
     * phase are never changed here, and a rejected report is dropped rather than retried, so a
     * stale generation can never write into a newer timeline.
     */
    fun onFirstFrameRendered(epoch: Long) {
        val current = _state.value as? DshWorkspaceUiState.Web ?: return
        if (current.epoch != epoch) return
        val live = client.snapshot.value
        if (live.phase != DshRuntimePhase.READY || live.epoch != epoch) return
        if (firstFrameReportedEpoch == epoch) return
        firstFrameReportedEpoch = epoch
        firstFrameReporter.onFirstFrameRendered(epoch)
    }

    fun onProgress(progress: Int) {
        _page.update { it.copy(progress = progress.coerceIn(0, 100)) }
    }

    /**
     * Called only for a same-origin main-frame finish. A token URL finish means the 303 cookie
     * exchange has not completed yet: nothing may be persisted or restored at that point.
     */
    fun onPageFinished(cleanUrl: String?, sameOrigin: Boolean, containsLaunchToken: Boolean) {
        val current = _state.value as? DshWorkspaceUiState.Web ?: return
        if (!sameOrigin || containsLaunchToken) return
        _page.update { it.copy(loading = false, progress = 100, error = null) }

        var next = current
        val path = DshNavigationPolicy.pathForRestore(cleanUrl, current.origin)
        if (path != null) {
            currentCleanPath = path
            // While a restore is pending, do not let the intermediate root page overwrite it.
            val persist = current.restoreApplied || current.restoredPath == null || path != "/"
            if (persist && path != lastPersistedPath) {
                lastPersistedPath = path
                viewModelScope.launch { navigationStore.writeLastPath(path) }
            }
            next = next.copy(authenticatedEntry = false, loadUrl = current.origin.pathUrl(path))
        }
        if (!current.restoreApplied) {
            val target = DshNavigationPolicy.restoreTargetIfNeeded(
                origin = current.origin,
                currentCleanUrl = cleanUrl,
                restorePath = current.restoredPath
            )
            next = if (target != null) {
                restoreSequence += 1
                next.copy(
                    restoreRequest = DshRestoreRequest(restoreSequence, target),
                    restoreApplied = true
                )
            } else {
                next.copy(restoreApplied = true)
            }
        }
        if (next != _state.value) _state.value = next
    }

    fun onMainFrameError(kind: DshWebErrorKind) {
        val current = _state.value as? DshWorkspaceUiState.Web ?: return
        if (current.authenticatedEntry) {
            retryEntryLoadOnceConnected(current)
            return
        }
        if (fallbackToRootIfNeeded(current)) return
        _page.update { it.copy(loading = false, error = DshPageError.Network(kind)) }
    }

    /**
     * The host reported READY, but its HTTP listener can still be a few hundred ms behind. Reusing
     * the in-memory entry URL (never persisted/logged) for at most two reloads bridges that window;
     * a real auth rejection follows the explicit failure path instead.
     */
    private fun retryEntryLoadOnceConnected(current: DshWorkspaceUiState.Web) {
        if (entryRetries >= MAX_ENTRY_LOAD_RETRIES) {
            fail(
                DshFailureProjectionMapper.project(
                    DshRuntimeFailure(
                        code = DshFailureCode.LOADER_NOT_READY,
                        message = "The loopback DSH page did not answer the authenticated entry",
                        retryable = true
                    )
                ),
                requiresRestart = true
            )
            return
        }
        entryRetries += 1
        viewModelScope.launch {
            delay(ENTRY_RETRY_DELAY_MILLIS)
            val live = _state.value as? DshWorkspaceUiState.Web ?: return@launch
            if (!live.authenticatedEntry || live.epoch != current.epoch) return@launch
            loadSequence += 1
            _state.value = live.copy(loadId = loadSequence)
            _page.value = DshPageUiState(loading = true)
        }
    }

    fun onHttpUnauthorized() {
        val current = _state.value as? DshWorkspaceUiState.Web ?: return
        if (!current.authenticatedEntry && fallbackToRootIfNeeded(current)) return
        fail(DshFailureProjectionMapper.authenticationRejected(), requiresRestart = true)
    }

    fun onHttpError(statusCode: Int) {
        if (statusCode == 401 || statusCode == 403) {
            onHttpUnauthorized()
            return
        }
        val current = _state.value as? DshWorkspaceUiState.Web ?: return
        if (!current.authenticatedEntry && fallbackToRootIfNeeded(current)) return
        _page.update { it.copy(loading = false, error = DshPageError.Http(statusCode)) }
    }

    /** The official static fallback does not serve unknown SPA paths; return to `/` once. */
    private fun fallbackToRootIfNeeded(current: DshWorkspaceUiState.Web): Boolean {
        if (current.authenticatedEntry) return false
        val path = currentCleanPath
        if (path == null || path == "/") return false
        currentCleanPath = null
        loadSequence += 1
        _page.value = DshPageUiState(loading = true)
        _state.value = current.copy(
            loadUrl = current.origin.pathUrl("/"),
            loadId = loadSequence,
            authenticatedEntry = false,
            restoredPath = null,
            restoreApplied = true,
            restoreRequest = null
        )
        return true
    }

    fun onRendererGone() {
        val current = _state.value as? DshWorkspaceUiState.Web ?: return
        val path = currentCleanPath ?: "/"
        currentCleanPath = path.takeIf { it != "/" }
        entryRetries = 0
        webGeneration += 1
        loadSequence += 1
        _state.value = current.copy(
            loadUrl = current.origin.pathUrl(path),
            loadId = loadSequence,
            authenticatedEntry = false,
            webGeneration = webGeneration,
            restoredPath = null,
            restoreApplied = true,
            restoreRequest = null
        )
        _page.value = DshPageUiState(loading = true)
        postNotice("DSH 页面渲染进程已重启，正在重新加载。")
        // The renderer, not the runtime, died. If the client can still issue an entry for the same
        // generation, prefer it (the frozen contract normally returns null after the first handoff);
        // otherwise the recreated WebView resumes on the persisted HttpOnly cookie.
        viewModelScope.launch {
            val entry = runCatching { client.webEntry() }.getOrNull() ?: return@launch
            val live = _state.value as? DshWorkspaceUiState.Web ?: return@launch
            if (entry.epoch != live.epoch || !live.origin.matches(entry.authenticatedUrl)) return@launch
            loadSequence += 1
            _state.value = live.copy(
                loadUrl = entry.authenticatedUrl,
                loadId = loadSequence,
                authenticatedEntry = true,
                restoreRequest = null,
                restoreApplied = true
            )
            _page.value = DshPageUiState(loading = true)
        }
    }

    fun onNavigationState(canGoBack: Boolean) {
        _page.update { it.copy(canGoBack = canGoBack) }
    }

    /** A new WebView is about to be composed; never let it replay a consumed token entry. */
    fun onWebHostDisposed(webGeneration: Long) {
        val current = _state.value as? DshWorkspaceUiState.Web ?: return
        // Only the generation that actually left composition may rewrite the state. A newer
        // generation already owns loadUrl (it may even be a fresh token entry) and must not be
        // downgraded to cookie resume by the disposal of its predecessor.
        if (current.webGeneration != webGeneration) return
        if (!current.authenticatedEntry) return
        entryRetries = 0
        _state.value = current.copy(
            loadUrl = current.origin.pathUrl(currentCleanPath ?: "/"),
            authenticatedEntry = false,
            restoredPath = null,
            restoreApplied = true,
            restoreRequest = null
        )
    }

    fun consumeRestoreRequest(id: Long) {
        val current = _state.value as? DshWorkspaceUiState.Web ?: return
        if (current.restoreRequest?.id != id) return
        _state.value = current.copy(restoreRequest = null, restoreApplied = true)
    }

    // -- host visibility (D25) --------------------------------------------

    /**
     * The tab became invisible. Backgrounding is not cancellation: no runtime, session or page
     * state is changed here. Only the time is remembered so a return can detect an interrupted
     * page load.
     */
    fun onHostBackgrounded(nowMillis: Long = clock()) {
        backgroundedAtMillis = nowMillis
    }

    /**
     * The tab is visible again. When the system interrupted a load while the app was backgrounded,
     * reload the same clean page; a healthy page is never reloaded and DSH work is never resent.
     */
    fun onHostForegrounded(nowMillis: Long = clock()) {
        val backgroundedAt = backgroundedAtMillis ?: return
        backgroundedAtMillis = null
        if (_state.value !is DshWorkspaceUiState.Web) return
        val action = DshWebRecoveryPolicy.onForeground(
            backgroundMillis = nowMillis - backgroundedAt,
            loadingInterrupted = _page.value.loading,
            hasWebPage = true
        )
        if (action == DshWebRecoveryAction.RELOAD_CURRENT_PAGE) {
            requestPageReload()
            postNotice("DSH 页面在后台被中断，已重新加载。")
        }
    }

    fun requestPageReload() {
        val current = _state.value as? DshWorkspaceUiState.Web ?: return
        loadSequence += 1
        _page.value = DshPageUiState(loading = true)
        _state.value = current.copy(loadId = loadSequence)
    }

    fun dismissPageError() {
        _page.update { it.copy(error = null) }
    }

    // -- notices -----------------------------------------------------------

    fun postNotice(text: String) {
        noticeSequence += 1
        _notice.value = DshNotice(noticeSequence, text)
    }

    fun consumeNotice(id: Long) {
        if (_notice.value?.id == id) _notice.value = null
    }

    companion object {
        private const val MAX_ENTRY_LOAD_RETRIES = 2
        private const val ENTRY_RETRY_DELAY_MILLIS = 700L

        fun factory(
            client: DshRuntimeClient,
            navigationStore: DshNavigationStore,
            firstFrameReporter: DshFirstFrameReporter = DshFirstFrameReporter.None,
            clock: () -> Long = System::currentTimeMillis
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { DshWorkspaceViewModel(client, navigationStore, firstFrameReporter, clock) }
        }
    }
}
