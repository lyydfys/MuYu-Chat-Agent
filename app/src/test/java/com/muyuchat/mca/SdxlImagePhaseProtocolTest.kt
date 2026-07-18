package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SdxlImagePhaseProtocolTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val conditioningArtifactSha256 = "d".repeat(64)

    @Test
    fun `request carries multi step size profile and one expected runtime profile`() {
        val params = contractParams(steps = 30)
        val request = SdxlImagePhaseRequest(
            requestId = "sdxl-1",
            phase = SdxlImagePhase.UNET,
            expectedHtpArch = SDXL_AUTO_TRANSPORT_HTP_ARCH,
            profileId = "generic.sdxl.test",
            profileRevision = 3,
            modelFingerprint = "a".repeat(64),
            steps = 30,
            width = 1024,
            height = 1024,
            bundleRoot = "/bundle",
            runtimeDirsJson = "[\"/packaged-qnn\"]",
            paramsJson = params.toString(),
            embeddingsPath = "/cache/conditioning.f32",
            latentPath = "/cache/latent.f32",
            metadataPath = "/cache/latent.json",
            outputPath = "",
            journalPath = "/cache/unet.json",
            conditioningArtifactSha256 = conditioningArtifactSha256
        )

        val parsed = SdxlImagePhaseProtocol.parseRequest(SdxlImagePhaseProtocol.request(request))

        assertEquals(SdxlImagePhase.UNET, parsed.phase)
        assertEquals(0, parsed.expectedHtpArch)
        assertEquals(30, parsed.steps)
        assertEquals(1024, parsed.width)
        assertEquals("generic.sdxl.test", parsed.profileId)
        assertEquals(conditioningArtifactSha256, parsed.conditioningArtifactSha256)

        val tampered = JSONObject(SdxlImagePhaseProtocol.request(request)).put("steps", 1)
        assertTrue(runCatching { SdxlImagePhaseProtocol.parseRequest(tampered.toString()) }.isFailure)
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

        val parsed = SdxlImagePhaseProtocol.parseProgress(SdxlImagePhaseProtocol.progress(envelope))

        assertEquals(SdxlImagePhase.VAE, parsed.phase)
        assertEquals(7502, parsed.workerPid)
        assertEquals("V75", parsed.runtimeProfile)
        assertEquals(envelope.progress.stageTrace, parsed.progress.stageTrace)
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
            .put("nativeDetailStageMask", 511L)
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
            .put("nativeDetailStageMask", 511L)
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
            .put("nativeDetailStageMask", 511L)
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
            .put("nativeDetailStageMask", 511L)

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
    fun `merged journal keeps process and profile boundaries distinct`() {
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
        var trace = SdxlTwoPhaseJournal.merge(emptyList(), event(SdxlImagePhase.UNET, 75, "V75", "unet_graph_execute"))
        trace = SdxlTwoPhaseJournal.appendBoundary(
            trace, SdxlImagePhase.UNET, 75, "V75", "process_exit_confirmed"
        )
        trace = SdxlTwoPhaseJournal.merge(trace, event(SdxlImagePhase.VAE, 76, "V75", "vae_graph_execute"))

        assertTrue(trace.any { it == "unet[pid=75,profile=V75]:process_exit_confirmed" })
        assertTrue(trace.any { it == "vae[pid=76,profile=V75]:vae_graph_execute" })
        assertNotEquals(75, 76)
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
    fun `unet auto transport accepts native V79 and vae binds the same transport`() {
        val native = JSONObject().put("htpArchVersion", 79)

        assertEquals(
            79,
            validateSdxlNativeTransport(
                SdxlImagePhase.UNET,
                SDXL_AUTO_TRANSPORT_HTP_ARCH,
                native
            )
        )
        assertEquals(79, validateSdxlNativeTransport(SdxlImagePhase.VAE, 79, native))
        assertEquals("V79", sdxlTransportProfile(79))
        assertEquals("AUTO", sdxlTransportProfile(SDXL_AUTO_TRANSPORT_HTP_ARCH))
        assertTrue(
            runCatching {
                validateSdxlNativeTransport(
                    SdxlImagePhase.VAE,
                    75,
                    native
                )
            }.isFailure
        )
    }

    @Test
    fun `final execution metadata preserves dynamic transport and isolated process proof`() {
        val nativeExecution = nativeEffectiveParams(steps = 30)
            .put("runtimeSessionMode", "isolated_unet_then_vae_same_transport")
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
                .put("nativeDetailStageMask", 1023L)
                .put("runtimeSessionMode", "isolated_unet_then_vae_same_transport")
                .put("conditioningFormat", "sdxl_qnn_conditioning")
                .put("archiveContextHtpArch", 75)
                .put("transportHtpArch", 79)
                .put("unetWorkerPid", 14149)
                .put("unetRuntimeProfile", "V79")
                .put("unetProcessDeathConfirmed", true)
                .put("vaeWorkerPid", 14242)
                .put("vaeRuntimeProfile", "V79")
                .put("vaeTransportHtpArch", 79)
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

        assertEquals(75, metadata.getInt("archiveContextHtpArch"))
        assertEquals(79, metadata.getInt("transportHtpArch"))
        assertEquals("V79", metadata.getString("unetRuntimeProfile"))
        assertEquals("V79", metadata.getString("vaeRuntimeProfile"))
        assertTrue(metadata.getBoolean("unetProcessDeathConfirmed"))
        assertTrue(metadata.getBoolean("vaeProcessDeathConfirmed"))
        assertFalse(metadata.getBoolean("fallback"))
        assertEquals(7L, metadata.getLong("nativeGenerationSequence"))
        assertEquals(123_456L, metadata.getLong("nativeStartedAtMonotonicMs"))
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
            "isolated_unet_then_vae_same_transport",
            metadata.getJSONObject("nativeEffective").getString("runtimeSessionMode")
        )
        assertEquals(
            9,
            metadata.getJSONObject("nativeEffective")
                .getJSONObject("sdxlPhaseProof")
                .getInt("vaeTileCount")
        )
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
    fun `two phase merge returns flat strict execution and native proofs`() {
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
            .put("nativeDetailStageMask", 511L)
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
            .put("runtimeProfile", "V79")
            .put("htpArchVersion", 79)
            .putAll(vaeDecodeEvidence())
            .putAll(vaePixelRangeEvidence())
            .put("mimeType", "image/png")
            .put("outputPath", output.canonicalPath)
            .put("outputBytes", output.length())
            .put("outputSha256", sdxlArtifactSha256(output))
            .put("nativeGenerationSequence", 52L)
            .put("nativeStageMask", 255L)
            .put("nativeDetailStageMask", 1023L)
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
            pid = 8102,
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
            transportHtpArch = 79,
            vaeTransportHtpArch = 79,
            outputFile = output,
            stageTrace = listOf("unet:completed", "vae:completed")
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
        assertTrue(merged.getBoolean("vaeTiled"))
        assertEquals(1, merged.getInt("unetContextLoadCount"))
        assertEquals(1, merged.getInt("unetSamplingLoopCount"))
        assertEquals(30, merged.getInt("unetSamplingStepCount"))
        assertEquals(60, merged.getInt("unetGraphExecutionCount"))
        assertTrue(merged.getBoolean("unetContextReusedAcrossSteps"))
        assertEquals("model", merged.getString("unetGraph"))
        assertEquals("model", merged.getString("vaeGraph"))
        assertEquals(1, merged.getInt("vaeContextLoadCount"))
        assertEquals(8, merged.getInt("vaeDecodeSpatialScale"))
        val persistedPhaseProof = merged.getJSONObject("nativeEffective")
            .getJSONObject("sdxlPhaseProof")
        assertEquals(30, persistedPhaseProof.getInt("unetSamplingStepCount"))
        assertEquals(9, persistedPhaseProof.getInt("vaeTileCount"))
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
                    transportHtpArch = 79,
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
                    transportHtpArch = 79,
                    vaeTransportHtpArch = 79,
                    outputFile = output,
                    stageTrace = emptyList()
                )
            }.isFailure
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
    fun `manifest declares two different disposable phase processes`() {
        val manifest = sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).first { it.isFile }.readText()

        assertTrue(manifest.contains(".SdxlUnetWorkerService"))
        assertTrue(manifest.contains("android:process=\":sdxl_unet_v75\""))
        assertTrue(manifest.contains(".SdxlVaeWorkerService"))
        assertTrue(manifest.contains("android:process=\":sdxl_vae_v75\""))
        assertFalse(manifest.contains("SdxlUnetWorkerService\"\n            android:exported=\"true"))
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
            .put("tokenCount", 154)
            .put("promptWeightingSupported", true)
            .put("embeddingDiskDataType", ImageEmbeddingDiskDataType.FP16.name)
            .put("vaeScalingLocation", ImageVaeScalingLocation.HOST_BEFORE_GRAPH.name)
            .put("vaeScalingFactor", 0.13025)
            .put("pixelRange", ImagePixelRange.NEGATIVE_ONE_TO_ONE.name)
            .put("conditioningArtifactSha256", conditioningArtifactSha256)
            .put("conditioningExecutionMode", "external_mnn_sdxl_embeddings")
            .put("conditioningBackend", "MNN")
            .put("conditioningGraph", "clip.mnn+clip_2.mnn")
            .put("conditioningGraphSha256", "e".repeat(64))
            .put("conditioningOrder", "negative_then_positive")
            .put("conditioningEncoderExecutionCount", 4)
            .put("width", 1024)
            .put("height", 1024)
            .put("seed", 1234L)
            .put("graphName", "model")
            .put("fallback", false)
    }

    private fun nativeEffectiveParams(steps: Int, useCfg: Boolean = true): JSONObject =
        JSONObject(contractParams(steps, useCfg).toString())
            .putAll(nativePromptWeightingEvidence())
            .put("conditioningExecutionMode", "external_mnn_sdxl_embeddings")
            .put("conditioningBackend", "MNN")
            .put("conditioningGraph", "clip.mnn+clip_2.mnn")
            .put("conditioningGraphSha256", "e".repeat(64))
            .put("conditioningOrder", "negative_then_positive")
            .put("conditioningEncoderExecutionCount", 4)
            .put("textEncoderExecutionCount", 0)
            .put("conditioningArtifactConsumed", true)
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
        runtimeProfile = "V79",
        artifactPath = artifact.canonicalPath,
        metadataPath = "",
        nativeGenerationSequence = proof.nativeGenerationSequence,
        nativeStageMask = proof.nativeStageMask,
        nativeDetailStageMask = proof.nativeDetailStageMask,
        conditioningArtifactSha256 = conditioningArtifactSha256,
        nativeResultJson = native.toString()
    )
}

private fun JSONObject.putAll(values: JSONObject): JSONObject = apply {
    values.keys().forEach { key -> put(key, values.get(key)) }
}
