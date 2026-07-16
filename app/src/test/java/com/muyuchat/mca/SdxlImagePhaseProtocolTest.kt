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
    fun `request carries explicit phase and one expected runtime profile`() {
        val request = SdxlImagePhaseRequest(
            requestId = "sdxl-1",
            phase = SdxlImagePhase.UNET,
            expectedHtpArch = SDXL_AUTO_TRANSPORT_HTP_ARCH,
            bundleRoot = "/bundle",
            runtimeDirsJson = "[\"/packaged-qnn\"]",
            paramsJson = "{\"steps\":1}",
            embeddingsPath = "/cache/conditioning.f32",
            latentPath = "/cache/latent.f32",
            metadataPath = "/cache/latent.json",
            outputPath = "",
            journalPath = "/cache/unet.json"
        )

        val parsed = SdxlImagePhaseProtocol.parseRequest(SdxlImagePhaseProtocol.request(request))

        assertEquals(SdxlImagePhase.UNET, parsed.phase)
        assertEquals(0, parsed.expectedHtpArch)
        assertFalse(parsed.paramsJson.contains("vae"))
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
                steps = 1,
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
            .put("runtimeProfile", "V79")
            .put("htpArchVersion", 79)
            .put("latentDtype", "float32-le")
            .put("latentShape", JSONArray(listOf(1, 4, 2, 2)))

        val published = SdxlLatentArtifact.publishMetadata(
            requestId = "sdxl-3",
            producerPid = 7503,
            nativeResult = native,
            latentFile = latent,
            metadataFile = metadataFile
        )
        val validated = SdxlLatentArtifact.validate(
            requestId = "sdxl-3",
            latentFile = latent,
            metadataFile = metadataFile,
            expectedProducerArch = 79
        )

        assertEquals(published.sha256, validated.sha256)
        assertTrue(metadataFile.readText().contains("\"committed\":true"))
        latent.appendBytes(byteArrayOf(1))
        val failure = runCatching {
            SdxlLatentArtifact.validate("sdxl-3", latent, metadataFile, 79)
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("byte size"))
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
                    steps = 1,
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
        val metadata = qnnImageExecutionMetadata(
            nativeRequestId = "qnn-native-1",
            nativeResult = JSONObject()
                .put("backend", "qnn_htp")
                .put("executionStage", "sdxl_two_phase_passed")
                .put("npuActive", true)
                .put("qnnGraphExecution", true)
                .put("nativeExecution", true)
                .put("fallback", false)
                .put("runtimeSessionMode", "isolated_unet_then_vae_same_transport")
                .put("archiveContextHtpArch", 75)
                .put("transportHtpArch", 79)
                .put("unetWorkerPid", 14149)
                .put("unetRuntimeProfile", "V79")
                .put("unetProcessDeathConfirmed", true)
                .put("vaeWorkerPid", 14242)
                .put("vaeRuntimeProfile", "V79")
                .put("vaeTransportHtpArch", 79)
                .put("vaeProcessDeathConfirmed", true),
            outputBytes = 2_834_965L
        )

        assertEquals(75, metadata.getInt("archiveContextHtpArch"))
        assertEquals(79, metadata.getInt("transportHtpArch"))
        assertEquals("V79", metadata.getString("unetRuntimeProfile"))
        assertEquals("V79", metadata.getString("vaeRuntimeProfile"))
        assertTrue(metadata.getBoolean("unetProcessDeathConfirmed"))
        assertTrue(metadata.getBoolean("vaeProcessDeathConfirmed"))
        assertFalse(metadata.getBoolean("fallback"))
    }

    @Test
    fun `phase deadlines fit inside the outer disposable worker watchdog`() {
        assertTrue(SDXL_UNET_PHASE_TIMEOUT_MS < SDXL_QNN_WORKER_TIMEOUT_MS)
        assertTrue(SDXL_VAE_PHASE_TIMEOUT_MS < SDXL_QNN_WORKER_TIMEOUT_MS)
        assertTrue(
            SDXL_UNET_PHASE_TIMEOUT_MS + SDXL_VAE_PHASE_TIMEOUT_MS <
                SDXL_QNN_WORKER_TIMEOUT_MS
        )
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
}
