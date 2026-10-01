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

    @Test fun litertE4bSuffixRepoRevisionAndHashHaveIsolatedDownloadIdentities() {
        val gpu = RemoteModelFile("owner/gemma-e4b", "main", "gemma-4-E4B-it-gpu.litertlm",
            "gemma-4-E4B-it-gpu.litertlm", 100, "a".repeat(64), downloadUrl = "https://example.invalid/gpu")
        val cpu = gpu.copy(path = "gemma-4-E4B-it-cpu.litertlm", name = "gemma-4-E4B-it-cpu.litertlm")
        val npu = gpu.copy(path = "gemma-4-E4B-it_qualcomm_sm8750.litertlm", name = "gemma-4-E4B-it_qualcomm_sm8750.litertlm")
        val variants = listOf(gpu, cpu, npu, gpu.copy(repoId = "other/gemma-e4b"),
            gpu.copy(revision = "changed"), gpu.copy(sha256 = "b".repeat(64)))

        assertEquals(variants.size, variants.map(::managedRemoteIdentity).toSet().size)
        assertEquals(variants.size, variants.map { ManagedDownloadRequest(remote = it).identity }.toSet().size)
    }

    @Test fun partiallyKnownBundleSizesNeverProduceFalseEta() {
        val known = RemoteModelFile("owner/bundle", "main", "a", "a", 100, downloadUrl = "https://example.invalid/a")
        assertEquals(300L, knownManagedBundleDownloadSize(listOf(known, known.copy(sizeBytes = 200))))
        assertEquals(0L, knownManagedBundleDownloadSize(listOf(known, known.copy(sizeBytes = null))))
        assertEquals(0L, knownManagedBundleDownloadSize(listOf(known, known.copy(sizeBytes = 0))))
        assertEquals(0L, knownManagedBundleDownloadSize(listOf(known.copy(sizeBytes = Long.MAX_VALUE), known)))
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
