package com.muyuchat.mca

fun MainViewModel.emitEffect(effect: com.muyuchat.mca.presentation.MainUiEffect) {
    _uiEffects.tryEmit(effect)
}

fun MainViewModel.processIntent(intent: com.muyuchat.mca.presentation.MainUiIntent) {
    when (intent) {
        is com.muyuchat.mca.presentation.MainUiIntent.SelectTab -> selectTab(intent.tab)
        is com.muyuchat.mca.presentation.MainUiIntent.SendChatMessage -> {
            onInputChange(intent.prompt)
            sendMessage()
        }
        is com.muyuchat.mca.presentation.MainUiIntent.CancelGeneration -> stopGeneration()
        is com.muyuchat.mca.presentation.MainUiIntent.StartImageGeneration -> generateImageAsset(intent.prompt)
        is com.muyuchat.mca.presentation.MainUiIntent.SelectModel -> selectChatModel(intent.modelId)
        is com.muyuchat.mca.presentation.MainUiIntent.ClearCurrentChat -> clearChat()
        is com.muyuchat.mca.presentation.MainUiIntent.CheckAppUpdate -> checkForAppUpdate()
        is com.muyuchat.mca.presentation.MainUiIntent.ShowStatusMessage -> showStatus(intent.message)
    }
}
