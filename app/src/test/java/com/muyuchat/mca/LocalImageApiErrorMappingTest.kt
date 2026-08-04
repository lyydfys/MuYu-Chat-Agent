package com.muyuchat.mca

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageApiErrorMappingTest {
    @Test
    fun `authenticated image count capability is explicit per runtime`() {
        assertTrue(supportsAuthenticatedLocalImageCount(LocalImageRuntime.STABLE_DIFFUSION_CPP, 8))
        assertTrue(supportsAuthenticatedLocalImageCount(LocalImageRuntime.MNN_DIFFUSION, 1))
        assertFalse(supportsAuthenticatedLocalImageCount(LocalImageRuntime.MNN_DIFFUSION, 2))
        assertFalse(supportsAuthenticatedLocalImageCount(LocalImageRuntime.QNN_HTP, 8))
        assertFalse(supportsAuthenticatedLocalImageCount(LocalImageRuntime.STABLE_DIFFUSION_CPP, 9))
    }

    @Test
    fun `structured worker errors retain code and select a non generic API status`() {
        val unsupported = requireNotNull(
            LocalImageWorkerRemoteException(
                code = "unsupported_preview",
                message = "Preview publication is unavailable."
            ).toLocalImageApiProviderExceptionOrNull()
        )
        assertEquals("unsupported_preview", unsupported.code)
        assertEquals(422, unsupported.httpStatus)

        val timeout = requireNotNull(
            LocalImageWorkerRemoteException(
                code = LOCAL_IMAGE_WORKER_WATCHDOG_TIMEOUT_CODE,
                message = "The native worker timed out."
            ).toLocalImageApiProviderExceptionOrNull()
        )
        assertEquals(504, timeout.httpStatus)

        val disconnected = requireNotNull(
            LocalImageWorkerDisconnectedException("Worker disconnected.")
                .toLocalImageApiProviderExceptionOrNull()
        )
        assertEquals("image_worker_unavailable", disconnected.code)
        assertEquals(503, disconnected.httpStatus)

        val invalidResponse = requireNotNull(
            LocalImageWorkerException("Worker result was malformed.")
                .toLocalImageApiProviderExceptionOrNull()
        )
        assertEquals("invalid_image_worker_response", invalidResponse.code)
        assertEquals(502, invalidResponse.httpStatus)
    }

    @Test
    fun `worker service preserves product phase and native contract codes`() {
        assertEquals(
            "unsupported_batch_count",
            localImageWorkerErrorCode(
                LocalImageProductContractException(
                    code = "unsupported_batch_count",
                    message = "Only one output is supported."
                )
            )
        )
        assertEquals(
            "qnn_sdxl_worker_timeout",
            localImageWorkerErrorCode(
                LocalImageWorkerRemoteException(
                    code = "qnn_sdxl_worker_timeout",
                    message = "Timed out."
                )
            )
        )
        assertEquals(
            "execution_contract_mismatch",
            localImageWorkerErrorCode(
                ImageNativeExecutionContractException(
                    code = EXECUTION_CONTRACT_MISMATCH,
                    field = "steps",
                    message = "Native steps did not match."
                )
            )
        )
    }

    @Test
    fun `native prompt weighting rejection keeps its code through worker and API mapping`() {
        val nativeFailure = try {
            throwLocalImageNativeFailure(
                JSONObject()
                    .put("ok", false)
                    .put("errorCode", "PROMPT_WEIGHTING_EXECUTION_UNSUPPORTED")
                    .put("error", "The selected graph cannot apply prompt weights."),
                fallbackMessage = "Generation failed."
            )
        } catch (error: LocalImageProductContractException) {
            error
        }

        assertEquals("prompt_weighting_execution_unsupported", nativeFailure.code)
        assertEquals(
            "prompt_weighting_execution_unsupported",
            localImageWorkerErrorCode(nativeFailure)
        )
        val apiFailure = requireNotNull(nativeFailure.toLocalImageApiProviderExceptionOrNull())
        assertEquals("prompt_weighting_execution_unsupported", apiFailure.code)
        assertEquals(422, apiFailure.httpStatus)
    }

    @Test
    fun `qnn img2img pndm rejection is a stable 422 product error`() {
        val apiFailure = requireNotNull(
            LocalImageProductContractException(
                code = "unsupported_img2img_sampler",
                message = "QNN img2img cannot use PNDM/PLMS at a strength-derived index."
            ).toLocalImageApiProviderExceptionOrNull()
        )

        assertEquals("unsupported_img2img_sampler", apiFailure.code)
        assertEquals(422, apiFailure.httpStatus)
        assertTrue(apiFailure.message.contains("PNDM"))
    }

    @Test
    fun `ultrafix resolution rejection keeps its product error code through worker and API`() {
        val resolutionFailure = ImageProfileResolutionException(
            ImageProfileValidationReport(
                listOf(
                    ImageProfileValidationIssue(
                        code = "ULTRAFIX_EXECUTION_UNSUPPORTED",
                        field = "capabilities.supportsUltraFix",
                        message = "The resolved graph and sampler cannot execute UltraFix dimensions."
                    )
                )
            )
        )

        assertEquals(
            "ultrafix_execution_unsupported",
            localImageWorkerErrorCode(resolutionFailure)
        )
        val apiFailure = requireNotNull(resolutionFailure.toLocalImageApiProviderExceptionOrNull())
        assertEquals("ultrafix_execution_unsupported", apiFailure.code)
        assertEquals(422, apiFailure.httpStatus)
    }
}
