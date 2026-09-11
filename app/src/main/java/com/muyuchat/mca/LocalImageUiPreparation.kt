package com.muyuchat.mca

internal data class PreparedLocalImageUi(
    val capabilities: LocalImageUiCapabilitiesSnapshot?,
    val readiness: String?,
    val label: String
)

/** Call on IO. Compose consumes only the immutable result, never the model filesystem. */
internal fun prepareLocalImageUi(model: LocalImageModelRecord, verified: Boolean?): PreparedLocalImageUi =
    try {
        val capabilities = model.imageCapabilitiesForUi()
        PreparedLocalImageUi(capabilities,
            model.localImageReadinessForUi(verified, capabilities),
            model.localImageReadinessLabelForUi(verified, capabilities))
    } catch (error: Exception) {
        PreparedLocalImageUi(null, "无法读取模型配置：${error.message}。请在模型页刷新或重新导入。", "读取失败")
    }
