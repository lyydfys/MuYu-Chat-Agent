package com.muyuchat.mca

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * R1/S10：MainUiState 的唯一状态源持有者（方案 §14.3 最小契约，行为不变）。
 *
 * 责任：持有唯一 [MutableStateFlow]；对外只暴露只读 [state]；写入必须经 [update]（CAS 语义）。
 *
 * 过渡说明：MainViewModel 与其 inner port 暂时通过 [ownerFlow] 访问同一可变流，逐字保留既有
 * CAS/读取语义（含 port 契约中的 `MutableStateFlow` 型 `uiState` 属性）。该访问器是过渡 adapter，
 * 退出条件 = 全部 `_uiState.update`/`_uiState.value` 站点迁移到 [update]/[current]（R2–R4 收口），
 * 之后删除 [ownerFlow] 并收敛 port 契约到只读 [state]。
 */
class UiStateStore(initial: MainUiState) {
    private val mutable = MutableStateFlow(initial)

    val state: StateFlow<MainUiState> = mutable.asStateFlow()

    val current: MainUiState
        get() = mutable.value

    fun update(reducer: (MainUiState) -> MainUiState) {
        mutable.update(reducer)
    }

    /**
     * R3/C2：状态行唯一写入口（自 MainViewModel.showStatus 下移，体逐字等价）。
     * 单字段写入必须经此漏斗（StatusMessageWriterContractTest 的规则不变，只换了漏斗的家）。
     */
    fun showStatus(message: String?) {
        update { it.copy(statusMessage = message) }
    }

    internal val ownerFlow: MutableStateFlow<MainUiState>
        get() = mutable
}
