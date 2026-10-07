package com.muyuchat.mca

import com.muyuchat.api.local.LocalApiControlPlane
import com.muyuchat.api.local.LocalApiRuntime
import com.muyuchat.core.engine.GenerationParams

internal const val LOCAL_API_REST_PORT: Int = 11435

/**
 * Manages the runtime assembly, provider publication and release lifecycle for Local API.
 * Encapsulates the provider bindings so MainViewModel does not directly wire the global
 * [LocalApiRuntime] internals.
 */
internal class LocalApiRuntimeOwner(
    val token: Any = Any()
) {
    fun claim(onReplaced: () -> Unit) {
        LocalApiRuntime.claimOwner(token, onReplaced)
    }

    fun publishBindings(
        auxProviderPort: LocalApiAuxProviderPort,
        controlPlane: LocalApiControlPlane
    ) {
        installLocalApiAuxProviders(auxProviderPort)
        LocalApiRuntime.controlPlane = controlPlane
        LocalApiRuntime.publishBindings()
    }

    fun release() {
        LocalApiRuntime.releaseOwner(token)
    }

    fun apiUrl(host: String): String =
        "http://$host:$LOCAL_API_REST_PORT/v1"

    fun currentOpenApiAddress(lanIp: String?): String =
        apiUrl(lanIp ?: "本机局域网IP")

    suspend fun runLoopbackApiStreamSmoke(): LocalApiStreamSmokeResult =
        runLoopbackApiStreamSmokeFor()

    suspend fun runLoopbackEngineStreamSmoke(params: GenerationParams): LocalApiStreamSmokeResult =
        runLoopbackEngineStreamSmokeFor(params)
}
