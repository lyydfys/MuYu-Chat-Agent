package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal fun requireSdxlPhaseMergeArtifacts(
    taskMode: LocalImageTaskMode,
    preparedInpaint: QnnPreparedInpaintInput?,
    contract: SdxlImageExecutionContract,
    unetNative: JSONObject,
    vaeNative: JSONObject,
    encoderNative: JSONObject?,
    metadata: SdxlLatentMetadata,
    unetResult: SdxlImagePhaseResult,
    vaeResult: SdxlImagePhaseResult,
    outputFile: File
) {
    if (taskMode == LocalImageTaskMode.INPAINT) {
        val prepared = requireNotNull(preparedInpaint)
        val params = contract.paramsObject()
        val unetEffectiveJson = unetNative.getJSONObject("nativeEffective")
        require(encoderNative?.getString("taskMode") == taskMode.wireName &&
            unetEffectiveJson.getString("taskMode") == taskMode.wireName &&
            vaeNative.getString("taskMode") == taskMode.wireName
        ) { "Split-SDXL inpaint task identity changed across isolated phases." }
        require(unetEffectiveJson.getString("inpaintTopology") ==
            QnnInpaintMaskTopology.LATENT_BLEND_4.wireName &&
            File(unetEffectiveJson.getString("maskImageTensorPath")).canonicalFile ==
                File(prepared.maskTensorPath).canonicalFile &&
            unetEffectiveJson.getString("maskImageTensorSha256").lowercase() ==
                prepared.maskTensorSha256 &&
            unetEffectiveJson.getLong("maskImageTensorBytes") == prepared.maskTensorBytes &&
            unetEffectiveJson.getInt("inpaintPreserveStepCount") ==
                contract.expectedTimetableCount &&
            unetEffectiveJson.getInt("inpaintLatentBlendCount") ==
                contract.expectedTimetableCount &&
            unetEffectiveJson.getInt("inpaintSourceNoiseUseCount") ==
                contract.expectedTimetableCount &&
            unetEffectiveJson.getString("inpaintSourceNoiseSha256")
                .matches(Regex("^[a-f0-9]{64}$")) &&
            unetEffectiveJson.getString("inpaintPreservedLatentChecksum")
                .matches(Regex("^(?!0{16}$)[a-f0-9]{16}$"))
        ) { "Split-SDXL UNet inpaint preservation evidence is incomplete." }
        require(File(vaeNative.getString("inputImageTensorPath")).canonicalFile ==
            File(prepared.source.tensorPath).canonicalFile &&
            vaeNative.getString("inputImageTensorSha256").lowercase() ==
                prepared.source.tensorSha256 &&
            File(vaeNative.getString("maskImageFullTensorPath")).canonicalFile ==
                File(prepared.fullMaskTensorPath).canonicalFile &&
            vaeNative.getString("maskImageFullTensorSha256").lowercase() ==
                prepared.fullMaskTensorSha256 &&
            vaeNative.getLong("maskImageFullTensorBytes") == prepared.fullMaskTensorBytes &&
            vaeNative.getJSONArray("maskImageFullTensorShape").toString() ==
                JSONArray(prepared.fullMaskTensorShape).toString() &&
            vaeNative.getString("inpaintFinalMode") ==
                "per_step_source_latent_blend_then_final_vae_laplacian_pixel_blend" &&
            vaeNative.getBoolean("inpaintPixelBlendApplied") &&
            vaeNative.getInt("inpaintPixelBlendLevels") ==
                qnnInpaintLaplacianLevelCount(prepared.targetWidth, prepared.targetHeight) &&
            vaeNative.getString("inpaintPixelBlendChecksum")
                .matches(Regex("^(?!0{16}$)[a-f0-9]{16}$")) &&
            params.getString("maskImageTensorSha256").lowercase() == prepared.maskTensorSha256 &&
            params.getString("maskImageFullTensorSha256").lowercase() ==
                prepared.fullMaskTensorSha256
        ) { "Split-SDXL VAE inpaint pixel-blend evidence is incomplete." }
    }
    require(metadata.unetNativeGenerationSequence == unetResult.nativeGenerationSequence) {
        "Committed latent sequence mismatch."
    }
    require(File(unetResult.artifactPath).canonicalFile == File(metadata.latentPath).canonicalFile) {
        "UNet artifact path does not match committed latent metadata."
    }
    require(outputFile.isFile && outputFile.length() > 0L) { "SDXL output is missing." }
    require(File(vaeResult.artifactPath).canonicalFile == outputFile.canonicalFile) {
        "VAE protocol artifact path mismatch."
    }

}

