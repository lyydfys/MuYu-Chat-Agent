package com.muyuchat.feature.dsh.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleAvailability
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsolePort
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleResult
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State of the D23 maintenance section inside the DSH console. */
sealed interface DshMaintenanceConsoleUiState {
    data object Loading : DshMaintenanceConsoleUiState

    /** The maintenance owner reported UNAVAILABLE; all actions are disabled honestly. */
    data class Disabled(val reason: String) : DshMaintenanceConsoleUiState

    data class Failure(val code: String, val retryable: Boolean) : DshMaintenanceConsoleUiState

    data class Ready(
        val snapshot: DshMaintenanceConsoleSnapshot,
        val model: DshMaintenanceConsoleUiModel,
        val availableIntents: Set<DshMaintenanceConsoleIntent>,
        val busy: Boolean = false,
        val notice: String? = null
    ) : DshMaintenanceConsoleUiState
}

/**
 * Owner of the maintenance console projection (D23).
 *
 * It reads only through [DshMaintenanceConsolePort.snapshot] and acts only through
 * [DshMaintenanceConsolePort.execute]. It performs no file IO, opens no SAF picker, touches no
 * session/backup/patch state directly and never sees a path, URI, token or credential.
 */
class DshMaintenanceConsoleViewModel(
    private val port: DshMaintenanceConsolePort
) : ViewModel() {

    private val _state = MutableStateFlow<DshMaintenanceConsoleUiState>(DshMaintenanceConsoleUiState.Loading)
    val state: StateFlow<DshMaintenanceConsoleUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var started = false
    private var reloadRequested = false

    /** First entry into the maintenance section. Idempotent. */
    fun start() {
        if (started) return
        started = true
        load(showLoading = true)
    }

    fun refresh() {
        if (!started) return
        load(showLoading = false)
    }

    fun retry() {
        if (!started) return
        load(showLoading = true)
    }

    /**
     * Submits one intent after the local gate. Destructive intents are expected to carry
     * `confirmed = true` (the UI asks first); an unconfirmed one is refused locally.
     */
    fun execute(intent: DshMaintenanceConsoleIntent) {
        val ready = _state.value as? DshMaintenanceConsoleUiState.Ready ?: return
        if (ready.busy) return
        when (val gate = DshMaintenanceConsolePolicy.gate(intent, ready.snapshot, portAvailable = true)) {
            is DshMaintenanceGate.Denied -> {
                _state.value = ready.copy(
                    notice = DshMaintenanceConsolePolicy.refusalMessage(gate.reason)
                )
                return
            }

            DshMaintenanceGate.Allowed -> Unit
        }
        _state.value = ready.copy(busy = true, notice = null)
        viewModelScope.launch(start = CoroutineStart.DEFAULT) {
            val outcome = runCatching { port.execute(intent) }
            outcome.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
                val current = _state.value as? DshMaintenanceConsoleUiState.Ready
                if (current != null) {
                    _state.value = current.copy(busy = false, notice = "维护操作失败，请重试。")
                }
                return@launch
            }
            val result = outcome.getOrNull()
            val current = _state.value as? DshMaintenanceConsoleUiState.Ready
            if (current == null) return@launch
            val notice = result?.let(DshMaintenanceConsolePolicy::resultMessage)
                ?: "维护操作没有返回结果。"
            _state.value = current.copy(busy = false, notice = notice)
            when (result) {
                is DshMaintenanceConsoleResult.Applied,
                is DshMaintenanceConsoleResult.Pending -> load(showLoading = false)
                else -> Unit
            }
        }
    }

    /**
     * Reports that a user-selected document could not be read (provider refused the stream, the
     * file was empty, or it exceeded the contract size limit). Nothing is submitted: the console
     * must never apply a partially read document.
     */
    fun reportDocumentUnreadable() {
        val ready = _state.value as? DshMaintenanceConsoleUiState.Ready ?: return
        _state.value = ready.copy(notice = "无法读取所选文件；请确认文件未超出大小上限后重试。")
    }

    private fun load(showLoading: Boolean) {
        if (loadJob?.isActive == true) {
            reloadRequested = true
            return
        }
        val previous = _state.value as? DshMaintenanceConsoleUiState.Ready
        val previousNotice = previous?.notice
        if (showLoading || previous == null) {
            _state.value = DshMaintenanceConsoleUiState.Loading
        } else {
            _state.value = previous.copy(busy = true)
        }
        loadJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val snapshot = port.snapshot()
                _state.value = if (snapshot.availability == DshMaintenanceConsoleAvailability.UNAVAILABLE) {
                    DshMaintenanceConsoleUiState.Disabled("维护接口当前不可用。")
                } else {
                    DshMaintenanceConsoleUiState.Ready(
                        snapshot = snapshot,
                        model = DshMaintenanceConsoleProjector.project(snapshot),
                        availableIntents = DshMaintenanceConsolePolicy.availableIntents(
                            snapshot,
                            portAvailable = true
                        ),
                        notice = previousNotice
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                if (previous != null) {
                    _state.value = previous.copy(busy = false, notice = "刷新维护状态失败。")
                } else {
                    _state.value = DshMaintenanceConsoleUiState.Failure(
                        code = "MAINTENANCE_SNAPSHOT_UNAVAILABLE",
                        retryable = true
                    )
                }
            } finally {
                if (loadJob === currentCoroutineContext()[Job]) loadJob = null
                if (reloadRequested) {
                    reloadRequested = false
                    load(showLoading = false)
                }
            }
        }
        loadJob?.start()
    }

    companion object {
        fun factory(port: DshMaintenanceConsolePort): ViewModelProvider.Factory = viewModelFactory {
            initializer { DshMaintenanceConsoleViewModel(port) }
        }
    }
}
