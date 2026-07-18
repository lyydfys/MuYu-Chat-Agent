package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageUpscaleProductClosureTest {
    @Test
    fun `snapshot ownership keeps every existing image model regardless of configuration`() {
        assertEquals(
            setOf("localimagemodel:local-a", "cloudimagemodel:cloud-unconfigured"),
            existingImageModelChoiceIds(
                localModelIds = listOf("local-a"),
                cloudImageModelIds = listOf("cloud-unconfigured")
            )
        )
    }

    @Test
    fun `upscale request snapshot is immutable across later product selection changes`() {
        var source = ImageAssetRecord(
            id = "11111111-1111-4111-8111-111111111111",
            name = "source.png",
            uriString = "file:///data/user/0/private/source.png",
            source = "generated",
            width = 512,
            height = 384
        )
        var upscaler = LocalImagePreparedUpscaler(
            id = "22222222-2222-4222-8222-222222222222",
            name = "Photo 4x",
            path = "/data/user/0/private/photo4x.pth",
            sha256 = "a".repeat(64),
            sizeBytes = 1_024L
        )
        val snapshot = ImageUpscaleJobSpec(
            sourceImageSnapshot = source,
            upscalerSnapshot = upscaler,
            targetScale = 3,
            tileSize = 128,
            threads = 4
        )

        source = source.copy(id = "33333333-3333-4333-8333-333333333333")
        upscaler = upscaler.copy(name = "Different selection")

        assertEquals("11111111-1111-4111-8111-111111111111", snapshot.sourceImageSnapshot.id)
        assertEquals("Photo 4x", snapshot.upscalerSnapshot.name)
        assertEquals(3, snapshot.targetScale)
        assertFalse(snapshot.sourceImageSnapshot.id == source.id)
        assertFalse(snapshot.upscalerSnapshot.name == upscaler.name)
    }

    @Test
    fun `product upscale shares coordinator protects active inputs and atomically commits result`() {
        val viewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val activity = sourceFile("app/src/main/java/com/muyuchat/mca/MainActivity.kt")
        val chat = sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreen.kt")
        val upscale = functionBody(viewModel, "fun upscaleImageAsset(")
        val enqueueGeneration = functionBody(viewModel, "private fun enqueueImageGeneration(")
        val localApiGeneration = functionBody(viewModel, "private suspend fun generateLocalApiImage(")
        val previewPublish = functionBody(viewModel, "private fun publishLocalImagePreview(")
        val deleteImages = functionBody(viewModel, "fun deleteImageAssets(")
        val clearImages = functionBody(viewModel, "fun clearImageLibrary(")
        val exportImages = functionBody(viewModel, "fun exportImageLibraryBackup(")
        val attachFile = functionBody(viewModel, "fun attachFile(")
        val importImage = functionBody(viewModel, "private suspend fun importImageAsset(")
        val atomicImport = functionBody(
            viewModel,
            "internal suspend fun copyImageAssetStreamAtomically("
        )
        val deleteUpscaler = functionBody(viewModel, "fun deleteLocalImageUpscaler(")
        val publish = functionBody(viewModel, "private suspend fun createUpscaledImageAsset(")
        val cloudPublish = functionBody(viewModel, "private suspend fun createCloudGeneratedImageAsset(")
        val localPublish = functionBody(viewModel, "private suspend fun createLocalGeneratedImageAsset(")
        val importBackup = functionBody(viewModel, "fun importImageLibraryBackup(")
        val startBackup = functionBody(viewModel, "private fun startImageLibraryBackupJob(")
        val observedAcquire = functionBody(
            viewModel,
            "private fun tryAcquireObservedImageGenerationLease("
        )
        val observedRelease = functionBody(
            viewModel,
            "private fun releaseObservedImageGenerationLease("
        )

        assertTrue(upscale.contains("tryAcquireObservedImageGenerationLease(requestId)"))
        assertTrue(upscale.contains("ImageUpscaleJobSpec("))
        assertTrue(upscale.contains("requestId = requestId"))
        assertTrue(upscale.contains("chatSessionStore.upsertImages(listOf(generated))"))
        assertTrue(upscale.contains("imageLibraryMutationMutex.withLock"))
        assertTrue(upscale.contains("ImageUpscaleStatusRecord.CANCEL_REQUESTED"))
        assertTrue(upscale.contains("executionJob.invokeOnCompletion"))
        assertTrue(upscale.contains("releaseObservedImageGenerationLease(lease)"))
        assertFalse(upscale.contains("deviceProfile"))
        assertTrue(deleteImages.contains("activeImageUpscaleSourceImageId"))
        assertTrue(deleteImages.contains("imageLibraryMutationMutex.withLock"))
        assertTrue(deleteImages.contains("synchronized(localImageUpscaleLifecycleLock)"))
        assertTrue(deleteImages.contains("stageImageAssetDeletionLeases("))
        assertTrue(deleteImages.contains("retainedImages = _uiState.value.images.filterNot"))
        assertTrue(deleteImages.contains("commitImageAssetDeletionLeases(leases)"))
        assertTrue(clearImages.contains("activeImageUpscaleSourceImageId"))
        assertTrue(clearImages.contains("imageLibraryMutationMutex.withLock"))
        assertTrue(clearImages.contains("synchronized(localImageUpscaleLifecycleLock)"))
        assertTrue(clearImages.contains("stageImageAssetDeletionLeases("))
        assertTrue(clearImages.contains("retainedImages = emptyList()"))
        assertTrue(clearImages.contains("commitImageAssetDeletionLeases(leases)"))
        assertTrue(deleteUpscaler.contains("activeLocalImageUpscalerId"))
        assertTrue(publish.contains("writeImageAssetBytesAtomically("))
        assertTrue(publish.contains("imageAssetWriteMutex.withLock"))
        assertTrue(cloudPublish.contains("imageAssetWriteMutex.withLock"))
        assertTrue(localPublish.contains("imageAssetWriteMutex.withLock"))
        assertTrue(previewPublish.contains("imageAssetWriteMutex.tryLock()"))
        assertTrue(previewPublish.contains("imageAssetWriteMutex.unlock()"))
        assertTrue(publish.contains("ImageUpscaleHistoryMetadata.fromNativeExecution("))
        assertTrue(enqueueGeneration.contains("imageLibraryMutationMutex.withLock"))
        assertTrue(enqueueGeneration.contains("chatSessionStore.upsertImages(generatedImages)"))
        assertTrue(enqueueGeneration.contains("currentCoroutineContext().ensureActive()"))
        assertTrue(enqueueGeneration.contains("activeImageGenerationJobId != jobId"))
        assertTrue(enqueueGeneration.contains("ImageGenerationStatusRecord.DONE"))
        assertTrue(enqueueGeneration.contains("image.deleteLocalCopy(imageAssetDirectory)"))
        assertTrue(attachFile.contains("imageLibraryMutationMutex.withLock"))
        assertTrue(importImage.contains("copyImageAssetStreamAtomically("))
        assertTrue(importImage.contains("imageAssetWriteMutex.withLock"))
        assertTrue(atomicImport.contains("currentCoroutineContext().ensureActive()"))
        assertTrue(atomicImport.contains("\"\" -> suggestedExtension.lowercase()"))
        assertTrue(atomicImport.contains("else -> error(\"Unsupported image format.\")"))
        assertTrue(exportImages.contains("imageLibraryMutationMutex.withLock"))
        assertTrue(exportImages.contains("deleteIncompleteImageLibraryBackup(destination)"))
        val backupWriteLock = importBackup.indexOf("imageAssetWriteMutex.withLock")
        val backupMutationLock = importBackup.indexOf("imageLibraryMutationMutex.withLock")
        assertTrue(backupWriteLock >= 0 && backupMutationLock > backupWriteLock)
        assertTrue(startBackup.contains("imageLibraryBackupJob?.isCompleted == false"))
        assertTrue(viewModel.contains("imageLibraryBackup.reconcile(initialImages)"))
        assertTrue(viewModel.contains(
            "reconcileImageAssetDirectory(imageAssetDirectory, initialImages)"
        ))
        assertTrue(localApiGeneration.contains("tryAcquireObservedImageGenerationLease(requestId)"))
        assertTrue(localApiGeneration.contains("releaseObservedImageGenerationLease(generationLease)"))
        assertTrue(observedAcquire.indexOf("updateGenerationImageGrantReleaseDefer(true)") <
            observedAcquire.indexOf("localImageGenerationCoordinator.tryAcquire(requestId)"))
        assertTrue(observedRelease.contains("localImageGenerationCoordinator.activeRequestId() != null"))
        assertTrue(activity.contains("onUpscaleImageAsset = viewModel::upscaleImageAsset"))
        assertTrue(activity.contains(
            "deferGenerationImageGrantRelease = state.deferGenerationImageGrantRelease"
        ))
        assertTrue(activity.contains(
            "viewModel::releaseGenerationImageGrantsIfCoordinatorIdle"
        ))
        assertTrue(chat.contains("releaseOwnedUrisIfCoordinatorIdle"))
        assertTrue(chat.contains("IMAGE_UPSCALE_TARGET_SCALES: List<Int> = listOf(2, 3, 4)"))
        assertTrue(chat.contains("if (sourceUpscaleRunning) \"放大中，暂不可删除\""))
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

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val openingParenthesis = source.indexOf('(', start)
        var parenthesisDepth = 0
        var closingParenthesis = -1
        for (index in openingParenthesis until source.length) {
            when (source[index]) {
                '(' -> parenthesisDepth += 1
                ')' -> {
                    parenthesisDepth -= 1
                    if (parenthesisDepth == 0) {
                        closingParenthesis = index
                        break
                    }
                }
            }
        }
        require(closingParenthesis >= 0)
        val openingBrace = source.indexOf('{', closingParenthesis)
        require(openingBrace >= 0)
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unterminated function body: $signature")
    }
}
