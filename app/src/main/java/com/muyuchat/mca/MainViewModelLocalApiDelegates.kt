package com.muyuchat.mca

fun MainViewModel.toggleApi(enabled: Boolean) {
    val restEnabled = if (enabled) uiStateStore.current.restEnabled else false
    persistApiPreferences(apiEnabled = enabled, restEnabled = restEnabled)
    showStatus(if (enabled) "正在启动本机 API..." else "正在停止本机 API...")
    requestLocalApiState(
        enabled = enabled,
        restEnabled = restEnabled,
        failurePrefix = "本机 API 启动失败"
    )
}

fun MainViewModel.toggleRest(enabled: Boolean) {
    val apiEnabled = enabled || uiStateStore.current.apiEnabled
    persistApiPreferences(apiEnabled = apiEnabled, restEnabled = enabled)
    showStatus(if (enabled) "正在开放局域网 REST..." else "正在关闭局域网 REST...")
    requestLocalApiState(
        enabled = apiEnabled,
        restEnabled = enabled,
        failurePrefix = "REST 启动失败"
    )
}

internal fun MainViewModel.requestLocalApiState(
    enabled: Boolean,
    restEnabled: Boolean,
    failurePrefix: String
) = localApiServerCoordinator.requestLocalApiState(enabled, restEnabled, failurePrefix)

internal suspend fun MainViewModel.applyLocalApiState(
    operation: Long,
    enabled: Boolean,
    restEnabled: Boolean,
    failurePrefix: String
): LocalApiApplyResult? =
    localApiServerCoordinator.applyLocalApiState(operation, enabled, restEnabled, failurePrefix)

internal suspend fun MainViewModel.awaitLocalApiForegroundServiceReady(): Boolean =
    localApiServerCoordinator.awaitLocalApiForegroundServiceReady()

internal suspend fun MainViewModel.ensureLocalApiForegroundServiceReady(restEnabled: Boolean): Boolean =
    localApiServerCoordinator.ensureLocalApiForegroundServiceReady(restEnabled)

internal fun MainViewModel.setLocalApiForegroundService(enabled: Boolean, restEnabled: Boolean) =
    localApiServerCoordinator.setLocalApiForegroundService(enabled, restEnabled)

internal fun MainViewModel.releaseLocalApiProcessOwnership() =
    localApiServerCoordinator.releaseLocalApiProcessOwnership()

internal fun MainViewModel.startApiServer(bindHost: String) =
    localApiServerCoordinator.startApiServer(bindHost)

internal fun MainViewModel.stopApiServer() =
    localApiServerCoordinator.stopApiServer()
