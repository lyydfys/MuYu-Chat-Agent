package com.muyuchat.mca

/**
 * `+` menu Agent mode: the app-layer entry point.
 *
 * The write lives with the other turn-scoping operations (`applyChangeAgentMode` in
 * WebSearchConfigOperations) because Agent mode is one of the three mutually exclusive turn modes and
 * shares their state group. Keeping the delegate this thin means the ViewModel never owns a second
 * copy of the exclusion rule — the pure rule is in `feature/chat/.../ChatComposerModes.kt` and the
 * write is in one place.
 */
internal fun MainViewModel.changeAgentMode(enabled: Boolean) =
    webSearchWorkflowCoordinator.changeAgentMode(enabled)

/**
 * Drops the next-turn research override.
 *
 * Called when another exclusive mode is armed: the override is a *turn-scoped* choice, so leaving it
 * set would mean the next chat turn still runs a plan the user did not see themselves choosing.
 */
internal fun MainViewModel.clearWebSearchResearchModeForNextTurn() =
    webSearchWorkflowCoordinator.clearWebSearchResearchModeForNextTurn()
