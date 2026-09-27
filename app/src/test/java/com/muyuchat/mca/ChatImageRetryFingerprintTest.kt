package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatImageRetryFingerprintTest {
    @Test
    fun cloudCredentialRotationKeepsIdentityButEndpointOrModelChangeDoesNot() {
        val original = cloudConfig()

        assertEquals(
            original.chatImageRetryFingerprint(),
            original.copy(apiKey = "rotated-secret").chatImageRetryFingerprint()
        )
        assertNotEquals(
            original.chatImageRetryFingerprint(),
            original.copy(baseUrl = "https://other.example/v1").chatImageRetryFingerprint()
        )
        assertNotEquals(
            original.chatImageRetryFingerprint(),
            original.copy(imageModel = "different-image-model").chatImageRetryFingerprint()
        )
    }

    @Test
    fun localModelBytesOrExecutionProfileChangeIdentity() {
        val original = localModel()

        assertNotEquals(
            original.chatImageRetryFingerprint(),
            original.copy(sha256 = "b".repeat(64)).chatImageRetryFingerprint()
        )
        assertNotEquals(
            original.chatImageRetryFingerprint(),
            original.copy(imageSize = "1024x1024").chatImageRetryFingerprint()
        )
    }

    private fun cloudConfig() = CloudApiConfig(
        enabled = true,
        baseUrl = "https://image.example/v1",
        apiKey = "original-secret",
        chatModel = "chat-model",
        imageModel = "image-model",
        imageEndpointPath = "images/generations"
    )

    private fun localModel() = LocalImageModelRecord(
        id = "image-model-1",
        displayName = "Model",
        path = "model.mnn",
        fileName = "model.mnn",
        sizeBytes = 1024,
        sha256 = "a".repeat(64),
        runtime = LocalImageRuntime.MNN_DIFFUSION
    )
}
