package com.muyuchat.mca

import android.os.Process
import java.io.File
import org.json.JSONObject

internal fun SdxlTwoPhaseCoordinator.prepareLease(
    requestId: String,
    params: JSONObject,
    embeddingsFile: File,
    latentFile: File,
    metadataFile: File,
    inputTensorFile: File,
    encoderLatentFile: File,
    encoderMetadataFile: File,
    outputFile: File,
    encoderJournal: File,
    unetJournal: File,
    vaeJournal: File,
    maskTensorFile: File? = null,
    fullMaskTensorFile: File? = null
): SdxlTwoPhaseRequestLease {
    requireSplitSdxlPreviewDisabled(params, "lease admission")
    return SdxlTwoPhaseRequestLease.acquire(
        requestId = requestId,
        params = params,
        workerPid = Process.myPid(),
        coordinationRoot = coordinationRoot,
        embeddingsFile = embeddingsFile,
        latentFile = latentFile,
        metadataFile = metadataFile,
        inputTensorFile = inputTensorFile,
        encoderLatentFile = encoderLatentFile,
        encoderMetadataFile = encoderMetadataFile,
        outputFile = outputFile,
        encoderJournal = encoderJournal,
        unetJournal = unetJournal,
        vaeJournal = vaeJournal,
        maskTensorFile = maskTensorFile,
        fullMaskTensorFile = fullMaskTensorFile
    )
}
