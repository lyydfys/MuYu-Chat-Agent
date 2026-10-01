package com.muyuchat.core.download

import org.junit.Assert.*
import org.junit.Test

class RemoteArtifactIdentityTest {
    private val model = RemoteModelFile("owner/e4b", "main", "nested/e4b-it-int4.litertlm",
        "e4b-it-int4.litertlm", 4L, downloadUrl = "https://example.test/file")

    @Test fun suffixVariantsAndNestedRepositoryPathsHaveDifferentIdentity() {
        assertNotEquals(model.sourceIdentity(), model.copy(path = "nested/e4b-it-int8.litertlm",
            name = "e4b-it-int8.litertlm", relativePath = "e4b-it-int8.litertlm").sourceIdentity())
        assertNotEquals(model.sourceIdentity(), model.copy(path = "other/e4b-it-int4.litertlm").sourceIdentity())
    }
    @Test fun mutableDisplayTitleOrSignedUrlDoesNotChangeSourceIdentity() {
        assertEquals(model.sourceIdentity(), model.copy(name = "display title",
            downloadUrl = "https://example.test/file?token=new").sourceIdentity())
    }
    @Test fun repositoryRevisionAndImmutableDigestArePartOfIdentity() {
        assertNotEquals(model.sourceIdentity(), model.copy(repoId = "other/e4b").sourceIdentity())
        assertNotEquals(model.sourceIdentity(), model.copy(revision = "new-revision").sourceIdentity())
        assertNotEquals(model.sourceIdentity(), model.copy(sha256 = "a".repeat(64)).sourceIdentity())
    }
    @Test fun bundleIdentityIsOrderIndependentButRuntimeAndComponentsMatter() {
        val projector = model.copy(path = "mmproj.gguf", relativePath = "mmproj.gguf")
        assertEquals(modelBundleSourceIdentity(listOf(model, projector), "vision"),
            modelBundleSourceIdentity(listOf(projector, model), "vision"))
        assertNotEquals(modelBundleSourceIdentity(listOf(model), "vision"),
            modelBundleSourceIdentity(listOf(model), "text"))
        assertNotEquals(modelBundleSourceIdentity(listOf(model, projector), "vision"),
            modelBundleSourceIdentity(listOf(model), "vision"))
    }
}
