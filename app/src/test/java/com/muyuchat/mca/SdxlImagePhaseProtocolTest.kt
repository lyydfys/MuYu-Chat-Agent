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
            journalPath = "/cache/unet.json"
        )

        val parsed = SdxlImagePhaseProtocol.parseRequest(SdxlImagePhaseProtocol.request(request))

        assertEquals(SdxlImagePhase.UNET, parsed.phase)
        assertEquals(0, parsed.expectedHtpArch)
        assertEquals(30, parsed.steps)
        assertEquals(1024, parsed.width)
        assertEquals("generic.sdxl.test", parsed.profileId)

        val tampered = JSONObject(SdxlImagePhaseProtocol.request(request)).put("steps", 1)
        assertTrue(runCatching { SdxlImagePhaseProtocol.parseRequest(tampered.toString()) }.isFailure)
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
        latent.writeBytes(ByteArray(4 * 2 * 2 * 4) { index -> index.toByte() })
        val metadataFile = File(temporaryFolder.root, "latent.json")
        val native = JSONObject()
            .putAll(nativeEffectiveParams(steps = 30))
            .put("runtimeProfile", "V79")
            .put("htpArchVersion", 79)
            .put("latentDtype", "float32-le")
            .put("latentShape", JSONArray(listOf(1, 4, 2, 2)))
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
            .apply { writeBytes(ByteArray(4 * 2 * 2 * 4)) }
        val metadataFile = File(temporaryFolder.root, "contract-latent.json")
        val params = contractParams(steps = 30)
        val contract = SdxlImageExecutionContract.fromParams(params.toString())
        val native = JSONObject(params.toString())
            .putAll(nativePromptWeightingEvidence())
            .put("runtimeProfile", "V79")
            .put("htpArchVersion", 79)
            .put("latentDtype", "float32-le")
            .put("latentShape", JSONArray(listOf(1, 4, 2, 2)))
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
        assertEquals(30, metadata.getInt("steps"))
    }

    @Test
    fun `multi step phase deadlines fit inside the step aware outer watchdog`() {
        val unetTimeout = sdxlUnetPhaseTimeoutMs(unetExecutionCount = 60)
        val vaeTimeout = sdxlVaePhaseTimeoutMs(steps = 30)
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
            .apply { writeBytes(ByteArray(4 * 2 * 2 * 4)) }
        val metadataFile = File(temporaryFolder.root, "merge-latent.json")
        val output = temporaryFolder.newFile("merge-output.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val unetNative = JSONObject(params.toString())
            .putAll(nativePromptWeightingEvidence())
            .put("ok", true)
            .put("runtimeProfile", "V79")
            .put("htpArchVersion", 79)
            .put("latentDtype", "float32-le")
            .put("latentShape", JSONArray(listOf(1, 4, 2, 2)))
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
            .put("vaeScalingLocation", ImageVaeScalingLocation.HOST_BEFORE_GRAPH.name)
            .put("vaeScalingFactor", 0.13025)
            .put("effectiveVaeHostScale", 1.0 / 0.13025)
            .put("vaeExecutionCount", 1)
            .put("width", 1024)
            .put("height", 1024)
            .putAll(vaePixelRangeEvidence())
            .put("mimeType", "image/png")
            .put("outputPath", output.canonicalPath)
            .put("outputBytes", output.length())
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
    }

    @Test
    fun `vae scaling mismatch fails before final publication`() {
        val contract = SdxlImageExecutionContract.fromParams(contractParams(steps = 30).toString())
        val wrongScale = JSONObject()
            .put("vaeScalingLocation", ImageVaeScalingLocation.GRAPH_INTERNAL.name)
            .put("vaeScalingFactor", 0.13025)
            .put("effectiveVaeHostScale", 1.0)
            .put("vaeExecutionCount", 1)
            .put("width", 1024)
            .put("height", 1024)
            .putAll(vaePixelRangeEvidence())

        assertTrue(runCatching { validateSdxlVaeNativeEvidence(contract, wrongScale) }.isFailure)
    }

    @Test
    fun `vae pixel range mismatch fails before final publication`() {
        val contract = SdxlImageExecutionContract.fromParams(contractParams(steps = 30).toString())
        val wrongRange = JSONObject()
            .put("vaeScalingLocation", ImageVaeScalingLocation.HOST_BEFORE_GRAPH.name)
            .put("vaeScalingFactor", 0.13025)
            .put("effectiveVaeHostScale", 1.0 / 0.13025)
            .put("vaeExecutionCount", 1)
            .put("width", 1024)
            .put("height", 1024)
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
            .put("cfgScale", 7.0)
            .put("useCfg", useCfg)
            .put("unconditionalBranch", useCfg)
            .put("tokenizerBackend", ImageTokenizerBackend.TOKENIZERS_CPP.name)
            .put("tokenCount", 154)
            .put("promptWeightingSupported", true)
            .put("embeddingDiskDataType", ImageEmbeddingDiskDataType.FP16.name)
            .put("vaeScalingLocation", ImageVaeScalingLocation.HOST_BEFORE_GRAPH.name)
            .put("vaeScalingFactor", 0.13025)
            .put("pixelRange", ImagePixelRange.NEGATIVE_ONE_TO_ONE.name)
            .put("width", 1024)
            .put("height", 1024)
            .put("seed", 1234L)
            .put("graphName", "model")
            .put("fallback", false)
    }

    private fun nativeEffectiveParams(steps: Int, useCfg: Boolean = true): JSONObject =
        JSONObject(contractParams(steps, useCfg).toString())
            .putAll(nativePromptWeightingEvidence())

    private fun nativePromptWeightingEvidence(): JSONObject = JSONObject()
        .put("promptWeightingApplied", false)
        .put("positiveWeightedTokenCount", 0)
        .put("negativeWeightedTokenCount", 0)
        .put("promptWeightFingerprint", "c".repeat(64))

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
        nativeResultJson = native.toString()
    )
}

private fun JSONObject.putAll(values: JSONObject): JSONObject = apply {
    values.keys().forEach { key -> put(key, values.get(key)) }
}
