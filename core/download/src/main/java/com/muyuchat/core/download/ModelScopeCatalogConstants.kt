package com.muyuchat.core.download

/**
 * The ModelScope catalog data + execution-profile builders (P6/M4), moved verbatim from the private
 * companion object of ModelScopeClient: recommended-model tables, revision pins, endpoint defaults,
 * QAIRT asset tables and the pure bundle/profile spec builders. Declarations widened from
 * companion-private to internal top-level (documented deliberate widening for pure data/builders;
 * no state). ModelScopeClient resolves every name unqualified from this same package.
 */

internal const val QNN_SD15_EXECUTION_PROFILE_REVISION = 5
internal const val QNN_DREAMSHAPER_SD15_EXECUTION_PROFILE_REVISION = 6
internal const val QNN_REALISTICVISIONHYPER_SD15_EXECUTION_PROFILE_REVISION = 6
internal const val QNN_SDXL_EXECUTION_PROFILE_REVISION = 7
internal const val QNN_GEN5_EXECUTION_PROFILE_REVISION = 3
internal val QNN_SD15_CONDITIONING_RUNTIME_ASSETS = listOf(
    "tokenizer.json",
    "token_emb.bin",
    "pos_emb.bin"
)
internal val QNN_SDXL_CONDITIONING_RUNTIME_ASSETS = listOf(
    "clip_2.mnn",
    "clip_2.mnn.weight",
    "tokenizer.json",
    "token_emb.bin",
    "token_emb_2.bin",
    "pos_emb.bin",
    "pos_emb_2.bin"
)
internal val QAIRT_IMAGE_RELEASE_ASSET_MODEL_IDS = setOf(
    "qualcomm_sd15_gen5_qnn",
    "qualcomm_sd21_gen5_qnn",
    "qualcomm_controlnet_canny_gen5_qnn"
)
internal const val QAIRT_GEN5_IMAGE_RELEASE_ASSET_CHIPSET =
    "qualcomm-snapdragon-8-elite-gen5-for-galaxy"
internal val QAIRT_IMAGE_RELEASE_ASSET_SIZE_BYTES = mapOf(
    "qualcomm_sd15_gen5_qnn" to 711_934_104L,
    "qualcomm_sd21_gen5_qnn" to 874_955_354L,
    "qualcomm_controlnet_canny_gen5_qnn" to 950_517_794L
)
// SHA-256 of the exact pinned S3 release objects named by each immutable
// release_assets.json revision. These are download/install contracts,
// not device admission rules.
internal val QAIRT_IMAGE_RELEASE_ASSET_SHA256 = mapOf(
    "qualcomm_sd15_gen5_qnn" to
        "3716ba4c32d6dcf1af93857d22889e1e95f9c3e4c62983fff2d2a743eeff644e",
    "qualcomm_sd21_gen5_qnn" to
        "3fc5fc8df77e4952776020d932ee5934ec432b397a456515ae7f8ed2af004ae8",
    "qualcomm_controlnet_canny_gen5_qnn" to
        "582b4dee61584cdd2e0f96bdbaff19a6bf919af365c4a7f258ed260be5e1262d"
)
internal val MODEL_FILE_EXTENSIONS = setOf(
    "gguf",
    "safetensors",
    "sft",
    "ckpt",
    "pth",
    "pt",
    "onnx",
    "mnn",
    "zip",
    "task",
    "tflite",
    "litertlm",
    "bin",
    "ctx",
    "qnn",
    "json"
)
internal val MNN_MODEL_FILE_EXTENSIONS = setOf("json", "mnn", "weight", "txt", "bin", "mtok")

internal val DEFAULT_ENDPOINTS = listOf(
    "https://www.modelscope.cn",
    "https://modelscope.cn",
    "https://www.modelscope.ai",
    "https://modelscope.ai"
)

internal val DEFAULT_HUGGING_FACE_ENDPOINTS = listOf(
    "https://hf-mirror.com",
    "https://huggingface.co"
)

internal const val SANA_EDIT_V2_REVISION = "50adc28b4682161542f893c624048adf6dd027ca"
internal const val SD15_MNN_REVISION = "346de5fcde406781a34368140419ac3f62440916"

// Keep every recommended MNN package on one immutable repository revision. The
// installer still validates the per-file SHA-256 returned by the source, but a
// pinned tree prevents config/tokenizer/model files from drifting independently
// between a file-list request and a later repair-install.
internal const val QWEN35_2B_MNN_REVISION = "b9ae8c8f3da3fceb4278b558a747286b8a087dbe"
internal const val QWEN35_08B_UNCENSORED_MNN_REVISION = "7ad2802ed360a3066112bd973506a6bb3820df2c"
internal const val QWEN35_08B_MNN36_REVISION = "47f681e8e87d8b2f57ea697a9fe78136d1ec0951"
internal const val QWEN35_4B_UNCENSORED_MNN_REVISION = "cfa553d6b42bd9ed86f17e02da3b78e7093736ed"
internal const val QWEN35_9B_UNCENSORED_MNN_REVISION = "9eadc756519bf42e6b8aed8a6ade5b124c79e564"
internal const val QWEN35_2B_ABLITERATED_GGUF_REVISION = "f36848fead3fdda244cf60195c46993d23183d4c"
internal const val GEMMA4_26B_A4B_ABLITERATED_GGUF_REVISION = "5e35628c3cd1f39fdde4d7a16ee6653d42df0e95"
internal const val GEMMA4_E2B_UNCENSORED_GGUF_REVISION = "4345c0c77cde7da43084c94b1deac23c09bccfc1"
internal const val GEMMA4_E4B_UNCENSORED_GGUF_REVISION = "771f130d4c49735ace331f68a80f7ae31387e51c"
internal const val QWEN35_35B_A3B_MNN_REVISION = "5e21a599fd2e01d2f1f6ccedac48912439ba22f5"
internal const val GEMMA4_E2B_MNN_REVISION = "ad38122704d7a0cfd207abb75a815a2436ab92e6"
internal const val GEMMA4_E4B_MNN_REVISION = "69a938a0f52bedcffc7e42215932f03de15bfe86"
internal const val GEMMA4_26B_A4B_MNN_REVISION = "2dcaf1402d04cf22c738b937cbab2b8147afc2a0"

internal const val QWEN_IMAGE_21_MNN_REPO = "evankuo/Qwen-Image-2.1-MNN"
internal const val QWEN_IMAGE_21_MNN_REVISION = "ed6891ea7e1e855246f74e250c2c849defa009da"
