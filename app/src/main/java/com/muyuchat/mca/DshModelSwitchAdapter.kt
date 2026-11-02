package com.muyuchat.mca

import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.feature.dsh.workspace.DshModelChoice
import com.muyuchat.feature.dsh.workspace.DshModelSwitchPort
import com.muyuchat.feature.dsh.workspace.DshModelSwitchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Read-projection of the existing MCA model state into the DSH ball's model panel.
 *
 * The adapter owns no model state and never loads anything itself: it maps the production
 * [MainUiState] (the same list and the same loaded id the chat screen shows) and forwards a
 * selection to the existing `selectChatModel` owner. Loading, admission, memory arbitration and
 * persistence stay in that owner.
 */
internal class DshModelSwitchAdapter(
    uiState: StateFlow<MainUiState>,
    private val onSelect: (String) -> Unit,
    scope: CoroutineScope
) : DshModelSwitchPort {

    override val state: StateFlow<DshModelSwitchState> = uiState
        .map { state ->
            DshModelSwitchState(
                choices = state.models.map { model ->
                    DshModelChoice(
                        modelId = model.id,
                        label = model.displayName.ifBlank { model.fileName },
                        detail = describe(model),
                        loaded = model.id == state.loadedModelId
                    )
                },
                activeModelId = state.loadedModelId,
                busy = state.modelLoadStage != null,
                status = state.modelLoadStage
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, DshModelSwitchState())

    override fun select(modelId: String) = onSelect(modelId)
}

/** Bounded row detail: quant/runtime plus a human size; never a path or a hash. */
internal fun describe(model: ModelManifest): String {
    val kind = model.quant?.takeIf { it.isNotBlank() }
        ?: model.runtime.name.lowercase().replace('_', '.')
    return kind + " · " + sizeLabel(model.sizeBytes)
}

internal fun sizeLabel(bytes: Long): String {
    if (bytes <= 0L) return "大小未知"
    val gb = bytes / 1_000_000_000.0
    if (gb >= 1.0) return String.format(java.util.Locale.US, "%.1f GB", gb)
    val mb = bytes / 1_000_000.0
    return String.format(java.util.Locale.US, "%.0f MB", mb)
}
