package com.muyuchat.core.download

internal fun gemmaTextOnlyMnnComponents(): List<MnnModelBundleComponentSpec> = listOf(
    MnnModelBundleComponentSpec(MnnModelBundleComponentRole.CONFIG, "config.json"),
    MnnModelBundleComponentSpec(MnnModelBundleComponentRole.LLM_CONFIG, "llm_config.json"),
    MnnModelBundleComponentSpec(MnnModelBundleComponentRole.MODEL, "llm.mnn"),
    MnnModelBundleComponentSpec(MnnModelBundleComponentRole.WEIGHT, "llm.mnn.weight"),
    MnnModelBundleComponentSpec(MnnModelBundleComponentRole.OPTIONAL, "llm.mnn.json"),
    MnnModelBundleComponentSpec(MnnModelBundleComponentRole.TOKENIZER, "tokenizer.mtok"),
    MnnModelBundleComponentSpec(MnnModelBundleComponentRole.WEIGHT, "ple_embeddings_int4.bin")
)

internal fun qwen35CommunityMnnComponents(
    tokenizerFileName: String,
    requiresEmbeddingFile: Boolean = false,
    requiresVision: Boolean = false
): List<MnnModelBundleComponentSpec> = buildList {
    add(MnnModelBundleComponentSpec(MnnModelBundleComponentRole.CONFIG, "config.json"))
    add(MnnModelBundleComponentSpec(MnnModelBundleComponentRole.LLM_CONFIG, "llm_config.json"))
    add(MnnModelBundleComponentSpec(MnnModelBundleComponentRole.MODEL, "llm.mnn"))
    add(MnnModelBundleComponentSpec(MnnModelBundleComponentRole.WEIGHT, "llm.mnn.weight"))
    add(MnnModelBundleComponentSpec(MnnModelBundleComponentRole.TOKENIZER, tokenizerFileName))
    add(MnnModelBundleComponentSpec(MnnModelBundleComponentRole.OPTIONAL, "llm.mnn.json"))
    if (requiresEmbeddingFile) {
        add(MnnModelBundleComponentSpec(MnnModelBundleComponentRole.WEIGHT, "embeddings_bf16.bin"))
    }
    if (requiresVision) {
        add(MnnModelBundleComponentSpec(MnnModelBundleComponentRole.MODEL, "visual.mnn"))
        add(MnnModelBundleComponentSpec(MnnModelBundleComponentRole.WEIGHT, "visual.mnn.weight"))
    }
}

/**
 * Community GGUF releases keep the multimodal projector separate from
 * the quantized language model. The installer must therefore always
 * materialize both files as one managed bundle rather than register a
 * text-only main model and leave the card's vision claim dangling.
 */
internal fun communityLowRefusalGgufVisionBundle(
    id: String,
    title: String,
    repoId: String,
    revision: String,
    mainFileName: String,
    projectorFileName: String,
    smokeImageSize: Int = 448,
    smokeTimeoutSeconds: Int = 180
): VisionModelBundleSpec = VisionModelBundleSpec(
    id = id,
    title = title,
    runtime = VisionModelBundleRuntime.GGUF_MMPROJ,
    accelerator = VisionModelAccelerator.CPU,
    minDeviceTier = ImageEngineMinDeviceTier.ANY,
    requiresQnnRuntime = false,
    requiresSmokeTest = true,
    downloadProjectorByDefault = true,
    smokeSpec = VisionModelSmokeSpec(
        imageWidth = smokeImageSize,
        imageHeight = smokeImageSize,
        prompt = "请用中文描述这张图片",
        timeoutSeconds = smokeTimeoutSeconds
    ),
    components = listOf(
        VisionModelBundleComponentSpec(
            role = VisionModelBundleComponentRole.MAIN_MODEL,
            repoId = repoId,
            revision = revision,
            provider = ModelRepositoryProvider.HUGGING_FACE,
            fileName = mainFileName
        ),
        VisionModelBundleComponentSpec(
            role = VisionModelBundleComponentRole.PROJECTOR,
            repoId = repoId,
            revision = revision,
            provider = ModelRepositoryProvider.HUGGING_FACE,
            fileName = projectorFileName
        )
    )
)

internal fun sanaEditV2MnnComponents(): List<ImageEngineBundleComponentSpec> {
    val repoId = "MNN/MNN-Sana-Edit-V2"
    val revision = SANA_EDIT_V2_REVISION

    fun component(
        role: ImageEngineBundleComponentRole,
        fileName: String,
        expectedSizeBytes: Long,
        sha256: String
    ) = ImageEngineBundleComponentSpec(
        role = role,
        repoId = repoId,
        revision = revision,
        provider = ModelRepositoryProvider.MODELSCOPE,
        fileName = fileName,
        expectedSizeBytes = expectedSizeBytes,
        sha256 = sha256,
        relativePath = fileName
    )

    return listOf(
        component(ImageEngineBundleComponentRole.CONFIG, "config.json", 810L, "9471a0ffd2ac3afb78d70ec8b9d4fdc4696fcdd905ba2a25f1806c3529bf00f2"),
        component(ImageEngineBundleComponentRole.CONFIG, "llm/config.json", 210L, "c4bd25dbbc950feffccc3b154d634fdfbce96fbed453dd738bda4abfc763b73a"),
        component(ImageEngineBundleComponentRole.CONFIG, "llm/llm_config.json", 4_638L, "2e45095efda4d17853d8b565f7f354210d3f14f97ac24b24a87a5ab771f5980a"),
        component(ImageEngineBundleComponentRole.TEXT_ENCODER, "llm/llm.mnn", 504_504L, "a3e32dc50e8988e78d416031023345048f4b6cf152db021da6ee1de921d45096"),
        component(ImageEngineBundleComponentRole.TEXT_ENCODER, "llm/llm.mnn.weight", 373_018_866L, "79db6ac8267ec6a7c9172a363112fa613c0cf17d6f46121d947a75c987ccf49a"),
        component(ImageEngineBundleComponentRole.TOKENIZER, "llm/tokenizer.txt", 3_193_562L, "80e75c6cbf70c75fdd51ac1cd53505ac127dcb0e21ebe4d19751fa772e2868bd"),
        component(ImageEngineBundleComponentRole.CONDITIONING, "llm/meta_queries.mnn", 1_048_824L, "5e80d4e591af78cca31b6e4cf4ee4ead410e9d2f64ee34c73cf5b633def16e0c"),
        component(ImageEngineBundleComponentRole.CONDITIONING, "connector.mnn", 99_096L, "d72239e1c2626cfb8f349b9d6b8c9d85f1cd9def0063d3ea23695aa6b2ef48fd"),
        component(ImageEngineBundleComponentRole.CONDITIONING, "connector.mnn.weight", 76_268_760L, "7128351d2de561932741f7f874b116ea3b4e5979296d8adf41472a93fcb889cd"),
        component(ImageEngineBundleComponentRole.CONDITIONING, "projector.mnn", 2_416L, "6236a92633bab8ee33416b2f84ec41b934885be652aea7f0412a057de817d4c0"),
        component(ImageEngineBundleComponentRole.CONDITIONING, "projector.mnn.weight", 2_387_206L, "34b5afdb0c3b1fc815cdee7f3ed293e8d8f1f377328e5785cad2bd1768a843c3"),
        component(ImageEngineBundleComponentRole.DIFFUSION, "transformer.mnn", 1_454_264L, "092dd75e8b8c12694ffe43476addcbde07fe7227774a1a40b19420f87b217386"),
        component(ImageEngineBundleComponentRole.DIFFUSION, "transformer.mnn.weight", 884_435_680L, "b3bab45fbabc8dabd05840b52ea3cd9bd3e54dd990e153ff6fbecd8b6c17f331"),
        component(ImageEngineBundleComponentRole.VAE, "vae_decoder.mnn", 751_784L, "9fbe51979b27339b7685cf88f1010a0ff3ab7ff1a7d873fba321eea94b762911"),
        component(ImageEngineBundleComponentRole.VAE, "vae_decoder.mnn.weight", 162_011_594L, "a6ef7a13ba9af29754adf9b97651cb29a7eaee20b716c16dbe079f500d5eddae"),
        component(ImageEngineBundleComponentRole.VAE_ENCODER, "vae_encoder.mnn", 761_568L, "06da21081f8ee98792bd1838990068e7284351157cafbfa8793282b611eacb24"),
        component(ImageEngineBundleComponentRole.VAE_ENCODER, "vae_encoder.mnn.weight", 155_787_522L, "b44ac00f4683697add9578ef4c0f561fb5753fe24a3f4525e7f492028409d05e")
    )
}

internal fun stableDiffusion15MnnComponents(): List<ImageEngineBundleComponentSpec> {
    val repoId = "MNN/stable-diffusion-v1-5-mnn-opencl"
    val revision = SD15_MNN_REVISION

    fun component(
        role: ImageEngineBundleComponentRole,
        fileName: String,
        expectedSizeBytes: Long,
        sha256: String,
        required: Boolean = true
    ) = ImageEngineBundleComponentSpec(
        role = role,
        repoId = repoId,
        revision = revision,
        provider = ModelRepositoryProvider.MODELSCOPE,
        fileName = fileName,
        required = required,
        expectedSizeBytes = expectedSizeBytes,
        sha256 = sha256
    )

    return listOf(
        component(ImageEngineBundleComponentRole.TEXT_ENCODER, "text_encoder.mnn", 249_944L, "5713fa5c83aa446b5b9c28a48a90c647a5bababc5ee5f254cf72e7f479551036"),
        component(ImageEngineBundleComponentRole.TEXT_ENCODER, "text_encoder.mnn.weight", 238_120_368L, "c245ac80dd8a72279976414435801d579a8dcf83aba84e121c4e4a0b74bbed3d"),
        component(ImageEngineBundleComponentRole.DIFFUSION, "unet.mnn", 1_248_536L, "0aaad66712a3f86ef7891392517a5d7471327574a6f4c6defcefc10ce5e06fee"),
        component(ImageEngineBundleComponentRole.DIFFUSION, "unet.mnn.weight", 863_262_988L, "67049e0d6ce8cb34ab1cd78e58909427db1b57f9fd048e16f2f9c86c39f7479b"),
        component(ImageEngineBundleComponentRole.VAE, "vae_decoder.mnn", 128_248L, "fa6f34c9e77fb715f57d27d65c930455c2ba11b42f2b956da8dcadb2b4bf14b2"),
        component(ImageEngineBundleComponentRole.VAE, "vae_decoder.mnn.weight", 49_639_112L, "20db884599922383eb168fd2fd018892a7741bb45f3ad7073d4cfaf7d75f2241"),
        component(ImageEngineBundleComponentRole.TOKENIZER, "vocab.json", 1_059_962L, "e089ad92ba36837a0d31433e555c8f45fe601ab5c221d4f607ded32d9f7a4349"),
        component(ImageEngineBundleComponentRole.TOKENIZER, "merges.txt", 524_619L, "9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a"),
        ImageEngineBundleComponentSpec(
            role = ImageEngineBundleComponentRole.TOKENIZER,
            repoId = "openai/clip-vit-large-patch14",
            revision = "32bd64288804d66eefd0ccbe215aa642df71cc41",
            provider = ModelRepositoryProvider.HUGGING_FACE,
            fileName = "tokenizer.json",
            expectedSizeBytes = 2_224_003L,
            sha256 = "a83e0809aa4c3af7208b2df632a7a69668c6d48775b3c3fe4e1b1199d1f8b8f4",
            relativePath = "tokenizer.json"
        ),
        component(ImageEngineBundleComponentRole.OPTIONAL, "configuration.json", 54L, "70da9376517abe492bc35499d5455724da1cbc454126a1dbfad8c0542deace48", required = false),
        component(ImageEngineBundleComponentRole.OPTIONAL, "alphas.txt", 6_999L, "18fe126a911bad616346202b0542adacf59881edcb2edc4a74c5a571ed297f51", required = false)
    )
}

/**
 * Exact text-to-image closure from the immutable Hugging Face MNN export.
 * Image-edit-only graphs are catalogued for future use, but excluded from the default
 * download so a text-to-image install does not fetch another ~0.5 GB of unused weights.
 */
internal fun qwenImage21MnnComponents(): List<ImageEngineBundleComponentSpec> {
    fun component(
        role: ImageEngineBundleComponentRole,
        fileName: String,
        size: Long,
        sha256: String,
        required: Boolean = true,
        downloadByDefault: Boolean = true
    ) = ImageEngineBundleComponentSpec(
        role = role,
        repoId = QWEN_IMAGE_21_MNN_REPO,
        fileName = fileName,
        revision = QWEN_IMAGE_21_MNN_REVISION,
        provider = ModelRepositoryProvider.HUGGING_FACE,
        required = required,
        expectedSizeBytes = size,
        sha256 = sha256,
        relativePath = fileName,
        downloadByDefault = downloadByDefault
    )

    return listOf(
        // Keep the recommended main diffusion graph first; the recommendation validator
        // binds dit.mnn to recommendedFileName.
        component(
            ImageEngineBundleComponentRole.DIFFUSION,
            "dit.mnn",
            756_880L,
            "06afa72d2e180a369de30dc534eafbfa6796bc505bdade22d9c89ff0fe68d721"
        ),
        component(
            ImageEngineBundleComponentRole.DIFFUSION,
            "dit.mnn.weight",
            4_472_625_758L,
            "9e74678793b4b8d30bd82e5bc01ce59dc498748ec273a7f95ad418d1c8114c6a"
        ),
        component(
            ImageEngineBundleComponentRole.TEXT_ENCODER,
            "text_encoder/llm.mnn",
            591_728L,
            "61f0fe3b5d0447518ae5b26ab19c0b8a7f07c51f598be5f52fa0aee7405972e3"
        ),
        component(
            ImageEngineBundleComponentRole.TEXT_ENCODER,
            "text_encoder/llm.mnn.weight",
            4_732_532_162L,
            "a52971cb29c0bef35ab336b370db00d676223e6d721b99e3bf0b9c70f2d9bcfe"
        ),
        component(
            ImageEngineBundleComponentRole.TEXT_ENCODER,
            "text_encoder/embeddings_int4.bin",
            388_956_160L,
            "311cd44e48dcec950bcc5cffb465ae0e2fec8154247f2ff545b7fd1240960bf4"
        ),
        component(
            ImageEngineBundleComponentRole.TOKENIZER,
            "text_encoder/tokenizer.txt",
            3_193_555L,
            "7119de4966cc6a8ae87d7f083e65b315282d06c3122fdd41ce783fdd2d3c1ca2"
        ),
        component(
            ImageEngineBundleComponentRole.CONFIG,
            "text_encoder/te_config.json",
            253L,
            "d18d300491cde6e9c9cbb706297eb2cc8909f61838e2ff8f1ce5d8db9ee632f3"
        ),
        component(
            ImageEngineBundleComponentRole.CONFIG,
            "text_encoder/te_llm_config.json",
            6_426L,
            "6b80aeee77008118f39a7772ca3b2f28e168cef9b0124d95854285ea6890f00b"
        ),
        component(
            ImageEngineBundleComponentRole.CONFIG,
            "text_encoder/llm_config.json",
            6_436L,
            "ce8f3a6532832d37eff85cf45ddb24b4d21567d293cfc8c64a67fa0fdca93df9"
        ),
        component(
            ImageEngineBundleComponentRole.CONDITIONING,
            "txt_in.mnn",
            3_944L,
            "415d32f5757b3e6cb961827731f9af5e551ad2538881626f24f2530329f3d958"
        ),
        component(
            ImageEngineBundleComponentRole.CONDITIONING,
            "txt_in.mnn.weight",
            35_684_876L,
            "9a531bbca04c40f5d6628d033d932faaa7016fa1203f0c3b8f8052cd5b76c2f2"
        ),
        component(
            ImageEngineBundleComponentRole.CONDITIONING,
            "img_in.mnn",
            1_144L,
            "e6c9689a573ab9cde743ff3a6abe44af6dba521a43e429320e980c45cda5d4de"
        ),
        component(
            ImageEngineBundleComponentRole.CONDITIONING,
            "img_in.mnn.weight",
            524_288L,
            "84ceb8c9ab500f646909606af4ff2a0e8d8deb2f14455f49b2f84e2e50201492"
        ),
        component(
            ImageEngineBundleComponentRole.VAE,
            "vae_decoder.mnn",
            506_595_396L,
            "2525485bba44d4d8176d05522fa7e8b4b1080c9e7b7df18dfde7d58e667ff055"
        ),
        // These files are only used by image editing, so they must not be required or
        // included when a user downloads the text-to-image model.
        component(
            ImageEngineBundleComponentRole.VAE_ENCODER,
            "vae_encoder.mnn",
            156_215_404L,
            "a131ea3a9c822a62842c17ddf0526685d5d8cd842ed13d5500112121df770248",
            required = false,
            downloadByDefault = false
        ),
        component(
            ImageEngineBundleComponentRole.TEXT_ENCODER,
            "text_encoder/visual.mnn",
            562_048L,
            "8f1653348a94c29e56d47529dd461215c80d5380263136adee7945809f407bf7",
            required = false,
            downloadByDefault = false
        ),
        component(
            ImageEngineBundleComponentRole.TEXT_ENCODER,
            "text_encoder/visual.mnn.weight",
            326_728_744L,
            "6d8b1a4886cca9ab8b8c86979f4e5291e126ac930124e9a2361833e7b11301e6",
            required = false,
            downloadByDefault = false
        ),
        component(
            ImageEngineBundleComponentRole.CONFIG,
            "text_encoder/te_vl_config.json",
            400L,
            "87e37fe0849b4f6b7865fcc986057dd848eae53ee4fb78963f69e03241475eac",
            required = false,
            downloadByDefault = false
        ),
        component(
            ImageEngineBundleComponentRole.CONFIG,
            "text_encoder/te_vl_llm_config.json",
            6_386L,
            "cedce3e0dfab7639df57935aa056953a8e033b697c5a108b14cfea4276ee7b55",
            required = false,
            downloadByDefault = false
        )
    )
}
