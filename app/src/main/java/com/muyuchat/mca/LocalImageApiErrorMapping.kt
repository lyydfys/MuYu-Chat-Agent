package com.muyuchat.mca

import com.muyuchat.api.local.ImageGenerationProviderException

/** Keeps worker/runtime failures structured when they cross the authenticated Local Images API. */
internal fun Throwable.toLocalImageApiProviderExceptionOrNull(): ImageGenerationProviderException? =
    when (this) {
        is ImageGenerationProviderException -> this
        is LocalImageWorkerRemoteException ->
            ImageGenerationProviderException.fromWorkerFailure(code, message.orEmpty())
        is LocalImageWorkerCancelledException -> ImageGenerationProviderException(
            code = "image_generation_cancelled",
            httpStatus = 409,
            message = message.orEmpty().ifBlank { "Local image generation was cancelled." }
        )
        is LocalImageWorkerDisconnectedException -> ImageGenerationProviderException(
            code = "image_worker_unavailable",
            httpStatus = 503,
            message = message.orEmpty().ifBlank { "The local image worker is unavailable." }
        )
        is LocalImageProductContractException ->
            ImageGenerationProviderException.fromWorkerFailure(code, message.orEmpty())
        is ImageNativeExecutionContractException ->
            ImageGenerationProviderException.fromWorkerFailure(code, message.orEmpty())
        is ImageProfileResolutionException -> ImageGenerationProviderException(
            code = localImagePublicErrorCode(),
            httpStatus = 422,
            message = message.orEmpty().ifBlank { "The selected image model has an invalid execution profile." }
        )
        is LocalImageWorkerException -> ImageGenerationProviderException(
            code = "invalid_image_worker_response",
            httpStatus = 502,
            message = message.orEmpty().ifBlank { "The local image worker returned an invalid response." }
        )
        else -> null
    }

/** Maps only stable, user-actionable resolution issues to their product error contracts. */
internal fun ImageProfileResolutionException.localImagePublicErrorCode(): String =
    when {
        validation.issues.any { it.code == "ULTRAFIX_EXECUTION_UNSUPPORTED" } ->
            "ultrafix_execution_unsupported"
        else -> "invalid_image_execution_profile"
    }
