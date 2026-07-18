package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageExecutionProfileJsonTest {
    @Test
    fun `manifest without execution profile remains compatible`() {
        assertNull(ImageExecutionProfileJson.parseManifest(JSONObject().put("schema", "legacy")))
    }

    @Test
    fun `manifest execution profile parses every contract`() {
        val manifest = JSONObject().put("executionProfile", validProfileJson())

        val profile = requireNotNull(ImageExecutionProfileJson.parseManifest(manifest))

        assertEquals("package.sd15.qnn", profile.profileId)
        assertEquals(FINGERPRINT, profile.modelFingerprint)
        assertEquals(ImageProfileSource.MANIFEST, profile.provenance.primarySource)
        assertEquals(ImageTokenizerBackend.TOKENIZERS_CPP, profile.tokenizer.backend)
        assertEquals(77, profile.tokenizer.maxLength)
        assertEquals(ImageSchedulerAlgorithm.EULER, profile.scheduler.algorithm)
        assertEquals(ImagePredictionType.EPSILON, profile.scheduler.predictionType)
        assertEquals(0.00085, profile.scheduler.betaStart ?: error("missing betaStart"), 0.0)
        assertEquals(0.012, profile.scheduler.betaEnd ?: error("missing betaEnd"), 0.0)
        assertEquals(ImageFinalSigmaType.SIGMA_MIN, profile.scheduler.finalSigmaType)
        assertTrue(profile.scheduler.clipSample)
        assertEquals(1.5, profile.scheduler.clipSampleRange, 0.0)
        assertTrue(profile.scheduler.thresholding)
        assertEquals(0.25, profile.scheduler.eta, 0.0)
        assertEquals(false, profile.scheduler.lowerOrderFinal)
        assertEquals(ImageVaeScalingLocation.GRAPH_INTERNAL, profile.vae.scalingLocation)
        assertEquals("graphs/unet.bin", profile.graph.unet?.relativePath)
        assertEquals(512, profile.defaults.width)
        assertTrue(profile.bindingFingerprint.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun `manifest behavior gives nested defaults priority while preserving partial values`() {
        val manifest = JSONObject()
            .put("prompt", "root prompt")
            .put("steps", 4)
            .put("width", 256)
            .put(
                "modelConfig",
                JSONObject()
                    .put("prompt", "model prompt")
                    .put("height", 320)
            )
            .put(
                "generation",
                JSONObject()
                    .put("prompt", "generation prompt")
                    .put("steps", 6)
            )
            .put(
                "generationDefaults",
                JSONObject()
                    .put("prompt", "generation defaults prompt")
                    .put("cfg", 2.5)
            )
            .put(
                "defaults",
                JSONObject()
                    .put("prompt", "defaults prompt")
                    .put("width", 640)
            )

        val behavior = requireNotNull(ImageExecutionProfileJson.parseManifestBehavior(manifest))

        assertEquals("defaults prompt", behavior.defaultPrompt)
        assertEquals(6, behavior.steps)
        assertEquals(2.5, behavior.cfgScale ?: error("missing cfgScale"), 0.0)
        assertEquals(640, behavior.width)
        assertEquals(320, behavior.height)
    }

    @Test
    fun `manifest image size parses and nested defaults retain priority`() {
        val imageSizeOnly = requireNotNull(
            ImageExecutionProfileJson.parseManifestBehavior(
                JSONObject().put("imageSize", "768 x 512")
            )
        )
        val manifest = JSONObject()
            .put("imageSize", "768 x 512")
            .put("defaults", JSONObject().put("width", 384).put("height", 384))

        val behavior = requireNotNull(ImageExecutionProfileJson.parseManifestBehavior(manifest))

        assertEquals(768, imageSizeOnly.width)
        assertEquals(512, imageSizeOnly.height)
        assertEquals(384, behavior.width)
        assertEquals(384, behavior.height)
    }

    @Test
    fun `manifest graph path escape is an explicit package error`() {
        val profile = validProfileJson()
        profile.getJSONObject("graph")
            .getJSONObject("unet")
            .put("relativePath", "../unet.bin")

        val error = expectJsonFailure {
            ImageExecutionProfileJson.parseManifest(JSONObject().put("executionProfile", profile))
        }

        assertEquals("PROFILE_PATH_INVALID", error.code)
        assertEquals("graph.unet.relativePath", error.field)
    }

    @Test
    fun `manifest rejects tokenizer and conditioning sequence axis drift`() {
        val profile = validProfileJson()
        profile.getJSONObject("tokenizer").put("maxLength", 256)

        val error = expectJsonFailure {
            ImageExecutionProfileJson.parseManifest(JSONObject().put("executionProfile", profile))
        }

        assertEquals("PROFILE_VALIDATION_FAILED", error.code)
        assertEquals("conditioning.textEncoderInputShape", error.field)
    }

    @Test
    fun `scheduler sidecar supports required algorithms and fields`() {
        val cases = listOf(
            Triple("EulerDiscreteScheduler", null, ImageSchedulerAlgorithm.EULER),
            Triple("DDIMScheduler", null, ImageSchedulerAlgorithm.DDIM),
            Triple("PNDMScheduler", null, ImageSchedulerAlgorithm.PNDM_PLMS),
            Triple("DPMSolverMultistepScheduler", "dpmsolver++", ImageSchedulerAlgorithm.DPMPP_2M)
        )

        cases.forEach { (className, algorithmType, expected) ->
            val json = schedulerJson(className).also { config ->
                algorithmType?.let {
                    config.put("algorithm_type", it)
                    config.put("solver_order", 2)
                }
            }
            val scheduler = ImageExecutionProfileJson.parseSchedulerConfig(json)

            assertEquals(expected, scheduler.algorithm)
            assertEquals(ImagePredictionType.V_PREDICTION, scheduler.predictionType)
            assertEquals(ImageNoiseSchedule.SCALED_LINEAR, scheduler.noiseSchedule)
            assertEquals(0.00085, scheduler.betaStart ?: error("missing betaStart"), 0.0)
            assertEquals(0.012, scheduler.betaEnd ?: error("missing betaEnd"), 0.0)
            assertEquals(ImageTimestepSpacing.TRAILING, scheduler.timestepSpacing)
            assertEquals(1, scheduler.stepsOffset)
            assertTrue(scheduler.setAlphaToOne)
            assertTrue(scheduler.skipPrkSteps)
            assertEquals(ImageFinalSigmaType.SIGMA_MIN, scheduler.finalSigmaType)
            assertTrue(scheduler.clipSample)
            assertEquals(1.5, scheduler.clipSampleRange, 0.0)
            assertTrue(scheduler.thresholding)
            assertEquals(0.25, scheduler.eta, 0.0)
            assertEquals(false, scheduler.lowerOrderFinal)
            assertEquals(if (expected == ImageSchedulerAlgorithm.DPMPP_2M) 2 else 1, scheduler.order)
        }
    }

    @Test
    fun `sd21 scheduler sidecar preserves explicit ddim execution semantics`() {
        val json = schedulerJson("DDIMScheduler")
            .put("prediction_type", "v_prediction")
            .put("steps_offset", 1)
            .put("set_alpha_to_one", false)

        val scheduler = ImageExecutionProfileJson.parseSchedulerConfig(json)

        assertEquals(ImageSchedulerAlgorithm.DDIM, scheduler.algorithm)
        assertEquals(ImagePredictionType.V_PREDICTION, scheduler.predictionType)
        assertEquals(1, scheduler.stepsOffset)
        assertEquals(false, scheduler.setAlphaToOne)
    }

    @Test
    fun `manifest accepts explicit sd21 family and scheduler contract`() {
        val profileJson = validProfileJson()
            .put("family", "SD21")
        profileJson.getJSONObject("scheduler")
            .put("algorithm", "DDIM")
            .put("predictionType", "V_PREDICTION")
            .put("stepsOffset", 1)
            .put("setAlphaToOne", false)
        profileJson.getJSONObject("capabilities")
            .put("supportedSchedulers", JSONArray().put("DDIM"))

        val profile = requireNotNull(
            ImageExecutionProfileJson.parseManifest(JSONObject().put("executionProfile", profileJson))
        )

        assertEquals(LocalImageModelFamily.SD21, profile.family)
        assertEquals(ImageSchedulerAlgorithm.DDIM, profile.scheduler.algorithm)
        assertEquals(ImagePredictionType.V_PREDICTION, profile.scheduler.predictionType)
        assertEquals(1, profile.scheduler.stepsOffset)
        assertEquals(false, profile.scheduler.setAlphaToOne)
    }

    @Test
    fun `scheduler sidecar rejects unsupported or incomplete declarations`() {
        val unsupported = expectJsonFailure {
            ImageExecutionProfileJson.parseSchedulerConfig(schedulerJson("UnknownScheduler"))
        }
        assertEquals("PROFILE_FORMAT_INVALID", unsupported.code)
        assertEquals("scheduler.algorithm", unsupported.field)

        val missingPrediction = schedulerJson("EulerDiscreteScheduler").apply { remove("prediction_type") }
        val missing = expectJsonFailure {
            ImageExecutionProfileJson.parseSchedulerConfig(missingPrediction)
        }
        assertEquals("PROFILE_FORMAT_INVALID", missing.code)
        assertEquals("prediction_type", missing.field)
    }

    @Test
    fun `fixed sd15 publisher sidecar resolves to euler target defaults`() {
        val scheduler = ImageExecutionProfileJson.parseSchedulerConfig(realSd15SchedulerJson())

        assertEquals(ImageSchedulerAlgorithm.EULER, scheduler.algorithm)
        assertEquals(ImagePredictionType.EPSILON, scheduler.predictionType)
        assertEquals(ImageTimestepSpacing.LINSPACE, scheduler.timestepSpacing)
        assertEquals(1, scheduler.stepsOffset)
        assertEquals(false, scheduler.setAlphaToOne)
        assertTrue(scheduler.skipPrkSteps)
        assertEquals(false, scheduler.clipSample)
        assertTrue(scheduler.scaleModelInput)
        assertEquals(0.00085, scheduler.betaStart ?: error("missing betaStart"), 0.0)
        assertEquals(0.012, scheduler.betaEnd ?: error("missing betaEnd"), 0.0)
    }

    @Test
    fun `fixed sd21 publisher sidecar keeps ddim values and defaults leading spacing`() {
        val scheduler = ImageExecutionProfileJson.parseSchedulerConfig(realSd21SchedulerJson())

        assertEquals(ImageSchedulerAlgorithm.DDIM, scheduler.algorithm)
        assertEquals(ImagePredictionType.V_PREDICTION, scheduler.predictionType)
        assertEquals(ImageTimestepSpacing.LEADING, scheduler.timestepSpacing)
        assertEquals(1, scheduler.stepsOffset)
        assertEquals(false, scheduler.setAlphaToOne)
        assertTrue(scheduler.skipPrkSteps)
        assertEquals(false, scheduler.clipSample)
        assertEquals(false, scheduler.scaleModelInput)
    }

    @Test
    fun `tokenizer sidecar parses max length and special token ids`() {
        val tokenizer = ImageExecutionProfileJson.parseTokenizerConfig(tokenizerJson())

        assertEquals(77, tokenizer.maxLength)
        assertEquals(49_406, tokenizer.bosId)
        assertEquals(49_407, tokenizer.eosId)
        assertEquals(49_407, tokenizer.padId)
        assertEquals(ImageUnicodeNormalization.NFC, tokenizer.unicodeNormalization)
        assertEquals(ImageClipPadRule.EOS, tokenizer.clip1PadRule)
        assertTrue(tokenizer.separateNegativePrompt)
    }

    @Test
    fun `tokenizer sidecar keeps missing special token id explicit`() {
        val json = tokenizerJson().apply { remove("pad_token") }

        val error = expectJsonFailure {
            ImageExecutionProfileJson.parseTokenizerConfig(json)
        }

        assertEquals("PROFILE_FORMAT_INVALID", error.code)
        assertEquals("tokenizer.pad_token_id", error.field)
    }

    @Test
    fun `package behavior config parses generation defaults`() {
        val behavior = requireNotNull(
            ImageExecutionProfileJson.parsePackageBehaviorConfig(
                JSONObject()
                    .put("prompt", "  a quiet landscape  ")
                    .put("negative_prompt", "  blur, artifacts  ")
                    .put("steps", 8)
                    .put("cfg", 1.5)
                    .put("scheduler", "DPM++ 2M")
                    .put("width", 768)
                    .put("height", 512)
            )
        )

        assertEquals("a quiet landscape", behavior.defaultPrompt)
        assertEquals("blur, artifacts", behavior.defaultNegativePrompt)
        assertEquals(8, behavior.steps)
        assertEquals(1.5, behavior.cfgScale ?: error("missing cfgScale"), 0.0)
        assertEquals(ImageSchedulerAlgorithm.DPMPP_2M, behavior.scheduler)
        assertEquals(768, behavior.width)
        assertEquals(512, behavior.height)
    }

    @Test
    fun `package behavior config preserves an explicitly empty negative prompt`() {
        val behavior = requireNotNull(
            ImageExecutionProfileJson.parsePackageBehaviorConfig(
                JSONObject().put("negativePrompt", "   ")
            )
        )

        assertEquals("", behavior.defaultNegativePrompt)
    }

    @Test
    fun `package behavior config recognizes dmd2 and turbo markers`() {
        val dmd2 = requireNotNull(
            ImageExecutionProfileJson.parsePackageBehaviorConfig(
                JSONObject().put("is_dmd2", true)
            )
        )
        val turbo = requireNotNull(
            ImageExecutionProfileJson.parsePackageBehaviorConfig(
                JSONObject()
                    .put("isTurbo", true)
                    .put("modelFamily", "sd_turbo")
            )
        )
        val zImageTurbo = requireNotNull(
            ImageExecutionProfileJson.parsePackageBehaviorConfig(
                JSONObject().put("pipelineType", "z-image-turbo")
            )
        )

        assertEquals(LocalImageModelFamily.SDXL, dmd2.family)
        assertEquals(ImageModelVariant.DMD2_ALT, dmd2.variant)
        assertEquals(LocalImageModelFamily.SD_TURBO, turbo.family)
        assertEquals(ImageModelVariant.SD_TURBO, turbo.variant)
        assertEquals(LocalImageModelFamily.Z_IMAGE, zImageTurbo.family)
        assertEquals(ImageModelVariant.Z_IMAGE_TURBO, zImageTurbo.variant)
    }

    @Test
    fun `package behavior config rejects invalid field types`() {
        val cases = listOf(
            JSONObject().put("prompt", 7) to "config.prompt",
            JSONObject().put("negative_prompt", false) to "config.negative_prompt",
            JSONObject().put("steps", "8") to "config.steps",
            JSONObject().put("cfg", "1.5") to "config.cfg",
            JSONObject().put("scheduler", 1) to "config.scheduler",
            JSONObject().put("width", 512.5) to "config.width",
            JSONObject().put("height", true) to "config.height"
        )

        cases.forEach { (json, expectedField) ->
            val error = expectJsonFailure {
                ImageExecutionProfileJson.parsePackageBehaviorConfig(json)
            }
            assertEquals("PROFILE_FORMAT_INVALID", error.code)
            assertEquals(expectedField, error.field)
        }
    }

    @Test
    fun `package behavior config rejects non positive and malformed dimensions`() {
        val invalidConfigSizes = listOf(
            JSONObject().put("width", 0) to "config.width",
            JSONObject().put("height", -1) to "config.height"
        )

        invalidConfigSizes.forEach { (json, expectedField) ->
            val error = expectJsonFailure {
                ImageExecutionProfileJson.parsePackageBehaviorConfig(json)
            }
            assertEquals("PROFILE_FORMAT_INVALID", error.code)
            assertEquals(expectedField, error.field)
        }

        listOf("512-by-512", "0x512", "512x0").forEach { imageSize ->
            val error = expectJsonFailure {
                ImageExecutionProfileJson.parseManifestBehavior(
                    JSONObject().put("imageSize", imageSize)
                )
            }
            assertEquals("PROFILE_FORMAT_INVALID", error.code)
            assertEquals("manifest.imageSize", error.field)
        }
    }

    @Test
    fun `missing sidecars return null and present sidecars merge`() {
        val root = Files.createTempDirectory("image-profile-sidecars").toFile()
        try {
            assertNull(ImageExecutionProfileJson.parseSidecars(root))

            File(root, "scheduler/scheduler_config.json").also { file ->
                file.parentFile.mkdirs()
                file.writeText(schedulerJson("DDIMScheduler").toString(), Charsets.UTF_8)
            }
            File(root, "tokenizer/tokenizer_config.json").also { file ->
                file.parentFile.mkdirs()
                file.writeText(tokenizerJson().toString(), Charsets.UTF_8)
            }

            val sidecar = requireNotNull(ImageExecutionProfileJson.parseSidecars(root))
            assertEquals(ImageSchedulerAlgorithm.DDIM, sidecar.scheduler?.algorithm)
            assertEquals(77, sidecar.tokenizer?.maxLength)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `sidecar reader loads behavior from root config json`() {
        val root = Files.createTempDirectory("image-profile-behavior-sidecar").toFile()
        try {
            File(root, "config.json").writeText(
                JSONObject()
                    .put("prompt", "package prompt")
                    .put("negative_prompt", "")
                    .put("steps", 5)
                    .put("cfg_scale", 0.0)
                    .put("sample_method", "euler_a")
                    .put("width", 640)
                    .put("height", 384)
                    .toString(),
                Charsets.UTF_8
            )

            val sidecar = requireNotNull(ImageExecutionProfileJson.parseSidecars(root))
            val behavior = requireNotNull(sidecar.behavior)

            assertNull(sidecar.scheduler)
            assertNull(sidecar.tokenizer)
            assertEquals("package prompt", behavior.defaultPrompt)
            assertEquals("", behavior.defaultNegativePrompt)
            assertEquals(5, behavior.steps)
            assertEquals(0.0, behavior.cfgScale ?: error("missing cfgScale"), 0.0)
            assertEquals(ImageSchedulerAlgorithm.EULER_A, behavior.scheduler)
            assertEquals(640, behavior.width)
            assertEquals(384, behavior.height)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `sidecar reader rejects path escape before file access`() {
        val root = Files.createTempDirectory("image-profile-safe-path").toFile()
        try {
            val error = expectJsonFailure {
                ImageExecutionProfileJson.parseSidecars(
                    bundleRoot = root,
                    schedulerRelativePath = "../scheduler_config.json"
                )
            }
            assertEquals("PROFILE_PATH_INVALID", error.code)
            assertEquals("schedulerSidecar", error.field)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `malformed present sidecar is not treated as missing`() {
        val root = Files.createTempDirectory("image-profile-malformed-sidecar").toFile()
        try {
            File(root, "scheduler/scheduler_config.json").also { file ->
                file.parentFile.mkdirs()
                file.writeText("{broken", Charsets.UTF_8)
            }

            val error = expectJsonFailure { ImageExecutionProfileJson.parseSidecars(root) }
            assertEquals("PROFILE_JSON_INVALID", error.code)
            assertEquals("scheduler/scheduler_config.json", error.field)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun validProfileJson(): JSONObject = JSONObject()
        .put("schemaVersion", IMAGE_EXECUTION_PROFILE_SCHEMA_VERSION)
        .put("profileId", "package.sd15.qnn")
        .put("profileRevision", 2)
        .put("modelFingerprint", FINGERPRINT)
        .put("runtime", "QNN_HTP")
        .put("family", "SD15")
        .put("variant", "STANDARD")
        .put("task", "TEXT_TO_IMAGE")
        .put(
            "provenance",
            JSONObject()
                .put("primarySource", "MANIFEST")
                .put("sources", JSONArray().put("MANIFEST"))
                .put("recommendationId", "package-model")
                .put("notes", JSONArray().put("package-declared"))
        )
        .put(
            "tokenizer",
            JSONObject()
                .put("backend", "TOKENIZERS_CPP")
                .put("assets", JSONArray())
                .put("bosId", 49_406)
                .put("eosId", 49_407)
                .put("padId", 49_407)
                .put("maxLength", 77)
                .put("unicodeNormalization", "NFC")
                .put("lowercase", true)
                .put("preTokenizer", "model_declared")
                .put("postProcessor", "model_declared")
                .put("clip1PadRule", "EOS")
                .put("supportsPromptWeighting", true)
                .put("supportsTextualInversion", false)
                .put("separateNegativePrompt", true)
        )
        .put(
            "conditioning",
            JSONObject()
                .put("diskDataType", "GRAPH_INTERNAL")
                .put("textEncoderInputShape", JSONArray(listOf(1, 77)))
                .put("textEncoderOutputShapes", JSONArray().put(JSONArray(listOf(1, 77, 768))))
                .put("conversionStrategy", "GRAPH_EXECUTION")
                .put("dualEncoder", false)
                .put("pooledOutput", false)
                .put("concatenationOrder", JSONArray().put("negative").put("positive"))
        )
        .put(
            "scheduler",
            JSONObject()
                .put("algorithm", "EULER")
                .put("predictionType", "EPSILON")
                .put("numTrainTimesteps", 1_000)
                .put("noiseSchedule", "SCALED_LINEAR")
                .put("betaStart", 0.00085)
                .put("betaEnd", 0.012)
                .put("timestepSpacing", "LEADING")
                .put("stepsOffset", 0)
                .put("setAlphaToOne", true)
                .put("skipPrkSteps", false)
                .put("finalSigmaType", "SIGMA_MIN")
                .put("clipSample", true)
                .put("clipSampleRange", 1.5)
                .put("thresholding", true)
                .put("eta", 0.25)
                .put("lowerOrderFinal", false)
                .put("initNoiseSigma", 1.0)
                .put("scaleModelInput", true)
                .put("order", 1)
                .put("defaultSteps", 20)
                .put("minSteps", 10)
                .put("maxSteps", 50)
                .put("rng", "MT19937")
                .put("seedBits", 32)
        )
        .put(
            "latent",
            JSONObject()
                .put("channels", 4)
                .put("downsampleFactor", 8)
                .put("schedulerLayout", "NCHW")
                .put("graphLayout", "NCHW")
                .put("initialShape", JSONArray(listOf(1, 4, 64, 64)))
                .put("dataType", "FP32")
        )
        .put(
            "vae",
            JSONObject()
                .put("scalingLocation", "GRAPH_INTERNAL")
                .put("scalingFactor", 0.18215)
                .put("inputShape", JSONArray(listOf(1, 4, 64, 64)))
                .put("outputShape", JSONArray(listOf(1, 3, 512, 512)))
                .put("inputLayout", "NCHW")
                .put("outputLayout", "NCHW")
                .put("outputRange", "NEGATIVE_ONE_TO_ONE")
                .put("channelOrder", "RGB")
        )
        .put(
            "graph",
            JSONObject()
                .put("textEncoder", graph("graphs/text_encoder.bin"))
                .put("unet", graph("graphs/unet.bin"))
                .put("vae", graph("graphs/vae.bin"))
                .put("schedulerSidecar", "scheduler/scheduler_config.json")
                .put("tokenizerSidecar", "tokenizer/tokenizer_config.json")
                .put("configSidecars", JSONArray())
                .put("qnnSdk", "2.45")
                .put("htpArch", 81)
                .put("contextMetadataFingerprint", FINGERPRINT)
                .put("workerStrategy", "SHARED_TEXT_UNET_VAE")
        )
        .put(
            "defaults",
            JSONObject()
                .put("width", 512)
                .put("height", 512)
                .put("steps", 20)
                .put("cfgScale", 7.0)
                .put("seed", 42)
                .put("useCfg", true)
                .put("defaultNegativePrompt", "")
        )
        .put(
            "capabilities",
            JSONObject()
                .put("supportedSchedulers", JSONArray().put("EULER"))
                .put("minWidth", 512)
                .put("maxWidth", 512)
                .put("minHeight", 512)
                .put("maxHeight", 512)
                .put("widthMultiple", 8)
                .put("heightMultiple", 8)
                .put("supportsNegativePrompt", true)
                .put("supportsPromptWeighting", true)
                .put("supportsTextualInversion", false)
                .put("requiresControlImage", false)
                .put("requiresInputImage", false)
                .put("supportsMask", false)
        )

    private fun graph(path: String): JSONObject = JSONObject()
        .put("relativePath", path)
        .put("graphName", "model")
        .put("inputs", JSONArray())
        .put("outputs", JSONArray())

    private fun schedulerJson(className: String): JSONObject = JSONObject()
        .put("_class_name", className)
        .put("prediction_type", "v_prediction")
        .put("beta_schedule", "scaled_linear")
        .put("beta_start", 0.00085)
        .put("beta_end", 0.012)
        .put("timestep_spacing", "trailing")
        .put("steps_offset", 1)
        .put("set_alpha_to_one", true)
        .put("skip_prk_steps", true)
        .put("final_sigma_type", "sigma_min")
        .put("clip_sample", true)
        .put("clip_sample_range", 1.5)
        .put("thresholding", true)
        .put("eta", 0.25)
        .put("lower_order_final", false)
        .put("num_train_timesteps", 1_000)

    private fun realSd15SchedulerJson(): JSONObject = JSONObject()
        .put("_class_name", "PNDMScheduler")
        .put("beta_start", 0.00085)
        .put("beta_end", 0.012)
        .put("beta_schedule", "scaled_linear")
        .put("num_train_timesteps", 1_000)
        .put("set_alpha_to_one", false)
        .put("skip_prk_steps", true)
        .put("steps_offset", 1)
        .put("clip_sample", false)

    private fun realSd21SchedulerJson(): JSONObject = JSONObject()
        .put("_class_name", "DDIMScheduler")
        .put("beta_start", 0.00085)
        .put("beta_end", 0.012)
        .put("beta_schedule", "scaled_linear")
        .put("clip_sample", false)
        .put("num_train_timesteps", 1_000)
        .put("prediction_type", "v_prediction")
        .put("set_alpha_to_one", false)
        .put("skip_prk_steps", true)
        .put("steps_offset", 1)

    private fun tokenizerJson(): JSONObject = JSONObject()
        .put("backend", "TOKENIZERS_CPP")
        .put("model_max_length", 77)
        .put("bos_token", "<|startoftext|>")
        .put("eos_token", JSONObject().put("content", "<|endoftext|>").put("special", true))
        .put("pad_token", "<|endoftext|>")
        .put("unicode_normalization", "NFC")
        .put("lowercase", true)
        .put("pre_tokenizer", "byte_level_bpe")
        .put("post_processor", "clip")
        .put("clip1_pad_rule", "EOS")
        .put("supports_prompt_weighting", true)
        .put("supports_textual_inversion", false)
        .put("separate_negative_prompt", true)

    private fun expectJsonFailure(block: () -> Unit): ImageExecutionProfileJsonException = try {
        block()
        throw AssertionError("Expected JSON parsing to fail.")
    } catch (error: ImageExecutionProfileJsonException) {
        error
    }

    private companion object {
        val FINGERPRINT: String = "c".repeat(64)
    }
}
