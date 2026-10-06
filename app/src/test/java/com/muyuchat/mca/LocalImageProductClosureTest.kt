package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.muyuchat.mca.presentation.toLocalImagePreviewOptions

class LocalImageProductClosureTest {
    @Test
    fun `UI request identity reaches coordinator and worker while API keeps its own identity`() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val ui = functionBody(sourceFile("app/src/main/java/com/muyuchat/mca/ImageGenerationOwner.kt"), "fun submit(")
        // The publish half moved to its own file. Reading it through `source` used to pass for the
        // wrong reason: the wrapper left behind is an expression body, so the extractor walked past it
        // into an unrelated declaration and the assertions below never saw the real code.
        val localAsset = functionBody(
            sourceFile("app/src/main/java/com/muyuchat/mca/CreateLocalGeneratedImageAssetOperations.kt"),
            "suspend fun createLocalGeneratedImageAsset("
        )
        val api = functionBody(sourceFile("app/src/main/java/com/muyuchat/mca/presentation/LocalApiImageGenerationOperations.kt"), "internal suspend fun generateLocalApiImageOperations(")

        assertTrue(ui.contains("val jobId = requestedJobId ?: \"ui-img-\${UUID.randomUUID()}\""))
        assertTrue(ui.contains("tryAcquireObservedImageGenerationLease(jobId)"))
        assertTrue(ui.contains("parentRequestId = jobId"))
        assertTrue(ui.contains("requestId = child.requestId"))
        assertTrue(localAsset.contains("requestId = requestId"))
        assertFalse(localAsset.contains("requestId = \"ui-img-"))
        assertTrue(api.contains("tryAcquireObservedImageGenerationLease(requestId)"))
        assertTrue(api.contains("requestId = requestId"))
    }

    @Test
    fun `requested image model is snapshotted and API model never falls through to selection`() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val ui = functionBody(sourceFile("app/src/main/java/com/muyuchat/mca/ImageGenerationOwner.kt"), "fun submit(")
        val api = functionBody(sourceFile("app/src/main/java/com/muyuchat/mca/presentation/LocalApiImageGenerationOperations.kt"), "internal suspend fun generateLocalApiImageOperations(")
        val explicitModelBranch = api.substring(
            api.indexOf("if (requestedModel.isNotBlank())"),
            api.indexOf("} else {", api.indexOf("if (requestedModel.isNotBlank())"))
        )

        assertTrue(ui.contains("val requestedLocalModel"))
        assertTrue(ui.contains("model = model"))
        assertTrue(ui.contains("val model = requireNotNull(requestedLocalModel)"))
        assertTrue(source.contains("rejectImageModelSwitchWhileGenerationIsActive()"))
        assertTrue(explicitModelBranch.contains("resolveLocalImageApiModel("))
        assertTrue(explicitModelBranch.contains("requestedModelId = requestedModel"))
        assertTrue(explicitModelBranch.contains("code = \"image_model_not_found\""))
        assertFalse(explicitModelBranch.contains("selectedLocalImageModel()"))
        assertTrue(api.contains("supportsAuthenticatedLocalImageCount(model.runtime, request.imageCount)"))
        assertTrue(api.contains("code = \"unsupported_image_count\""))
        assertTrue(api.contains("httpStatus = 422"))
        assertTrue(api.contains("require(orderedOutputs.size == request.imageCount)"))
        assertFalse(api.contains("request.copy(imageCount = 1)"))
    }

    @Test
    fun `generated image publication is atomic and leaves no staging file`() {
        val root = Files.createTempDirectory("image-asset-atomic").toFile()
        try {
            val bytes = byteArrayOf(1, 2, 3, 4)
            val output = writeImageAssetBytesAtomically(
                directory = root,
                fileName = "result.png",
                bytes = bytes,
                parentDirectorySyncer = ParentDirectorySyncer { }
            )

            assertTrue(output.isFile)
            assertArrayEquals(bytes, output.readBytes())
            assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `worker cancellation becomes a non-failure terminal UI state`() {
        val viewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val activity = sourceFile("app/src/main/java/com/muyuchat/mca/MainActivity.kt") + sourceFile("app/src/main/java/com/muyuchat/mca/MainChatScreenActions.kt") +
            sourceFile("app/src/main/java/com/muyuchat/mca/ChatUiStateMapping.kt")
        val chat = sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreen.kt") + // P9/S2: ChatUiItemModels.kt 承接原 ChatScreen.kt 的条目模型与小助手（多源拼接随迁）。
sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ImageWorkspaceScreens.kt") + sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ImageLibrarySupportScreens.kt") + sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatUiItemModels.kt")
        // ImageGenerationStatusRecord's entries moved to PersistedRecords.kt during P2, so the
        // first assertion reads from there. The other four read function bodies that stayed in
        // the ViewModel, an expression in MainActivity and one in ChatScreen. No assertion
        // changed -- only the file the first one reads.
        val persistedRecords = sourceFile("app/src/main/java/com/muyuchat/mca/PersistedRecords.kt")
        val generate = functionBody(sourceFile("app/src/main/java/com/muyuchat/mca/ImageGenerationOwner.kt"), "fun submit(")

        assertTrue(persistedRecords.contains("CANCELLED(\"已取消\", terminal = true)"))
        assertTrue(generate.contains("error is LocalImageWorkerCancelledException"))
        assertTrue(generate.contains("ImageGenerationStatusRecord.CANCELLED"))
        assertTrue(activity.contains("terminal = job.status.terminal"))
        assertTrue(chat.contains("get() = !terminal"))
    }

    @Test
    fun `image parameters reload per selected model and preserve explicit empty negative prompt`() {
        val source = sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreen.kt") + sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ImageWorkspaceScreens.kt") + sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatUiItemModels.kt") + // P9/S30: 授权/触发器簇移出 ChatScreen.kt，拼接表随迁。
sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatImageGenerationSupport.kt")
        val enqueue = functionBody(source, "fun enqueueImagePrompt(")

        assertTrue(source.contains("IMAGE_GENERATION_UI_PARAMETER_PREFS"))
        assertTrue(source.contains("IMAGE_GENERATION_UI_PARAMETER_KEY_PREFIX + modelId"))
        assertTrue(source.contains("ImageGenerationUiParameterSnapshot.fromJsonOrNull("))
        assertTrue(source.contains("restoredImageParameterModelId != modelId"))
        listOf(
            "negativePrompt = imageNegativePrompt",
            "batchCount = imageBatchCount",
            "widthText = imageWidthText",
            "heightText = imageHeightText",
            "stepsText = imageStepsText",
            "cfgScaleText = imageCfgScaleText",
            "seedText = imageSeedText",
            "sampler = imageSampler",
            "inputImageUri = imageInputUri",
            "maskImageUri = imageMaskUri",
            "controlImageUri = imageControlUri"
        ).forEach { field -> assertTrue("Missing persisted field: $field", source.contains(field)) }
        assertTrue(source.contains("ActivityResultContracts.OpenDocument()"))
        assertTrue(source.contains("takePersistableUriPermission("))
        assertTrue(source.contains("persistedGenerationImageUriOrNull(context"))
        assertTrue(source.contains("openFileDescriptor(uri, \"r\")"))
        assertTrue(source.contains("已被删除或读取权限已失效"))
        assertTrue(enqueue.contains("?: if (imageDisableModelNegativePrompt) \"\" else null"))
    }

    @Test
    fun `QNN conditioning digest is derived from the bytes consumed by the graph`() {
        val source = sourceFile("core/native/src/main/cpp/qnn_native_bridge.cpp")
        val isolatedSdxl = sourceFile("core/native/src/main/cpp/qnn_sdxl_isolated_phases.hpp")
        val mnn = sourceFile("core/native/src/main/cpp/mnn_native_engine.cpp")
        val provider = sourceFile("app/src/main/java/com/muyuchat/mca/LocalImageProvider.kt")
        // P5/L6: the QNN semantic-defaults JSON builder moved to LocalImageNativeConfigSupport.kt;
        // that one assertion reads the body where it now lives (everything else stays in provider).
        val nativeConfigSupport =
            sourceFile("app/src/main/java/com/muyuchat/mca/LocalImageNativeConfigSupport.kt")
        // P5/L8: the sdxlQnnConditioningGraphSha256 call moved with the generate() body.
        val generation =
            sourceFile("app/src/main/java/com/muyuchat/mca/LocalImageGenerationOperations.kt")
        // P7/S31: the durable request lease moved to SdxlTwoPhaseRequestLease.kt; reading it here
        // keeps the same contract under test -- the assertions below are unchanged.
        val coordinator = sourceFile("app/src/main/java/com/muyuchat/mca/SdxlTwoPhaseCoordinator.kt") +
            sourceFile("app/src/main/java/com/muyuchat/mca/SdxlTwoPhaseCoordinatorPhaseMerge.kt") +
            sourceFile("app/src/main/java/com/muyuchat/mca/SdxlTwoPhaseRequestLease.kt")
        val phaseProtocol = sourceFile("app/src/main/java/com/muyuchat/mca/SdxlImagePhaseProtocol.kt")
        val normalizedSource = source.replace("\r\n", "\n")
        val normalizedIsolatedSdxl = isolatedSdxl.replace("\r\n", "\n")
        val resolver = functionBody(source, "bool resolve_qnn_conditioning_evidence(")
        val runtimeSelector = functionBody(source, "bool select_qnn_runtime_profile_for_context(")

        assertTrue(source.contains("qnn_consumed_payload_sha256("))
        assertTrue(source.contains("consumed_conditioning_artifact_sha256"))
        assertTrue(resolver.contains("consumed_artifact_sha256"))
        assertTrue(resolver.contains("evidence->conditioning_artifact_sha256"))
        assertTrue(
            normalizedSource.contains(
                "native_evidence.conditioning_artifact_sha256 =\n" +
                    "            conditioning_evidence.conditioning_artifact_sha256;"
            )
        )
        assertTrue(
            normalizedIsolatedSdxl.contains(
                "native_evidence.conditioning_artifact_sha256 =\n" +
                    "        conditioning_evidence.conditioning_artifact_sha256;"
            )
        )
        assertTrue(mnn.contains("external_mnn_sdxl_embeddings"))
        assertTrue(mnn.contains("clip.mnn+clip_2.mnn"))
        assertTrue(generation.contains("sdxlQnnConditioningGraphSha256(conditioningRoot)"))
        assertTrue(nativeConfigSupport.contains("put(\"vaeEncoderContextSha256\", encoderContext.sha256Contents())"))
        assertTrue(source.contains("context_binary_sha256 = mca::qnn::controlnet::sha256_hex_bytes("))
        assertTrue(isolatedSdxl.contains("expected_encoder_context_sha256"))
        assertTrue(isolatedSdxl.contains("encoderContextSha256"))
        assertTrue(resolver.contains("format == \"sdxl_qnn_conditioning\""))
        assertTrue(source.contains("qnn_sdxl_conditioning_graph_sha256("))
        assertTrue(source.contains("clip_2.mnn.weight"))
        assertTrue(mnn.contains("result[\"promptWeightFingerprint\"] = conditioningArtifactSha256"))
        assertTrue(
            resolver.contains(
                "strict_external_conditioning_contract || format == \"sdxl_qnn_conditioning\""
            )
        )
        assertTrue(isolatedSdxl.contains("native_evidence.conditioning_graph_sha256"))
        assertTrue(isolatedSdxl.contains("native_evidence.conditioning_artifact_consumed"))
        assertTrue(
            isolatedSdxl.contains(
                "\\\"runtimeSessionMode\\\":\\\"isolated_unet_phase\\\""
            )
        )
        assertTrue(phaseProtocol.contains("SDXL_ISOLATED_UNET_VAE_MODE = \"isolated_unet_then_vae\""))
        assertTrue(
            phaseProtocol.contains(
                "SDXL_ISOLATED_ENCODER_UNET_VAE_MODE = \"isolated_encoder_then_unet_then_vae\""
            )
        )
        assertTrue(coordinator.contains("SDXL_ISOLATED_UNET_VAE_MODE"))
        assertTrue(coordinator.contains("SDXL_ISOLATED_ENCODER_UNET_VAE_MODE"))
        assertFalse(coordinator.contains("same_transport"))
        assertTrue(runtimeSelector.contains("int preferred_htp_arch"))
        // A known context/device pair is an exact admission constraint.  A
        // caller preference may only be used when it matches the required
        // architecture; the selector then appends that required arch.
        assertTrue(runtimeSelector.contains("const int required_arch"))
        assertTrue(runtimeSelector.contains("preferred_htp_arch == required_arch"))
        assertTrue(runtimeSelector.contains("append_arch(required_arch)"))
        assertTrue(isolatedSdxl.contains("expected_profile"))
        assertEquals(3, Regex("expected_profile\\)\\)").findAll(isolatedSdxl).count())
    }

    @Test
    fun `SDXL conditioning graph fingerprint covers required and present external weights`() {
        val root = Files.createTempDirectory("sdxl-conditioning-closure").toFile()
        try {
            val names = listOf(
                "clip.mnn",
                "clip.mnn.weight",
                "clip_2.mnn",
                "clip_2.mnn.weight"
            )
            names.forEachIndexed { index, name ->
                File(root, name).writeBytes(byteArrayOf(index.toByte(), (index + 17).toByte()))
            }
            fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString(separator = "") { byte ->
                    "%02x".format(byte.toInt() and 0xff)
                }
            val payload = names.joinToString(separator = "\n", postfix = "\n") { name ->
                "$name=${sha256(File(root, name).readBytes())}"
            }
            assertEquals(
                sha256(payload.toByteArray(Charsets.UTF_8)),
                sdxlQnnConditioningGraphSha256(root)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `shared external MNN evidence is artifact bound while legacy bundles stay discoverable`() {
        val provider = sourceFile("app/src/main/java/com/muyuchat/mca/LocalImageProvider.kt")
        // P5/L8: the shared-MNN evidence assembly moved with the generate() body.
        val generation =
            sourceFile("app/src/main/java/com/muyuchat/mca/LocalImageGenerationOperations.kt")
        val integration = sourceFile(
            "app/src/main/java/com/muyuchat/mca/LocalImageExecutionProfileIntegration.kt"
        )
        val kotlinBridge = sourceFile(
            "core/native/src/main/java/com/muyuchat/core/nativebridge/NativeMnnDiffusionBridge.kt"
        )
        val debugSmoke = sourceFile(
            "app/src/debug/java/com/muyuchat/mca/debug/LocalImageSmokeActivity.kt"
        )
        val qnn = sourceFile("core/native/src/main/cpp/qnn_native_bridge.cpp")
        val mnn = sourceFile("core/native/src/main/cpp/mnn_native_engine.cpp")
        val readinessProfile = functionBody(
            integration,
            "internal fun resolveEffectiveLocalImageManifestProfile("
        )
        val encoder = functionBody(mnn, "json encode_community_clip_embeddings_to_file(")
        val qnnEvidence = functionBody(qnn, "bool resolve_qnn_conditioning_evidence(")
        val jni = functionBody(
            mnn,
            "Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_encodeSd15PromptEmbeddings("
        )

        assertTrue(generation.contains("conditioningContractMode\", \"shared_unet_vae"))
        assertTrue(generation.contains("embeddingJson.optString(\"promptWeightFingerprint\")"))
        assertTrue(generation.contains("bundleRoot.safeDescendantOrNull(conditioningGraphPath)"))
        assertTrue(readinessProfile.contains("persisted.modelFingerprint"))
        assertFalse(readinessProfile.contains("sha256ForProfile"))
        assertTrue(encoder.contains("if (useCfg)"))
        assertTrue(encoder.contains("useCfg ? 2 : 1"))
        assertTrue(encoder.contains("useCfg ? ids.size() : pos_ids.size()"))
        assertTrue(encoder.contains("result[\"promptWeightFingerprint\"] = conditioningArtifactSha256"))
        assertTrue(jni.contains("jstring conditioningOrder"))
        assertFalse(jni.contains("jstring tokenEmbeddingMode"))
        assertTrue(kotlinBridge.contains("conditioningOrder: String"))
        assertFalse(kotlinBridge.contains("tokenEmbeddingMode: String"))
        assertTrue(debugSmoke.contains("val conditioningOrder = if"))
        assertFalse(debugSmoke.contains(".put(\"tokenEmbeddingMode\""))
        assertTrue(qnnEvidence.contains("strict_external_conditioning_contract"))
        assertTrue(qnnEvidence.contains("qnn_file_sha256(conditioning_graph_path"))
        assertTrue(qnnEvidence.contains("reported_fingerprint != evidence->conditioning_artifact_sha256"))
        assertTrue(qnnEvidence.contains("Old community packages did not publish"))
    }

    @Test
    fun `debug image semantic harness never treats manifest json as executable model`() {
        val debugSmoke = sourceFile(
            "app/src/debug/java/com/muyuchat/mca/debug/LocalImageSmokeActivity.kt"
        )

        assertTrue(debugSmoke.contains("val manifestFile = File(root, \"manifest.json\").canonicalFile"))
        assertTrue(debugSmoke.contains("requested?.takeUnless { it.canonicalFile == manifestFile }"))
        assertTrue(debugSmoke.contains("val declaredPrimary = manifest?.primaryFile"))
        assertTrue(debugSmoke.contains("val smokePrimary = manifest"))
    }

    @Test
    fun `debug QNN smoke treats compatibility metadata as advisory and publishes runtime evidence`() {
        val debugSmoke = sourceFile(
            "app/src/debug/java/com/muyuchat/mca/debug/LocalImageSmokeActivity.kt"
        )
        val graph = functionBody(debugSmoke, "private fun runQnnImageSmoke(")
        val semantic = functionBody(debugSmoke, "private fun runQnnSemanticGenerate(")

        assertTrue(graph.contains("val productReady = result.optBoolean(\"ok\") && smokeResult.provesNpuExecution"))
        assertFalse(graph.contains("compatibilityMessage == null"))
        assertFalse(graph.contains("result.put(\"ok\", false)"))
        assertTrue(graph.contains("result.put(\"compatibilityAdvisory\", compatibilityMessage != null)"))
        assertTrue(graph.contains("result.put(\"compatibilityBlocked\", false)"))
        listOf("runtimeDirs", "runtimeInspection").forEach { field ->
            assertTrue("Graph smoke must publish $field", graph.contains(".put(\"$field\""))
            assertTrue("Semantic smoke must publish $field", semantic.contains(".put(\"$field\""))
        }
        assertTrue(semantic.contains(".put(\"selectedHtpArch\", selectedHtpArch)"))
        assertTrue(semantic.contains(".put(\"transportHtpArch\", transportHtpArch)"))
        assertTrue(semantic.contains("result.put(\"selectedHtpArch\", selectedHtpArch)"))
        assertTrue(semantic.contains("result.put(\"transportHtpArch\", transportHtpArch)"))
    }

    @Test
    fun `retry and history recreation retain immutable model inputs and generation parameters`() {
        val viewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val activity = sourceFile("app/src/main/java/com/muyuchat/mca/MainActivity.kt") + sourceFile("app/src/main/java/com/muyuchat/mca/MainChatScreenActions.kt") +
            sourceFile("app/src/main/java/com/muyuchat/mca/ChatUiStateMapping.kt")
        // P9/S23: the result card's retry label lives in ImageAssetScreens.kt now, so the reader
        // concatenates it too; the immutable-input contract it pins is unchanged.
        val chat = sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreen.kt") + sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ImageWorkspaceScreens.kt") + sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ImageAssetScreens.kt") + sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatUiItemModels.kt") + // P9/S30: 授权/触发器簇移出 ChatScreen.kt，拼接表随迁。
sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatImageGenerationSupport.kt")
        // ImageGenerationJobRecord moved to PersistedRecords.kt during P2, so the one assertion
        // about its field reads from there. The other four read function bodies that stayed in
        // the ViewModel. No assertion changed -- only the file each one reads.
        val persistedRecords = sourceFile("app/src/main/java/com/muyuchat/mca/PersistedRecords.kt")
        val retry = functionBody(viewModel, "fun retryImageGeneration(")
        // Re-creation moved to ImageAssetCreationOperations.kt; the ViewModel keeps a forwarder.
        val recreate = functionBody(
            sourceFile("app/src/main/java/com/muyuchat/mca/ImageAssetCreationOperations.kt"),
            "internal fun recreateAsset("
        )

        assertTrue(persistedRecords.contains("val spec: ImageGenerationJobSpec? = null"))
        assertTrue(retry.contains("jobSnapshot = snapshot"))
        assertTrue(recreate.contains("it.id == history.modelId"))
        assertTrue(recreate.contains("jobSnapshot = snapshot"))
        assertTrue(recreate.contains("history.requiredContentInputReferences()"))
        assertTrue(recreate.contains("canReadGenerationHistoryInput(reference)"))
        assertTrue(
            recreate.indexOf("canReadGenerationHistoryInput(reference)") <
                recreate.indexOf("enqueueImageGeneration(")
        )
        assertTrue(activity.contains("generationDetails = generation?.displayDetails().orEmpty()"))
        assertTrue(activity.contains("generationHistoryInputUris = generationHistoryInputUris"))
        assertTrue(activity.contains("retainedGenerationImageContentReferences("))
        assertTrue(activity.contains("state.imageJobs.mapNotNull { job -> job.spec?.inputDraft }"))
        assertTrue(chat.contains("libraryHistoryReferencedUris"))
        assertTrue(chat.contains("onRetryImageGeneration(job.id)"))
        assertTrue(chat.contains("按原参数再生成"))
    }

    @Test
    fun `image editor snapshots one external stream per operation and bounds every private PNG`() {
        val adapter = sourceFile(
            "feature/chat/src/main/java/com/muyuchat/feature/chat/ImageGenerationBitmapEditing.kt"
        )
        val editor = sourceFile(
            "feature/chat/src/main/java/com/muyuchat/feature/chat/ImageGenerationEditor.kt"
        )
        val dimensionProbe = functionBody(adapter, "fun probeDimensionsBounded(")
        val decoder = functionBody(adapter, "fun decodeBounded(")
        val cropEditor = functionBody(editor, "private fun CropEditor(")
        val maskOverlay = functionBody(editor, "private fun MaskStrokeOverlay(")

        assertEquals(1, Regex("contentResolver\\.openInputStream\\(uri\\)").findAll(dimensionProbe).count())
        assertEquals(1, Regex("contentResolver\\.openInputStream\\(uri\\)").findAll(decoder).count())
        assertFalse(adapter.contains("openAssetFileDescriptor("))
        assertFalse(adapter.contains("openFileDescriptor("))
        assertTrue(adapter.contains("copyGenerationImageSnapshot("))
        assertTrue(adapter.contains("BitmapFactory.decodeFile(snapshot.absolutePath"))
        assertTrue(adapter.contains("ExifInterface(snapshot.absolutePath)"))
        assertTrue(adapter.contains("temporaryFile.length()"))
        assertTrue(adapter.contains("catch (_: AtomicMoveNotSupportedException)"))
        assertTrue(adapter.contains("StandardCopyOption.REPLACE_EXISTING"))
        assertTrue(editor.contains("checkCancelled = { decodingContext.ensureActive() }"))
        assertTrue(cropEditor.contains("clampToImage = false"))
        assertTrue(maskOverlay.contains("state.baseGrayscaleValue == ImageMaskStrokeMode.BRUSH.grayscaleValue"))
        assertTrue(maskOverlay.contains("clipRect("))
        assertTrue(maskOverlay.contains("topLeft = Offset(imageBounds.left.toFloat(), imageBounds.top.toFloat())"))
        assertTrue(editor.contains("private fun PositionedMaskDrawingDialog("))
        assertTrue(editor.contains("Text(\"在当前位置应用\")"))
        assertTrue(editor.contains("maskState.applyPointAtCursor("))
        assertFalse(adapter.contains("catch (error: Throwable)"))
        assertFalse(editor.contains("catch (saveError: Throwable)"))
    }

    @Test
    fun `LoRA crosses UI and Local API by id while native success requires applied tensors`() {
        val activity = sourceFile("app/src/main/java/com/muyuchat/mca/MainActivity.kt") + sourceFile("app/src/main/java/com/muyuchat/mca/MainChatScreenActions.kt") +
            sourceFile("app/src/main/java/com/muyuchat/mca/ChatUiStateMapping.kt")
        val chat = sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreen.kt") + // P9/B1: 头部类型与常量簇移出 ChatScreen.kt，拼接表随迁。
sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreenHeadTypes.kt") + sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ImageWorkspaceScreens.kt") + sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatUiItemModels.kt")
        val api = sourceFile("api/local/src/main/java/com/muyuchat/api/local/ImageGenerationApiContract.kt")
        val bridge = sourceFile("core/sd-native/src/main/cpp/stable_diffusion_bridge.cpp")
        val uiItemStart = chat.indexOf("data class ImageLoraUiItem(")
        val uiItemEnd = chat.indexOf("data class ImageGenerationUiLoraSelection(", uiItemStart)
        require(uiItemStart >= 0 && uiItemEnd > uiItemStart)
        val uiItem = chat.substring(uiItemStart, uiItemEnd)

        assertTrue(chat.contains("data class ImageLoraUiItem("))
        assertFalse(uiItem.contains("path:"))
        assertTrue(activity.contains("?.toPrepared(selection.multiplier)"))
        assertTrue(api.contains("val loras: List<ImageGenerationApiLora>"))
        assertTrue(api.contains("private val LORA_FIELDS = setOf(\"id\", \"multiplier\")"))
        assertTrue(bridge.contains("gen.loras = native_loras.empty() ? nullptr : native_loras.data()"))
        assertTrue(bridge.contains("LORA_NATIVE_APPLY_INCOMPLETE"))
        assertTrue(bridge.contains("lora_applied_tensor_count == 0u"))
    }

    @Test
    fun `native preview progress reaches the existing generation canvas while synchronous API rejects it`() {
        val viewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val mainActivity = sourceFile("app/src/main/java/com/muyuchat/mca/MainActivity.kt") + sourceFile("app/src/main/java/com/muyuchat/mca/MainChatScreenActions.kt") +
            sourceFile("app/src/main/java/com/muyuchat/mca/ChatUiStateMapping.kt")
        // The call site stays in MainActivity; the mapping it calls moved to the presentation
        // package during P2, so the second assertion reads it from there. Neither assertion
        // changed -- only the file each one reads.
        val imageChoicePresentation =
            sourceFile("app/src/main/java/com/muyuchat/mca/presentation/ImageChoicePresentation.kt")
        val protocol = sourceFile("app/src/main/java/com/muyuchat/mca/LocalImageWorkerProtocol.kt")
        // P9/S22: the generation canvas and its preview bindings moved to
        // ImageGenerationResultScreens.kt, so the reader concatenates it too; the preview-transport
        // contract it pins is unchanged.
        val chat = sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreen.kt") + sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ImageWorkspaceScreens.kt") + sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ImageGenerationResultScreens.kt") + sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatUiItemModels.kt")
        val api = functionBody(sourceFile("app/src/main/java/com/muyuchat/mca/presentation/LocalApiImageGenerationOperations.kt"), "internal suspend fun generateLocalApiImageOperations(")

        assertTrue(mainActivity.contains("preview = uiOptions.toLocalImagePreviewOptions()"))
        assertTrue(imageChoicePresentation.contains("LocalImagePreviewMode.fromWireName(uiMode.wireName)"))
        assertTrue(viewModel.contains("publishLocalImagePreview(jobId, progress)"))
        assertTrue(protocol.contains(".put(\"previewPath\", progress.previewPath)"))
        assertTrue(protocol.contains("previewRevision = progress.optLong(\"previewRevision\")"))
        assertTrue(chat.contains("uriString = previewUriString.orEmpty()"))
        assertTrue(chat.contains("rememberChatImageBitmap("))
        assertTrue(chat.contains("实时预览 · 第 \$previewStep 步"))
        assertTrue(api.contains("code = \"unsupported_preview_transport\""))
        assertTrue(
            api.indexOf("if (request.preview != null)") <
                api.indexOf("tryAcquireObservedImageGenerationLease(requestId)")
        )
    }

    @Test
    fun `download registration selects the detected model and active model cannot be deleted`() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val bundleDownload = functionBody(source, "private fun observeManagedDownloads(")
        val delete = functionBody(source, "fun deleteLocalImageModel(")
        val selectChoice = functionBody(source, "fun selectImageGenerationModel(")

        assertTrue(bundleDownload.contains("val selection = settleLocalImageSelection(image)"))
        assertTrue(bundleDownload.contains("selectedLocalImageModelId = selection.selectedId"))
        assertTrue(bundleDownload.contains("selectedImageBackend = selection.selectedBackend"))
        assertTrue(delete.contains("activeImageGenerationModelId == modelId"))
        assertTrue(delete.contains("activeLocalApiImageModelId == modelId"))
        assertTrue(delete.contains("localImageModelStore.deleteModel(modelId)"))
        assertFalse(selectChoice.contains("saveSelectedBackend(ImageBackend.CLOUD)"))
    }

    @Test
    fun `SDXL phase client rejects stale terminals and retires late connections`() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/SdxlTwoPhaseCoordinatorPhaseClient.kt")
        val start = source.indexOf("internal class SdxlPhaseClient(")
        val end = source.indexOf("private const val SDXL_PHASE_EXIT_CONFIRM_TIMEOUT_MS", start)
        require(start >= 0 && end > start)
        val client = source.substring(start, end)

        assertTrue(
            client.split("envelope.requestId != requestId || envelope.phase != phase").size - 1 == 3
        )
        assertTrue(client.contains("closed || (!bound && !bindingRequested)"))
        assertTrue(client.contains("closedDuringBind"))
        assertTrue(client.contains("context.unbindService(connection)"))
        assertTrue(client.contains("serviceReady.completeExceptionally(failure)"))
        assertTrue(client.contains("TerminalOutcome.Failure -> result.completeExceptionally(outcome.error)"))
        assertTrue(client.contains("private suspend fun cancelAndAwaitExit(): Boolean"))
        assertTrue(client.contains("isolated worker exit was confirmed"))
        assertTrue(client.contains("worker_exit_unconfirmed"))
        assertTrue(client.contains("The exact Binder death was not confirmed"))
        assertFalse(client.contains("isolated worker was terminated"))
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            File(root, relativePath).takeIf(File::isFile)?.let {
                return it.readText(Charsets.UTF_8)
            }
            directory = root.parentFile
        }
        error("Unable to locate $relativePath")
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val openingParenthesis = source.indexOf('(', start)
        require(openingParenthesis >= 0) { "Missing function parameter list: $signature" }
        var parenthesisDepth = 0
        var closingParenthesis = -1
        for (index in openingParenthesis until source.length) {
            when (source[index]) {
                '(' -> parenthesisDepth += 1
                ')' -> {
                    parenthesisDepth -= 1
                    if (parenthesisDepth == 0) {
                        closingParenthesis = index
                        break
                    }
                }
            }
        }
        require(closingParenthesis >= 0) { "Unterminated function parameter list: $signature" }
        val openingBrace = source.indexOf('{', closingParenthesis)
        require(openingBrace >= 0) { "Missing function body: $signature" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unterminated function body: $signature")
    }
}
