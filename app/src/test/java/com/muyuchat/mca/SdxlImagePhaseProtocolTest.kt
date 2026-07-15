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
            expectedHtpArch = SDXL_UNET_HTP_ARCH,
            bundleRoot = "/bundle",
            runtimeDirsJson = "[\"/runtime-v75\",\"/runtime-v73\"]",
            paramsJson = "{\"steps\":1}",
            embeddingsPath = "/cache/conditioning.f32",
            latentPath = "/cache/latent.f32",
            metadataPath = "/cache/latent.json",
            outputPath = "",
            journalPath = "/cache/unet.json"
        )

        val parsed = SdxlImagePhaseProtocol.parseRequest(SdxlImagePhaseProtocol.request(request))

        assertEquals(SdxlImagePhase.UNET, parsed.phase)
        assertEquals(75, parsed.expectedHtpArch)
        assertFalse(parsed.paramsJson.contains("vae"))
    }

    @Test
    fun `progress IPC preserves phase pid profile and native stages`() {
        val envelope = SdxlImagePhaseProgress(
            requestId = "sdxl-2",
            phase = SdxlImagePhase.VAE,
            workerPid = 7302,
            runtimeProfile = "V73",
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
        assertEquals(7302, parsed.workerPid)
        assertEquals("V73", parsed.runtimeProfile)
        assertEquals(envelope.progress.stageTrace, parsed.progress.stageTrace)
    }

    @Test
    fun `latent commit validates shape bytes and sha then rejects tampering`() {
        val latent = temporaryFolder.newFile("latent.f32")
        latent.writeBytes(ByteArray(4 * 2 * 2 * 4) { index -> index.toByte() })
        val metadataFile = File(temporaryFolder.root, "latent.json")
        val native = JSONObject()
            .put("runtimeProfile", "V75")
            .put("htpArchVersion", 75)
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
            expectedProducerArch = 75
        )

        assertEquals(published.sha256, validated.sha256)
        assertTrue(metadataFile.readText().contains("\"committed\":true"))
        latent.appendBytes(byteArrayOf(1))
        val failure = runCatching {
            SdxlLatentArtifact.validate("sdxl-3", latent, metadataFile, 75)
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
        trace = SdxlTwoPhaseJournal.merge(trace, event(SdxlImagePhase.VAE, 73, "V73", "vae_graph_execute"))

        assertTrue(trace.any { it == "unet[pid=75,profile=V75]:process_exit_confirmed" })
        assertTrue(trace.any { it == "vae[pid=73,profile=V73]:vae_graph_execute" })
        assertNotEquals(75, 73)
    }

    @Test
    fun `each phase puts its own exact runtime profile first`() {
        val fallback = listOf(
            "/data/app/com.muyuchat.mca/lib/arm64",
            "/data/user/0/com.muyuchat.mca/files/qnnlibs",
            "/runtime/v75",
            "/vendor/lib64"
        )
        val unet = JSONArray(orderedSdxlRuntimeDirs("/runtime/v75", fallback))
        val vae = JSONArray(orderedSdxlRuntimeDirs("/runtime/v73", fallback))

        assertTrue(unet.getString(0).replace('\\', '/').endsWith("/runtime/v75"))
        assertTrue(vae.getString(0).replace('\\', '/').endsWith("/runtime/v73"))
        assertNotEquals(unet.getString(0), vae.getString(0))
        assertEquals(1, unet.length())
        assertEquals(1, vae.length())
        assertFalse(unet.toString().contains("com.muyuchat.mca"))
        assertFalse(vae.toString().contains("/runtime/v75"))
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
        assertTrue(manifest.contains("android:process=\":sdxl_vae_v73\""))
        assertFalse(manifest.contains("SdxlUnetWorkerService\"\n            android:exported=\"true"))
    }
}
