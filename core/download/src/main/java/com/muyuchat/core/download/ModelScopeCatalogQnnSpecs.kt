package com.muyuchat.core.download

internal fun qnnZipComponent(
    repoId: String,
    fileName: String,
    revision: String = "main",
    expectedSizeBytes: Long? = null,
    sha256: String? = null
): List<ImageEngineBundleComponentSpec> = listOf(
    ImageEngineBundleComponentSpec(
        role = ImageEngineBundleComponentRole.DIFFUSION,
        repoId = repoId,
        revision = revision,
        provider = ModelRepositoryProvider.HUGGING_FACE,
        fileName = fileName,
        expectedSizeBytes = expectedSizeBytes,
        sha256 = sha256
    )
)

internal data class QnnContextIdentity(
    val sizeBytes: Long,
    val sha256: String
)

internal val QNN_SD15_VAE_ENCODER_IDENTITIES = mapOf(
    "cyberrealistic_sd15_qnn228" to QnnContextIdentity(
        sizeBytes = 58_870_768L,
        sha256 = "f2a5d073d0c4492361eb49005f03acd6ecdceba652c6fc7ba68eddd2b4d98da7"
    ),
    "realisticvisionhyper_sd15_qnn228" to QnnContextIdentity(
        sizeBytes = 58_862_576L,
        sha256 = "629797a9eb5204a2465fa993e9efa2546c60dce93d9bcd009ba7b06fc62ecf3b"
    ),
    "dreamshaper_sd15_qnn228" to QnnContextIdentity(
        sizeBytes = 58_862_576L,
        sha256 = "6baf4c28749e310404c1b079230cd47296d389fb4037f05267e589a50294bc66"
    ),
    "meinamix_sd15_qnn228" to QnnContextIdentity(
        sizeBytes = 41_438_176L,
        sha256 = "b32367e717c331cbacce7dc3482c7e5668dea90c8cc77396dee3761845d2bdd6"
    )
)

internal fun sd15QnnSmokeSpecs(
    recommendationId: String
): List<ImageEngineQnnSmokeSpec> {
    val encoderIdentity = requireNotNull(
        QNN_SD15_VAE_ENCODER_IDENTITIES[recommendationId]
    ) { "Missing pinned SD1.5 VAE encoder identity for $recommendationId." }
    return listOf(
        ImageEngineQnnSmokeSpec(
            graphName = "model",
            contextBinary = "unet.bin",
            width = 512,
            height = 512,
            steps = 1,
            timeoutSeconds = 180,
            prompt = "a small ceramic cup on a bright wooden desk",
            inputs = listOf(
                ImageEngineQnnSmokeTensorSpec("sample", "uint16", listOf(1, 4, 64, 64)),
                ImageEngineQnnSmokeTensorSpec("timestamp", "int32", listOf(1)),
                ImageEngineQnnSmokeTensorSpec("text_embedding", "uint16", listOf(1, 77, 768))
            ),
            outputs = listOf(
                ImageEngineQnnSmokeTensorSpec("output", "uint16", listOf(1, 4, 64, 64), role = "output")
            )
        ),
        ImageEngineQnnSmokeSpec(
            graphName = "model",
            contextBinary = "vae_decoder.bin",
            width = 512,
            height = 512,
            steps = 1,
            timeoutSeconds = 180,
            prompt = "vae decoder smoke",
            inputs = listOf(
                ImageEngineQnnSmokeTensorSpec("input", "uint16", listOf(1, 4, 64, 64))
            ),
            outputs = listOf(
                ImageEngineQnnSmokeTensorSpec("output", "uint16", listOf(1, 3, 512, 512), role = "output")
            )
        ),
        ImageEngineQnnSmokeSpec(
            graphName = "model",
            contextBinary = "vae_encoder.bin",
            expectedContextSizeBytes = encoderIdentity.sizeBytes,
            expectedContextSha256 = encoderIdentity.sha256,
            width = 512,
            height = 512,
            steps = 1,
            timeoutSeconds = 180,
            prompt = "vae encoder smoke",
            inputs = listOf(
                ImageEngineQnnSmokeTensorSpec("input", "uint16", listOf(1, 3, 512, 512))
            ),
            outputs = listOf(
                ImageEngineQnnSmokeTensorSpec("mean", "uint16", listOf(1, 4, 64, 64), role = "output"),
                ImageEngineQnnSmokeTensorSpec("std", "uint16", listOf(1, 4, 64, 64), role = "output")
            )
        )
    )
}

internal fun sdxlQnnSmokeSpecs(): List<ImageEngineQnnSmokeSpec> = listOf(
    ImageEngineQnnSmokeSpec(
        graphName = "model",
        contextBinary = "unet.bin",
        width = 1024,
        height = 1024,
        steps = 1,
        timeoutSeconds = 300,
        prompt = "a clean product photo of a ceramic cup on a wooden desk",
        inputs = listOf(
            ImageEngineQnnSmokeTensorSpec("sample", "float32", listOf(1, 4, 128, 128)),
            ImageEngineQnnSmokeTensorSpec("encoder_hidden_states", "float32", listOf(1, 77, 2048)),
            ImageEngineQnnSmokeTensorSpec("timestamp", "int32", listOf(1)),
            ImageEngineQnnSmokeTensorSpec("time_ids", "float32", listOf(1, 6)),
            ImageEngineQnnSmokeTensorSpec("text_embeds", "float32", listOf(1, 1280))
        ),
        outputs = listOf(
            ImageEngineQnnSmokeTensorSpec("output", "float32", listOf(1, 4, 128, 128), role = "output")
        )
    ),
    ImageEngineQnnSmokeSpec(
        graphName = "model",
        contextBinary = "vae_decoder.bin",
        // Published SDXL packages carry a 512 decoder graph. The
        // production SDXL path decodes a 128x128 latent through
        // overlapping 64x64 tiles and merges the 512x512 outputs.
        width = 512,
        height = 512,
        steps = 1,
        timeoutSeconds = 300,
        prompt = "sdxl vae decoder smoke",
        inputs = listOf(
            ImageEngineQnnSmokeTensorSpec("input", "float32", listOf(1, 4, 64, 64))
        ),
        outputs = listOf(
            ImageEngineQnnSmokeTensorSpec("output", "float32", listOf(1, 3, 512, 512), role = "output")
        )
    ),
    ImageEngineQnnSmokeSpec(
        graphName = "model",
        contextBinary = "vae_encoder.bin",
        width = 1024,
        height = 1024,
        steps = 1,
        timeoutSeconds = 300,
        prompt = "sdxl vae encoder smoke",
        inputs = listOf(
            ImageEngineQnnSmokeTensorSpec("input", "float32", listOf(1, 3, 1024, 1024))
        ),
        outputs = listOf(
            ImageEngineQnnSmokeTensorSpec("mean", "float32", listOf(1, 4, 128, 128), role = "output"),
            ImageEngineQnnSmokeTensorSpec("std", "float32", listOf(1, 4, 128, 128), role = "output")
        )
    )
)

internal fun sd15QnnBundle(
    id: String,
    title: String,
    repoId: String,
    fileName: String,
    revision: String = "main",
    expectedSizeBytes: Long? = null,
    sha256: String? = null,
    completeBundleRuntime: Boolean = true,
    minDeviceTier: ImageEngineMinDeviceTier = ImageEngineMinDeviceTier.SNAPDRAGON_8_GEN1
): ImageEngineBundleSpec {
    val profile = recommendedImageExecutionProfile(id)
    return ImageEngineBundleSpec(
        id = id,
        title = title,
        components = qnnZipComponent(repoId, fileName, revision, expectedSizeBytes, sha256),
        recommendationId = id,
        runtime = ImageEngineBundleRuntime.QNN_HTP,
        accelerator = ImageEngineAccelerator.QNN_HTP,
        minDeviceTier = minDeviceTier,
        requiresQnnRuntime = true,
        requiresSmokeTest = true,
        smokeSpec = ImageEngineSmokeSpec(
            width = profile.defaults.width,
            height = profile.defaults.height,
            steps = profile.defaults.steps,
            timeoutSeconds = 240
        ),
        qnnSmokeSpecs = sd15QnnSmokeSpecs(id),
        requiredRuntimeProfile = ImageEngineQnnRuntimeProfileSpec(
            qnnSdk = "2.28",
            // The `min` context targets the publisher's broadest hardware
            // baseline. A self-contained archive may also carry several
            // physical-device transports; installation resolves the exact
            // local HTP profile instead of treating this fallback as a
            // device-admission rule.
            htpArch = 68,
            completeBundleRuntime = completeBundleRuntime
        ),
        executionProfile = profile
    )
}

internal fun sdxlQnnBundle(
    id: String,
    title: String,
    repoId: String,
    fileName: String,
    revision: String = "main",
    expectedSizeBytes: Long? = null,
    sha256: String? = null
): ImageEngineBundleSpec {
    val recommendationId = id.removeSuffix("_bundle")
    val profile = recommendedImageExecutionProfile(recommendationId)
    return ImageEngineBundleSpec(
        id = id,
        title = title,
        components = qnnZipComponent(repoId, fileName, revision, expectedSizeBytes, sha256),
        recommendationId = recommendationId,
        runtime = ImageEngineBundleRuntime.QNN_HTP,
        accelerator = ImageEngineAccelerator.QNN_HTP,
        minDeviceTier = ImageEngineMinDeviceTier.SNAPDRAGON_8_GEN3,
        requiresQnnRuntime = true,
        requiresSmokeTest = true,
        smokeSpec = ImageEngineSmokeSpec(
            width = profile.defaults.width,
            height = profile.defaults.height,
            steps = profile.defaults.steps,
            timeoutSeconds = 360
        ),
        qnnSmokeSpecs = sdxlQnnSmokeSpecs(),
        // These archives were exported with QNN 2.28 for the SM8650/
        // HTP V75 class. The runtime contract is advisory at install
        // time; a V79 device may still download and try the package,
        // but the card and preflight must call it experimental.
        requiredRuntimeProfile = ImageEngineQnnRuntimeProfileSpec(
            qnnSdk = "2.28",
            htpArch = 75,
            completeBundleRuntime = false
        ),
        executionProfile = profile
    )
}

internal fun gen5QnnBundle(
    id: String,
    title: String,
    repoId: String,
    fileName: String,
    revision: String,
    useSd21Sidecars: Boolean = false,
    task: ImageEngineTask = ImageEngineTask.TEXT_TO_IMAGE
): ImageEngineBundleSpec {
    val recommendationId = id.removeSuffix("_bundle")
    val profile = recommendedImageExecutionProfile(recommendationId)
    return ImageEngineBundleSpec(
        id = id,
        title = title,
        components = qnnZipComponent(
            repoId = repoId,
            fileName = fileName,
            revision = revision,
            expectedSizeBytes = QAIRT_IMAGE_RELEASE_ASSET_SIZE_BYTES[recommendationId]
                ?: error("Missing pinned Gen5 QNN archive size for $recommendationId."),
            sha256 = QAIRT_IMAGE_RELEASE_ASSET_SHA256[recommendationId]
                ?: error("Missing pinned Gen5 QNN archive SHA-256 for $recommendationId.")
        ) + gen5TokenizerSidecars(useSd21Sidecars),
        recommendationId = recommendationId,
        task = task,
        runtime = ImageEngineBundleRuntime.QNN_HTP,
        accelerator = ImageEngineAccelerator.QNN_HTP,
        minDeviceTier = ImageEngineMinDeviceTier.SNAPDRAGON_8_ELITE,
        requiresQnnRuntime = true,
        requiresSmokeTest = true,
        smokeSpec = ImageEngineSmokeSpec(
            width = profile.defaults.width,
            height = profile.defaults.height,
            steps = profile.defaults.steps,
            timeoutSeconds = 300
        ),
        requiredRuntimeProfile = ImageEngineQnnRuntimeProfileSpec(
            qnnSdk = "2.45.0.260326154327",
            htpArch = 81,
            completeBundleRuntime = false
        ),
        executionProfile = profile
    )
}

internal fun gen5TokenizerSidecars(useSd21: Boolean): List<ImageEngineBundleComponentSpec> {
    val repoId = if (useSd21) {
        "sd2-community/stable-diffusion-2-1"
    } else {
        "stable-diffusion-v1-5/stable-diffusion-v1-5"
    }
    val revision = if (useSd21) {
        "bb2154823665391b4fb29b0b9cf82a198964ee05"
    } else {
        "451f4fe16113bff5a5d2269ed5ad43b0592e9a14"
    }
    val metadata = if (useSd21) {
        listOf(
            Triple("scheduler/scheduler_config.json", 345L, "4cd9b9597ca64549df35016ca02bd3450ecbac70ccd8b0465b018be4ba54fe4b"),
            Triple("tokenizer/merges.txt", 524_619L, "9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a"),
            Triple("tokenizer/special_tokens_map.json", 460L, "f118ab3a983206e4f32583448de6bd6aae4ee21869135cef1f5848a753cdaab6"),
            Triple("tokenizer/tokenizer_config.json", 824L, "87a3154f0990fd992fd59f9d42c39520155b3d77cd543efe3f2bf011726f379d"),
            Triple("tokenizer/vocab.json", 1_059_962L, "e089ad92ba36837a0d31433e555c8f45fe601ab5c221d4f607ded32d9f7a4349")
        )
    } else {
        listOf(
            Triple("scheduler/scheduler_config.json", 308L, "699cce92eb7c122e2eb7dfdea78e6187fda76a5ed4a8e42319b85610e620e091"),
            Triple("tokenizer/merges.txt", 524_619L, "9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a"),
            Triple("tokenizer/special_tokens_map.json", 472L, "c4864a9376a8401918425bed71fc14fc0e81f9b59ec45c1cf96cccb2df508eac"),
            Triple("tokenizer/tokenizer_config.json", 806L, "00439066fcba73de57644cf41e4e3b9f2dbb09d7f3fc2005898ba52399045882"),
            Triple("tokenizer/vocab.json", 1_059_962L, "e089ad92ba36837a0d31433e555c8f45fe601ab5c221d4f607ded32d9f7a4349")
        )
    }
    val repositorySidecars = metadata.map { (path, size, sha) ->
        ImageEngineBundleComponentSpec(
            role = if (path.startsWith("tokenizer/")) {
                ImageEngineBundleComponentRole.TOKENIZER
            } else {
                ImageEngineBundleComponentRole.CONFIG
            },
            repoId = repoId,
            revision = revision,
            provider = ModelRepositoryProvider.HUGGING_FACE,
            fileName = path,
            expectedSizeBytes = size,
            sha256 = sha,
            relativePath = path
        )
    }
    // The publisher repositories expose the legacy vocab/merges pair but not a
    // complete tokenizer.json. Pin the canonical CLIP tokenizer contract so
    // Android can execute normalization, pre-tokenization, BPE and post-processing
    // through the standard tokenizer backend instead of a handwritten approximation.
    return repositorySidecars + ImageEngineBundleComponentSpec(
        role = ImageEngineBundleComponentRole.TOKENIZER,
        repoId = "openai/clip-vit-large-patch14",
        revision = "32bd64288804d66eefd0ccbe215aa642df71cc41",
        provider = ModelRepositoryProvider.HUGGING_FACE,
        fileName = "tokenizer.json",
        expectedSizeBytes = 2_224_003L,
        sha256 = "a83e0809aa4c3af7208b2df632a7a69668c6d48775b3c3fe4e1b1199d1f8b8f4",
        relativePath = "tokenizer/tokenizer.json"
    )
}
