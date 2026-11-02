package com.muyuchat.feature.dsh.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.muyuchat.core.dsh.contract.console.DshConsoleIntent
import com.muyuchat.core.dsh.contract.console.DshConsoleIntentResult
import com.muyuchat.core.dsh.contract.console.DshConsolePort
import com.muyuchat.core.dsh.contract.console.DshConsoleSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State of the DSH-only console surface. */
sealed interface DshConsoleUiState {
    data object Loading : DshConsoleUiState

    data class Ready(
        val snapshot: DshConsoleSnapshot,
        val model: DshConsoleUiModel,
        val availableIntents: Set<DshConsoleIntent>,
        val refreshing: Boolean = false,
        val busy: Boolean = false
    ) : DshConsoleUiState

    data class Failure(val code: String, val retryable: Boolean) : DshConsoleUiState
}

/** One-shot console message with a monotonic id so repeats are not swallowed. */
data class DshConsoleNotice(val id: Long, val text: String)

/**
 * Owner of the console projection.
 *
 * It reads only through [DshConsolePort.snapshot] and acts only through [DshConsolePort.execute].
 * It never contacts the runtime host, capability owners, maintenance owner or diagnostics owner
 * directly, never builds an Android Intent and never writes DSH session/model/plugin state. Every
 * visible change is the port's own newer projection.
 */
class DshConsoleViewModel(private val port: DshConsolePort) : ViewModel() {

    private val _state = MutableStateFlow<DshConsoleUiState>(DshConsoleUiState.Loading)
    val state: StateFlow<DshConsoleUiState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<DshConsoleNotice?>(null)
    val notice: StateFlow<DshConsoleNotice?> = _notice.asStateFlow()

    private var loadJob: Job? = null
    private var started = false
    private var noticeSequence = 0L

    /** First entry into the console. Idempotent. */
    fun start() {
        if (started) return
        started = true
        load(showLoading = true)
    }

    /** Re-project without changing any owner state. */
    fun refresh() {
        if (!started) return
        load(showLoading = false)
    }

    fun retry() {
        if (!started) return
        load(showLoading = true)
    }

    /**
     * Submits one typed intent. The local gate only decides whether the button may submit; the
     * port owner remains the authority and can still reject.
     */
    fun execute(intent: DshConsoleIntent) {
        if (intent == DshConsoleIntent.Refresh) {
            refresh()
            return
        }
        val ready = _state.value as? DshConsoleUiState.Ready ?: return
        if (ready.busy) return
        when (val gate = DshConsoleIntentPolicy.gate(intent, ready.snapshot)) {
            is DshConsoleIntentGate.Denied -> {
                postNotice(gate.reason.messageFor())
                return
            }

            DshConsoleIntentGate.Allowed -> Unit
        }
        _state.value = ready.copy(busy = true)
        viewModelScope.launch(start = CoroutineStart.DEFAULT) {
            val result = runCatching { port.execute(intent) }
            val current = _state.value as? DshConsoleUiState.Ready
            if (current != null) _state.value = current.copy(busy = false)
            result.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
                postNotice("控制台操作失败，请重试。")
                return@launch
            }
            when (val outcome = result.getOrNull()) {
                is DshConsoleIntentResult.Applied -> {
                    postNotice(outcome.message ?: "已执行。")
                    load(showLoading = false)
                }

                is DshConsoleIntentResult.Pending -> {
                    postNotice(outcome.message ?: "已提交，等待完成。")
                    load(showLoading = false)
                }

                is DshConsoleIntentResult.Rejected -> {
                    postNotice(outcome.message ?: outcome.reason.messageFor())
                }

                is DshConsoleIntentResult.Failed -> {
                    postNotice(outcome.message ?: "操作失败，请重试。")
                }

                null -> postNotice("控制台操作没有返回结果。")
            }
        }
    }

    fun consumeNotice(id: Long) {
        if (_notice.value?.id == id) _notice.value = null
    }

    private fun load(showLoading: Boolean) {
        if (loadJob?.isActive == true) return
        val previous = _state.value as? DshConsoleUiState.Ready
        if (showLoading || previous == null) {
            _state.value = DshConsoleUiState.Loading
        } else {
            _state.value = previous.copy(refreshing = true)
        }
        loadJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val snapshot = port.snapshot()
                _state.value = DshConsoleUiState.Ready(
                    snapshot = snapshot,
                    model = DshConsoleProjector.project(snapshot),
                    availableIntents = DshConsoleIntentPolicy.availableIntents(snapshot)
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                if (previous != null) {
                    _state.value = previous.copy(refreshing = false)
                    postNotice("刷新控制台失败。")
                } else {
                    _state.value = DshConsoleUiState.Failure("SNAPSHOT_UNAVAILABLE", retryable = true)
                }
            } finally {
                if (loadJob === currentCoroutineContext()[Job]) {
                    loadJob = null
                }
            }
        }
        loadJob?.start()
    }

    private fun postNotice(text: String) {
        noticeSequence += 1
        _notice.value = DshConsoleNotice(noticeSequence, text)
    }

    companion object {
        fun factory(port: DshConsolePort): ViewModelProvider.Factory = viewModelFactory {
            initializer { DshConsoleViewModel(port) }
        }
    }
}
