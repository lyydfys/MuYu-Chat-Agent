package com.muyuchat.mca

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnPreviewExecutionMetadataTest {
    @Test
    fun `requested shared vae preview copies pathless independently counted audit`() {
        val native = validPreviewEvidence()
            .put("previewVaeExecutionAttemptCount", 2)
            .put("previewVaeExecutionCount", 1)
            .put("previewVaeExecutionMsTotal", 47)
            .put("previewPublicationCount", 1)
            .put("previewLastStep", 1)
            .put("previewLastRevision", 1)
            .put("previewFailureCode", "PREVIEW_VAE_EXECUTE_FAILED")
            .put("previewDegraded", true)

        val audit = qnnSharedPreviewExecutionAudit(
            native,
            LocalImagePreviewOptions(1, LocalImagePreviewMode.VAE)
        )

        assertEquals(1, audit.getInt("finalVaeExecutionCount"))
        assertEquals(1, audit.getInt("previewInterval"))
        assertEquals(2, audit.getInt("previewVaeExecutionAttemptCount"))
        assertEquals(1, audit.getInt("previewVaeExecutionCount"))
        assertEquals(1, audit.getInt("previewPublicationCount"))
        assertTrue(audit.getBoolean("previewDegraded"))
        assertFalse(audit.has("previewPath"))
    }

    @Test
    fun `no preview request requires none mode and zero preview evidence`() {
        val native = validPreviewEvidence()
            .put("previewRequested", false)
            .put("previewMode", "none")
            .put("previewInterval", 0)
            .put("previewVaeExecutionAttemptCount", 0)
            .put("previewVaeExecutionCount", 0)
            .put("previewVaeExecutionMsTotal", 0)
            .put("previewPublicationCount", 0)
            .put("previewLastStep", 0)
            .put("previewLastRevision", 0)

        val audit = qnnSharedPreviewExecutionAudit(native, requestedPreview = null)

        assertFalse(audit.getBoolean("previewRequested"))
        assertEquals("none", audit.getString("previewMode"))
        assertEquals(0, audit.getInt("previewPublicationCount"))
    }

    @Test
    fun `preview counters cannot substitute for final vae or expose a path`() {
        val request = LocalImagePreviewOptions(1, LocalImagePreviewMode.VAE)
        listOf(
            validPreviewEvidence().put("finalVaeExecutionCount", 0),
            validPreviewEvidence().put("finalVaeGraphExecutionCount", 0),
            validPreviewEvidence().put("vaeExecutionCount", 0),
            validPreviewEvidence().put("vaeTileCount", 0),
            validPreviewEvidence().put("vaeTiled", true),
            validPreviewEvidence()
                .put("previewVaeExecutionAttemptCount", 0)
                .put("previewVaeExecutionCount", 1),
            validPreviewEvidence()
                .put("previewVaeExecutionAttemptCount", 9)
                .put("previewVaeExecutionCount", 9)
                .put("previewPublicationCount", 9)
                .put("previewLastRevision", 9)
                .put("previewLastStep", 4),
            validPreviewEvidence().put("previewPath", "/data/user/0/private/preview-1.png"),
            validPreviewEvidence().put("previewMode", "projection"),
            validPreviewEvidence().put("previewInterval", 2),
            validPreviewEvidence().put("previewLastStep", 7)
        ).forEach { forged ->
            assertTrue(
                "Forgery should fail: $forged",
                runCatching { qnnSharedPreviewExecutionAudit(forged, request) }.isFailure
            )
        }
    }

    @Test
    fun `direct and tiled final vae keep logical and physical evidence separate`() {
        val request = LocalImagePreviewOptions(1, LocalImagePreviewMode.VAE)
        val direct = qnnSharedPreviewExecutionAudit(validPreviewEvidence(), request)
        assertEquals(1, direct.getInt("finalVaeExecutionCount"))
        assertEquals(1, direct.getInt("finalVaeGraphExecutionCount"))
        assertEquals(1, direct.getInt("vaeExecutionCount"))
        assertFalse(direct.getBoolean("vaeTiled"))

        val tiled = qnnSharedPreviewExecutionAudit(
            validPreviewEvidence()
                .put("finalVaeGraphExecutionCount", 9)
                .put("vaeExecutionCount", 9)
                .put("vaeTileCount", 9)
                .put("vaeTiled", true)
                .put("previewVaeExecutionAttemptCount", 1)
                .put("previewVaeExecutionCount", 0)
                .put("previewVaeExecutionMsTotal", 0)
                .put("previewPublicationCount", 0)
                .put("previewLastStep", 0)
                .put("previewLastRevision", 0)
                .put("previewFailureCode", "PREVIEW_VAE_INPUT_BIND_FAILED")
                .put("previewDegraded", true),
            request
        )
        assertEquals(1, tiled.getInt("finalVaeExecutionCount"))
        assertEquals(9, tiled.getInt("finalVaeGraphExecutionCount"))
        assertEquals(9, tiled.getInt("vaeTileCount"))
        assertTrue(tiled.getBoolean("vaeTiled"))
    }

    @Test
    fun `final vae physical evidence rejects direct tiled and graph count forgeries`() {
        val request = LocalImagePreviewOptions(1, LocalImagePreviewMode.VAE)
        listOf(
            validPreviewEvidence()
                .put("finalVaeGraphExecutionCount", 9)
                .put("vaeExecutionCount", 9)
                .put("vaeTileCount", 9),
            validPreviewEvidence()
                .put("finalVaeGraphExecutionCount", 9)
                .put("vaeExecutionCount", 8)
                .put("vaeTileCount", 9)
                .put("vaeTiled", true),
            validPreviewEvidence()
                .put("finalVaeGraphExecutionCount", 9)
                .put("vaeExecutionCount", 9)
                .put("vaeTileCount", 8)
                .put("vaeTiled", true),
            validPreviewEvidence()
                .put("finalVaeGraphExecutionCount", 1)
                .put("vaeExecutionCount", 1)
                .put("vaeTileCount", 1)
                .put("vaeTiled", true),
            validPreviewEvidence()
                .put("finalVaeGraphExecutionCount", 9)
                .put("vaeExecutionCount", 9)
                .put("vaeTileCount", 9)
                .put("vaeTiled", true)
        ).forEach { forged ->
            assertTrue(
                "Forgery should fail: $forged",
                runCatching { qnnSharedPreviewExecutionAudit(forged, request) }.isFailure
            )
        }
    }

    @Test
    fun `request without frames keeps last step and revision at zero`() {
        val request = LocalImagePreviewOptions(10, LocalImagePreviewMode.VAE)
        val audit = qnnSharedPreviewExecutionAudit(
            validPreviewEvidence()
                .put("steps", 5)
                .put("previewInterval", 10)
                .put("previewVaeExecutionAttemptCount", 0)
                .put("previewVaeExecutionCount", 0)
                .put("previewVaeExecutionMsTotal", 0)
                .put("previewPublicationCount", 0)
                .put("previewLastStep", 0)
                .put("previewLastRevision", 0),
            request
        )
        assertEquals(0, audit.getInt("previewPublicationCount"))
        assertEquals(0, audit.getInt("previewLastStep"))
        assertEquals(0, audit.getInt("previewLastRevision"))
    }

    @Test
    fun `img2img preview cadence uses effective scheduler tail and excludes final decode`() {
        val request = LocalImagePreviewOptions(4, LocalImagePreviewMode.VAE)
        val audit = qnnSharedPreviewExecutionAudit(
            validPreviewEvidence()
                .put("taskMode", LocalImageTaskMode.IMG2IMG.wireName)
                .put("steps", 20)
                .put("timetableCount", 15)
                .put("effectiveDenoiseSteps", 15)
                .put("previewInterval", 4)
                .put("previewVaeExecutionAttemptCount", 3)
                .put("previewVaeExecutionCount", 3)
                .put("previewVaeExecutionMsTotal", 30)
                .put("previewPublicationCount", 3)
                .put("previewLastStep", 12)
                .put("previewLastRevision", 3),
            request
        )

        assertEquals(3, audit.getInt("previewPublicationCount"))
        assertEquals(12, audit.getInt("previewLastStep"))
        assertTrue(
            runCatching {
                qnnSharedPreviewExecutionAudit(
                    validPreviewEvidence()
                        .put("taskMode", LocalImageTaskMode.IMG2IMG.wireName)
                        .put("steps", 20)
                        .put("timetableCount", 15)
                        .put("effectiveDenoiseSteps", 15)
                        .put("previewInterval", 4)
                        .put("previewVaeExecutionAttemptCount", 4)
                        .put("previewVaeExecutionCount", 4)
                        .put("previewPublicationCount", 4)
                        .put("previewLastStep", 16)
                        .put("previewLastRevision", 4),
                    request
                )
            }.isFailure
        )
    }

    @Test
    fun `inpaint preview cadence uses effective scheduler tail and excludes final decode`() {
        val request = LocalImagePreviewOptions(4, LocalImagePreviewMode.VAE)
        val audit = qnnSharedPreviewExecutionAudit(
            validPreviewEvidence()
                .put("taskMode", LocalImageTaskMode.INPAINT.wireName)
                .put("steps", 20)
                .put("timetableCount", 10)
                .put("effectiveDenoiseSteps", 10)
                .put("previewInterval", 4)
                .put("previewVaeExecutionAttemptCount", 2)
                .put("previewVaeExecutionCount", 2)
                .put("previewVaeExecutionMsTotal", 20)
                .put("previewPublicationCount", 2)
                .put("previewLastStep", 8)
                .put("previewLastRevision", 2),
            request
        )

        assertEquals(2, audit.getInt("previewPublicationCount"))
        assertEquals(8, audit.getInt("previewLastStep"))
        assertTrue(
            runCatching {
                qnnSharedPreviewExecutionAudit(
                    validPreviewEvidence()
                        .put("taskMode", LocalImageTaskMode.INPAINT.wireName)
                        .put("steps", 20)
                        .put("timetableCount", 10)
                        .put("effectiveDenoiseSteps", 10)
                        .put("previewInterval", 4)
                        .put("previewVaeExecutionAttemptCount", 4)
                        .put("previewVaeExecutionCount", 4)
                        .put("previewPublicationCount", 4)
                        .put("previewLastStep", 16)
                        .put("previewLastRevision", 4),
                    request
                )
            }.isFailure
        )
    }

    @Test
    fun `preview storage initialization failure is audited without replacing final vae evidence`() {
        val native = validPreviewEvidence()
            .put("previewVaeExecutionAttemptCount", 0)
            .put("previewVaeExecutionCount", 0)
            .put("previewVaeExecutionMsTotal", 0)
            .put("previewPublicationCount", 0)
            .put("previewLastStep", 0)
            .put("previewLastRevision", 0)
            .put("previewFailureCode", "PREVIEW_STORAGE_INVALID")
            .put("previewDegraded", true)
        val audit = qnnSharedPreviewExecutionAudit(
            native,
            LocalImagePreviewOptions(1, LocalImagePreviewMode.VAE)
        )

        assertEquals(1, audit.getInt("finalVaeExecutionCount"))
        assertEquals(0, audit.getInt("previewVaeExecutionAttemptCount"))
        assertEquals(0, audit.getInt("previewPublicationCount"))
        assertEquals("PREVIEW_STORAGE_INVALID", audit.getString("previewFailureCode"))
        assertTrue(audit.getBoolean("previewDegraded"))
    }

    @Test
    fun `shared sessions accept VAE while split SDXL rejects every preview request`() {
        val request = LocalImagePreviewOptions(1, LocalImagePreviewMode.VAE)
        listOf(
            "shared_unet_vae",
            "shared_text_unet_vae",
            "shared_unet_controlnet_vae",
            "shared_text_unet_controlnet_vae"
        ).forEach { runtimeSessionMode ->
            val audit = requireNotNull(
                qnnPreviewExecutionAuditForRuntime(
                    validPreviewEvidence().put("runtimeSessionMode", runtimeSessionMode),
                    request
                )
            )
            assertTrue(audit.getBoolean("previewRequested"))
            assertEquals("vae", audit.getString("previewMode"))
        }

        listOf(
            request,
            LocalImagePreviewOptions(4, LocalImagePreviewMode.PROJECTION)
        ).forEach { requestedPreview ->
            assertTrue(
                "Split-SDXL must reject requested preview $requestedPreview",
                runCatching {
                    qnnPreviewExecutionAuditForRuntime(
                        validDisabledSplitEvidence(),
                        requestedPreview
                    )
                }.isFailure
            )
        }

        val disabledAudit = requireNotNull(
            qnnPreviewExecutionAuditForRuntime(
                validDisabledSplitEvidence(),
                requestedPreview = null
            )
        )
        assertEquals(1, disabledAudit.getInt("finalVaeExecutionCount"))
        assertFalse(disabledAudit.getBoolean("previewRequested"))
        assertEquals("none", disabledAudit.getString("previewMode"))
        assertEquals(0, disabledAudit.getInt("previewInterval"))
        assertEquals(0, disabledAudit.getInt("previewVaeExecutionAttemptCount"))
        assertEquals(0, disabledAudit.getInt("previewVaeExecutionCount"))
        assertEquals(0, disabledAudit.getInt("previewPublicationCount"))
        assertEquals(0, disabledAudit.getInt("projectionPreviewAttemptCount"))
        assertEquals(0, disabledAudit.getInt("projectionPreviewPublicationCount"))
        assertEquals(0, disabledAudit.getInt("projectionPreviewProjectionMsTotal"))
    }

    private fun validDisabledSplitEvidence(): JSONObject = JSONObject()
        .put("runtimeSessionMode", SDXL_ISOLATED_UNET_VAE_MODE)
        .put("taskMode", LocalImageTaskMode.TEXT_TO_IMAGE.wireName)
        .put("steps", 20)
        .put("timetableCount", 20)
        .put("vaeExecutionCount", 1)
        .put("finalVaeExecutionCount", 1)
        .put("finalVaeGraphExecutionCount", 1)
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

    private fun validPreviewEvidence(): JSONObject = JSONObject()
        .put("steps", 20)
        .put("vaeExecutionCount", 1)
        .put("finalVaeExecutionCount", 1)
        .put("finalVaeGraphExecutionCount", 1)
        .put("vaeTileCount", 1)
        .put("vaeTiled", false)
        .put("previewRequested", true)
        .put("previewMode", "vae")
        .put("previewInterval", 1)
        .put("previewVaeExecutionAttemptCount", 19)
        .put("previewVaeExecutionCount", 19)
        .put("previewVaeExecutionMsTotal", 190)
        .put("previewPublicationCount", 19)
        .put("previewLastStep", 19)
        .put("previewLastRevision", 19)
        .put("previewFailureCode", "")
        .put("previewDegraded", false)
}
