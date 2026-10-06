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
        val chat = sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreen.kt") + // P9/S2: ChatUiItemModels.kt 承接原 ChatScreen.kt 的条目模型与小助手（多源拼接随迁）。
sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ImageWorkspaceScreens.kt") + sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ImageAssetScreens.kt") + sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatUiItemModels.kt") + // P9/S30: 授权/触发器簇移出 ChatScreen.kt，拼接表随迁。
sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatImageGenerationSupport.kt")
        // The upscale body moved to ImageUpscaleOwner.kt, which owns the job and the lifecycle lock.
        // It takes the scope as a parameter now, so the signature below is the owner's.
        val upscale = functionBody(
            sourceFile("app/src/main/java/com/muyuchat/mca/ImageUpscaleOwner.kt"),
            "fun upscaleImageAsset(imageId: String, targetScale: Int, scope: CoroutineScope)"
        )
        // R3/C2: the admission body moved to ImageGenerationOwner.kt; the assertions are unchanged
        // -- read where the body now lives.
        val enqueueGeneration = functionBody(
            sourceFile("app/src/main/java/com/muyuchat/mca/ImageGenerationOwner.kt"),
            "fun submit("
        )
        val localApiGeneration = functionBody(sourceFile("app/src/main/java/com/muyuchat/mca/presentation/LocalApiImageGenerationOperations.kt"), "internal suspend fun generateLocalApiImageOperations(")
        // R3/C3-S17: the preview publisher moved verbatim to LocalImagePreviewOperations.kt; the
        // assertions are unchanged (the mutex param keeps its original name) -- they are read where
        // the publisher now lives.
        val previewPublish = functionBody(
            sourceFile("app/src/main/java/com/muyuchat/mca/LocalImagePreviewOperations.kt"),
            "internal fun publishLocalImagePreviewTo("
        )
        // P5: deleteImageAssets / clearImageLibrary moved to ImageAssetDeletionOperations; the
        // assertions are unchanged -- the bodies are read where they now live.
        val deleteImages = functionBody(
            sourceFile("app/src/main/java/com/muyuchat/mca/ImageAssetDeletionOperations.kt"),
            "fun deleteImageAssetsOperation("
        )
        val clearImages = functionBody(
            sourceFile("app/src/main/java/com/muyuchat/mca/ImageAssetDeletionOperations.kt"),
            "fun clearImageLibraryOperation("
        )
        val backupOperationsSource = sourceFile("app/src/main/java/com/muyuchat/mca/ImageLibraryBackupOperations.kt")
        val exportImages = functionBody(backupOperationsSource, "internal fun exportImageLibraryBackupFor(")
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
        // R3-S28: the cloud publication body moved to CreateCloudGeneratedImageAssetsOperations.kt;
        // the assertions are unchanged（mutex 参数名保持）-- read where the body now lives.
        val cloudPublish = functionBody(
            sourceFile("app/src/main/java/com/muyuchat/mca/CreateCloudGeneratedImageAssetsOperations.kt"),
            "internal suspend fun createCloudGeneratedImageAssetsFor("
        )
        val localPublish = functionBody(
            sourceFile("app/src/main/java/com/muyuchat/mca/CreateLocalGeneratedImageAssetOperations.kt"),
            "suspend fun createLocalGeneratedImageAsset("
        )
        val importBackup = functionBody(backupOperationsSource, "internal fun importImageLibraryBackupFor(")
        val startBackup = functionBody(backupOperationsSource, "internal fun startImageLibraryBackupJobFor(")
        // R3/C3-S15: the two lease helpers moved verbatim to ImageGenerationOwner.kt; the ordering
        // and identity-check contracts are unchanged -- they are read where the helpers now live,
        // with the call spelling updated to the parameterized form (defer -> ...Into(store, ...)).
        val leaseSource = sourceFile("app/src/main/java/com/muyuchat/mca/ImageGenerationOwner.kt")
        val observedAcquire = functionBody(
            leaseSource,
            "internal fun tryAcquireObservedImageGenerationLeaseFor("
        )
        val observedRelease = functionBody(
            leaseSource,
            "internal fun releaseObservedImageGenerationLeaseFor("
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
        assertTrue(deleteImages.contains("retainedImages = uiState.value.images.filterNot"))
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
        assertTrue(enqueueGeneration.contains("image.deleteLocalCopy(host.imageAssetDirectory)"))
        assertTrue(attachFile.contains("upsertImportedImageUnderMutationLock(imageLibraryCommitPort, image) {"))
        // P5 step 6: the attach/import commit (row upsert + composer publish under the mutation
        // mutex) moved to ImageLibraryCommitOperations; the contract is read where it now lives.
        // The caller-side rollback (deleteLocalCopy / CancellationException / fail) stays in
        // attachFile and is pinned by the enqueue-style assertions above.
        val importedCommit = sourceFile("app/src/main/java/com/muyuchat/mca/ImageLibraryCommitOperations.kt")
            .substringAfter("fun upsertImportedImageUnderMutationLock(")
        assertTrue(importedCommit.contains("imageLibraryMutationMutex.withLock"))
        assertTrue(importedCommit.contains("currentCoroutineContext().ensureActive()"))
        val importedUpsert = importedCommit.indexOf("chatSessionStore.upsertImages(listOf(image))")
        val importedPublish = importedCommit.indexOf("publish()")
        assertTrue(importedUpsert >= 0 && importedPublish > importedUpsert)
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
        // R3/C3-S13: the startup reconciliation (both reconcile calls) moved verbatim to
        // ImageLibraryStartupReconciliation.kt; the assertions are unchanged -- they are read
        // where the block now lives.
        val startupReconciliation = sourceFile("app/src/main/java/com/muyuchat/mca/ImageLibraryStartupReconciliation.kt")
        assertTrue(startupReconciliation.contains("imageLibraryBackup.reconcile(initialImages)"))
        assertTrue(startupReconciliation.contains(
            "reconcileImageAssetDirectory(imageAssetDirectory, initialImages)"
        ))
        assertTrue(localApiGeneration.contains("tryAcquireObservedImageGenerationLease(requestId)"))
        assertTrue(localApiGeneration.contains("releaseObservedImageGenerationLease(generationLease)"))
        assertTrue(observedAcquire.indexOf("updateGenerationImageGrantReleaseDeferInto(store, true)") <
            observedAcquire.indexOf("coordinator.tryAcquire(requestId)"))
        assertTrue(observedRelease.contains("coordinator.activeRequestId() != null"))
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
