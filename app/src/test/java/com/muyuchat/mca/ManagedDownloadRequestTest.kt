package com.muyuchat.mca

import com.muyuchat.core.download.*
import org.junit.Assert.*
import org.junit.Test

class ManagedDownloadRequestTest {
    @Test fun processRestartRestoresExactRemoteAndProjectorIntent() {
        val remote = RemoteModelFile("owner/model", "revision-1", "dir/mmproj.gguf", "mmproj.gguf", 1234,
            "a".repeat(64), "license", "https://example.invalid/model", ModelRepositoryProvider.HUGGING_FACE,
            visionBundleRole = VisionModelBundleComponentRole.PROJECTOR, relativePath = "dir/mmproj.gguf")
        val request = ManagedDownloadRequest(remote = remote, projectorTargetId = "target-model")
        assertEquals(request, ManagedDownloadRequest.fromJson(request.toJson()))
        assertEquals(request.identity, ManagedDownloadRequest.fromJson(request.toJson()).identity)
        assertNotEquals(request.identity, request.copy(remote = remote.copy(repoId = "other/model")).identity)
        assertNotEquals(request.identity, request.copy(remote = remote.copy(revision = "revision-2")).identity)
    }

    @Test fun everyCatalogRequestSurvivesSerializationWithPinnedIdentity() {
        ModelScopeClient().recommendedModels().forEach { model ->
            val request = ManagedDownloadRequest.recommended(model)
            assertEquals(request, ManagedDownloadRequest.fromJson(request.toJson()))
            assertEquals(managedCatalogFingerprint(model), request.catalogFingerprint)
            assertNotEquals(request.catalogFingerprint, managedCatalogFingerprint(model.copy(revision = "changed")))
        }
    }
}
