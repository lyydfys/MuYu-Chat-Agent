package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalImageApiErrorMappingTest {
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
}
