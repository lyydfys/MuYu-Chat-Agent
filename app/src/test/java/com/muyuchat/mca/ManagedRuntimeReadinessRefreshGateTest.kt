package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedRuntimeReadinessRefreshGateTest {
    @Test
    fun `newer refresh invalidates an older publication token`() {
        val gate = ManagedRuntimeReadinessRefreshGate()
        val first = gate.begin()
        val second = gate.begin()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun `catalog mutation invalidates the active refresh`() {
        val gate = ManagedRuntimeReadinessRefreshGate()
        val refresh = gate.begin()

        gate.invalidate()

        assertFalse(gate.isCurrent(refresh))
    }

    @Test
    fun `advisory verification survives only an exact model sha identity`() {
        assertEquals(
            setOf("same"),
            verifiedModelIdsWithUnchangedSha(
                verifiedShaByModelId = mapOf("same" to "aaa", "replaced" to "bbb"),
                currentShaByModelId = mapOf("same" to "aaa", "replaced" to "ccc", "new" to "ddd")
            )
        )
    }

    @Test
    fun `refresh failure never replaces status while a generation lease is being published`() {
        assertFalse(
            shouldSurfaceManagedRuntimeRefreshFailure(
                refreshCurrent = true,
                busy = false,
                isGenerating = false,
                imageLibraryBackupRunning = false,
                imageLibraryBackupJobActive = false,
                generationImageGrantReleaseDeferred = true,
                activeImageGeneration = false,
                activeImageUpscale = false,
                activeLocalApiImageGeneration = false,
                coordinatorActive = false
            )
        )
        assertTrue(
            shouldSurfaceManagedRuntimeRefreshFailure(
                refreshCurrent = true,
                busy = false,
                isGenerating = false,
                imageLibraryBackupRunning = false,
                imageLibraryBackupJobActive = false,
                generationImageGrantReleaseDeferred = false,
                activeImageGeneration = false,
                activeImageUpscale = false,
                activeLocalApiImageGeneration = false,
                coordinatorActive = false
            )
        )
        assertFalse(
            shouldSurfaceManagedRuntimeRefreshFailure(
                refreshCurrent = true,
                busy = false,
                isGenerating = false,
                imageLibraryBackupRunning = false,
                imageLibraryBackupJobActive = true,
                generationImageGrantReleaseDeferred = false,
                activeImageGeneration = false,
                activeImageUpscale = false,
                activeLocalApiImageGeneration = false,
                coordinatorActive = false
            )
        )
    }

    @Test
    fun `every manifest validating UI path publishes the refreshed catalog before continuing`() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val load = section(source, "fun loadModel(", "fun verifyModel(")
        val verify = section(source, "fun verifyModel(", "private fun captureLoadedRuntimeSnapshot(")
        val smoke = section(source, "fun runAgentStabilitySmoke(", "fun startAgentTuning()")
        listOf(load, verify, smoke).forEach { body ->
            val validateIndex = body.indexOf("modelStore.validateForLoad(")
            val publishIndex = body.indexOf("publishManagedChatCatalogAfterValidation()")
            assertTrue(validateIndex >= 0)
            assertTrue(publishIndex > validateIndex)
        }
        assertTrue(
            load.indexOf("publishManagedChatCatalogAfterValidation()") <
                load.indexOf("if (!preflight.canLoad)")
        )
        assertTrue(
            smoke.indexOf("publishManagedChatCatalogAfterValidation()") <
                smoke.indexOf("if (!preflight.canLoad)")
        )
        assertTrue(load.contains("var model = persistedModels.firstOrNull { it.id == requestedModel.id }"))
        assertTrue(smoke.contains("validatedCatalog.models.firstOrNull { it.id == requestedModel.id }"))

        val publisher = section(
            source,
            "private fun publishManagedChatCatalogAfterValidation(",
            "/** Refreshes product-facing readiness"
        )
        assertTrue(
            publisher.indexOf("managedRuntimeReadinessRefreshGate.invalidate()") <
                publisher.indexOf("modelStore.listModels()")
        )
        assertTrue(publisher.contains("qairtVerifiedLocalModelIds = qairtVerifiedLocalModelIds"))
        assertTrue(publisher.contains("qairtVerifiedRecommendationIds = verifiedQairtRecommendationIds("))

        val backupStarter = section(
            source,
            "private fun startImageLibraryBackupJob(",
            "private val _uiState = MutableStateFlow("
        )
        assertTrue(
            backupStarter.indexOf("imageLibraryBackupJob = launchedJob") <
                backupStarter.indexOf("_uiState.update")
        )
        assertTrue(
            backupStarter.indexOf("_uiState.update") < backupStarter.indexOf("launchedJob.start()")
        )
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            File(root, relativePath).takeIf(File::isFile)?.let {
                return it.readText(Charsets.UTF_8)
            }
            directory = root.parentFile
        }
        error("Unable to locate $relativePath")
    }

    private fun section(source: String, startSignature: String, endSignature: String): String {
        val start = source.indexOf(startSignature)
        val end = source.indexOf(endSignature, start + startSignature.length)
        require(start >= 0 && end > start) {
            "Unable to locate source section $startSignature .. $endSignature"
        }
        return source.substring(start, end)
    }
}
