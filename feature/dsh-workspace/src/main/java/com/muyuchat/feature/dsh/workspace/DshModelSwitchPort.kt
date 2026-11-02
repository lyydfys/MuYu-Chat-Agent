package com.muyuchat.feature.dsh.workspace

import kotlinx.coroutines.flow.StateFlow

/**
 * One MCA local model offered by the DSH ball's model panel.
 *
 * [modelId] is the MCA manifest id, never a path or a credential. [loaded] is the app's live
 * loaded-model fact; selecting a non-loaded entry asks the MCA model owner to load it.
 */
data class DshModelChoice(
    val modelId: String,
    val label: String,
    val detail: String,
    val loaded: Boolean
)

/**
 * Bounded, read-only projection of the MCA model picker plus the one write action the DSH surface
 * is allowed to request: load/switch to an already downloaded MCA model.
 *
 * The host owns loading, admission, memory arbitration and persistence; this surface only submits
 * the intent and renders the resulting state. It never constructs an engine, never unloads a model
 * and never touches the model files.
 */
data class DshModelSwitchState(
    val choices: List<DshModelChoice> = emptyList(),
    val activeModelId: String? = null,
    val busy: Boolean = false,
    val status: String? = null
)

/** Host port consumed by the DSH floating ball. Implemented by the app composition root. */
interface DshModelSwitchPort {
    val state: StateFlow<DshModelSwitchState>

    /** Requests the production MCA load/switch path for [modelId]. */
    fun select(modelId: String)
}
