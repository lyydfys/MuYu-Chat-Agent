package com.muyuchat.mca

/**
 * The raw main-process input draft and the one task-shape rule it shares with the options
 * contract.
 *
 * Split out of LocalImageInputContract.kt. `validateImageInputShape` was file-private; it becomes
 * `localImageInputValidateShape` because the options contract in another file applies the same
 * rule, in the same order, with unchanged wording.
 */

/** Raw main-process references. These are never serialized into worker IPC. */
data class LocalImageInputDraft(
    val taskMode: LocalImageTaskMode = LocalImageTaskMode.TEXT_TO_IMAGE,
    val inputImageReference: String? = null,
    val maskImageReference: String? = null,
    val controlImageReference: String? = null,
    val strength: Double? = null,
    val controlStrength: Double? = null
) {
    fun validate() {
        localImageInputValidateShape(
            taskMode = taskMode,
            inputPresent = !inputImageReference.isNullOrBlank(),
            maskPresent = !maskImageReference.isNullOrBlank(),
            controlPresent = !controlImageReference.isNullOrBlank(),
            strength = strength,
            controlStrength = controlStrength
        )
        listOfNotNull(inputImageReference, maskImageReference, controlImageReference).forEach { reference ->
            require(reference.length <= MAX_REFERENCE_CHARS) {
                "Image input reference exceeds the $MAX_REFERENCE_CHARS character limit."
            }
        }
    }

    internal fun validateForHistory() {
        localImageInputValidateShape(
            taskMode = taskMode,
            inputPresent = !inputImageReference.isNullOrBlank(),
            maskPresent = !maskImageReference.isNullOrBlank(),
            controlPresent = !controlImageReference.isNullOrBlank(),
            strength = strength,
            controlStrength = controlStrength,
            allowMissingRequiredInputs = true
        )
        listOfNotNull(inputImageReference, maskImageReference, controlImageReference).forEach { reference ->
            require(reference.length <= MAX_REFERENCE_CHARS) {
                "Image input reference exceeds the $MAX_REFERENCE_CHARS character limit."
            }
        }
    }

    internal fun hasRequiredInputReferences(): Boolean = when (taskMode) {
        LocalImageTaskMode.TEXT_TO_IMAGE -> true
        LocalImageTaskMode.IMG2IMG,
        LocalImageTaskMode.EDIT -> !inputImageReference.isNullOrBlank()
        LocalImageTaskMode.INPAINT ->
            !inputImageReference.isNullOrBlank() && !maskImageReference.isNullOrBlank()
        LocalImageTaskMode.CONTROL -> !controlImageReference.isNullOrBlank()
    }

    companion object {
        const val MAX_REFERENCE_CHARS: Int = 48 * 1024 * 1024
    }
}

internal fun localImageInputValidateShape(
    taskMode: LocalImageTaskMode,
    inputPresent: Boolean,
    maskPresent: Boolean,
    controlPresent: Boolean,
    strength: Double?,
    controlStrength: Double?,
    allowMissingRequiredInputs: Boolean = false
) {
    strength?.let { value ->
        require(value.isFinite() && value >= 0.0 && value <= 1.0) {
            "strength must be finite and in the interval [0, 1]."
        }
    }
    controlStrength?.let { value ->
        require(value.isFinite() && value >= 0.0 && value <= 2.0) {
            "controlStrength must be finite and in the interval [0, 2]."
        }
    }
    when (taskMode) {
        LocalImageTaskMode.TEXT_TO_IMAGE -> {
            require(!inputPresent && !maskPresent && !controlPresent) {
                "text_to_image does not accept input, mask, or control images."
            }
            require(strength == null && controlStrength == null) {
                "text_to_image does not accept strength controls."
            }
        }
        LocalImageTaskMode.IMG2IMG -> {
            require(inputPresent || allowMissingRequiredInputs) { "img2img requires inputImage." }
            require(!maskPresent && !controlPresent) { "img2img accepts only inputImage." }
            require(controlStrength == null) { "img2img does not accept controlStrength." }
        }
        LocalImageTaskMode.INPAINT -> {
            require(inputPresent && maskPresent || allowMissingRequiredInputs) {
                "inpaint requires inputImage and maskImage."
            }
            require(!controlPresent) { "inpaint does not accept controlImage." }
            require(controlStrength == null) { "inpaint does not accept controlStrength." }
        }
        LocalImageTaskMode.CONTROL -> {
            require(controlPresent || allowMissingRequiredInputs) { "control requires controlImage." }
            require(!inputPresent && !maskPresent) { "control accepts only controlImage." }
            require(strength == null) { "control does not accept img2img strength." }
        }
        LocalImageTaskMode.EDIT -> {
            require(inputPresent || allowMissingRequiredInputs) { "edit requires inputImage." }
            require(!maskPresent && !controlPresent) { "edit accepts only inputImage." }
            require(controlStrength == null) { "edit does not accept controlStrength." }
        }
    }
}
