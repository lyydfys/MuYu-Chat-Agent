package com.muyuchat.mca

import org.json.JSONObject

/**
 * T2B22-T4（b 工位）拆片：`qnnPreviewExecutionAuditForRuntime` 自
 * LocalImageProviderQnnExecutionAudit.kt 逐字节平移；函数原为 internal 顶层，零改写。
 */

internal fun qnnPreviewExecutionAuditForRuntime(
    nativeResult: JSONObject,
    requestedPreview: LocalImagePreviewOptions?
): JSONObject? {
    val sharedSessionModes = setOf(
        "shared_unet_vae",
        "shared_text_unet_vae",
        "shared_unet_controlnet_vae",
        "shared_text_unet_controlnet_vae"
    )
    return when (nativeResult.optString("runtimeSessionMode")) {
        in sharedSessionModes -> qnnSharedPreviewExecutionAudit(nativeResult, requestedPreview)
        SDXL_ISOLATED_UNET_VAE_MODE,
        SDXL_ISOLATED_ENCODER_UNET_VAE_MODE,
        SDXL_ISOLATED_ULTRAFIX_MODE ->
            qnnSdxlProjectionPreviewExecutionAudit(nativeResult, requestedPreview)
        else -> {
            require(requestedPreview == null) {
                "Unknown QNN execution cannot carry a live preview request."
            }
            require(!nativeResult.toString().contains("\"previewPath\"")) {
                "Final QNN execution metadata must not expose a transient preview path."
            }
            null
        }
    }
}
