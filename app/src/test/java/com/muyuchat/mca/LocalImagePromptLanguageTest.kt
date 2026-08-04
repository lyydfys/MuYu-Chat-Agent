package com.muyuchat.mca

import com.muyuchat.core.download.ModelScopeClient
import com.muyuchat.api.local.imagePromptTranslationProofFingerprint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalImagePromptLanguageTest {
    @Test
    fun `prompt language fingerprint excludes generation choices but binds text topology`() {
        val profile = requireNotNull(
            ImageExecutionProfileResolver.legacyBuiltInProfileForCompatibility(
                recommendationId = "dreamshaper_sd15_qnn228",
                modelFingerprint = "a".repeat(64)
            )
        )
        val generationOnlyChange = profile.copy(
            profileRevision = profile.profileRevision + 1,
            scheduler = profile.scheduler.copy(defaultSteps = profile.scheduler.defaultSteps + 1),
            defaults = profile.defaults.copy(
                seed = profile.defaults.seed + 99,
                width = profile.defaults.width + 64,
                defaultNegativePrompt = "new default"
            )
        )

        assertNotEquals(profile.bindingFingerprint, generationOnlyChange.bindingFingerprint)
        assertEquals(
            profile.promptLanguageBindingFingerprint,
            generationOnlyChange.promptLanguageBindingFingerprint
        )
        assertNotEquals(
            profile.promptLanguageBindingFingerprint,
            profile.copy(tokenizer = profile.tokenizer.copy(maxLength = profile.tokenizer.maxLength + 1))
                .promptLanguageBindingFingerprint
        )
        assertNotEquals(
            profile.promptLanguageBindingFingerprint,
            profile.copy(
                conditioning = profile.conditioning.copy(
                    dualEncoder = !profile.conditioning.dualEncoder
                )
            ).promptLanguageBindingFingerprint
        )
        assertNotEquals(
            profile.promptLanguageBindingFingerprint,
            profile.copy(
                graph = profile.graph.copy(
                    textEncoder = profile.graph.textEncoder?.copy(graphName = "changed-text-encoder")
                        ?: ImageGraphArtifactContract(
                            relativePath = "text-encoder.bin",
                            graphName = "changed-text-encoder"
                        )
                )
            ).promptLanguageBindingFingerprint
        )
        assertNotEquals(
            profile.promptLanguageBindingFingerprint,
            profile.copy(modelFingerprint = "b".repeat(64)).promptLanguageBindingFingerprint
        )
        assertNotEquals(
            profile.promptLanguageBindingFingerprint,
            profile.copy(family = LocalImageModelFamily.QWEN_IMAGE).promptLanguageBindingFingerprint
        )
        assertNotEquals(
            profile.promptLanguageBindingFingerprint,
            profile.copy(task = ImageTask.IMAGE_EDIT).promptLanguageBindingFingerprint
        )
        assertNotEquals(
            profile.promptLanguageBindingFingerprint,
            profile.copy(
                graph = profile.graph.copy(workerStrategy = ImageWorkerStrategy.IN_PROCESS)
            ).promptLanguageBindingFingerprint
        )
    }

    @Test
    fun `english dominant profile rejects unsafe configured default while an unused default stays nonblocking`() {
        val englishDominant = requireNotNull(
            ImageExecutionProfileResolver.legacyBuiltInProfileForCompatibility(
                recommendationId = "dreamshaper_sd15_qnn228",
                modelFingerprint = "a".repeat(64)
            )
        )
        assertFalse(
            englishDominant.copy(
                defaults = englishDominant.defaults.copy(defaultNegativePrompt = "不要文字")
            ).hasCompatibleDefaultNegativePromptLanguage()
        )
        assertTrue(
            englishDominant.copy(
                defaults = englishDominant.defaults.copy(defaultNegativePrompt = "people, text")
            ).hasCompatibleDefaultNegativePromptLanguage()
        )

        assertTrue(
            englishDominant.copy(
                defaults = englishDominant.defaults.copy(
                    useCfg = false,
                    defaultNegativePrompt = "不要文字"
                )
            ).hasCompatibleDefaultNegativePromptLanguage()
        )
    }

    @Test
    fun `final negative prompt distinguishes user model default and empty`() {
        assertEquals(
            LocalImageFinalNegativePrompt("", LocalImageNegativePromptSource.USER),
            resolveLocalImageFinalNegativePrompt("", "model default")
        )
        assertEquals(
            LocalImageFinalNegativePrompt("model default", LocalImageNegativePromptSource.MODEL_DEFAULT),
            resolveLocalImageFinalNegativePrompt(null, "model default")
        )
        assertEquals(
            LocalImageFinalNegativePrompt("", LocalImageNegativePromptSource.MODEL_DEFAULT),
            resolveLocalImageFinalNegativePrompt(null, "")
        )
        assertEquals(
            LocalImageFinalNegativePrompt("", LocalImageNegativePromptSource.EMPTY),
            resolveLocalImageFinalNegativePrompt(null, null)
        )
    }

    @Test
    fun `stable prompt profile normalization matches runtime cfg inference`() {
        val base = LocalImageGenerationOptions(cfgScale = 1.0)
        assertEquals(
            false,
            base.normalizedForPromptExecutionProfile(LocalImageRuntime.STABLE_DIFFUSION_CPP).useCfg
        )
        assertEquals(
            true,
            base.copy(cfgScale = 7.0)
                .normalizedForPromptExecutionProfile(LocalImageRuntime.STABLE_DIFFUSION_CPP)
                .useCfg
        )
        assertNull(base.normalizedForPromptExecutionProfile(LocalImageRuntime.QNN_HTP).useCfg)
        assertEquals(
            true,
            base.copy(useCfg = true)
                .normalizedForPromptExecutionProfile(LocalImageRuntime.STABLE_DIFFUSION_CPP)
                .useCfg
        )
    }

    @Test
    fun `all eighteen recommended profiles require canonical English tags until native topology is verified`() {
        val targets = ImageExecutionProfileResolver.builtInTargets

        assertEquals(18, targets.size)
        targets.forEach { target ->
            val profile = requireNotNull(
                ImageExecutionProfileResolver.legacyBuiltInProfileForCompatibility(
                    recommendationId = target.recommendationId,
                    modelFingerprint = "a".repeat(64)
                )
            )
            assertEquals(
                target.recommendationId,
                LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT,
                profile.textEncoderLanguageCapability()
            )
            assertTrue(
                target.recommendationId,
                requiresLocalImagePromptTranslation(profile, "一只红色杯子", null)
            )
            assertFalse(requiresLocalImagePromptTranslation(profile, "a red cup", null))
        }
    }

    @Test
    fun `downloaded manifest qwen profile keeps default conditioning ASCII until topology is built in`() {
        val bundle = requireNotNull(
            ModelScopeClient().recommendedModels()
                .single { model -> model.id == "qwen_image_2512_q2" }
                .imageEngineBundle
        )
        val profile = requireNotNull(
            materializeDownloadedImageExecutionProfile(bundle, "a".repeat(64))
        )
        val defaultNegativePrompt = requireNotNull(profile.defaults.defaultNegativePrompt)

        assertEquals(ImageProfileSource.MANIFEST, profile.provenance.primarySource)
        assertFalse(defaultNegativePrompt.containsHanScript())
        assertEquals(
            LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT,
            profile.textEncoderLanguageCapability()
        )
        assertTrue(profile.hasCompatibleDefaultNegativePromptLanguage())
        assertTrue(
            requiresLocalImagePromptTranslation(
                profile = profile,
                prompt = "一只红色杯子",
                negativePrompt = defaultNegativePrompt
            )
        )
    }

    @Test
    fun `provenance cannot grant native language capability without verified topology`() {
        val qwen = requireNotNull(
            ImageExecutionProfileResolver.legacyBuiltInProfileForCompatibility(
                recommendationId = "qwen_image_2512_q2",
                modelFingerprint = "a".repeat(64)
            )
        )
        val candidates = listOf(
            "built-in" to qwen,
            "user-override" to qwen.copy(
                provenance = ImageProfileProvenance(
                    primarySource = ImageProfileSource.USER_OVERRIDE,
                    sources = listOf(ImageProfileSource.USER_OVERRIDE, ImageProfileSource.BUILT_IN)
                )
            ),
            "sidecar-with-built-in" to qwen.copy(
                provenance = ImageProfileProvenance(
                    primarySource = ImageProfileSource.SIDECAR,
                    sources = listOf(ImageProfileSource.SIDECAR, ImageProfileSource.BUILT_IN)
                )
            )
        )

        val unverifiedSources = listOf(
            ImageProfileSource.MANIFEST,
            ImageProfileSource.CAPABILITY_DISCOVERY,
            ImageProfileSource.GENERIC_FALLBACK,
            ImageProfileSource.SIDECAR
        ).map { source ->
            source.name to qwen.copy(
                provenance = ImageProfileProvenance(
                    primarySource = source,
                    sources = listOf(source)
                )
            )
        }

        (candidates + unverifiedSources).forEach { (label, candidate) ->
            assertEquals(
                label,
                LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT,
                candidate.textEncoderLanguageCapability()
            )
            assertTrue(label, requiresLocalImagePromptTranslation(candidate, "一只红色杯子", null))
        }
    }

    @Test
    fun `generic compatible profiles do not claim native multilingual routing`() {
        listOf(
            "qwen_image_2512_q2",
            "z_image_turbo_q4",
            "longcat_image_q4",
            "mnn_sana_edit_v2"
        ).forEach { recommendationId ->
            val profile = requireNotNull(
                ImageExecutionProfileResolver.legacyBuiltInProfileForCompatibility(
                    recommendationId = recommendationId,
                    modelFingerprint = "b".repeat(64)
                )
            ).copy(variant = ImageModelVariant.GENERIC_COMPATIBLE)

            assertEquals(
                recommendationId,
                LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT,
                profile.textEncoderLanguageCapability()
            )
            assertTrue(
                recommendationId,
                requiresLocalImagePromptTranslation(profile, "一只红色杯子", null)
            )
        }

        val flux = requireNotNull(
            ImageExecutionProfileResolver.legacyBuiltInProfileForCompatibility(
                recommendationId = "flux2_klein_4b_q4",
                modelFingerprint = "c".repeat(64)
            )
        ).copy(variant = ImageModelVariant.GENERIC_COMPATIBLE)
        assertEquals(
            LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT,
            flux.textEncoderLanguageCapability()
        )
        assertTrue(requiresLocalImagePromptTranslation(flux, "一只红色杯子", null))
    }

    @Test
    fun `language routing requires a coherent native multilingual text topology`() {
        val english = requireNotNull(
            ImageExecutionProfileResolver.legacyBuiltInProfileForCompatibility(
                recommendationId = "dreamshaper_sd15_qnn228",
                modelFingerprint = "a".repeat(64)
            )
        )
        val qwen = requireNotNull(
            ImageExecutionProfileResolver.legacyBuiltInProfileForCompatibility(
                recommendationId = "qwen_image_2512_q2",
                modelFingerprint = "b".repeat(64)
            )
        )
        val sana = requireNotNull(
            ImageExecutionProfileResolver.legacyBuiltInProfileForCompatibility(
                recommendationId = "mnn_sana_edit_v2",
                modelFingerprint = "c".repeat(64)
            )
        )

        assertEquals(
            LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT,
            english.copy(variant = ImageModelVariant.QWEN_IMAGE).textEncoderLanguageCapability()
        )
        assertEquals(
            LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT,
            qwen.copy(family = LocalImageModelFamily.SD15).textEncoderLanguageCapability()
        )
        assertEquals(
            LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT,
            qwen.copy(
                tokenizer = qwen.tokenizer.copy(backend = ImageTokenizerBackend.TOKENIZERS_CPP)
            ).textEncoderLanguageCapability()
        )
        assertEquals(
            LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT,
            qwen.copy(
                conditioning = qwen.conditioning.copy(
                    diskDataType = ImageEmbeddingDiskDataType.FP16
                )
            ).textEncoderLanguageCapability()
        )
        assertEquals(
            LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT,
            sana.textEncoderLanguageCapability()
        )
        assertEquals(
            LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT,
            sana.copy(graph = sana.graph.copy(textEncoder = null)).textEncoderLanguageCapability()
        )
        assertEquals(
            LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT,
            sana.copy(tokenizer = sana.tokenizer.copy(backend = ImageTokenizerBackend.TOKENIZERS_CPP))
                .textEncoderLanguageCapability()
        )
        assertEquals(
            LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT,
            sana.copy(task = ImageTask.TEXT_TO_IMAGE).textEncoderLanguageCapability()
        )
        assertEquals(
            LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT,
            sana.copy(
                conditioning = sana.conditioning.copy(
                    conversionStrategy = ImageEmbeddingConversionStrategy.RUNTIME_NATIVE
                )
            ).textEncoderLanguageCapability()
        )
        assertEquals(
            LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT,
            sana.copy(
                graph = sana.graph.copy(workerStrategy = ImageWorkerStrategy.IN_PROCESS)
            ).textEncoderLanguageCapability()
        )
    }

    @Test
    fun `semantic validator fixture preserves the planned device canary bindings`() {
        val translation = parseLocalImagePromptTranslation(
            raw = translationJson(
                prompt = "one red cup on a blue table, two green apples to the left of the cup",
                negativePrompt = "people, text, extra fruit"
            ),
            originalPrompt = "一只红色杯子放在蓝色桌子上，杯子左侧有两个绿色苹果",
            originalNegativePrompt = "不要人物，不要文字，不要多余水果"
        )

        assertTrue(translation.prompt.contains("red cup"))
        assertTrue(translation.prompt.contains("blue table"))
        assertEquals("people, text, extra fruit", translation.negativePrompt)
    }

    @Test
    fun `device translator singular article and negative prefixes normalize before validation`() {
        val deviceRaw = JSONObject(
            translationJson(
                prompt = "a red cup on a blue table, two green apples to the left of the cup",
                negativePrompt = "no people, without text, do not include extra fruit"
            )
        )
            .put(
                "contract_canary_prompt",
                "a red cup on a blue table, two green apples to the left of the cup"
            )
            .put(
                "contract_canary_negative_prompt",
                "no people, no text, no extra fruit"
            )

        val translation = parseLocalImagePromptTranslation(
            raw = deviceRaw.toString(),
            originalPrompt = "一只红色杯子放在蓝色桌子上，杯子左侧有两个绿色苹果",
            originalNegativePrompt = "不要人物，不要文字，不要多余水果"
        )

        assertEquals(
            "one red cup on a blue table, two green apples to the left of the cup",
            translation.prompt
        )
        assertEquals("people, text, extra fruit", translation.negativePrompt)
        listOf(" no ", " not ", " without ", " avoid ", " do not ").forEach { operator ->
            assertFalse(" ${translation.negativePrompt.orEmpty().lowercase()} ".contains(operator))
        }
    }

    @Test
    fun `singular article repair rejects missing or ambiguous entity bindings`() {
        listOf(
            "一只红色杯子放在蓝色桌子上" to "red cup on a blue table",
            "一只红色杯子和杯子" to "a red cup and a cup"
        ).forEach { (source, translated) ->
            assertTrue(
                source,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(translated, null),
                        originalPrompt = source,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `negative prefix repair rejects residual mid clause operators`() {
        assertTrue(
            runCatching {
                parseLocalImagePromptTranslation(
                    raw = translationJson(
                        prompt = "one red cup",
                        negativePrompt = "no people, text without watermark, no extra fruit"
                    ),
                    originalPrompt = "一个红色杯子",
                    originalNegativePrompt = "不要人物，不要文字，不要多余水果"
                )
            }.isFailure
        )
    }

    @Test
    fun `all controlled negative clause prefixes canonicalize to concepts`() {
        listOf(
            "no",
            "not",
            "without",
            "avoid",
            "exclude",
            "do not",
            "do not include",
            "don't",
            "don't include"
        ).forEach { prefix ->
            assertEquals(
                prefix,
                "people",
                parseLocalImagePromptTranslation(
                    raw = translationJson("one red cup", "$prefix people"),
                    originalPrompt = "一个红色杯子",
                    originalNegativePrompt = "不要人物"
                ).negativePrompt
            )
        }
    }

    @Test
    fun `swapped color subject and side bindings fail closed`() {
        assertTrue(
            runCatching {
                parseLocalImagePromptTranslation(
                    raw = translationJson(
                        "a blue dog on the left, a red cat on the right",
                        null
                    ),
                    originalPrompt = "左边一只红色的猫，右边一只蓝色的狗",
                    originalNegativePrompt = null
                )
            }.isFailure
        )
    }

    @Test
    fun `same-clause attribute quantity and relative-direction swaps fail closed`() {
        listOf(
            "红猫和蓝狗" to "blue cat and red dog",
            "两只猫和三只狗" to "three cats and two dogs",
            "红猫在蓝狗左边" to "blue dog to the left of red cat"
        ).forEach { (source, wrongTranslation) ->
            assertTrue(
                source,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(wrongTranslation, null),
                        originalPrompt = source,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
        assertEquals(
            "one red cat",
            parseLocalImagePromptTranslation(
                raw = translationJson("a red cat", null),
                originalPrompt = "一只红猫",
                originalNegativePrompt = null
            ).prompt
        )
        listOf(
            "杯子在桌子下" to "cup on table",
            "杯子在桌子下" to "cup in table",
            "杯子在桌子上" to "cup in table",
            "苹果绿杯子" to "green apple cup"
        ).forEach { (source, wrongTranslation) ->
            assertTrue(
                source,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(wrongTranslation, null),
                        originalPrompt = source,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `adjacency possession and copula relations require bound English tuples`() {
        listOf(
            Triple("杯子旁边有两个苹果", "two apples next to the cup", "two apples next to the cup"),
            Triple("杯子放在桌子旁边", "cup beside table", "cup beside table"),
            Triple("杯子位于桌子旁边", "cup near table", "cup near table"),
            Triple("杯子有苹果", "cup with apple", "cup with apple"),
            Triple("杯子是苹果", "cup is apple", "cup is apple"),
            Triple("杯子是红色", "red cup", "red cup"),
            Triple("杯子为红色", "red cup", "red cup")
        ).forEach { (source, translated, expected) ->
            assertEquals(
                source,
                expected,
                parseLocalImagePromptTranslation(
                    raw = translationJson(translated, null),
                    originalPrompt = source,
                    originalNegativePrompt = null
                ).prompt
            )
        }

        listOf(
            "杯子旁边有两个苹果" to "cup with two apples",
            "杯子放在桌子旁边" to "cup on table",
            "杯子有苹果" to "apple has cup",
            "杯子有苹果" to "cup and apple",
            "杯子是苹果" to "apple is cup"
        ).forEach { (source, translated) ->
            assertTrue(
                source,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(translated, null),
                        originalPrompt = source,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `spatial auxiliary copulas are consumed in subject first and inverted grammar`() {
        listOf(
            "红猫在蓝狗左边" to "red cat is to the left of blue dog",
            "两个苹果放在桌子上" to "two apples are on table",
            "猫位于狗上方" to "cat is on top of dog",
            "杯子左侧有两个苹果" to "to the left of cup are two apples",
            "杯子放在桌子上" to "on table is cup",
            "猫在杯子里" to "inside cup is cat"
        ).forEach { (source, translated) ->
            assertEquals(
                source,
                translated,
                parseLocalImagePromptTranslation(
                    raw = translationJson(translated, null),
                    originalPrompt = source,
                    originalNegativePrompt = null
                ).prompt
            )
        }
        listOf(
            "cup is are on table",
            "on table is are cup"
        ).forEach { translated ->
            assertTrue(
                translated,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(translated, null),
                        originalPrompt = "杯子放在桌子上",
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `unsupported semantic connectors remain fail closed`() {
        listOf(
            "杯子放在桌子" to "cup on table",
            "杯子位于桌子" to "cup near table",
            "杯子坐落在桌子" to "cup near table",
            "杯子放置于桌子" to "cup near table",
            "杯子在桌子" to "cup near table",
            "杯子旁边两个苹果" to "two apples next to cup"
        ).forEach { (source, translated) ->
            assertTrue(
                source,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(translated, null),
                        originalPrompt = source,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `semantic relation markers never bind across prompt clauses`() {
        listOf(
            "杯子，旁边有两个苹果" to "cup, two apples",
            "杯子，有苹果" to "cup, apple",
            "杯子，是苹果" to "cup, apple"
        ).forEach { (source, translated) ->
            assertTrue(
                source,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(translated, null),
                        originalPrompt = source,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `Chinese conjunctions require an explicit bound English conjunction`() {
        listOf("和", "与", "及", "以及").forEach { conjunction ->
            assertEquals(
                conjunction,
                "cat and dog",
                parseLocalImagePromptTranslation(
                    raw = translationJson("cat and dog", null),
                    originalPrompt = "猫${conjunction}狗",
                    originalNegativePrompt = null
                ).prompt
            )
        }
        listOf(
            "猫和狗" to "cat of dog",
            "猫和狗" to "cat dog",
            "猫的狗" to "cat and dog"
        ).forEach { (source, translated) ->
            assertTrue(
                source,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(translated, null),
                        originalPrompt = source,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `protected prompt fragments preserve nesting clause order and entity binding`() {
        val source =
            "一只红猫 ((cinematic):1.2) embedding:cat-style 和 一只蓝狗 <lora:dog-style:1>"
        val translated =
            "one red cat ((cinematic):1.2) embedding:cat-style and one blue dog <lora:dog-style:1>"
        assertEquals(
            translated,
            parseLocalImagePromptTranslation(
                raw = translationJson(translated, null),
                originalPrompt = source,
                originalNegativePrompt = null
            ).prompt
        )

        listOf(
            "一只红猫 (cinematic:1.2)，一只蓝狗" to
                "one red cat, one blue dog (cinematic:1.2)",
            "一只红猫 <lora:cat:1> 和 一只蓝狗" to
                "one red cat and one blue dog <lora:cat:1>",
            "一只红猫 <lora:cat:1> 和 一只蓝狗 <lora:dog:1>" to
                "one red cat <lora:dog:1> and one blue dog <lora:cat:1>",
            "一只红猫 ((cinematic):1.2)" to "one red cat (cinematic):1.2()"
        ).forEach { (wrongSource, wrongTranslation) ->
            assertTrue(
                wrongSource,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(wrongTranslation, null),
                        originalPrompt = wrongSource,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }


        assertEquals(
            "one red cat <lora:dog:1>",
            parseLocalImagePromptTranslation(
                raw = translationJson("one red cat <lora:dog:1>", null),
                originalPrompt = "一只红猫 <lora:dog:1>",
                originalNegativePrompt = null
            ).prompt
        )
        listOf(
            "一只红猫 <lora:dog:1>" to "one red cat and one dog <lora:dog:1>",
            "一只红猫 <lora:cat:1>" to "one red dog <lora:cat:1>"
        ).forEach { (sourceWithLora, pollutedTranslation) ->
            assertTrue(
                sourceWithLora,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(pollutedTranslation, null),
                        originalPrompt = sourceWithLora,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `unbalanced escaped and case sensitive ASCII diffusion syntax is fail closed`() {
        assertTrue(
            runCatching {
                parseLocalImagePromptTranslation(
                    raw = translationJson("(dog, cat", null),
                    originalPrompt = "(猫，狗",
                    originalNegativePrompt = null
                )
            }.isFailure
        )
        assertEquals(
            "one red cat \\((cinematic)\\)",
            parseLocalImagePromptTranslation(
                raw = translationJson("one red cat \\((cinematic)\\)", null),
                originalPrompt = "一只红猫 \\((cinematic)\\)",
                originalNegativePrompt = null
            ).prompt
        )
        listOf(
            "one red cat (cinematic)" to "一只红猫 \\((cinematic)\\)",
            "one red cat, break, one blue dog" to "一只红猫, BREAK, 一只蓝狗"
        ).forEach { (translated, source) ->
            assertTrue(
                source,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(translated, null),
                        originalPrompt = source,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `unclosed and escaped attention syntax is usable only when structure stays exact`() {
        listOf(
            "(一只红猫，一只蓝狗" to "(one red cat, one blue dog",
            "\\(一只红猫\\)" to "\\(one red cat\\)",
            "一只红猫\\, raw_tag，一只蓝狗 (sharp:1.25)" to
                "one red cat\\, raw_tag, one blue dog (sharp:1.25)"
        ).forEach { (source, translated) ->
            assertEquals(
                source,
                translated,
                parseLocalImagePromptTranslation(
                    raw = translationJson(translated, null),
                    originalPrompt = source,
                    originalNegativePrompt = null
                ).prompt
            )
        }
        listOf(
            "(一只红猫，一只蓝狗" to "one red cat, one blue dog",
            "\\(一只红猫\\)" to "(one red cat)",
            "(一只红猫，一只蓝狗" to "[one red cat, one blue dog"
        ).forEach { (source, translated) ->
            assertTrue(
                source,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(translated, null),
                        originalPrompt = source,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `clause boundary families line endings and BREAK position remain stable`() {
        val source = "一只红猫，\r\n一只蓝狗；两个杯子"
        val translated = "one red cat,\r\none blue dog; two cups"
        assertEquals(
            translated,
            parseLocalImagePromptTranslation(
                raw = translationJson(translated, null),
                originalPrompt = source,
                originalNegativePrompt = null
            ).prompt
        )
        listOf(
            "one red cat;\r\none blue dog, two cups",
            "one red cat,\none blue dog; two cups",
            "one red cat, one blue dog; two cups"
        ).forEach { wrong ->
            assertTrue(
                wrong,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(wrong, null),
                        originalPrompt = source,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }

        val breakSource = "一只红猫 BREAK 一只蓝狗"
        val exactBreak = "one red cat BREAK one blue dog"
        assertEquals(
            exactBreak,
            parseLocalImagePromptTranslation(
                raw = translationJson(exactBreak, null),
                originalPrompt = breakSource,
                originalNegativePrompt = null
            ).prompt
        )
        listOf(
            "BREAK one red cat one blue dog",
            "one red cat break one blue dog",
            "one red cat one blue dog BREAK"
        ).forEach { wrong ->
            assertTrue(
                wrong,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(wrong, null),
                        originalPrompt = breakSource,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `quantity words require matching English noun number`() {
        listOf(
            "一个苹果" to "one apple",
            "两个苹果" to "two apples",
            "一个人物" to "one character",
            "两个杯子" to "two cups",
            "两个水杯" to "two mugs",
            "两个桌子" to "two tables",
            "三个桌面" to "three tabletops",
            "四只猫咪" to "four felines",
            "两个狗狗" to "two canines",
            "一个文本" to "one text",
            "两个文本" to "two texts"
        ).forEach { (source, translated) ->
            assertEquals(
                source,
                translated,
                parseLocalImagePromptTranslation(
                    raw = translationJson(translated, null),
                    originalPrompt = source,
                    originalNegativePrompt = null
                ).prompt
            )
        }
        listOf(
            "一个苹果" to "one apples",
            "两个苹果" to "two apple",
            "一个人物" to "one characters",
            "两个杯子" to "two cup",
            "两个桌子" to "two desk",
            "一个文本" to "one texts",
            "两个文本" to "two text"
        ).forEach { (source, translated) ->
            assertTrue(
                source,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(translated, null),
                        originalPrompt = source,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `multiple adjacent attributes remain bound to one entity`() {
        listOf(
            "一个红色陶瓷杯子" to "one red ceramic cup",
            "一个红色陶瓷木质杯子" to "one red ceramic wooden cup"
        ).forEach { (source, translated) ->
            assertEquals(
                source,
                translated,
                parseLocalImagePromptTranslation(
                    raw = translationJson(translated, null),
                    originalPrompt = source,
                    originalNegativePrompt = null
                ).prompt
            )
        }
        listOf(
            "一个红色陶瓷杯子" to "one ceramic red cup",
            "红色蓝色杯子" to "blue red cup",
            "两个绿色苹果" to "green two apples",
            "红色          猫和蓝色狗" to "cat and red blue dog",
            "两个          苹果和三个          狗" to "three apples and two dogs"
        ).forEach { (source, translated) ->
            assertTrue(
                source,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(translated, null),
                        originalPrompt = source,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `Chinese concepts inside weights translate while ASCII syntax remains exact`() {
        assertEquals(
            "(red cat:1.2)",
            parseLocalImagePromptTranslation(
                raw = translationJson("(red cat:1.2)", null),
                originalPrompt = "(红猫:1.2)",
                originalNegativePrompt = null
            ).prompt
        )
        assertEquals(
            "one red cat, (sharp:2) soft",
            parseLocalImagePromptTranslation(
                raw = translationJson("one red cat, (sharp:2) soft", null),
                originalPrompt = "一只红猫, (sharp:2) soft",
                originalNegativePrompt = null
            ).prompt
        )
        assertEquals(
            "(one red ceramic cup:1.2)",
            parseLocalImagePromptTranslation(
                raw = translationJson("(one red ceramic cup:1.2)", null),
                originalPrompt = "（一个红色陶瓷杯子：1.2）",
                originalNegativePrompt = null
            ).prompt
        )
        listOf(
            "(红猫:1.2) <lora:style:1>" to "(red cat:1.2) <lora:style:1>",
            "(猫和狗:1.2)" to "(cat and dog:1.2)"
        ).forEach { (source, translated) ->
            assertEquals(
                source,
                translated,
                parseLocalImagePromptTranslation(
                    raw = translationJson(translated, null),
                    originalPrompt = source,
                    originalNegativePrompt = null
                ).prompt
            )
        }
        listOf(
            "(blue cat:1.2)" to "(红猫:1.2)",
            "(dog:1.2) and (cat:0.8)" to "(猫:1.2)和(狗:0.8)",
            "one red cat, soft (sharp:2)" to "一只红猫, (sharp:2) soft"
        ).forEach { (translated, source) ->
            assertTrue(
                source,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(translated, null),
                        originalPrompt = source,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `protected relation words cannot satisfy spatial tuples`() {
        listOf(
            "杯子放在桌子上 (on)" to "cup table (on)",
            "红猫在蓝狗左边 (left)" to "red cat blue dog (left)"
        ).forEach { (source, translated) ->
            assertTrue(
                source,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(translated, null),
                        originalPrompt = source,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `ambiguous repeated entities and swapped extra binding fail closed`() {
        assertTrue(
            runCatching {
                parseLocalImagePromptTranslation(
                    raw = translationJson("red apple green and apple", null),
                    originalPrompt = "红苹果和苹果绿",
                    originalNegativePrompt = null
                )
            }.isFailure
        )
        assertEquals(
            "one red apple and two green apples",
            parseLocalImagePromptTranslation(
                raw = translationJson("one red apple and two green apples", null),
                originalPrompt = "一个红苹果和两个绿苹果",
                originalNegativePrompt = null
            ).prompt
        )
        assertTrue(
            runCatching {
                parseLocalImagePromptTranslation(
                    raw = translationJson("one green apple and two red apples", null),
                    originalPrompt = "一个红苹果和两个绿苹果",
                    originalNegativePrompt = null
                )
            }.isFailure
        )
        assertEquals(
            "extra fruit and people",
            parseLocalImagePromptTranslation(
                raw = translationJson("one red cup", "extra fruit and people"),
                originalPrompt = "一个红色杯子",
                originalNegativePrompt = "不要多余水果和人物"
            ).negativePrompt
        )
        assertTrue(
            runCatching {
                parseLocalImagePromptTranslation(
                    raw = translationJson("one red cup", "extra people and fruit"),
                    originalPrompt = "一个红色杯子",
                    originalNegativePrompt = "不要多余水果和人物"
                )
            }.isFailure
        )
    }

    @Test
    fun `predicative Chinese adjectives bind to the same English entity`() {
        listOf("red cup", "cup is red").forEach { translated ->
            assertEquals(
                translated,
                parseLocalImagePromptTranslation(
                    raw = translationJson(translated, null),
                    originalPrompt = "杯子是红色",
                    originalNegativePrompt = null
                ).prompt
            )
        }
        assertTrue(
            runCatching {
                parseLocalImagePromptTranslation(
                    raw = translationJson("cup red", null),
                    originalPrompt = "杯子是红色",
                    originalNegativePrompt = null
                )
            }.isFailure
        )
    }

    @Test
    fun `weighted entities still participate in conjunction and spatial relations`() {
        listOf(
            "(红猫:1.2)和蓝狗" to "(red cat:1.2) and blue dog",
            "(红猫:1.2)位于蓝狗下方" to "(red cat:1.2) under blue dog",
            "红猫位于(蓝狗:1.1)上方" to "red cat above (blue dog:1.1)",
            "红猫放在(蓝色桌子:1.1)上" to "red cat on (blue table:1.1)",
            "红猫位于(蓝狗:1.1)旁边" to "red cat beside (blue dog:1.1)"
        ).forEach { (source, translated) ->
            assertEquals(
                source,
                translated,
                parseLocalImagePromptTranslation(
                    raw = translationJson(translated, null),
                    originalPrompt = source,
                    originalNegativePrompt = null
                ).prompt
            )
        }
        assertEquals(
            "people and cats and dogs",
            parseLocalImagePromptTranslation(
                raw = translationJson("people and cats and dogs", null),
                originalPrompt = "人物和猫和狗",
                originalNegativePrompt = null
            ).prompt
        )
        assertEquals(
            "cat and dog and cat",
            parseLocalImagePromptTranslation(
                raw = translationJson("cat and dog and cat", null),
                originalPrompt = "猫和狗和猫",
                originalNegativePrompt = null
            ).prompt
        )
        assertTrue(
            runCatching {
                parseLocalImagePromptTranslation(
                    raw = translationJson("cat and dog cat", null),
                    originalPrompt = "猫和狗和猫",
                    originalNegativePrompt = null
                )
            }.isFailure
        )
    }

    @Test
    fun `positive Chinese negation binds every scoped entity`() {
        assertEquals(
            "no people and no cats",
            parseLocalImagePromptTranslation(
                raw = translationJson("no people and no cats", null),
                originalPrompt = "不要人物和猫",
                originalNegativePrompt = null
            ).prompt
        )
        assertEquals(
            "(no people:1.2)",
            parseLocalImagePromptTranslation(
                raw = translationJson("(no people:1.2)", null),
                originalPrompt = "(不要人物:1.2)",
                originalNegativePrompt = null
            ).prompt
        )
        assertEquals(
            "(no people:1.2) and cat",
            parseLocalImagePromptTranslation(
                raw = translationJson("(no people:1.2) and cat", null),
                originalPrompt = "(不要人物:1.2)和猫",
                originalNegativePrompt = null
            ).prompt
        )
        assertTrue(
            runCatching {
                parseLocalImagePromptTranslation(
                    raw = translationJson("(no people:1.2) and no cat", null),
                    originalPrompt = "(不要人物:1.2)和猫",
                    originalNegativePrompt = null
                )
            }.isFailure
        )
        listOf(
            "people and no cats",
            "no people and cats",
            "no cats and no people"
        ).forEach { wrong ->
            assertTrue(
                wrong,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(wrong, null),
                        originalPrompt = "不要人物和猫",
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `mixed ASCII literals stay case exact and bound between the same entities`() {
        val source = "一只红猫 raw_tag 一只蓝狗"
        val translated = "one red cat raw_tag one blue dog"
        assertEquals(
            translated,
            parseLocalImagePromptTranslation(
                raw = translationJson(translated, null),
                originalPrompt = source,
                originalNegativePrompt = null
            ).prompt
        )
        listOf(
            "raw_tag one red cat one blue dog",
            "one red cat one blue dog raw_tag",
            "one red cat RAW_TAG one blue dog"
        ).forEach { wrong ->
            assertTrue(
                wrong,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(wrong, null),
                        originalPrompt = source,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `mixed ASCII anchors remain valid in entity neighborhoods and attention scopes`() {
        listOf(
            "红猫 red" to "red cat red",
            "猫 cat" to "cat cat",
            "猫 on 狗" to "cat on dog",
            "(猫 cat 狗:1.2)" to "(cat cat dog:1.2)",
            "(猫 inside 狗:1.2)" to "(cat inside dog:1.2)"
        ).forEach { (source, translated) ->
            assertEquals(
                source,
                translated,
                parseLocalImagePromptTranslation(
                    raw = translationJson(translated, null),
                    originalPrompt = source,
                    originalNegativePrompt = null
                ).prompt
            )
        }

        listOf(
            "猫 狗 on" to "cat on dog",
            "猫 狗 in" to "cat in dog",
            "猫 狗 inside" to "cat inside dog",
            "猫 狗 outside" to "cat outside dog"
        ).forEach { (source, translated) ->
            assertTrue(
                source,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(translated, null),
                        originalPrompt = source,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `translation cannot inject diffusion control punctuation`() {
        listOf("|", "*", "/", ":", "\"", "'", "+", "-", "\\", "_").forEach { injected ->
            assertTrue(
                injected,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson("red cat and blue dog $injected", null),
                        originalPrompt = "红猫和蓝狗",
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `unverified non Han source Unicode never disappears silently`() {
        listOf(
            "一只红猫😊",
            "一只红猫 ネオン"
        ).forEach { source ->
            assertTrue(
                source,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson("one red cat", null),
                        originalPrompt = source,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `singular article repair never rewrites protected prompt structures`() {
        listOf(
            "一只红猫 (cinematic:1.2)" to "(a:1.2) red cat cinematic",
            "一只红猫 (cinematic:1.2)" to "(one:1.2) red cat cinematic",
            "一只红猫 [tag]" to "[a] red cat tag",
            "一只红猫 <lora:style:1>" to "<lora:a:1> red cat style"
        ).forEach { (source, translated) ->
            assertTrue(
                source,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(translated, null),
                        originalPrompt = source,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }

        val preserved = parseLocalImagePromptTranslation(
            raw = translationJson(
                "one red cat, (cinematic:1.2), [tag], <lora:style:1>",
                null
            ),
            originalPrompt = "一只红猫, (cinematic:1.2), [tag], <lora:style:1>",
            originalNegativePrompt = null
        )
        assertEquals(
            "one red cat, (cinematic:1.2), [tag], <lora:style:1>",
            preserved.prompt
        )
    }

    @Test
    fun `postpositive and prepositive Chinese colors preserve entity order`() {
        assertEquals(
            "apple green",
            parseLocalImagePromptTranslation(
                raw = translationJson("apple green", null),
                originalPrompt = "苹果绿",
                originalNegativePrompt = null
            ).prompt
        )
        assertEquals(
            "green apple",
            parseLocalImagePromptTranslation(
                raw = translationJson("green apple", null),
                originalPrompt = "绿色苹果",
                originalNegativePrompt = null
            ).prompt
        )
        listOf(
            "苹果绿" to "green apple",
            "绿色苹果" to "apple green",
            "苹果绿杯子" to "apple green cup"
        ).forEach { (source, wrongTranslation) ->
            assertTrue(
                source,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(wrongTranslation, null),
                        originalPrompt = source,
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun `translated outputs reject non ASCII and control character bypasses`() {
        listOf(
            "one red cup Ж",
            "one red cup α",
            "one red cup 😀",
            "one red cup\u202E",
            "one red\tcup"
        ).forEach { unsafePrompt ->
            assertTrue(
                unsafePrompt,
                runCatching {
                    parseLocalImagePromptTranslation(
                        raw = translationJson(unsafePrompt, null),
                        originalPrompt = "一个红色杯子",
                        originalNegativePrompt = null
                    )
                }.isFailure
            )
        }
        assertTrue(
            runCatching {
                parseLocalImagePromptTranslation(
                    raw = translationJson(
                        prompt = "one red cup",
                        negativePrompt = "people, text, extra fruit ¬"
                    ),
                    originalPrompt = "一个红色杯子",
                    originalNegativePrompt = "不要人物，不要文字，不要多余水果"
                )
            }.isFailure
        )
    }

    @Test
    fun `known hallucinated subject fails closed`() {
        assertTrue(
            runCatching {
                parseLocalImagePromptTranslation(
                    raw = translationJson("a red cat and a blue dog", null),
                    originalPrompt = "一只红色的猫",
                    originalNegativePrompt = null
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                parseLocalImagePromptTranslation(
                    raw = translationJson("one red cat and one red cat", null),
                    originalPrompt = "一只红猫",
                    originalNegativePrompt = null
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                parseLocalImagePromptTranslation(
                    raw = translationJson("one red cat, 999", null),
                    originalPrompt = "一只红猫",
                    originalNegativePrompt = null
                )
            }.isFailure
        )
    }

    @Test
    fun `uncovered Chinese and unauthorized English hallucinations fail closed`() {
        assertTrue(
            runCatching {
                parseLocalImagePromptTranslation(
                    raw = translationJson("a modern spaceship holding a gun", null),
                    originalPrompt = "古老寺庙",
                    originalNegativePrompt = null
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                parseLocalImagePromptTranslation(
                    raw = translationJson("one red cat and a nuclear explosion", null),
                    originalPrompt = "一只红猫",
                    originalNegativePrompt = null
                )
            }.isFailure
        )
    }

    @Test
    fun `translator response must pass the embedded semantic canary`() {
        val invalidCanary = JSONObject(translationJson("one red cat", null))
            .put(
                "contract_canary_prompt",
                "two green apples on a blue table with one red cup on the left"
            )

        assertTrue(
            runCatching {
                parseLocalImagePromptTranslation(
                    raw = invalidCanary.toString(),
                    originalPrompt = "一只红猫",
                    originalNegativePrompt = null
                )
            }.isFailure
        )
    }

    @Test
    fun `translated diffusion negative prompt canonicalizes clause prefix operators`() {
        assertEquals(
            "people, text, extra fruit",
            parseLocalImagePromptTranslation(
                raw = translationJson(
                    prompt = "one red cup",
                    negativePrompt = "no people, no text, no extra fruit"
                ),
                originalPrompt = "一个红色杯子",
                originalNegativePrompt = "不要人物，不要文字，不要多余水果"
            ).negativePrompt
        )
    }

    @Test
    fun `mixed Chinese prompt preserves ASCII tags numbers weights and structure`() {
        val translation = parseLocalImagePromptTranslation(
            raw = translationJson("a red cat, (cinematic:1.2), 8k", null),
            originalPrompt = "红色的猫, (cinematic:1.2), 8k",
            originalNegativePrompt = null
        )

        assertEquals("a red cat, (cinematic:1.2), 8k", translation.prompt)
        assertTrue(
            runCatching {
                parseLocalImagePromptTranslation(
                    raw = translationJson("a red cat, cinematic, 8k", null),
                    originalPrompt = "红色的猫, (cinematic:1.2), 8k",
                    originalNegativePrompt = null
                )
            }.isFailure
        )

        val canonicalTag = parseLocalImagePromptTranslation(
            raw = translationJson("one red cat, (blue:1.2)", null),
            originalPrompt = "一只红猫, (blue:1.2)",
            originalNegativePrompt = null
        )
        assertEquals("one red cat, (blue:1.2)", canonicalTag.prompt)
    }

    @Test
    fun `prompt evidence rejects null-state tampering and legacy weak translations`() {
        val translated = translatedExecution()
        val nullStateTamper = translated.toJson()
            .put("originalNegativePrompt", JSONObject.NULL)
        assertNull(LocalImagePromptExecution.fromJsonOrNull(nullStateTamper))

        val swappedBinding = translated.toJson()
            .put("originalPrompt", "左边一只红色的猫，右边一只蓝色的狗")
            .put("effectivePrompt", "a blue dog on the left, a red cat on the right")
        assertNull(LocalImagePromptExecution.fromJsonOrNull(swappedBinding))

        val legacyWeakEvidence = translated.toJson()
            .put("version", 2)
            .apply { remove("translationContractVersion") }
        assertNull(LocalImagePromptExecution.fromJsonOrNull(legacyWeakEvidence))
    }

    @Test
    fun `translated prompt with omitted negative applies model default outside translation semantics`() {
        val evidence = translatedExecution(
            originalPrompt = "一只红色杯子",
            effectivePrompt = "one red cup",
            originalNegativePrompt = null,
            effectiveNegativePrompt = "bad anatomy",
            negativePromptSource = LocalImageNegativePromptSource.MODEL_DEFAULT,
            imageProfileBindingFingerprint = "a".repeat(64),
            promptLanguageBindingFingerprint = "b".repeat(64),
            translatorRuntime = "LLAMA_CPP",
            translatorModelSha256 = "c".repeat(64)
        )

        val restored = requireNotNull(LocalImagePromptExecution.fromJsonOrNull(evidence.toJson()))
        assertEquals("bad anatomy", restored.effectiveNegativePrompt)
        assertEquals(LocalImageNegativePromptSource.MODEL_DEFAULT, restored.negativePromptSource)
        assertNull(restored.originalNegativePrompt)
        assertNull(
            LocalImagePromptExecution.fromJsonOrNull(
                evidence.toJson().put("negativePromptSource", LocalImageNegativePromptSource.USER.name)
            )
        )
    }

    @Test
    fun `captured prompt rebind refreshes only profile-owned negative prompt`() {
        val capturedDefault = translatedExecution(
            originalPrompt = "一只红色杯子",
            effectivePrompt = "one red cup",
            originalNegativePrompt = null,
            effectiveNegativePrompt = "old default",
            negativePromptSource = LocalImageNegativePromptSource.MODEL_DEFAULT,
            imageProfileBindingFingerprint = "1".repeat(64),
            promptLanguageBindingFingerprint = "2".repeat(64),
            translatorRuntime = "MNN",
            translatorModelSha256 = "3".repeat(64)
        )

        val refreshedDefault = capturedDefault.rebindToCurrentImageProfile(
            finalNegativePrompt = LocalImageFinalNegativePrompt(
                "new default",
                LocalImageNegativePromptSource.MODEL_DEFAULT
            ),
            imageProfileBindingFingerprint = "4".repeat(64),
            promptLanguageBindingFingerprint = "2".repeat(64)
        )
        assertEquals("new default", refreshedDefault.effectiveNegativePrompt)
        assertEquals(LocalImageNegativePromptSource.MODEL_DEFAULT, refreshedDefault.negativePromptSource)
        assertEquals("4".repeat(64), refreshedDefault.imageProfileBindingFingerprint)

        val clearedDefault = refreshedDefault.rebindToCurrentImageProfile(
            finalNegativePrompt = LocalImageFinalNegativePrompt(
                "",
                LocalImageNegativePromptSource.EMPTY
            ),
            imageProfileBindingFingerprint = "5".repeat(64),
            promptLanguageBindingFingerprint = "2".repeat(64)
        )
        assertEquals("", clearedDefault.effectiveNegativePrompt)
        assertEquals(LocalImageNegativePromptSource.EMPTY, clearedDefault.negativePromptSource)

        val capturedUser = translatedExecution()
        val reboundUser = capturedUser.rebindToCurrentImageProfile(
            finalNegativePrompt = LocalImageFinalNegativePrompt(
                requireNotNull(capturedUser.originalNegativePrompt),
                LocalImageNegativePromptSource.USER
            ),
            imageProfileBindingFingerprint = "6".repeat(64),
            promptLanguageBindingFingerprint = capturedUser.promptLanguageBindingFingerprint
        )
        assertEquals("people", reboundUser.effectiveNegativePrompt)
        assertEquals(LocalImageNegativePromptSource.USER, reboundUser.negativePromptSource)
    }

    @Test
    fun `main activity prompt binding rejects missing and tampered worker metadata`() {
        val profile = requireNotNull(
            ImageExecutionProfileResolver.legacyBuiltInProfileForCompatibility(
                recommendationId = "dreamshaper_sd15_qnn228",
                modelFingerprint = "a".repeat(64)
            )
        )
        val evidence = LocalImagePromptExecution(
            originalPrompt = "studio portrait",
            effectivePrompt = "studio portrait",
            originalNegativePrompt = null,
            effectiveNegativePrompt = profile.defaults.defaultNegativePrompt.orEmpty(),
            negativePromptSource = if (profile.defaults.defaultNegativePrompt == null) {
                LocalImageNegativePromptSource.EMPTY
            } else {
                LocalImageNegativePromptSource.MODEL_DEFAULT
            },
            method = LocalImagePromptTransformationMethod.DIRECT,
            imageProfileBindingFingerprint = profile.bindingFingerprint,
            promptLanguageBindingFingerprint = profile.promptLanguageBindingFingerprint
        )
        val execution = JSONObject()
            .put("imageProfileBindingFingerprint", profile.bindingFingerprint)
            .put("promptLanguageBindingFingerprint", profile.promptLanguageBindingFingerprint)
            .put(
                "textEncoderLanguageCapability",
                LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT.name
            )
            .put(
                "promptExecutionSha256",
                com.muyuchat.api.local.imagePromptExecutionSha256(
                    evidence.effectivePrompt,
                    evidence.effectiveNegativePrompt
                )
            )
        val nativePromptSha256 = com.muyuchat.api.local.imagePromptExecutionSha256(
            evidence.effectivePrompt,
            evidence.effectiveNegativePrompt
        )
        execution
            .put("nativePromptExecutionSha256", nativePromptSha256)
            .put("nativePromptBindingStage", "conditioning_consumed")
            .put(
                "nativeEffective",
                JSONObject()
                    .put("nativePromptExecutionSha256", nativePromptSha256)
                    .put("nativePromptBindingStage", "conditioning_consumed")
            )
        validateLocalImagePromptExecutionBinding(evidence, profile, execution.toString())

        listOf(
            "imageProfileBindingFingerprint",
            "promptLanguageBindingFingerprint",
            "textEncoderLanguageCapability",
            "promptExecutionSha256"
        ).forEach { field ->
            assertThrows(IllegalArgumentException::class.java) {
                validateLocalImagePromptExecutionBinding(
                    evidence,
                    profile,
                    JSONObject(execution.toString()).apply { remove(field) }.toString()
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                validateLocalImagePromptExecutionBinding(
                    evidence,
                    profile,
                    JSONObject(execution.toString()).put(field, 7).toString()
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateLocalImagePromptExecutionBinding(
                evidence,
                profile,
                JSONObject(execution.toString())
                    .put("promptExecutionSha256", "b".repeat(64))
                    .toString()
            )
        }
        listOf("nativePromptExecutionSha256", "nativePromptBindingStage").forEach { field ->
            assertThrows(IllegalArgumentException::class.java) {
                validateLocalImagePromptExecutionBinding(
                    evidence,
                    profile,
                    JSONObject(execution.toString()).apply { remove(field) }.toString()
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                validateLocalImagePromptExecutionBinding(
                    evidence,
                    profile,
                    JSONObject(execution.toString()).apply {
                        getJSONObject("nativeEffective").put(field, "b".repeat(64))
                    }.toString()
                )
            }
        }
    }

    @Test
    fun `native conditioner binds Chinese UTF-8 prompts and rejects swapped or forged evidence`() {
        val prompt = "\u4e00\u53ea\u7ea2\u8272\u676f\u5b50\u653e\u5728\u84dd\u8272\u684c\u5b50\u4e0a"
        val negativePrompt = "\u4e0d\u8981\u4eba\u7269\uff0c\u4e0d\u8981\u6587\u5b57"
        val expectedSha256 = com.muyuchat.api.local.imagePromptExecutionSha256(
            prompt,
            negativePrompt
        )
        val source = JSONObject()
            .put("promptWeightingApplied", false)
            .put("positiveWeightedTokenCount", 0)
            .put("negativeWeightedTokenCount", 0)
            .put("promptWeightFingerprint", "a".repeat(64))
            .put("nativePromptExecutionSha256", expectedSha256)
            .put("nativePromptBindingStage", "conditioning_encoded")

        val evidence = requireNativePromptEncodingEvidence(
            source = source,
            prompt = prompt,
            negativePrompt = negativePrompt,
            requireNoAppliedWeights = true
        )
        assertEquals(expectedSha256, evidence.nativePromptExecutionSha256)
        assertEquals("conditioning_encoded", evidence.nativePromptBindingStage)

        listOf(
            JSONObject(source.toString()).put("nativePromptExecutionSha256", "b".repeat(64)),
            JSONObject(source.toString()).put("nativePromptBindingStage", "conditioning_consumed"),
            JSONObject(source.toString()).put("promptWeightingApplied", true),
            JSONObject(source.toString()).put("positiveWeightedTokenCount", 1),
            JSONObject(source.toString()).put("promptWeightFingerprint", "A".repeat(64))
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                requireNativePromptEncodingEvidence(
                    source = invalid,
                    prompt = prompt,
                    negativePrompt = negativePrompt,
                    requireNoAppliedWeights = true
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireNativePromptEncodingEvidence(
                source = source,
                prompt = negativePrompt,
                negativePrompt = prompt,
                requireNoAppliedWeights = true
            )
        }
    }

    @Test
    fun `legacy direct and native evidence remains readable while migrating null to explicit empty`() {
        listOf(
            LocalImagePromptTransformationMethod.DIRECT to "plain English",
            LocalImagePromptTransformationMethod.NATIVE_MULTILINGUAL to "原生中文"
        ).forEach { (method, prompt) ->
            val legacy = JSONObject()
                .put("version", 2)
                .put("originalPrompt", prompt)
                .put("effectivePrompt", prompt)
                .put("originalNegativePrompt", JSONObject.NULL)
                .put("effectiveNegativePrompt", JSONObject.NULL)
                .put("method", method.name)
                .put("imageProfileBindingFingerprint", "d".repeat(64))

            val restored = requireNotNull(LocalImagePromptExecution.fromJsonOrNull(legacy))
            assertEquals("", restored.effectiveNegativePrompt)
            assertEquals(LocalImageNegativePromptSource.EMPTY, restored.negativePromptSource)
            assertEquals("d".repeat(64), restored.promptLanguageBindingFingerprint)
            assertEquals(4, restored.toJson().getInt("version"))
        }
    }

    @Test
    fun `model default negative survives omitted seed batch children history and retry`() {
        val execution = LocalImagePromptExecution(
            originalPrompt = "studio portrait",
            effectivePrompt = "studio portrait",
            originalNegativePrompt = null,
            effectiveNegativePrompt = "blur, artifacts",
            negativePromptSource = LocalImageNegativePromptSource.MODEL_DEFAULT,
            method = LocalImagePromptTransformationMethod.DIRECT,
            imageProfileBindingFingerprint = "1".repeat(64),
            promptLanguageBindingFingerprint = "2".repeat(64)
        )
        val plan = planLocalImageBatch(
            parentRequestId = "prompt-batch",
            runtime = LocalImageRuntime.QNN_HTP,
            requestedOptions = LocalImageGenerationOptions(batchCount = 3, seed = null),
            randomBaseSeed = { 91 }
        )
        val model = LocalImageModelRecord(
            id = "prompt-model",
            displayName = "Prompt Model",
            path = "missing.bin",
            fileName = "missing.bin",
            sizeBytes = 1,
            sha256 = "3".repeat(64),
            runtime = LocalImageRuntime.QNN_HTP
        )
        val spec = ImageGenerationJobSpec(
            prompt = execution.originalPrompt,
            backend = ImageBackend.LOCAL,
            localModelSnapshot = model,
            modelId = model.id,
            modelName = model.displayName,
            inputDraft = LocalImageInputDraft(),
            options = plan.parentOptions,
            promptExecution = execution
        )
        val child = plan.requests[1]

        val childOptions = spec.effectiveOptions(child.options)
        assertEquals(92, childOptions.seed)
        assertEquals(1, childOptions.batchCount)
        assertEquals("blur, artifacts", childOptions.negativePrompt)

        val restored = requireNotNull(
            ImageGenerationHistoryMetadata.fromJsonOrNull(
                spec.toHistoryMetadata()
                    .forBatchOutput(child.outputLineages.single())
                    .toJsonString()
            )
        )
        assertNull(restored.options.negativePrompt)
        assertEquals(92, restored.options.seed)
        assertEquals(execution, restored.promptExecution)

        val retry = spec.copy(options = restored.options, promptExecution = restored.promptExecution)
        assertEquals("blur, artifacts", retry.effectiveOptions().negativePrompt)
        assertEquals(execution.promptLanguageBindingFingerprint,
            retry.promptExecution?.promptLanguageBindingFingerprint
        )
    }

    @Test
    fun `direct and native evidence cannot rewrite captured text`() {
        listOf(
            LocalImagePromptTransformationMethod.DIRECT to "plain English",
            LocalImagePromptTransformationMethod.NATIVE_MULTILINGUAL to "原生中文"
        ).forEach { (method, original) ->
            val evidence = LocalImagePromptExecution(
                originalPrompt = original,
                effectivePrompt = original,
                originalNegativePrompt = null,
                effectiveNegativePrompt = "",
                negativePromptSource = LocalImageNegativePromptSource.EMPTY,
                method = method,
                imageProfileBindingFingerprint = "b".repeat(64),
                promptLanguageBindingFingerprint = "c".repeat(64)
            )
            assertNull(
                LocalImagePromptExecution.fromJsonOrNull(
                    evidence.toJson().put("effectivePrompt", "rewritten")
                )
            )
        }
    }

    private fun translatedExecution(
        originalPrompt: String = "一只红色杯子",
        effectivePrompt: String = "one red cup",
        originalNegativePrompt: String? = "不要人物",
        effectiveNegativePrompt: String = "people",
        negativePromptSource: LocalImageNegativePromptSource = LocalImageNegativePromptSource.USER,
        imageProfileBindingFingerprint: String = "c".repeat(64),
        promptLanguageBindingFingerprint: String = "e".repeat(64),
        translatorRuntime: String = "LLAMA_CPP",
        translatorModelSha256: String = "d".repeat(64)
    ): LocalImagePromptExecution {
        val planSha = "1".repeat(64)
        val receiptSha = "2".repeat(64)
        val translationSystemSha = "3".repeat(64)
        val verificationSystemSha = "4".repeat(64)
        val proof = imagePromptTranslationProofFingerprint(
            contractVersion = CURRENT_LOCAL_IMAGE_PROMPT_TRANSLATION_CONTRACT_VERSION,
            originalPrompt = originalPrompt,
            effectivePrompt = effectivePrompt,
            originalNegativePrompt = originalNegativePrompt,
            effectiveNegativePrompt = effectiveNegativePrompt,
            negativePromptSource = negativePromptSource.name,
            translationPlanSha256 = planSha,
            verificationReceiptSha256 = receiptSha,
            translationPhaseSystemPromptSha256 = translationSystemSha,
            verificationPhaseSystemPromptSha256 = verificationSystemSha,
            translatorRuntime = translatorRuntime,
            translatorModelSha256 = translatorModelSha256,
            promptLanguageBindingFingerprint = promptLanguageBindingFingerprint
        )
        return LocalImagePromptExecution(
            originalPrompt = originalPrompt,
            effectivePrompt = effectivePrompt,
            originalNegativePrompt = originalNegativePrompt,
            effectiveNegativePrompt = effectiveNegativePrompt,
            negativePromptSource = negativePromptSource,
            method = LocalImagePromptTransformationMethod.LOCAL_LLM_ZH_TO_EN,
            imageProfileBindingFingerprint = imageProfileBindingFingerprint,
            promptLanguageBindingFingerprint = promptLanguageBindingFingerprint,
            translatorModelId = "translator-id",
            translatorModelName = "Translator",
            translatorRuntime = translatorRuntime,
            translatorModelSha256 = translatorModelSha256,
            translationPlanSha256 = planSha,
            verificationReceiptSha256 = receiptSha,
            translationPhaseSystemPromptSha256 = translationSystemSha,
            verificationPhaseSystemPromptSha256 = verificationSystemSha,
            translationProofFingerprint = proof
        )
    }

    private fun translationJson(prompt: String, negativePrompt: String?): String = JSONObject()
        .put("prompt", prompt)
        .put("negative_prompt", negativePrompt ?: JSONObject.NULL)
        .put(
            "contract_canary_prompt",
            "one red cup on a blue table, two green apples to the left of the cup"
        )
        .put("contract_canary_negative_prompt", "people, text, extra fruit")
        .toString()
}
