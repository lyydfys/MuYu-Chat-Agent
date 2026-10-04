package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.muyuchat.mca.presentation.existingImageModelChoiceIds

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
        // copyImageAssetStreamAtomically moved to ImageAssetFiles.kt during P2. Only that one
        // lookup reads from the new file; every other functionBody below still reads the
        // ViewModel, and no assertion changed.
        val imageAssetFiles = sourceFile("app/src/main/java/com/muyuchat/mca/ImageAssetFiles.kt")
        val activity = sourceFile("app/src/main/java/com/muyuchat/mca/MainActivity.kt") + sourceFile("app/src/main/java/com/muyuchat/mca/MainChatScreenActions.kt") +
            sourceFile("app/src/main/java/com/muyuchat/mca/ChatUiStateMapping.kt")
        val chat = sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreen.kt") + sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ImageWorkspaceScreens.kt")
        // The upscale body moved to ImageUpscaleOwner.kt, which owns the job and the lifecycle lock.
        // It takes the scope as a parameter now, so the signature below is the owner's.
        val upscale = functionBody(
            sourceFile("app/src/main/java/com/muyuchat/mca/ImageUpscaleOwner.kt"),
            "fun upscaleImageAsset(imageId: String, targetScale: Int, scope: CoroutineScope)"
        )
        val enqueueGeneration = functionBody(viewModel, "private fun enqueueImageGeneration(")
        val localApiGeneration = functionBody(sourceFile("app/src/main/java/com/muyuchat/mca/presentation/LocalApiImageGenerationOperations.kt"), "internal suspend fun generateLocalApiImageOperations(")
        val previewPublish = functionBody(viewModel, "private fun publishLocalImagePreview(")
        val deleteImages = functionBody(viewModel, "fun deleteImageAssets(")
        val clearImages = functionBody(viewModel, "fun clearImageLibrary(")
        val exportImages = functionBody(viewModel, "fun exportImageLibraryBackup(")
        val attachFile = functionBody(viewModel, "fun attachFile(")
        val importImage = functionBody(viewModel, "private suspend fun importImageAsset(")
        val atomicImport = functionBody(
            imageAssetFiles,
            "internal suspend fun copyImageAssetStreamAtomically("
        )
        // The delete-upscaler body moved to LocalImageUpscalerOperations.kt during the upscaler
        // extraction; the ViewModel keeps a forwarding call with the original name. The assertion
        // below is unchanged -- only the file and signature this section is read from.
        val deleteUpscaler = functionBody(
            sourceFile("app/src/main/java/com/muyuchat/mca/LocalImageUpscalerOperations.kt"),
            "internal fun deleteLocalImageUpscalerModel("
        )
        // The write itself moved to ImageAssetCreationOperations.kt with the asset-creation family;
        // the ViewModel keeps a forwarding call. The assertions below are unchanged.
        val publish = functionBody(
            sourceFile("app/src/main/java/com/muyuchat/mca/ImageAssetCreationOperations.kt"),
            "internal suspend fun createUpscaledAsset("
        )
        val cloudPublish = functionBody(viewModel, "private suspend fun createCloudGeneratedImageAssets(")
        val localPublish = functionBody(
            sourceFile("app/src/main/java/com/muyuchat/mca/CreateLocalGeneratedImageAssetOperations.kt"),
            "suspend fun createLocalGeneratedImageAsset("
        )
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
        // The image-library mutation lock is now entered through the port
        // (`withImageLibraryMutationLock`); the lock itself stays in the ViewModel. Same contract.
        assertTrue(upscale.contains("withImageLibraryMutationLock"))
        assertTrue(upscale.contains("ImageUpscaleStatusRecord.CANCEL_REQUESTED"))
        assertTrue(upscale.contains("executionJob.invokeOnCompletion"))
        assertTrue(upscale.contains("releaseObservedImageGenerationLease(lease)"))
        assertFalse(upscale.contains("deviceProfile"))
        // The active upscale identity and its lifecycle lock now live in ImageUpscaleOwner, so
        // these two assertions name the owner's calls. The contract is unchanged: the check runs
        // under the upscale lifecycle lock, inside the image-library mutation lock.
        assertTrue(deleteImages.contains("imageUpscaleOwner.activeSourceImageId()"))
        assertTrue(deleteImages.contains("imageLibraryMutationMutex.withLock"))
        assertTrue(deleteImages.contains("imageUpscaleOwner.withUpscaleLifecycleLock {"))
        assertTrue(deleteImages.contains("stageImageAssetDeletionLeases("))
        assertTrue(deleteImages.contains("retainedImages = _uiState.value.images.filterNot"))
        assertTrue(deleteImages.contains("commitImageAssetDeletionLeases(leases)"))
        assertTrue(clearImages.contains("imageUpscaleOwner.activeSourceImageId()"))
        assertTrue(clearImages.contains("imageLibraryMutationMutex.withLock"))
        assertTrue(clearImages.contains("imageUpscaleOwner.withUpscaleLifecycleLock {"))
        assertTrue(clearImages.contains("stageImageAssetDeletionLeases("))
        assertTrue(clearImages.contains("retainedImages = emptyList()"))
        assertTrue(clearImages.contains("commitImageAssetDeletionLeases(leases)"))
        assertTrue(deleteUpscaler.contains("activeLocalImageUpscalerId"))
        assertTrue(publish.contains("writeImageAssetBytesAtomically("))
        // The asset write still happens under the image-asset write lock, but that lock no longer
        // leaves the ViewModel: the extracted code enters it through `withImageAssetWriteLock`.
        // Same contract, new spelling -- the other two publishers below are untouched.
        assertTrue(publish.contains("withImageAssetWriteLock"))
        assertTrue(cloudPublish.contains("imageAssetWriteMutex.withLock"))
        assertTrue(localPublish.contains("imageAssetWriteMutex.withLock"))
        assertTrue(previewPublish.contains("imageAssetWriteMutex.tryLock()"))
        assertTrue(previewPublish.contains("imageAssetWriteMutex.unlock()"))
        assertTrue(publish.contains("ImageUpscaleHistoryMetadata.fromNativeExecution("))
        // P5: the commit transaction moved to ImageLibraryCommitOperations; the assertions are
        // unchanged -- they are read where the transaction now lives.
        val commitTransaction = sourceFile("app/src/main/java/com/muyuchat/mca/ImageLibraryCommitOperations.kt")
            .substringAfter("fun commitImageLibraryTransaction(")
        assertTrue(commitTransaction.contains("imageLibraryMutationMutex.withLock"))
        assertTrue(commitTransaction.contains("chatSessionStore.upsertImages(generatedImages)"))
        assertTrue(commitTransaction.contains("currentCoroutineContext().ensureActive()"))
        assertTrue(commitTransaction.contains("activeImageGenerationJobId != jobId"))
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
