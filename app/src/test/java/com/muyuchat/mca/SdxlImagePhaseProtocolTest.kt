package com.muyuchat.mca

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SdxlImagePhaseProtocolTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val conditioningArtifactSha256 = "d".repeat(64)
    private val encoderContextSha256 = "b".repeat(64)

    @Test
    fun `request v10 binds exact runtime roles separately from source artifact provenance`() {
        val params = contractParams(steps = 30)
        val request = SdxlImagePhaseRequest(
            requestId = "sdxl-1",
            phase = SdxlImagePhase.UNET,
            runtimeProfile = runtimeProfile(79),
            profileId = "generic.sdxl.test",
            profileRevision = 3,
            modelFingerprint = "a".repeat(64),
            steps = 30,
            width = 1024,
            height = 1024,
            bundleRoot = "/bundle",
            paramsJson = params.toString(),
            embeddingsPath = "/cache/conditioning.f32",
            latentPath = "/cache/latent.f32",
            metadataPath = "/cache/latent.json",
            outputPath = "",
            journalPath = "/cache/unet.json",
            conditioningArtifactSha256 = conditioningArtifactSha256
        )

        val encoded = SdxlImagePhaseProtocol.request(request)
        val parsed = SdxlImagePhaseProtocol.parseRequest(encoded)

        assertEquals(10, JSONObject(encoded).getInt("version"))
        assertEquals(SdxlImagePhase.UNET, parsed.phase)
        assertEquals(79, parsed.phaseHtpArch)
        assertEquals("/packaged-qnn", parsed.runtimeProfile.hostDirectory)
        assertEquals("/packaged-qnn", parsed.runtimeProfile.dspDirectory)
        assertEquals(0, parsed.sourceArtifactProducerHtpArch)
        assertFalse(JSONObject(encoded).has("expectedHtpArch"))
        assertFalse(JSONObject(encoded).has("runtimeDirsJson"))
        assertFalse(JSONObject(encoded).has("phaseHtpArch"))
        assertEquals(30, parsed.steps)
        assertEquals(1024, parsed.width)
        assertEquals("generic.sdxl.test", parsed.profileId)
        assertEquals(conditioningArtifactSha256, parsed.conditioningArtifactSha256)

        val tampered = JSONObject(SdxlImagePhaseProtocol.request(request)).put("steps", 1)
        assertTrue(runCatching { SdxlImagePhaseProtocol.parseRequest(tampered.toString()) }.isFailure)
        val legacy = JSONObject(encoded).put("version", 6)
        assertTrue(runCatching { SdxlImagePhaseProtocol.parseRequest(legacy.toString()) }.isFailure)
        val ambiguousLegacyField = JSONObject(encoded)
            .put("expectedHtpArch", 79)
        assertTrue(
            runCatching { SdxlImagePhaseProtocol.parseRequest(ambiguousLegacyField.toString()) }.isFailure
        )
        val legacyUntypedRuntime = JSONObject(encoded)
            .put("runtimeDirsJson", "[\"/packaged-qnn\"]")
        assertTrue(
            runCatching { SdxlImagePhaseProtocol.parseRequest(legacyUntypedRuntime.toString()) }.isFailure
        )
        val ambiguousRuntimeProfile = JSONObject(encoded).put(
            "runtimeProfile",
            request.runtimeProfile.toJson().put("directories", JSONArray(listOf("/packaged-qnn")))
        )
        assertTrue(
            runCatching { SdxlImagePhaseProtocol.parseRequest(ambiguousRuntimeProfile.toString()) }.isFailure
        )
    }

    @Test
    fun `request v10 rejects split projection and emits a disabled preview wire`() {
        val projection = SdxlProjectionPreviewRequest(interval = 4)
        val params = contractParams(steps = 30).put("preview", projection.toJson())
        val request = SdxlImagePhaseRequest(
            requestId = "sdxl-projection-v10",
            phase = SdxlImagePhase.UNET,
            runtimeProfile = runtimeProfile(79),
            profileId = "generic.sdxl.test",
            profileRevision = 3,
            modelFingerprint = "a".repeat(64),
            steps = 30,
            width = 1024,
            height = 1024,
            bundleRoot = "/bundle",
            paramsJson = params.toString(),
            embeddingsPath = "/cache/conditioning.f32",
            latentPath = "/cache/latent.f32",
            metadataPath = "/cache/latent.json",
            outputPath = "",
            journalPath = "/cache/unet.json",
            conditioningArtifactSha256 = conditioningArtifactSha256,
            projectionPreview = projection
        )

        assertTrue(runCatching { SdxlImagePhaseProtocol.request(request) }.isFailure)

        val disabledRequest = request.copy(
            paramsJson = contractParams(steps = 30).toString(),
            projectionPreview = null
        )
        val encoded = SdxlImagePhaseProtocol.request(disabledRequest)
        val parsed = SdxlImagePhaseProtocol.parseRequest(encoded)

        assertEquals(10, JSONObject(encoded).getInt("version"))
        assertTrue(parsed.projectionPreview == null)
        assertTrue(JSONObject(encoded).has("projectionPreview"))
        assertTrue(JSONObject(encoded).isNull("projectionPreview"))
        assertTrue(
            runCatching {
                SdxlImagePhaseProtocol.parseRequest(
                    JSONObject(encoded)
                        .put("projectionPreview", projection.toJson())
                        .toString()
                )
            }.isFailure
        )
    }

    @Test
    fun `encoder request v10 roundtrip binds exact runtime and resolved context digest`() {
        val params = contractParams(steps = 30)
            .put("taskMode", LocalImageTaskMode.IMG2IMG.wireName)
            .put("vaeEncoderContextSha256", encoderContextSha256)
            .put("inputImageTensorPath", "/cache/input.f32")
        val request = SdxlImagePhaseRequest(
            requestId = "sdxl-encoder-v10",
            phase = SdxlImagePhase.ENCODER,
            runtimeProfile = runtimeProfile(75),
            profileId = "generic.sdxl.test",
            profileRevision = 3,
            modelFingerprint = "a".repeat(64),
            steps = 30,
            width = 1024,
            height = 1024,
            bundleRoot = "/bundle",
            paramsJson = params.toString(),
            embeddingsPath = "",
            latentPath = "/cache/encoder-latent.f32",
            metadataPath = "/cache/encoder-latent.json",
            outputPath = "",
            journalPath = "/cache/encoder.json",
            conditioningArtifactSha256 = conditioningArtifactSha256,
            expectedVaeEncoderContextSha256 = encoderContextSha256,
            inputTensorPath = "/cache/input.f32"
        )

        val encoded = SdxlImagePhaseProtocol.request(request)
        val parsed = SdxlImagePhaseProtocol.parseRequest(encoded)

        assertEquals(10, JSONObject(encoded).getInt("version"))
        assertEquals(75, parsed.phaseHtpArch)
        assertEquals(encoderContextSha256, parsed.expectedVaeEncoderContextSha256)
        assertTrue(
            runCatching {
                SdxlImagePhaseProtocol.parseRequest(
                    JSONObject(encoded)
                        .put("expectedVaeEncoderContextSha256", "c".repeat(64))
                        .toString()
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                SdxlImagePhaseProtocol.parseRequest(
                    JSONObject(encoded)
                        .put("expectedVaeEncoderContextSha256", "")
                        .toString()
                )
            }.isFailure
        )
    }

    @Test
    fun `request v10 binds split inpaint artifacts to their consuming phases`() {
        val sourceTensor = "/cache/request.input-rgb-nchw.f32"
        val latentMask = "/cache/request.inpaint-mask-latent.f32"
        val fullMask = "/cache/request.inpaint-mask-full.f32"
        val params = contractParams(steps = 30)
            .put("taskMode", LocalImageTaskMode.INPAINT.wireName)
            .put("vaeEncoderContextSha256", encoderContextSha256)
            .put("inputImageTensorPath", sourceTensor)
            .put("maskImageTensorPath", latentMask)
            .put("maskImageFullTensorPath", fullMask)
        fun request(phase: SdxlImagePhase) = SdxlImagePhaseRequest(
            requestId = "sdxl-inpaint-v10",
            phase = phase,
            runtimeProfile = runtimeProfile(79),
            sourceArtifactProducerHtpArch = if (phase == SdxlImagePhase.ENCODER) 0 else 75,
            profileId = "generic.sdxl.test",
            profileRevision = 3,
            modelFingerprint = "a".repeat(64),
            steps = 30,
            width = 1024,
            height = 1024,
            bundleRoot = "/bundle",
            paramsJson = params.toString(),
            embeddingsPath = if (phase == SdxlImagePhase.UNET) "/cache/conditioning.f32" else "",
            latentPath = "/cache/latent.f32",
            metadataPath = "/cache/latent.json",
            outputPath = if (phase == SdxlImagePhase.VAE) "/cache/output.png" else "",
            journalPath = "/cache/${phase.wireName}.json",
            conditioningArtifactSha256 = conditioningArtifactSha256,
            expectedVaeEncoderContextSha256 = if (phase == SdxlImagePhase.ENCODER) {
                encoderContextSha256
            } else {
                ""
            },
            inputTensorPath = when (phase) {
                SdxlImagePhase.ENCODER, SdxlImagePhase.VAE -> sourceTensor
                SdxlImagePhase.UNET -> ""
            },
            maskTensorPath = if (phase == SdxlImagePhase.UNET) latentMask else "",
            fullMaskTensorPath = if (phase == SdxlImagePhase.VAE) fullMask else "",
            sourceLatentPath = if (phase == SdxlImagePhase.UNET) {
                "/cache/encoder-latent.f32"
            } else {
                ""
            },
            sourceMetadataPath = if (phase == SdxlImagePhase.UNET) {
                "/cache/encoder-latent.json"
            } else {
                ""
            },
        )

        SdxlImagePhase.entries.forEach { phase ->
            val encoded = SdxlImagePhaseProtocol.request(request(phase))
            assertEquals(10, JSONObject(encoded).getInt("version"))
            assertEquals(request(phase), SdxlImagePhaseProtocol.parseRequest(encoded))
        }
        val unet = JSONObject(SdxlImagePhaseProtocol.request(request(SdxlImagePhase.UNET)))
        assertTrue(runCatching {
            SdxlImagePhaseProtocol.parseRequest(
                unet.put("maskTensorPath", "/cache/other-mask.f32").toString()
            )
        }.isFailure)
        val vae = JSONObject(SdxlImagePhaseProtocol.request(request(SdxlImagePhase.VAE)))
        assertTrue(runCatching {
            SdxlImagePhaseProtocol.parseRequest(
                vae.put("fullMaskTensorPath", "/cache/other-full-mask.f32").toString()
            )
        }.isFailure)
    }

    @Test
    fun `conditioning artifact digest is mandatory and identity bound`() {
        val missing = contractParams(steps = 30).apply { remove("conditioningArtifactSha256") }
        val malformed = contractParams(steps = 30).put("conditioningArtifactSha256", "not-a-digest")

        assertTrue(runCatching { SdxlImageExecutionContract.fromParams(missing.toString()) }.isFailure)
        assertTrue(runCatching { SdxlImageExecutionContract.fromParams(malformed.toString()) }.isFailure)
    }

    @Test
    fun `isolated sdxl contract pins host vae scaling to 0_13025`() {
        val wrongFactor = contractParams(steps = 30).put("vaeScalingFactor", 0.18215)
        val wrongLocation = contractParams(steps = 30).put(
            "vaeScalingLocation",
            ImageVaeScalingLocation.GRAPH_INTERNAL.name
        )

        assertTrue(runCatching { SdxlImageExecutionContract.fromParams(wrongFactor.toString()) }.isFailure)
        assertTrue(runCatching { SdxlImageExecutionContract.fromParams(wrongLocation.toString()) }.isFailure)
    }

    @Test
    fun `encoder native and committed latent evidence bind the mapped context digest`() {
        val inputImageSha256 = "f".repeat(64)
        val inputTensorSha256 = "e".repeat(64)
        val params = contractParams(steps = 30)
            .put("taskMode", LocalImageTaskMode.IMG2IMG.wireName)
            .put("vaeEncoderContextSha256", encoderContextSha256)
            .put("inputImageSha256", inputImageSha256)
            .put("inputImageTensorSha256", inputTensorSha256)
        val contract = SdxlImageExecutionContract.fromParams(params.toString())
        val native = encoderPhaseEvidence(
            inputImageSha256 = inputImageSha256,
            inputTensorSha256 = inputTensorSha256,
            contextSha256 = encoderContextSha256
        )
        val latent = temporaryFolder.newFile("encoder-context-latent.f32")
            .apply { writeBytes(ByteArray(1 * 4 * 128 * 128 * 4)) }
        val metadataFile = File(temporaryFolder.root, "encoder-context-latent.json")

        validateSdxlEncoderNativeEvidence(contract, native)
        val published = SdxlEncoderLatentArtifact.publishMetadata(
            requestId = "sdxl-encoder-context",
            producerPid = 8301,
            contract = contract,
            proof = SdxlNativePhaseProof.fromNativeResult(native, SdxlImagePhase.ENCODER),
            nativeResult = native,
            latentFile = latent,
            metadataFile = metadataFile
        )
        val validated = SdxlEncoderLatentArtifact.validate(
            requestId = "sdxl-encoder-context",
            latentFile = latent,
            metadataFile = metadataFile,
            expectedProducerArch = 79,
            contract = contract
        )

        assertEquals(encoderContextSha256, published.encoderContextSha256)
        assertEquals(encoderContextSha256, validated.encoderContextSha256)
        assertTrue(
            runCatching {
                validateSdxlEncoderNativeEvidence(
                    contract,
                    JSONObject(native.toString()).put("encoderContextSha256", "c".repeat(64))
                )
            }.isFailure
        )
        metadataFile.writeText(
            JSONObject(metadataFile.readText())
                .put("encoderContextSha256", "c".repeat(64))
                .toString()
        )
        assertTrue(
            runCatching {
                SdxlEncoderLatentArtifact.validate(
                    "sdxl-encoder-context",
                    latent,
                    metadataFile,
                    79,
                    contract
                )
            }.isFailure
        )
    }

    @Test
    fun `progress IPC preserves phase pid profile and native stages`() {
        val envelope = SdxlImagePhaseProgress(
            requestId = "sdxl-2",
            phase = SdxlImagePhase.VAE,
            workerPid = 7502,
            runtimeProfile = "V75",
            progress = LocalImageProgress(
                phase = "graph_execute",
                message = "decode",
                step = 0,
                steps = 30,
                elapsedMs = 500,
                secondsPerStep = 0.0,
                threads = 0,
                width = 1024,
                height = 1024,
                cancelRequested = false,
                stageTrace = listOf("vae_context_create_before", "vae_graph_execute")
            )
        )

        val encoded = SdxlImagePhaseProtocol.progress(envelope)
        val parsed = SdxlImagePhaseProtocol.parseProgress(encoded)

        assertEquals(SdxlImagePhase.VAE, parsed.phase)
        assertEquals(7502, parsed.workerPid)
        assertEquals("V75", parsed.runtimeProfile)
        assertEquals(envelope.progress.stageTrace, parsed.progress.stageTrace)
        assertTrue(
            runCatching {
                SdxlImagePhaseProtocol.parseProgress(
                    JSONObject(encoded).put("version", 5).toString()
                )
            }.isFailure
        )
    }

    @Test
    fun `v10 progress and result preserve disabled projection audit`() {
        val progress = LocalImageProgress(
            phase = "sampling",
            message = "sampling",
            step = 8,
            steps = 30,
            elapsedMs = 500L,
            secondsPerStep = 1.0,
            threads = 0,
            width = 1024,
            height = 1024,
            cancelRequested = false,
            stageTrace = listOf("unet_graph_execute")
        )
        val progressEnvelope = SdxlImagePhaseProgress(
            requestId = "sdxl-projection-v10",
            phase = SdxlImagePhase.UNET,
            workerPid = 7901,
            runtimeProfile = "V79",
            progress = progress
        )
        val progressWire = SdxlImagePhaseProtocol.progress(progressEnvelope)
        val parsedProgress = SdxlImagePhaseProtocol.parseProgress(progressWire)
        assertEquals(progress, parsedProgress.progress)
        assertEquals(SdxlProjectionPreviewAudit.NONE, parsedProgress.projectionPreviewAudit)
        assertEquals(
            "none",
            JSONObject(progressWire)
                .getJSONObject("projectionPreviewAudit")
                .getString("mode")
        )

        val forgedProgress = progress.copy(
            previewPath = "/cache/unet-stage.json.previews/preview-1.png",
            previewMimeType = "image/png",
            previewMode = "projection",
            previewStep = 4,
            previewRevision = 1L,
            previewWidth = 128,
            previewHeight = 128,
            previewFrameCount = 1,
            previewPublicationCount = 1,
            previewLastStep = 4,
            previewLastRevision = 1L
        )
        assertTrue(
            runCatching {
                SdxlImagePhaseProtocol.progress(progressEnvelope.copy(progress = forgedProgress))
            }.isFailure
        )

        val native = JSONObject().withSdxlPreviewDisabled().put("ok", true)
        val result = SdxlImagePhaseResult(
            requestId = "sdxl-projection-v10",
            phase = SdxlImagePhase.UNET,
            workerPid = 7901,
            runtimeProfile = "V79",
            artifactPath = "/cache/latent.f32",
            metadataPath = "/cache/latent.json",
            nativeGenerationSequence = 11L,
            nativeStageMask = 7L,
            nativeDetailStageMask = 15uL,
            conditioningArtifactSha256 = conditioningArtifactSha256,
            nativeResultJson = native.toString()
        )
        val resultWire = SdxlImagePhaseProtocol.result(result)
        val parsedResult = SdxlImagePhaseProtocol.parseResult(resultWire)
        assertEquals(result, parsedResult)
        assertEquals(
            SdxlProjectionPreviewAudit.NONE,
            parsedResult.projectionPreviewAudit
        )
        assertTrue(
            runCatching {
                SdxlImagePhaseProtocol.result(
                    result.copy(
                        projectionPreviewAudit = SdxlProjectionPreviewAudit(
                            requested = true,
                            interval = 4,
                            attemptCount = 1,
                            publicationCount = 1,
                            lastStep = 4,
                            lastRevision = 1L
                        )
                    )
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                SdxlImagePhaseProtocol.result(
                    result.copy(
                        nativeResultJson = JSONObject(native.toString())
                            .put("previewRequested", true)
                            .toString()
                    )
                )
            }.isFailure
        )
    }

    @Test
    fun `result and error IPC reject the legacy phase protocol`() {
        val result = SdxlImagePhaseResult(
            requestId = "sdxl-result-v5",
            phase = SdxlImagePhase.ENCODER,
            workerPid = 7501,
            runtimeProfile = "V79",
            artifactPath = "/cache/encoder-latent.f32",
            metadataPath = "/cache/encoder-latent.json",
            nativeGenerationSequence = 9L,
            nativeStageMask = 17L,
            nativeDetailStageMask = 33uL,
            conditioningArtifactSha256 = conditioningArtifactSha256,
            nativeResultJson = "{\"ok\":true}"
        )
        val resultJson = SdxlImagePhaseProtocol.result(result)
        assertEquals(result, SdxlImagePhaseProtocol.parseResult(resultJson))
        assertEquals(
            "0000000000000021",
            JSONObject(resultJson).getString("nativeDetailStageMaskHex")
        )
        assertFalse(JSONObject(resultJson).has("nativeDetailStageMask"))
        assertTrue(
            runCatching {
                SdxlImagePhaseProtocol.parseResult(
                    JSONObject(resultJson).put("version", 5).toString()
                )
            }.isFailure
        )
        val legacyMask = JSONObject(resultJson).apply {
            remove("nativeDetailStageMaskHex")
            put("nativeDetailStageMask", 33L)
        }
        assertTrue(
            runCatching { SdxlImagePhaseProtocol.parseResult(legacyMask.toString()) }.isFailure
        )
        listOf(
            "21",
            "00000000000000021",
            "00000000000000FF",
            "0x0000000000000021",
            "-000000000000021",
            "00000000000000gg"
        ).forEach { malformed ->
            assertTrue(
                runCatching {
                    SdxlImagePhaseProtocol.parseResult(
                        JSONObject(resultJson)
                            .put("nativeDetailStageMaskHex", malformed)
                            .toString()
                    )
                }.isFailure
            )
        }

        val error = SdxlImagePhaseError(
            requestId = "sdxl-error-v5",
            phase = SdxlImagePhase.ENCODER,
            workerPid = 7501,
            code = "encoder_failed",
            message = "failed"
        )
        val errorJson = SdxlImagePhaseProtocol.error(error)
        assertEquals(error, SdxlImagePhaseProtocol.parseError(errorJson))
        assertTrue(
            runCatching {
                SdxlImagePhaseProtocol.parseError(
                    JSONObject(errorJson).put("version", 5).toString()
                )
            }.isFailure
        )
    }

    @Test
    fun `latent commit validates shape bytes and sha then rejects tampering`() {
        val latent = temporaryFolder.newFile("latent.f32")
        latent.writeBytes(ByteArray(1 * 4 * 128 * 128 * 4) { index -> index.toByte() })
        val metadataFile = File(temporaryFolder.root, "latent.json")
        val native = JSONObject()
            .putAll(nativeEffectiveParams(steps = 30))
            .put("runtimeProfile", "V79")
            .put("htpArchVersion", 79)
            .put("latentDtype", "float32-le")
            .putAll(unetPhaseEvidence())
            .put("nativeGenerationSequence", 41L)
            .put("nativeStageMask", 127L)
            .put("nativeDetailStageMaskHex", "00000000000001ff")
        val contract = SdxlImageExecutionContract.fromParams(contractParams(steps = 30).toString())
        val proof = SdxlNativePhaseProof.fromNativeResult(native, SdxlImagePhase.UNET)

        val published = SdxlLatentArtifact.publishMetadata(
            requestId = "sdxl-3",
            producerPid = 7503,
            contract = contract,
            proof = proof,
            nativeResult = native,
            latentFile = latent,
            metadataFile = metadataFile
        )
        val validated = SdxlLatentArtifact.validate(
            requestId = "sdxl-3",
            latentFile = latent,
            metadataFile = metadataFile,
            expectedProducerArch = 79,
            contract = contract
        )

        assertEquals(published.sha256, validated.sha256)
        assertTrue(metadataFile.readText().contains("\"committed\":true"))
        latent.appendBytes(byteArrayOf(1))
        val failure = runCatching {
            SdxlLatentArtifact.validate("sdxl-3", latent, metadataFile, 79, contract)
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("byte size"))
    }

    @Test
    fun `latent metadata rejects changed native execution contract`() {
        val latent = temporaryFolder.newFile("contract-latent.f32")
            .apply { writeBytes(ByteArray(1 * 4 * 128 * 128 * 4)) }
        val metadataFile = File(temporaryFolder.root, "contract-latent.json")
        val params = contractParams(steps = 30)
        val contract = SdxlImageExecutionContract.fromParams(params.toString())
        val native = nativeEffectiveParams(steps = 30)
            .put("runtimeProfile", "V79")
            .put("htpArchVersion", 79)
            .put("latentDtype", "float32-le")
            .putAll(unetPhaseEvidence())
            .put("nativeGenerationSequence", 42L)
            .put("nativeStageMask", 127L)
            .put("nativeDetailStageMaskHex", "00000000000001ff")
        SdxlLatentArtifact.publishMetadata(
            requestId = "sdxl-contract",
            producerPid = 7504,
            contract = contract,
            proof = SdxlNativePhaseProof.fromNativeResult(native, SdxlImagePhase.UNET),
            nativeResult = native,
            latentFile = latent,
            metadataFile = metadataFile
        )
        val persisted = JSONObject(metadataFile.readText())
        persisted.getJSONObject("nativeEffective").put("steps", 1)
        metadataFile.writeText(persisted.toString())

        assertTrue(
            runCatching {
                SdxlLatentArtifact.validate(
                    "sdxl-contract",
                    latent,
                    metadataFile,
                    79,
                    contract
                )
            }.isFailure
        )
    }

    @Test
    fun `latent metadata rejects missing or changed conditioning artifact digest`() {
        val latent = temporaryFolder.newFile("conditioning-latent.f32")
            .apply { writeBytes(ByteArray(1 * 4 * 128 * 128 * 4)) }
        val metadataFile = File(temporaryFolder.root, "conditioning-latent.json")
        val contract = SdxlImageExecutionContract.fromParams(contractParams(steps = 30).toString())
        val native = nativeEffectiveParams(steps = 30)
            .put("runtimeProfile", "V79")
            .put("htpArchVersion", 79)
            .put("latentDtype", "float32-le")
            .putAll(unetPhaseEvidence())
            .put("nativeGenerationSequence", 44L)
            .put("nativeStageMask", 127L)
            .put("nativeDetailStageMaskHex", "00000000000001ff")
        SdxlLatentArtifact.publishMetadata(
            requestId = "sdxl-conditioning",
            producerPid = 7506,
            contract = contract,
            proof = SdxlNativePhaseProof.fromNativeResult(native, SdxlImagePhase.UNET),
            nativeResult = native,
            latentFile = latent,
            metadataFile = metadataFile
        )

        val missing = JSONObject(metadataFile.readText()).apply { remove("conditioningArtifactSha256") }
        metadataFile.writeText(missing.toString())
        assertTrue(
            runCatching {
                SdxlLatentArtifact.validate("sdxl-conditioning", latent, metadataFile, 79, contract)
            }.isFailure
        )

        val changed = JSONObject(missing.toString())
            .put("conditioningArtifactSha256", "e".repeat(64))
        metadataFile.writeText(changed.toString())
        assertTrue(
            runCatching {
                SdxlLatentArtifact.validate("sdxl-conditioning", latent, metadataFile, 79, contract)
            }.isFailure
        )
    }

    @Test
    fun `latent metadata rejects a same byte count with the wrong spatial shape`() {
        val latent = temporaryFolder.newFile("wrong-shape-latent.f32")
            .apply { writeBytes(ByteArray(1 * 4 * 128 * 128 * 4)) }
        val contract = SdxlImageExecutionContract.fromParams(contractParams(steps = 30).toString())
        val native = nativeEffectiveParams(steps = 30)
            .put("runtimeProfile", "V79")
            .put("htpArchVersion", 79)
            .put("latentDtype", "float32-le")
            .putAll(unetPhaseEvidence())
            .put("latentShape", JSONArray(listOf(1, 4, 64, 256)))
            .put("nativeGenerationSequence", 43L)
            .put("nativeStageMask", 127L)
            .put("nativeDetailStageMaskHex", "00000000000001ff")

        assertTrue(
            runCatching {
                SdxlLatentArtifact.publishMetadata(
                    requestId = "sdxl-wrong-shape",
                    producerPid = 7505,
                    contract = contract,
                    proof = SdxlNativePhaseProof.fromNativeResult(native, SdxlImagePhase.UNET),
                    nativeResult = native,
                    latentFile = latent,
                    metadataFile = File(temporaryFolder.root, "wrong-shape-latent.json")
                )
            }.isFailure
        )
    }

    @Test
    fun `merged journal keeps three phase boundaries distinct when pid is reused`() {
        fun event(phase: SdxlImagePhase, pid: Int, profile: String, stage: String) =
            SdxlImagePhaseProgress(
                requestId = "sdxl-4",
                phase = phase,
                workerPid = pid,
                runtimeProfile = profile,
                progress = LocalImageProgress(
                    phase = stage,
                    message = stage,
                    step = 0,
                    steps = 30,
                    elapsedMs = 0,
                    secondsPerStep = 0.0,
                    threads = 0,
                    width = 1024,
                    height = 1024,
                    cancelRequested = false,
                    stageTrace = listOf(stage)
                )
            )
        var trace = SdxlTwoPhaseJournal.merge(
            emptyList(),
            event(SdxlImagePhase.ENCODER, 75, "V75", "encoder_graph_execute")
        )
        trace = SdxlTwoPhaseJournal.appendBoundary(
            trace, SdxlImagePhase.ENCODER, 75, "V75", "process_exit_confirmed"
        )
        trace = SdxlTwoPhaseJournal.merge(
            trace,
            event(SdxlImagePhase.UNET, 75, "V75", "unet_graph_execute")
        )
        trace = SdxlTwoPhaseJournal.appendBoundary(
            trace, SdxlImagePhase.UNET, 75, "V75", "process_exit_confirmed"
        )
        trace = SdxlTwoPhaseJournal.merge(
            trace,
            event(SdxlImagePhase.VAE, 75, "V75", "vae_graph_execute")
        )
        trace = SdxlTwoPhaseJournal.appendBoundary(
            trace, SdxlImagePhase.VAE, 75, "V75", "process_exit_confirmed"
        )

        assertTrue(trace.any { it == "encoder[pid=75,profile=V75]:process_exit_confirmed" })
        assertTrue(trace.any { it == "unet[pid=75,profile=V75]:process_exit_confirmed" })
        assertTrue(trace.any { it == "vae[pid=75,profile=V75]:process_exit_confirmed" })
    }

    @Test
    fun `public archive uses one coherent packaged runtime in both disposable phases`() {
        val incomplete = temporaryFolder.newFolder("incomplete-qnn")
        File(incomplete, "libQnnSystem.so").writeBytes(byteArrayOf(1))
        val coherent = temporaryFolder.newFolder("packaged-qnn")
        listOf(
            "libQnnSystem.so",
            "libQnnHtp.so",
            "libQnnHtpV79Skel.so",
            "libQnnHtpV79Stub.so"
        ).forEach { name -> File(coherent, name).writeBytes(byteArrayOf(1)) }

        val selected = JSONArray(
            isolatedSdxlPackagedRuntimeDirs(
                JSONArray(listOf(incomplete.absolutePath, coherent.absolutePath)).toString()
            )
        )

        assertEquals(1, selected.length())
        assertEquals(coherent.canonicalPath, File(selected.getString(0)).canonicalPath)
        assertEquals(75, SDXL_ARCHIVE_CONTEXT_HTP_ARCH)
    }

    @Test
    fun `packaged runtime candidates rank the advisory transport and retain every exact profile`() {
        fun runtime(name: String, arch: Int): File = temporaryFolder.newFolder(name).also { directory ->
            listOf(
                "libQnnSystem.so",
                "libQnnHtp.so",
                "libQnnHtpV${arch}Skel.so",
                "libQnnHtpV${arch}Stub.so"
            ).forEach { fileName -> File(directory, fileName).writeBytes(byteArrayOf(1)) }
        }
        val futureA = runtime("packaged-qnn-v83-a", 83)
        val preferred = runtime("packaged-qnn-v75", 75)
        val futureB = runtime("packaged-qnn-v83-b", 83)
        val dirs = JSONArray(
            listOf(futureA.absolutePath, preferred.absolutePath, futureB.absolutePath)
        ).toString()

        val candidates = sdxlRuntimeCandidatesForPackagedDirs(
            runtimeDirsJson = dirs,
            preferredHtpArch = 75
        )

        assertEquals(listOf(75, 83, 83), candidates.map(SdxlPhaseRuntimeCandidate::phaseHtpArch))
        assertEquals(
            listOf(preferred, futureA, futureB).map { directory ->
                listOf(directory.canonicalPath)
            },
            candidates.map { candidate ->
                candidate.runtimeProfile.let { profile ->
                    if (profile.hostDirectory == profile.dspDirectory) {
                        listOf(profile.hostDirectory)
                    } else {
                        listOf(profile.hostDirectory, profile.dspDirectory)
                    }
                }
            }
        )
    }

    @Test
    fun `side loaded split profile is not emitted and cannot block coherent fallback`() {
        val coherent = temporaryFolder.newFolder("packaged-qnn-coherent-v79")
        listOf(
            "libQnnSystem.so",
            "libQnnHtp.so",
            "libQnnHtpV79Skel.so",
            "libQnnHtpV79Stub.so"
        ).forEach { name -> File(coherent, name).writeBytes(byteArrayOf(1)) }
        val sideLoadedDsp = temporaryFolder.newFolder("side-loaded-dsp-v83")
        listOf("libQnnHtpV83Skel.so", "libQnnHtpV83Stub.so").forEach { name ->
            File(sideLoadedDsp, name).writeBytes(byteArrayOf(1))
        }

        val candidates = sdxlRuntimeCandidatesForPackagedDirs(
            runtimeDirsJson = JSONArray(
                listOf(coherent.absolutePath, sideLoadedDsp.absolutePath)
            ).toString(),
            preferredHtpArch = 83
        )
        assertEquals(79, candidates.single().phaseHtpArch)
        assertEquals(coherent.canonicalPath, candidates.single().runtimeProfile.hostDirectory)
        assertEquals(coherent.canonicalPath, candidates.single().runtimeProfile.dspDirectory)
    }

    @Test
    fun `runtime candidate retries a structured load failure in the next disposable worker`() = runBlocking {
        val firstProfile = runtimeProfile(
            arch = 83,
            hostDirectory = "/platform/host-a",
            dspDirectory = "/platform/dsp-a"
        )
        val winningProfile = runtimeProfile(
            arch = 83,
            hostDirectory = "/platform/host-b",
            dspDirectory = "/platform/dsp-b"
        )
        val candidates = listOf(
            SdxlPhaseRuntimeCandidate(firstProfile),
            SdxlPhaseRuntimeCandidate(winningProfile)
        )
        val attempted = mutableListOf<SdxlQnnRuntimeProfile>()

        val selected = executeSdxlRuntimeCandidates(
            phase = SdxlImagePhase.UNET,
            candidates = candidates,
            isCancelled = { false }
        ) { candidate ->
            attempted += candidate.runtimeProfile
            if (candidate.runtimeProfile == firstProfile) {
                throw LocalImageWorkerRemoteException(
                    "sdxl_unet_runtime_candidate_failed",
                    "UNet context load failed on the first transport."
                )
            }
            candidate.runtimeProfile.hostDirectory
        }

        assertEquals(winningProfile.hostDirectory, selected.value)
        assertEquals(winningProfile, selected.candidate.runtimeProfile)
        assertEquals(listOf(firstProfile, winningProfile), attempted)

        val winningEvidence = JSONObject()
            .put("htpArchVersion", 83)
            .put("runtimeEvidence", runtimeEvidence(winningProfile))
        assertEquals(
            83,
            validateSdxlNativeTransport(
                phase = SdxlImagePhase.UNET,
                expectedRuntimeProfile = selected.candidate.runtimeProfile,
                nativeResult = winningEvidence
            )
        )
        assertTrue(
            runCatching {
                validateSdxlNativeTransport(
                    phase = SdxlImagePhase.UNET,
                    expectedRuntimeProfile = selected.candidate.runtimeProfile,
                    nativeResult = JSONObject(winningEvidence.toString())
                        .put("runtimeEvidence", runtimeEvidence(firstProfile))
                )
            }.isFailure
        )
    }

    @Test
    fun `runtime candidate cancellation and execution failures never advance`() = runBlocking {
        val candidates = listOf(
            SdxlPhaseRuntimeCandidate(runtimeProfile(73, "/runtime-v73")),
            SdxlPhaseRuntimeCandidate(runtimeProfile(75, "/runtime-v75"))
        )
        var cancelled = false
        val cancelledAttempts = mutableListOf<Int>()
        assertTrue(
            runCatching {
                executeSdxlRuntimeCandidates(
                    phase = SdxlImagePhase.VAE,
                    candidates = candidates,
                    isCancelled = { cancelled }
                ) { candidate ->
                    cancelledAttempts += candidate.phaseHtpArch
                    cancelled = true
                    throw LocalImageWorkerRemoteException(
                        "sdxl_vae_runtime_candidate_failed",
                        "VAE load failed while cancellation won the boundary."
                    )
                }
            }.isFailure
        )
        assertEquals(listOf(73), cancelledAttempts)

        val executionAttempts = mutableListOf<Int>()
        assertTrue(
            runCatching {
                executeSdxlRuntimeCandidates(
                    phase = SdxlImagePhase.VAE,
                    candidates = candidates,
                    isCancelled = { false }
                ) { candidate ->
                    executionAttempts += candidate.phaseHtpArch
                    throw LocalImageWorkerRemoteException(
                        "sdxl_vae_phase_failed",
                        "VAE graph execution failed after load."
                    )
                }
            }.isFailure
        )
        assertEquals(listOf(73), executionAttempts)

        val profileMismatchAttempts = mutableListOf<Int>()
        val profileMismatchWinner = executeSdxlRuntimeCandidates(
            phase = SdxlImagePhase.UNET,
            candidates = candidates,
            isCancelled = { false }
        ) { candidate ->
            profileMismatchAttempts += candidate.phaseHtpArch
            if (candidate == candidates.first()) {
                throw LocalImageWorkerRemoteException(
                    "sdxl_unet_runtime_candidate_failed",
                    "Native selected a different transport than the requested profile."
                )
            }
            candidate.phaseHtpArch
        }
        assertEquals(75, profileMismatchWinner.value)
        assertEquals(listOf(73, 75), profileMismatchAttempts)
    }

    @Test
    fun `worker runtime retry classifier is phase and stage exact`() {
        assertEquals(
            "sdxl_encoder_runtime_candidate_failed",
            sdxlRuntimeCandidateFailureCode(SdxlImagePhase.ENCODER, "sdxl_encoder_load_failed")
        )
        assertEquals(
            "sdxl_unet_runtime_candidate_failed",
            sdxlRuntimeCandidateFailureCode(SdxlImagePhase.UNET, "unet_load_failed")
        )
        assertEquals(
            "sdxl_vae_runtime_candidate_failed",
            sdxlRuntimeCandidateFailureCode(SdxlImagePhase.VAE, "runtime_unavailable")
        )
        assertEquals(
            null,
            sdxlRuntimeCandidateFailureCode(SdxlImagePhase.UNET, "sdxl_unet_cond_failed")
        )
        assertEquals(
            null,
            sdxlRuntimeCandidateFailureCode(SdxlImagePhase.VAE, "sdxl_vae_decode_failed")
        )
        listOf(
            SdxlImagePhase.ENCODER to "encoder_profile_mismatch",
            SdxlImagePhase.UNET to "unet_profile_mismatch",
            SdxlImagePhase.VAE to "vae_profile_mismatch"
        ).forEach { (phase, stage) ->
            assertEquals(
                "sdxl_${phase.wireName}_runtime_candidate_failed",
                sdxlRuntimeCandidateFailureCode(phase, stage)
            )
        }
    }

    @Test
    fun `exact phase profiles accept independent physical arches and reject auto transport`() {
        val unetProfile = runtimeProfile(
            arch = 73,
            hostDirectory = "/vendor/lib64/qnn-v73",
            dspDirectory = "/vendor/lib/rfsa/adsp-v73"
        )
        val vaeProfile = runtimeProfile(
            arch = 79,
            hostDirectory = "/vendor/lib64/qnn-v79",
            dspDirectory = "/vendor/lib/rfsa/adsp-v79"
        )
        val nativeV73 = JSONObject()
            .put("htpArchVersion", 73)
            .put("runtimeEvidence", runtimeEvidence(unetProfile))
        val nativeV79 = JSONObject()
            .put("htpArchVersion", 79)
            .put("runtimeEvidence", runtimeEvidence(vaeProfile))

        assertEquals(
            73,
            validateSdxlNativeTransport(
                phase = SdxlImagePhase.UNET,
                expectedRuntimeProfile = unetProfile,
                nativeResult = nativeV73
            )
        )
        assertEquals(
            79,
            validateSdxlNativeTransport(
                phase = SdxlImagePhase.VAE,
                expectedRuntimeProfile = vaeProfile,
                nativeResult = nativeV79
            )
        )
        assertEquals("V79", sdxlTransportProfile(79))
        assertTrue(runCatching { sdxlTransportProfile(0) }.isFailure)
        assertTrue(
            runCatching {
                validateSdxlNativeTransport(
                    phase = SdxlImagePhase.VAE,
                    expectedRuntimeProfile = vaeProfile.copy(htpArchVersion = 75),
                    nativeResult = nativeV79
                )
            }.isFailure
        )

        val params = contractParams(steps = 30)
            .put("taskMode", LocalImageTaskMode.IMG2IMG.wireName)
        val unetRequest = SdxlImagePhaseRequest(
            requestId = "sdxl-mixed-arch",
            phase = SdxlImagePhase.UNET,
            runtimeProfile = runtimeProfile(79),
            sourceArtifactProducerHtpArch = 75,
            profileId = "generic.sdxl.test",
            profileRevision = 3,
            modelFingerprint = "a".repeat(64),
            steps = 30,
            width = 1024,
            height = 1024,
            bundleRoot = "/bundle",
            paramsJson = params.toString(),
            embeddingsPath = "/cache/conditioning.f32",
            latentPath = "/cache/unet-latent.f32",
            metadataPath = "/cache/unet-latent.json",
            outputPath = "",
            journalPath = "/cache/unet.json",
            conditioningArtifactSha256 = conditioningArtifactSha256,
            sourceLatentPath = "/cache/encoder-latent.f32",
            sourceMetadataPath = "/cache/encoder-latent.json"
        )
        val parsedUnet = SdxlImagePhaseProtocol.parseRequest(
            SdxlImagePhaseProtocol.request(unetRequest)
        )
        assertEquals(79, parsedUnet.phaseHtpArch)
        assertEquals(75, parsedUnet.sourceArtifactProducerHtpArch)

        val vaeRequest = unetRequest.copy(
            phase = SdxlImagePhase.VAE,
            runtimeProfile = runtimeProfile(73),
            sourceArtifactProducerHtpArch = 73,
            embeddingsPath = "",
            outputPath = "/cache/output.png",
            journalPath = "/cache/vae.json",
            sourceLatentPath = "",
            sourceMetadataPath = ""
        )
        val parsedVae = SdxlImagePhaseProtocol.parseRequest(
            SdxlImagePhaseProtocol.request(vaeRequest)
        )
        assertEquals(73, parsedVae.phaseHtpArch)
        assertEquals(73, parsedVae.sourceArtifactProducerHtpArch)
    }

    @Test
    fun `exact role evidence cannot be shadowed by a same arch dsp beside the host`() {
        val hostWithSameArchDsp = temporaryFolder.newFolder("platform-host-v79")
        val selectedDsp = temporaryFolder.newFolder("platform-rfsa-v79")
        val selectedProfile = runtimeProfile(
            arch = 79,
            hostDirectory = hostWithSameArchDsp.canonicalPath,
            dspDirectory = selectedDsp.canonicalPath
        )
        val correct = JSONObject()
            .put("htpArchVersion", 79)
            .put("runtimeEvidence", runtimeEvidence(selectedProfile))

        assertEquals(
            79,
            validateSdxlNativeTransport(
                phase = SdxlImagePhase.UNET,
                expectedRuntimeProfile = selectedProfile,
                nativeResult = correct
            )
        )

        val shadowedByHostDirectory = JSONObject(correct.toString())
            .put(
                "runtimeEvidence",
                runtimeEvidence(selectedProfile).put(
                    "dspRuntimeDirectory",
                    hostWithSameArchDsp.canonicalPath
                )
            )
        assertTrue(
            runCatching {
                validateSdxlNativeTransport(
                    phase = SdxlImagePhase.UNET,
                    expectedRuntimeProfile = selectedProfile,
                    nativeResult = shadowedByHostDirectory
                )
            }.isFailure
        )
    }

    @Test
    fun `final execution metadata preserves dynamic transport and isolated process proof`() {
        val nativeExecution = nativeEffectiveParams(steps = 30)
            .put("runtimeSessionMode", SDXL_ISOLATED_UNET_VAE_MODE)
            .put("transportHtpArch", 79)
            .put("unetTransportHtpArch", 79)
            .put("vaeTransportHtpArch", 73)
            .put("sdxlPhaseProof", sdxlPhaseProofEvidence())
        val metadata = qnnImageExecutionMetadata(
            nativeRequestId = "qnn-native-1",
            nativeResult = JSONObject(nativeExecution.toString())
                .put("nativeEffective", JSONObject(nativeExecution.toString()))
                .putAll(vaePixelRangeEvidence())
                .put("backend", "qnn_htp")
                .put("executionStage", "sdxl_two_phase_passed")
                .put("npuActive", true)
                .put("qnnGraphExecution", true)
                .put("nativeExecution", true)
                .put("fallback", false)
                .put("nativeGenerationSequence", 7L)
                .put("nativeStartedAtMonotonicMs", 123_456L)
                .put("nativeStageMask", 255L)
                .put("nativeDetailStageMaskHex", "00000000000003ff")
                .put("runtimeSessionMode", SDXL_ISOLATED_UNET_VAE_MODE)
                .put("conditioningFormat", "sdxl_qnn_conditioning")
                .put("vaeExecutionCount", 9)
                .put("finalVaeExecutionCount", 1)
                .put("finalVaeGraphExecutionCount", 9)
                .put("previewRequested", false)
                .put("previewMode", "none")
                .put("previewInterval", 0)
                .put("previewVaeExecutionAttemptCount", 0)
                .put("previewVaeExecutionCount", 0)
                .put("previewVaeExecutionMsTotal", 0)
                .put("previewPublicationCount", 0)
                .put("previewLastStep", 0)
                .put("previewLastRevision", 0)
                .put("previewFailureCode", "")
                .put("projectionPreviewAttemptCount", 0)
                .put("projectionPreviewPublicationCount", 0)
                .put("projectionPreviewProjectionMsTotal", 0)
                .put("projectionPreviewLastStep", 0)
                .put("projectionPreviewLastRevision", 0)
                .put("projectionPreviewFailureCode", "")
                .put("previewDegraded", false)
                .put("transportHtpArch", 79)
                .put("unetTransportHtpArch", 79)
                .put("unetWorkerPid", 14149)
                .put("unetRuntimeProfile", "V79")
                .put("unetProcessDeathConfirmed", true)
                .put("vaeWorkerPid", 14242)
                .put("vaeRuntimeProfile", "V73")
                .put("vaeTransportHtpArch", 73)
                .put("vaeProcessDeathConfirmed", true)
                .put(
                    "runtimeEvidence",
                    JSONObject()
                        .put("htpArchVersion", 79)
                        .put("loadable", true)
                        .put("qnnInterfacePresent", true)
                        .put(
                            "compile",
                            JSONObject()
                                .put("sdkHeadersPresent", true)
                                .put("typedGraphBindingsCompiled", true)
                        )
                ),
            outputBytes = 2_834_965L
        )

        assertFalse(metadata.has("archiveContextHtpArch"))
        assertEquals(79, metadata.getInt("transportHtpArch"))
        assertEquals(79, metadata.getInt("unetTransportHtpArch"))
        assertEquals(73, metadata.getInt("vaeTransportHtpArch"))
        assertEquals("V79", metadata.getString("unetRuntimeProfile"))
        assertEquals("V73", metadata.getString("vaeRuntimeProfile"))
        assertTrue(metadata.getBoolean("unetProcessDeathConfirmed"))
        assertTrue(metadata.getBoolean("vaeProcessDeathConfirmed"))
        assertFalse(metadata.getBoolean("fallback"))
        assertEquals(7L, metadata.getLong("nativeGenerationSequence"))
        assertEquals(123_456L, metadata.getLong("nativeStartedAtMonotonicMs"))
        assertEquals("00000000000003ff", metadata.getString("nativeDetailStageMaskHex"))
        assertFalse(metadata.has("nativeDetailStageMask"))
        assertEquals("d".repeat(64), metadata.getString("nativePromptExecutionSha256"))
        assertEquals("conditioning_consumed", metadata.getString("nativePromptBindingStage"))
        assertEquals(
            metadata.getString("nativePromptExecutionSha256"),
            metadata.getJSONObject("nativeEffective").getString("nativePromptExecutionSha256")
        )
        assertEquals("sdxl_qnn_conditioning", metadata.getString("conditioningFormat"))
        assertEquals(
            "external_mnn_sdxl_embeddings",
            metadata.getString("conditioningExecutionMode")
        )
        assertEquals("clip.mnn+clip_2.mnn", metadata.getString("conditioningGraph"))
        assertEquals(4, metadata.getInt("conditioningEncoderExecutionCount"))
        assertEquals(0, metadata.getInt("textEncoderExecutionCount"))
        assertTrue(metadata.getBoolean("conditioningArtifactConsumed"))
        assertEquals(30, metadata.getInt("steps"))
        assertEquals(
            SDXL_ISOLATED_UNET_VAE_MODE,
            metadata.getJSONObject("nativeEffective").getString("runtimeSessionMode")
        )
        assertEquals(
            9,
            metadata.getJSONObject("nativeEffective")
                .getJSONObject("sdxlPhaseProof")
                .getInt("vaeTileCount")
        )
        val legacyNumericEvidence = JSONObject(metadata.toString()).apply {
            remove("nativeDetailStageMaskHex")
            put("nativeDetailStageMask", 1023L)
        }
        assertTrue(sanitizeNativeExecutionJson(legacyNumericEvidence.toString()).isEmpty())
        val migrated = JSONObject(
            sanitizeNativeExecutionJson(
                legacyNumericEvidence.toString(),
                allowLegacyQnnDetailStageMaskMigration = true
            )
        )
        assertEquals("00000000000003ff", migrated.getString("nativeDetailStageMaskHex"))
        assertFalse(migrated.has("nativeDetailStageMask"))
    }

    @Test
    fun `three phase final verifier requires one encoder context digest everywhere`() {
        val phaseProof = sdxlPhaseProofEvidence()
            .put("encoderTransportHtpArch", 75)
            .put("encoderContextSha256", encoderContextSha256)
            .put("encoderRuntimeProfileSha256", "c".repeat(64))
        val nativeEffective = nativeEffectiveParams(steps = 30)
            .put("runtimeSessionMode", SDXL_ISOLATED_ENCODER_UNET_VAE_MODE)
            .put("transportHtpArch", 79)
            .put("unetTransportHtpArch", 79)
            .put("vaeTransportHtpArch", 73)
            .put("encoderTransportHtpArch", 75)
            .put("encoderContextSha256", encoderContextSha256)
            .put("sdxlPhaseProof", phaseProof)
        val result = JSONObject(nativeEffective.toString())
            .put("nativeEffective", JSONObject(nativeEffective.toString()))

        validateSdxlFlatNativeEffective(result)

        val changedNested = JSONObject(result.toString())
        changedNested.getJSONObject("nativeEffective")
            .put("encoderContextSha256", "c".repeat(64))
        assertTrue(runCatching { validateSdxlFlatNativeEffective(changedNested) }.isFailure)

        val missingPhaseProofDigest = JSONObject(result.toString())
        missingPhaseProofDigest.getJSONObject("sdxlPhaseProof")
            .remove("encoderContextSha256")
        assertTrue(runCatching { validateSdxlFlatNativeEffective(missingPhaseProofDigest) }.isFailure)

        val missingExactProfileDigest = JSONObject(result.toString())
        missingExactProfileDigest.getJSONObject("sdxlPhaseProof")
            .remove("unetRuntimeProfileSha256")
        missingExactProfileDigest.getJSONObject("nativeEffective")
            .getJSONObject("sdxlPhaseProof")
            .remove("unetRuntimeProfileSha256")
        assertTrue(runCatching { validateSdxlFlatNativeEffective(missingExactProfileDigest) }.isFailure)
    }

    @Test
    fun `multi step phase deadlines fit inside the step aware outer watchdog`() {
        val unetTimeout = sdxlUnetPhaseTimeoutMs(unetExecutionCount = 60)
        val vaeTimeout = sdxlVaePhaseTimeoutMs(vaeExecutionCount = 9)
        val outerTimeout = sdxlWorkerTimeoutMs(steps = 30, useCfg = true)

        assertTrue(unetTimeout < outerTimeout)
        assertTrue(vaeTimeout < outerTimeout)
        assertTrue(
            unetTimeout + vaeTimeout < outerTimeout
        )
    }

    @Test
    fun `two phase merge returns flat strict execution with preview disabled`() {
        val params = contractParams(steps = 30)
        val contract = SdxlImageExecutionContract.fromParams(params.toString())
        val latent = temporaryFolder.newFile("merge-latent.f32")
            .apply { writeBytes(ByteArray(1 * 4 * 128 * 128 * 4)) }
        val metadataFile = File(temporaryFolder.root, "merge-latent.json")
        val output = temporaryFolder.newFile("merge-output.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val unetNative = nativeEffectiveParams(steps = 30)
            .put("ok", true)
            .put("runtimeProfile", "V79")
            .put("htpArchVersion", 79)
            .put("latentDtype", "float32-le")
            .putAll(unetPhaseEvidence())
            .put("nativeGenerationSequence", 51L)
            .put("nativeStageMask", 127L)
            .put("nativeDetailStageMaskHex", "00000000000001ff")
            .withSdxlPreviewDisabled()
        val unetProof = SdxlNativePhaseProof.fromNativeResult(unetNative, SdxlImagePhase.UNET)
        val committed = SdxlLatentArtifact.publishMetadata(
            requestId = "sdxl-merge",
            producerPid = 8101,
            contract = contract,
            proof = unetProof,
            nativeResult = unetNative,
            latentFile = latent,
            metadataFile = metadataFile
        )
        val vaeNative = JSONObject()
            .put("ok", true)
            .put("runtimeProfile", "V73")
            .put("htpArchVersion", 73)
            .putAll(vaeDecodeEvidence())
            .putAll(vaePixelRangeEvidence())
            .put("mimeType", "image/png")
            .put("outputPath", output.canonicalPath)
            .put("outputBytes", output.length())
            .put("outputSha256", sdxlArtifactSha256(output))
            .put("nativeGenerationSequence", 52L)
            .put("nativeStageMask", 255L)
            .put("nativeDetailStageMaskHex", "00000000000003ff")
            .withSdxlPreviewDisabled()
        val unetResult = phaseResult(
            requestId = "sdxl-merge",
            phase = SdxlImagePhase.UNET,
            pid = 8101,
            proof = unetProof,
            artifact = latent,
            native = unetNative
        )
        val vaeProof = SdxlNativePhaseProof.fromNativeResult(vaeNative, SdxlImagePhase.VAE)
        val vaeResult = phaseResult(
            requestId = "sdxl-merge",
            phase = SdxlImagePhase.VAE,
            // Android may reuse the exited UNet PID for the later VAE
            // process. Distinct native generation identities and ordered
            // Binder-death boundaries prove separate instances.
            pid = 8101,
            proof = vaeProof,
            artifact = output,
            native = vaeNative
        )

        val merged = mergeSdxlPhaseNativeResults(
            contract = contract,
            unetResult = unetResult,
            unetNative = unetNative,
            vaeResult = vaeResult,
            vaeNative = vaeNative,
            metadata = committed,
            unetRuntimeProfile = runtimeProfile(79),
            vaeRuntimeProfile = runtimeProfile(73),
            unetTransportHtpArch = 79,
            vaeTransportHtpArch = 73,
            outputFile = output,
            stageTrace = listOf(
                "unet[pid=8101,profile=V79]:process_exit_confirmed",
                "vae[pid=8101,profile=V73]:process_exit_confirmed"
            )
        )

        assertEquals(ImageExecutionProfileNativeContract.requiredFields, merged.keys().asSequence()
            .filter { it in ImageExecutionProfileNativeContract.requiredFields }
            .toSet())
        assertEquals(30, merged.getInt("steps"))
        assertEquals(60, merged.getInt("unetExecutionCount"))
        assertEquals(51L, merged.getLong("nativeGenerationSequence"))
        assertEquals(52L, merged.getLong("vaeNativeGenerationSequence"))
        assertEquals(9, merged.getInt("vaeExecutionCount"))
        assertEquals(9, merged.getInt("vaeTileCount"))
        assertEquals(1, merged.getInt("finalVaeExecutionCount"))
        assertEquals(9, merged.getInt("finalVaeGraphExecutionCount"))
        assertEquals(0, merged.getInt("previewVaeExecutionAttemptCount"))
        assertEquals(0, merged.getInt("previewVaeExecutionCount"))
        assertEquals(0, merged.getInt("projectionPreviewPublicationCount"))
        assertEquals(0, merged.getInt("previewPublicationCount"))
        assertEquals("none", merged.getString("previewMode"))
        assertTrue(merged.getBoolean("vaeTiled"))
        assertEquals(1, merged.getInt("unetContextLoadCount"))
        assertEquals(1, merged.getInt("unetSamplingLoopCount"))
        assertEquals(30, merged.getInt("unetSamplingStepCount"))
        assertEquals(60, merged.getInt("unetGraphExecutionCount"))
        assertTrue(merged.getBoolean("unetContextReusedAcrossSteps"))
        assertEquals("model", merged.getString("unetGraph"))
        assertEquals("model", merged.getString("vaeGraph"))
        assertEquals(SDXL_ISOLATED_UNET_VAE_MODE, merged.getString("runtimeSessionMode"))
        assertEquals(79, merged.getInt("transportHtpArch"))
        assertEquals(79, merged.getInt("unetTransportHtpArch"))
        assertEquals(73, merged.getInt("vaeTransportHtpArch"))
        assertEquals(1, merged.getInt("vaeContextLoadCount"))
        assertEquals(8, merged.getInt("vaeDecodeSpatialScale"))
        val persistedPhaseProof = merged.getJSONObject("nativeEffective")
            .getJSONObject("sdxlPhaseProof")
        assertEquals(30, persistedPhaseProof.getInt("unetSamplingStepCount"))
        assertEquals(9, persistedPhaseProof.getInt("vaeTileCount"))
        assertEquals(79, persistedPhaseProof.getInt("unetTransportHtpArch"))
        assertEquals(73, persistedPhaseProof.getInt("vaeTransportHtpArch"))
        assertEquals(
            runtimeProfile(79).identitySha256(),
            persistedPhaseProof.getString("unetRuntimeProfileSha256")
        )
        assertEquals(
            runtimeProfile(73).identitySha256(),
            persistedPhaseProof.getString("vaeRuntimeProfileSha256")
        )
        assertTrue(merged.getBoolean("nativeExecution"))
        assertFalse(merged.getBoolean("fallback"))
        assertEquals(
            ImagePixelRange.NEGATIVE_ONE_TO_ONE.name,
            merged.getString("pixelRange")
        )
        assertEquals(
            ImagePixelRange.NEGATIVE_ONE_TO_ONE.name,
            merged.getJSONObject("nativeEffective").getString("pixelRange")
        )
        assertEquals(
            "negative_one_to_one_to_u8",
            merged.getString("pixelRangeConversion")
        )
        assertEquals(1024L * 1024L * 3L, merged.getLong("pixelRangeValueCount"))
        assertEquals(sdxlArtifactSha256(output), merged.getString("outputSha256"))
        assertEquals(
            sdxlArtifactSha256(output),
            persistedPhaseProof.getString("outputSha256")
        )
        validateSdxlFlatNativeEffective(merged)
        val conflictingFlat = JSONObject(merged.toString()).put("steps", 29)
        assertTrue(runCatching { validateSdxlFlatNativeEffective(conflictingFlat) }.isFailure)

        val changedMetadataEvidence = JSONObject(committed.nativeEffectiveJson)
            .put("promptWeightFingerprint", "e".repeat(64))
        assertTrue(
            runCatching {
                mergeSdxlPhaseNativeResults(
                    contract = contract,
                    unetResult = unetResult,
                    unetNative = unetNative,
                    vaeResult = vaeResult,
                    vaeNative = vaeNative,
                    metadata = committed.copy(nativeEffectiveJson = changedMetadataEvidence.toString()),
                    unetRuntimeProfile = runtimeProfile(79),
                    vaeRuntimeProfile = runtimeProfile(79),
                    unetTransportHtpArch = 79,
                    vaeTransportHtpArch = 79,
                    outputFile = output,
                    stageTrace = emptyList()
                )
            }.isFailure
        )

        output.writeBytes(byteArrayOf(3, 2, 1))
        assertTrue(
            runCatching {
                mergeSdxlPhaseNativeResults(
                    contract = contract,
                    unetResult = unetResult,
                    unetNative = unetNative,
                    vaeResult = vaeResult,
                    vaeNative = vaeNative,
                    metadata = committed,
                    unetRuntimeProfile = runtimeProfile(79),
                    vaeRuntimeProfile = runtimeProfile(79),
                    unetTransportHtpArch = 79,
                    vaeTransportHtpArch = 79,
                    outputFile = output,
                    stageTrace = emptyList()
                )
            }.isFailure
        )
    }

    @Test
    fun `encoder bit63 survives native phase metadata result final and history roundtrip`() {
        val inputImageSha256 = "f".repeat(64)
        val inputTensorSha256 = "e".repeat(64)
        val inputImagePath = "/cache/source.png"
        val inputTensorPath = "/cache/input-rgb-nchw.f32"
        val params = contractParams(steps = 30)
            .put("taskMode", LocalImageTaskMode.IMG2IMG.wireName)
            .put("vaeEncoderContextSha256", encoderContextSha256)
            .put("inputImagePath", inputImagePath)
            .put("inputImageSha256", inputImageSha256)
            .put("inputImageTensorPath", inputTensorPath)
            .put("inputImageTensorSha256", inputTensorSha256)
            .put("inputImagePreprocess", SDXL_INPUT_TENSOR_PREPROCESS)
            .put("strength", 1.0)
            .put("fullTimetableCount", 30)
            .put("effectiveDenoiseSteps", 30)
            .put("img2imgBeginIndex", 0)
        val contract = SdxlImageExecutionContract.fromParams(params.toString())
        val encoderLatent = temporaryFolder.newFile("bit63-encoder-latent.f32")
            .apply { writeBytes(ByteArray(1 * 4 * 128 * 128 * 4)) }
        val encoderMetadataFile = File(temporaryFolder.root, "bit63-encoder-latent.json")
        val encoderNative = encoderPhaseEvidence(
            inputImageSha256 = inputImageSha256,
            inputTensorSha256 = inputTensorSha256,
            contextSha256 = encoderContextSha256
        )
            .put("runtimeProfile", "V75")
            .put("htpArchVersion", 75)
        val encoderProof = SdxlNativePhaseProof.fromNativeResult(
            encoderNative,
            SdxlImagePhase.ENCODER
        )
        val encoderMetadata = SdxlEncoderLatentArtifact.publishMetadata(
            requestId = "sdxl-bit63",
            producerPid = 8101,
            contract = contract,
            proof = encoderProof,
            nativeResult = encoderNative,
            latentFile = encoderLatent,
            metadataFile = encoderMetadataFile
        )
        val encoderResult = phaseResult(
            requestId = "sdxl-bit63",
            phase = SdxlImagePhase.ENCODER,
            pid = 8101,
            proof = encoderProof,
            artifact = encoderLatent,
            native = encoderNative
        )
        val encoderResultJson = SdxlImagePhaseProtocol.result(encoderResult)
        val parsedEncoderResult = SdxlImagePhaseProtocol.parseResult(encoderResultJson)

        assertEquals(0x8000000000000000uL, encoderProof.nativeDetailStageMask)
        assertEquals(encoderProof.nativeDetailStageMask, encoderMetadata.encoderNativeDetailStageMask)
        assertEquals(encoderProof.nativeDetailStageMask, parsedEncoderResult.nativeDetailStageMask)
        assertEquals(2, JSONObject(encoderMetadataFile.readText()).getInt("version"))
        assertEquals(
            "8000000000000000",
            JSONObject(encoderMetadataFile.readText())
                .getString("encoderNativeDetailStageMaskHex")
        )

        val unetEffective = JSONObject(params.toString())
            .putAll(nativePromptWeightingEvidence())
            .put("textEncoderExecutionCount", 0)
            .put("conditioningArtifactConsumed", true)
            .put("inputImageExecutionCount", 1)
            .put("runtimeSessionMode", "isolated_unet_phase")
            .put("encoderLatentSha256", encoderMetadata.sha256)
        val latent = temporaryFolder.newFile("bit63-unet-latent.f32")
            .apply { writeBytes(ByteArray(1 * 4 * 128 * 128 * 4)) }
        val metadataFile = File(temporaryFolder.root, "bit63-unet-latent.json")
        val unetNative = JSONObject(unetEffective.toString())
            .putAll(unetPhaseEvidence())
            .put("nativeEffective", JSONObject(unetEffective.toString()))
            .put("ok", true)
            .put("runtimeProfile", "V73")
            .put("htpArchVersion", 73)
            .put("latentDtype", "float32-le")
            .put("encoderLatentSha256", encoderMetadata.sha256)
            .put("nativeGenerationSequence", 72L)
            .put("nativeStageMask", 127L)
            .put("nativeDetailStageMaskHex", "00000000000001ff")
            .withSdxlPreviewDisabled()
        val unetProof = SdxlNativePhaseProof.fromNativeResult(unetNative, SdxlImagePhase.UNET)
        val committed = SdxlLatentArtifact.publishMetadata(
            requestId = "sdxl-bit63",
            producerPid = 8101,
            contract = contract,
            proof = unetProof,
            nativeResult = unetNative,
            latentFile = latent,
            metadataFile = metadataFile
        )
        val output = temporaryFolder.newFile("bit63-output.png")
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val vaeNative = JSONObject()
            .put("ok", true)
            .put("runtimeProfile", "V79")
            .put("htpArchVersion", 79)
            .putAll(vaeDecodeEvidence())
            .putAll(vaePixelRangeEvidence())
            .put("mimeType", "image/png")
            .put("outputPath", output.canonicalPath)
            .put("outputBytes", output.length())
            .put("outputSha256", sdxlArtifactSha256(output))
            .put("nativeGenerationSequence", 73L)
            .put("nativeStageMask", 255L)
            .put("nativeDetailStageMaskHex", "00000000000003ff")
            .withSdxlPreviewDisabled()
        val unetResult = phaseResult(
            "sdxl-bit63",
            SdxlImagePhase.UNET,
            8101,
            unetProof,
            latent,
            unetNative
        )
        val vaeProof = SdxlNativePhaseProof.fromNativeResult(vaeNative, SdxlImagePhase.VAE)
        val vaeResult = phaseResult(
            "sdxl-bit63",
            SdxlImagePhase.VAE,
            8101,
            vaeProof,
            output,
            vaeNative
        )
        val merged = mergeSdxlPhaseNativeResults(
            contract = contract,
            unetResult = unetResult,
            unetNative = unetNative,
            vaeResult = vaeResult,
            vaeNative = vaeNative,
            metadata = committed,
            encoderRuntimeProfile = runtimeProfile(75),
            unetRuntimeProfile = runtimeProfile(73),
            vaeRuntimeProfile = runtimeProfile(79),
            unetTransportHtpArch = 73,
            vaeTransportHtpArch = 79,
            outputFile = output,
            stageTrace = listOf(
                "encoder[pid=8101,profile=V75]:process_exit_confirmed",
                "unet[pid=8101,profile=V73]:process_exit_confirmed",
                "vae[pid=8101,profile=V79]:process_exit_confirmed"
            ),
            encoderResult = encoderResult,
            encoderNative = encoderNative,
            encoderMetadata = encoderMetadata
        )
        val executionMetadata = qnnImageExecutionMetadata(
            nativeRequestId = "qnn-bit63",
            nativeResult = merged,
            outputBytes = output.length()
        )
        val persistedHistoryEvidence = JSONObject(
            sanitizeNativeExecutionJson(executionMetadata.toString())
        )

        listOf(merged, executionMetadata, persistedHistoryEvidence).forEach { evidence ->
            assertEquals(
                "80000000000003ff",
                evidence.getString("nativeDetailStageMaskHex")
            )
            assertFalse(evidence.has("nativeDetailStageMask"))
            assertEquals(75, evidence.getInt("encoderTransportHtpArch"))
            assertEquals(73, evidence.getInt("unetTransportHtpArch"))
            assertEquals(79, evidence.getInt("vaeTransportHtpArch"))
            val exactProfiles = evidence.optJSONObject("sdxlPhaseProof")
                ?: evidence.getJSONObject("nativeEffective").getJSONObject("sdxlPhaseProof")
            assertEquals(
                runtimeProfile(75).identitySha256(),
                exactProfiles.getString("encoderRuntimeProfileSha256")
            )
            assertEquals(
                runtimeProfile(73).identitySha256(),
                exactProfiles.getString("unetRuntimeProfileSha256")
            )
            assertEquals(
                runtimeProfile(79).identitySha256(),
                exactProfiles.getString("vaeRuntimeProfileSha256")
            )
        }
        assertEquals(
            SDXL_ISOLATED_ENCODER_UNET_VAE_MODE,
            merged.getString("runtimeSessionMode")
        )
        assertEquals(
            75,
            merged.getJSONObject("nativeEffective")
                .getJSONObject("sdxlPhaseProof")
                .getInt("encoderTransportHtpArch")
        )
        assertEquals(
            "8000000000000000",
            merged.getString("encoderNativeDetailStageMaskHex")
        )
        assertEquals(
            "80000000000003ff",
            merged.getJSONObject("nativeEffective")
                .getJSONObject("sdxlPhaseProof")
                .getString("nativeDetailStageMaskHex")
        )
    }

    @Test
    fun `vae scaling mismatch fails before final publication`() {
        val contract = SdxlImageExecutionContract.fromParams(contractParams(steps = 30).toString())
        val wrongScale = JSONObject()
            .putAll(vaeDecodeEvidence())
            .put("vaeScalingLocation", ImageVaeScalingLocation.GRAPH_INTERNAL.name)
            .put("vaeScalingFactor", 0.13025)
            .put("effectiveVaeHostScale", 1.0)
            .putAll(vaePixelRangeEvidence())

        assertTrue(runCatching { validateSdxlVaeNativeEvidence(contract, wrongScale) }.isFailure)
    }

    @Test
    fun `tiled vae execution evidence accepts a positive graph execution count`() {
        val contract = SdxlImageExecutionContract.fromParams(contractParams(steps = 30).toString())
        val tiled = JSONObject()
            .putAll(vaeDecodeEvidence())
            .putAll(vaePixelRangeEvidence())

        validateSdxlVaeNativeEvidence(contract, tiled)
        tiled.put("vaeExecutionCount", 0)
        assertTrue(runCatching { validateSdxlVaeNativeEvidence(contract, tiled) }.isFailure)
    }

    @Test
    fun `unet evidence requires one context for the full multi step timetable`() {
        val contract = SdxlImageExecutionContract.fromParams(contractParams(steps = 30).toString())
        val native = nativeEffectiveParams(steps = 30)
            .putAll(unetPhaseEvidence())

        validateSdxlUnetNativeEvidence(contract, native)
        native.put("unetContextLoadCount", 2)
        assertTrue(runCatching { validateSdxlUnetNativeEvidence(contract, native) }.isFailure)
    }

    @Test
    fun `unet evidence rejects forged conditioning consumption fields`() {
        val contract = SdxlImageExecutionContract.fromParams(contractParams(steps = 30).toString())
        val mutations: List<Pair<String, Any>> = listOf(
            "conditioningExecutionMode" to "declared_only",
            "conditioningBackend" to "CPU",
            "conditioningGraph" to "clip.mnn",
            "conditioningGraphSha256" to "f".repeat(64),
            "conditioningOrder" to "positive_then_negative",
            "conditioningEncoderExecutionCount" to 2,
            "textEncoderExecutionCount" to 1,
            "conditioningArtifactConsumed" to false,
            "runtimeSessionMode" to "shared_process"
        )
        mutations.forEach { (field, value) ->
            val flatForgery = nativeEffectiveParams(steps = 30)
                .putAll(unetPhaseEvidence())
                .put(field, value)
            assertTrue(
                "Flat forged field unexpectedly passed: $field",
                runCatching { validateSdxlUnetNativeEvidence(contract, flatForgery) }.isFailure
            )

            val nestedForgery = nativeEffectiveParams(steps = 30)
                .putAll(unetPhaseEvidence())
            nestedForgery.getJSONObject("nativeEffective").put(field, value)
            assertTrue(
                "Nested forged field unexpectedly passed: $field",
                runCatching { validateSdxlUnetNativeEvidence(contract, nestedForgery) }.isFailure
            )
        }
    }

    @Test
    fun `dmd2 single branch still reuses one unet context for all four steps`() {
        val contract = SdxlImageExecutionContract.fromParams(
            contractParams(steps = 4, useCfg = false).toString()
        )
        val native = nativeEffectiveParams(steps = 4, useCfg = false)
            .putAll(unetPhaseEvidence(steps = 4, useCfg = false))

        validateSdxlUnetNativeEvidence(contract, native)
        assertEquals(4, native.getInt("unetSamplingStepCount"))
        assertEquals(4, native.getInt("unetGraphExecutionCount"))
        assertTrue(native.getBoolean("unetContextReusedAcrossSteps"))
    }

    @Test
    fun `tiled vae evidence rejects an incomplete or inconsistent decode plan`() {
        val contract = SdxlImageExecutionContract.fromParams(contractParams(steps = 30).toString())
        val missingShape = vaeDecodeEvidence()
            .putAll(vaePixelRangeEvidence())
            .apply { remove("vaeInputLatentShape") }
        val wrongTileCount = vaeDecodeEvidence()
            .putAll(vaePixelRangeEvidence())
            .put("vaeTileCount", 4)

        assertTrue(runCatching { validateSdxlVaeNativeEvidence(contract, missingShape) }.isFailure)
        assertTrue(runCatching { validateSdxlVaeNativeEvidence(contract, wrongTileCount) }.isFailure)
    }

    @Test
    fun `vae pixel range mismatch fails before final publication`() {
        val contract = SdxlImageExecutionContract.fromParams(contractParams(steps = 30).toString())
        val wrongRange = JSONObject()
            .putAll(vaeDecodeEvidence())
            .putAll(vaePixelRangeEvidence(ImagePixelRange.ZERO_TO_ONE))

        assertTrue(runCatching { validateSdxlVaeNativeEvidence(contract, wrongRange) }.isFailure)
    }

    @Test
    fun `manifest declares three private disposable phase processes`() {
        val manifest = sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).first { it.isFile }.readText()

        assertTrue(manifest.contains(".SdxlEncoderWorkerService"))
        assertTrue(manifest.contains("android:process=\":sdxl_encoder_v75\""))
        assertTrue(manifest.contains(".SdxlUnetWorkerService"))
        assertTrue(manifest.contains("android:process=\":sdxl_unet_v75\""))
        assertTrue(manifest.contains(".SdxlVaeWorkerService"))
        assertTrue(manifest.contains("android:process=\":sdxl_vae_v75\""))
        listOf(
            "SdxlEncoderWorkerService",
            "SdxlUnetWorkerService",
            "SdxlVaeWorkerService"
        ).forEach { service ->
            assertFalse(manifest.contains("$service\"\n            android:exported=\"true"))
        }
    }

    @Test
    fun `phase process lifetime retains request ownership until process death`() {
        val lockFile = temporaryFolder.newFile("phase-death-barrier.lock")
        val lifetime = SdxlPhaseProcessLifetime()
        assertTrue(lifetime.tryClaimRequest())
        val phaseOwnership = requireNotNull(
            SdxlRequestFileLock.acquire(lockFile, shared = true, timeoutMs = 0L)
        )
        try {
            lifetime.retainPhaseOwnershipUntilProcessDeath(phaseOwnership)

            assertTrue(lifetime.beginRetirement())
            assertTrue(lifetime.isRetiring)
            assertTrue(lifetime.hasRetainedPhaseOwnership)
            assertTrue(
                "Recovery must remain blocked after callback/finally retirement and before death.",
                SdxlRequestFileLock.acquire(lockFile, shared = false, timeoutMs = 0L) == null
            )
        } finally {
            // Production has no corresponding release path; the OS releases this lock on process
            // death. The test closes its synthetic lock only to keep the host JVM reusable.
            phaseOwnership.close()
        }
    }

    @Test
    fun `phase process lifetime is one shot and rejects same process reentry`() {
        val lifetime = SdxlPhaseProcessLifetime()

        assertTrue(lifetime.acceptsRequests)
        assertTrue(lifetime.tryClaimRequest())
        assertFalse(lifetime.acceptsRequests)
        assertFalse(lifetime.tryClaimRequest())
        assertTrue(lifetime.beginRetirement())
        assertTrue(lifetime.isRetiring)
        assertFalse(lifetime.tryClaimRequest())
        assertFalse(lifetime.beginRetirement())
    }

    private fun contractParams(steps: Int, useCfg: Boolean = true): JSONObject {
        val timetableCount = steps
        val unetExecutionCount = timetableCount * if (useCfg) 2 else 1
        return JSONObject()
            .put("profileId", "generic.sdxl.test")
            .put("profileRevision", 3)
            .put("modelFingerprint", "a".repeat(64))
            .put("runtime", LocalImageRuntime.QNN_HTP.name)
            .put("scheduler", ImageSchedulerAlgorithm.DPMPP_2M.name)
            .put("predictionType", ImagePredictionType.EPSILON.name)
            .put("steps", steps)
            .put("timetableCount", timetableCount)
            .put("unetExecutionCount", unetExecutionCount)
            .put("expectedTimetableCount", timetableCount)
            .put("expectedUnetExecutionCount", unetExecutionCount)
            .put("cfgScale", if (useCfg) 7.0 else 1.0)
            .put("useCfg", useCfg)
            .put("unconditionalBranch", useCfg)
            .put("tokenizerBackend", ImageTokenizerBackend.TOKENIZERS_CPP.name)
            .put("tokenCount", if (useCfg) 154 else 77)
            .put("promptWeightingSupported", true)
            .put("embeddingDiskDataType", ImageEmbeddingDiskDataType.FP16.name)
            .put("vaeScalingLocation", ImageVaeScalingLocation.HOST_BEFORE_GRAPH.name)
            .put("vaeScalingFactor", 0.13025)
            .put("pixelRange", ImagePixelRange.NEGATIVE_ONE_TO_ONE.name)
            .put("conditioningArtifactSha256", conditioningArtifactSha256)
            .put("nativePromptExecutionSha256", "d".repeat(64))
            .put("nativePromptBindingStage", "conditioning_encoded")
            .put("taskMode", LocalImageTaskMode.TEXT_TO_IMAGE.wireName)
            .put("conditioningExecutionMode", "external_mnn_sdxl_embeddings")
            .put("conditioningBackend", "MNN")
            .put("conditioningGraph", "clip.mnn+clip_2.mnn")
            .put("conditioningGraphSha256", "e".repeat(64))
            .put("conditioningOrder", if (useCfg) "negative_then_positive" else "positive_only")
            .put("conditioningEncoderExecutionCount", if (useCfg) 4 else 2)
            .put("width", 1024)
            .put("height", 1024)
            .put("seed", 1234L)
            .put("graphName", "model")
            .put("fallback", false)
    }

    private fun nativeEffectiveParams(steps: Int, useCfg: Boolean = true): JSONObject =
        JSONObject(contractParams(steps, useCfg).toString())
            .putAll(nativePromptWeightingEvidence())
            .put("nativePromptExecutionSha256", "d".repeat(64))
            .put("nativePromptBindingStage", "conditioning_consumed")
            .put("conditioningExecutionMode", "external_mnn_sdxl_embeddings")
            .put("conditioningBackend", "MNN")
            .put("conditioningGraph", "clip.mnn+clip_2.mnn")
            .put("conditioningGraphSha256", "e".repeat(64))
            .put("conditioningOrder", if (useCfg) "negative_then_positive" else "positive_only")
            .put("conditioningEncoderExecutionCount", if (useCfg) 4 else 2)
            .put("textEncoderExecutionCount", 0)
            .put("conditioningArtifactConsumed", true)
            .put("inputImageExecutionCount", 0)
            .put("inputImagePath", "")
            .put("runtimeSessionMode", "isolated_unet_phase")

    private fun nativePromptWeightingEvidence(): JSONObject = JSONObject()
        .put("promptWeightingApplied", false)
        .put("positiveWeightedTokenCount", 0)
        .put("negativeWeightedTokenCount", 0)
        .put("promptWeightFingerprint", "c".repeat(64))

    private fun unetPhaseEvidence(steps: Int = 30, useCfg: Boolean = true): JSONObject {
        val timetableCount = steps
        val unetExecutionCount = timetableCount * if (useCfg) 2 else 1
        val latentElements = 1L * 4L * 128L * 128L
        return JSONObject()
            .put("nativeEffective", nativeEffectiveParams(steps, useCfg))
            .put("phase", SdxlImagePhase.UNET.wireName)
            .put("processExitRequired", true)
            .put("contextReleased", false)
            .put("latentShape", JSONArray(listOf(1, 4, 128, 128)))
            .put("latentElements", latentElements)
            .put("latentBytes", latentElements * 4L)
            .put("unetContextLoadCount", 1)
            .put("unetSamplingLoopCount", 1)
            .put("unetSamplingStepCount", timetableCount)
            .put("unetGraphExecutionCount", unetExecutionCount)
            .put("unetContextReusedAcrossSteps", true)
            .put("unetGraphName", "model")
    }

    private fun encoderPhaseEvidence(
        inputImageSha256: String,
        inputTensorSha256: String,
        contextSha256: String
    ): JSONObject {
        val latentElements = 1L * 4L * 128L * 128L
        return JSONObject()
            .put("ok", true)
            .put("phase", SdxlImagePhase.ENCODER.wireName)
            .put("taskMode", LocalImageTaskMode.IMG2IMG.wireName)
            .put("processExitRequired", true)
            .put("contextReleased", false)
            .put("runtimeProfile", "V79")
            .put("htpArchVersion", 79)
            .put("encoderContextLoadCount", 1)
            .put("encoderExecutionCount", 1)
            .put("encoderGraphName", "model")
            .put("encoderContextSha256", contextSha256)
            .put("encoderInputName", "input")
            .put("encoderMeanOutputName", "mean")
            .put("encoderStdOutputName", "std")
            .put("encoderInputDtype", "float32")
            .put("encoderMeanDtype", "float32")
            .put("encoderStdDtype", "float32")
            .put("encoderInputShape", JSONArray(listOf(1, 3, 1024, 1024)))
            .put("encoderMeanShape", JSONArray(listOf(1, 4, 128, 128)))
            .put("encoderStdShape", JSONArray(listOf(1, 4, 128, 128)))
            .put("latentDtype", "float32-le")
            .put("latentShape", JSONArray(listOf(1, 4, 128, 128)))
            .put("latentElements", latentElements)
            .put("latentBytes", latentElements * 4L)
            .put("posteriorSampling", "mean_plus_std_times_normal_mt19937_domain_v1")
            .put("posteriorSampleCount", latentElements)
            .put("encoderLatentScalingFactor", SDXL_QNN_VAE_SCALING_FACTOR)
            .put("inputImageSha256", inputImageSha256)
            .put("inputImageSourceReadByNative", false)
            .put("inputImageSourceValidation", "android_preprocess_provenance")
            .put("inputImageTensorSha256", inputTensorSha256)
            .put("inputImagePreprocess", SDXL_INPUT_TENSOR_PREPROCESS)
            .put("inputImageTensorDtype", SDXL_INPUT_TENSOR_DTYPE)
            .put("inputImageTensorLayout", SDXL_INPUT_TENSOR_LAYOUT)
            .put("inputImageTensorRange", SDXL_INPUT_TENSOR_RANGE)
            .put("inputImageTensorShape", JSONArray(listOf(1, 3, 1024, 1024)))
            .put("inputImageTensorBytes", 1L * 3L * 1024L * 1024L * 4L)
            .put("nativeGenerationSequence", 71L)
            .put("nativeStageMask", 127L)
            .put("nativeDetailStageMaskHex", "8000000000000000")
            .withSdxlPreviewDisabled()
    }

    private fun vaeDecodeEvidence(): JSONObject = JSONObject()
        .put("phase", SdxlImagePhase.VAE.wireName)
        .put("processExitRequired", true)
        .put("contextReleased", false)
        .put("vaeScalingLocation", ImageVaeScalingLocation.HOST_BEFORE_GRAPH.name)
        .put("vaeScalingFactor", SDXL_QNN_VAE_SCALING_FACTOR)
        .put("effectiveVaeHostScale", 1.0 / SDXL_QNN_VAE_SCALING_FACTOR)
        .put("vaeExecutionCount", 9)
        .put("vaeTileCount", 9)
        .put("vaeTiled", true)
        .put("vaeContextLoadCount", 1)
        .put("vaeGraphName", "model")
        .put("vaeSourceLatentShape", JSONArray(listOf(1, 4, 128, 128)))
        .put("vaeInputLatentShape", JSONArray(listOf(1, 4, 64, 64)))
        .put("vaeOutputTileShape", JSONArray(listOf(1, 3, 512, 512)))
        .put("vaeFinalOutputShape", JSONArray(listOf(1, 3, 1024, 1024)))
        .put("vaeDecodeSpatialScale", 8)
        .put("conditioningArtifactSha256", conditioningArtifactSha256)
        .put("width", 1024)
        .put("height", 1024)
        .put("outputSha256", "d".repeat(64))

    private fun sdxlPhaseProofEvidence(): JSONObject = JSONObject()
        .put("unetTransportHtpArch", 79)
        .put("vaeTransportHtpArch", 73)
        .put("unetRuntimeProfileSha256", "a".repeat(64))
        .put("vaeRuntimeProfileSha256", "b".repeat(64))
        .put("unetContextLoadCount", 1)
        .put("unetSamplingLoopCount", 1)
        .put("unetSamplingStepCount", 30)
        .put("unetGraphExecutionCount", 60)
        .put("unetContextReusedAcrossSteps", true)
        .put("unetGraphName", "model")
        .put("vaeContextLoadCount", 1)
        .put("vaeExecutionCount", 9)
        .put("vaeTileCount", 9)
        .put("vaeTiled", true)
        .put("vaeGraphName", "model")
        .put("vaeSourceLatentShape", JSONArray(listOf(1, 4, 128, 128)))
        .put("vaeInputLatentShape", JSONArray(listOf(1, 4, 64, 64)))
        .put("vaeOutputTileShape", JSONArray(listOf(1, 3, 512, 512)))
        .put("vaeFinalOutputShape", JSONArray(listOf(1, 3, 1024, 1024)))
        .put("vaeDecodeSpatialScale", 8)

    private fun vaePixelRangeEvidence(
        range: ImagePixelRange = ImagePixelRange.NEGATIVE_ONE_TO_ONE
    ): JSONObject = JSONObject()
        .put("pixelRange", range.name)
        .put(
            "pixelRangeConversion",
            ImageExecutionProfileNativeContract.qnnPixelRangeConversionName(range)
        )
        .put("pixelRangeValueCount", 1024L * 1024L * 3L)
        .put("pixelRangeClampedValueCount", 17L)
        .put("pixelRangeObservedMin", -1.125)
        .put("pixelRangeObservedMax", 1.25)

    private fun runtimeProfile(
        arch: Int,
        hostDirectory: String = "/packaged-qnn",
        dspDirectory: String = hostDirectory
    ): SdxlQnnRuntimeProfile = SdxlQnnRuntimeProfile(
        hostDirectory = hostDirectory,
        dspDirectory = dspDirectory,
        htpArchVersion = arch
    )

    private fun runtimeEvidence(profile: SdxlQnnRuntimeProfile): JSONObject = JSONObject()
        .put("exactRoleBinding", true)
        .put("hostRuntimeDirectory", profile.hostDirectory)
        .put("dspRuntimeDirectory", profile.dspDirectory)
        .put("htpArchVersion", profile.htpArchVersion)

    private fun phaseResult(
        requestId: String,
        phase: SdxlImagePhase,
        pid: Int,
        proof: SdxlNativePhaseProof,
        artifact: File,
        native: JSONObject
    ): SdxlImagePhaseResult = SdxlImagePhaseResult(
        requestId = requestId,
        phase = phase,
        workerPid = pid,
        runtimeProfile = sdxlTransportProfile(native.getInt("htpArchVersion")),
        artifactPath = artifact.canonicalPath,
        metadataPath = "",
        nativeGenerationSequence = proof.nativeGenerationSequence,
        nativeStageMask = proof.nativeStageMask,
        nativeDetailStageMask = proof.nativeDetailStageMask,
        conditioningArtifactSha256 = conditioningArtifactSha256,
        nativeResultJson = native.toString()
    )

    private fun JSONObject.withSdxlPreviewDisabled(): JSONObject = put("previewRequested", false)
        .put("previewMode", "none")
        .put("previewInterval", 0)
        .put("previewPath", "")
        .put("previewMimeType", "")
        .put("previewStep", 0)
        .put("previewRevision", 0)
        .put("previewWidth", 0)
        .put("previewHeight", 0)
        .put("previewFrameCount", 0)
        .put("previewNoisy", false)
        .put("previewVaeExecutionAttemptCount", 0)
        .put("previewVaeExecutionCount", 0)
        .put("previewVaeExecutionMsTotal", 0)
        .put("previewPublicationCount", 0)
        .put("previewLastStep", 0)
        .put("previewLastRevision", 0)
        .put("previewFailureCode", "")
        .put("projectionPreviewAttemptCount", 0)
        .put("projectionPreviewPublicationCount", 0)
        .put("projectionPreviewProjectionMsTotal", 0)
        .put("projectionPreviewLastStep", 0)
        .put("projectionPreviewLastRevision", 0)
        .put("projectionPreviewFailureCode", "")
        .put("previewDegraded", false)
}

private fun JSONObject.putAll(values: JSONObject): JSONObject = apply {
    values.keys().forEach { key -> put(key, values.get(key)) }
}
