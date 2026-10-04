package com.muyuchat.mca

/**
 * Scheduler mapping for a product task and the capability check that guards it.
 *
 * Split out of LocalImageInputContract.kt; declarations moved verbatim.
 */

/**
 * Scheduler availability is a product-task property, not a model-id or device admission rule.
 * QNN img2img and inpaint start from a strength-derived denoising index. PNDM/PLMS has a stateful
 * PRK/PLMS warm-up and cannot enter that schedule at an arbitrary index, while the same scheduler
 * remains valid for a full text-to-image timetable.
 */
internal fun ImageExecutionProfile.supportedSchedulersForProductTask(
    taskMode: LocalImageTaskMode
): Set<ImageSchedulerAlgorithm> {
    val declared = capabilities.supportedSchedulers
    return when {
        taskMode == LocalImageTaskMode.IMG2IMG && hasExecutableQnnImg2ImgTopology() ->
            declared - ImageSchedulerAlgorithm.PNDM_PLMS
        taskMode == LocalImageTaskMode.INPAINT && hasExecutableQnnInpaintTopology() ->
            supportedQnnInpaintSchedulers()
        else -> declared
    }
}

private val PRODUCT_TASK_SAMPLER_PREFERENCE = listOf(
    ImageSchedulerAlgorithm.DPMPP_2M,
    ImageSchedulerAlgorithm.EULER,
    ImageSchedulerAlgorithm.EULER_A,
    ImageSchedulerAlgorithm.DDIM,
    ImageSchedulerAlgorithm.LCM,
    ImageSchedulerAlgorithm.FLOW_MATCH,
    ImageSchedulerAlgorithm.PNDM_PLMS
)

/**
 * Chooses the deterministic sampler used when a request omits `sampler`/`sampleMethod`.
 *
 * The profile's own default remains authoritative whenever it is valid for the selected task.
 * Only a task-incompatible default (notably generic QNN profiles whose legacy default is PNDM)
 * is replaced. The fallback order is intentionally independent of recommendation id, chipset,
 * and device validation state.
 */
internal fun ImageExecutionProfile.defaultSchedulerForProductTask(
    taskMode: LocalImageTaskMode
): ImageSchedulerAlgorithm {
    val supported = supportedSchedulersForProductTask(taskMode)
    scheduler.algorithm.takeIf { it in supported }?.let { return it }
    PRODUCT_TASK_SAMPLER_PREFERENCE.firstOrNull { it in supported }?.let { return it }
    localImageInputReject(
        "unsupported_sampler",
        "The resolved image profile has no sampler supported for ${taskMode.wireName}."
    )
}

internal fun ImageExecutionProfile.orderedSchedulersForProductTask(
    taskMode: LocalImageTaskMode
): List<ImageSchedulerAlgorithm> {
    val supported = supportedSchedulersForProductTask(taskMode)
    if (supported.isEmpty()) return emptyList()
    val taskDefault = defaultSchedulerForProductTask(taskMode)
    return buildList {
        add(taskDefault)
        PRODUCT_TASK_SAMPLER_PREFERENCE.forEach { scheduler ->
            if (scheduler != taskDefault && scheduler in supported) add(scheduler)
        }
        supported.forEach { scheduler ->
            if (scheduler !in this) add(scheduler)
        }
    }
}

internal fun validateLocalImageTaskSamplerCapability(
    profile: ImageExecutionProfile,
    taskMode: LocalImageTaskMode,
    requestedScheduler: ImageSchedulerAlgorithm
) {
    if (requestedScheduler in profile.supportedSchedulersForProductTask(taskMode)) return
    if (
        taskMode == LocalImageTaskMode.IMG2IMG &&
        profile.hasExecutableQnnImg2ImgTopology() &&
        requestedScheduler == ImageSchedulerAlgorithm.PNDM_PLMS
    ) {
        localImageInputReject(
            "unsupported_img2img_sampler",
            "QNN img2img cannot use PNDM/PLMS because that scheduler cannot start at the " +
            "strength-derived denoising index. Select another sampler supported by this model."
        )
    }
    if (
        taskMode == LocalImageTaskMode.INPAINT &&
        profile.hasExecutableQnnInpaintTopology() &&
        requestedScheduler == ImageSchedulerAlgorithm.PNDM_PLMS
    ) {
        localImageInputReject(
            "unsupported_inpaint_sampler",
            "QNN inpaint cannot use PNDM/PLMS because that scheduler cannot start at the " +
                "strength-derived denoising index. Select another sampler supported by this model."
        )
    }
    localImageInputReject(
        "unsupported_sampler",
        "The resolved image profile does not support ${imageSchedulerProductName(requestedScheduler)} " +
            "for ${taskMode.wireName}."
    )
}
