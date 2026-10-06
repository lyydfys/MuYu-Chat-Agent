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
        // v4-①：读者位置无关——按标记定位承载文件，文件不再含路径字面量；断言语义不变。
        val source = SourceTextFixtures.sourceOf("class MainViewModel @JvmOverloads constructor(")
        // R3-S24: the load chain moved to LoadModelOperations.kt; the load half is read where it
        // now lives（发布调用拼写随迁 publishChatCatalogSnapshot(readinessPort)），
        // verify/smoke halves stay on the ViewModel. Same ordering contract.
        val load = SourceTextFixtures.sourceOf("internal suspend fun CoroutineScope.runLoadModelBody(")
            .substringAfter("internal suspend fun CoroutineScope.runLoadModelBody(")
            .substringBefore("/** R3-S24：bootstrap")
        // R3/C1: captureLoadedRuntimeSnapshot 声明移出至运行时缝，区段边界标记随迁。
        val verify = section(source, "fun verifyModel(", "private fun clearNativeRuntimeSessionState(")
        // R3-S26: the smoke chain moved to ReadinessSmokeOperations.kt; the smoke half is read
        // where it now lives（发布调用拼写随迁），verify half stays on the ViewModel.
        val smoke = SourceTextFixtures.sourceOf("internal fun runAgentStabilitySmokeFor(")
            .substringAfter("internal fun runAgentStabilitySmokeFor(")
        assertTrue(verify.indexOf("modelStore.validateForLoad(") >= 0)
        assertTrue(
            verify.indexOf("publishManagedChatCatalogAfterValidation()") >
                verify.indexOf("modelStore.validateForLoad(")
        )
        assertTrue(load.indexOf("modelStore.validateForLoad(") >= 0)
        assertTrue(
            load.indexOf("publishChatCatalogSnapshot(readinessPort)") >
                load.indexOf("modelStore.validateForLoad(")
        )
        assertTrue(
            load.indexOf("publishChatCatalogSnapshot(readinessPort)") <
                load.indexOf("if (!preflight.canLoad)")
        )
        assertTrue(
            smoke.indexOf("publishChatCatalogSnapshot(readinessPort)") <
                smoke.indexOf("if (!preflight.canLoad)")
        )
        assertTrue(load.contains("var model = persistedModels.firstOrNull { it.id == requestedModel.id }"))
        assertTrue(smoke.contains("validatedCatalog.models.firstOrNull { it.id == requestedModel.id }"))

        // The publisher body moved to ManagedRuntimeReadinessRefresh.kt during the readiness
        // extraction (the ViewModel keeps a forwarding call with the original name). The ordering
        // contract -- invalidate the gate *before* reading the catalog -- is asserted where the code
        // now lives, and the two symbols it names are the ones the extraction renamed. The contract
        // itself is unchanged: nothing was relaxed.
        val publisher = section(
            sourceFile("app/src/main/java/com/muyuchat/mca/ManagedRuntimeReadinessRefresh.kt"),
            "internal fun publishChatCatalogSnapshot(",
            "internal fun refreshRuntimeReadiness("
        )
        assertTrue(
            publisher.indexOf("port.invalidateReadinessRefresh()") <
                publisher.indexOf("port.listLocalModels()")
        )
        assertTrue(publisher.contains("qairtVerifiedLocalModelIds = qairtVerifiedLocalModelIds"))
        assertTrue(publisher.contains("qairtVerifiedRecommendationIds = verifiedQairtRecommendationIds("))

        val backupStarter = section(
            source,
            "private fun startImageLibraryBackupJob(",
            // R1/S10: 状态源声明改为 UiStateStore 持有，区段边界标记随迁；区段内更新仍经 _uiState 过渡访问器。
            "private val uiStateStore = UiStateStore("
        )
        assertTrue(
            // R3/S23: 写入侧全面迁经 UiStateStore；顺序锚点随迁。
            backupStarter.indexOf("imageLibraryBackupJob = launchedJob") <
                backupStarter.indexOf("uiStateStore.update")
        )
        assertTrue(
            backupStarter.indexOf("uiStateStore.update") < backupStarter.indexOf("launchedJob.start()")
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
