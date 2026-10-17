package com.muyuchat.mca

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicReference
import com.muyuchat.mca.TextualInversionStore.Companion.processFileLockMutex
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal suspend fun TextualInversionStore.bindImpl(
        id: String,
        modelFingerprint: String,
        tokenizerFingerprint: String,
        profileId: String,
        profileRevision: Int,
        runtime: TextualInversionRuntime = TextualInversionRuntime.STABLE_DIFFUSION_CPP
    ): TextualInversionBinding = withStoreLock(shared = true) { checkCancelled ->
        val artifact = findInstalledArtifact(id, loadLocked(checkCancelled))
        artifact.bind(
            modelFingerprint = modelFingerprint.trim().lowercase(),
            tokenizerFingerprint = tokenizerFingerprint.trim().lowercase(),
            profileId = profileId.trim(),
            profileRevision = profileRevision,
            runtime = runtime
        )
    }
internal suspend fun TextualInversionStore.prepareBindingImpl(
        artifact: TextualInversionArtifact,
        modelFingerprint: String,
        tokenizerFingerprint: String,
        profileId: String,
        profileRevision: Int,
        runtime: TextualInversionRuntime = TextualInversionRuntime.STABLE_DIFFUSION_CPP
    ): TextualInversionBinding = withStoreLock(shared = true) { checkCancelled ->
        val current = findInstalledArtifact(artifact.id, loadLocked(checkCancelled))
        require(current.sha256 == artifact.sha256 && current.sizeBytes == artifact.sizeBytes) {
            "Textual inversion artifact changed since it was selected."
        }
        current.bind(modelFingerprint, tokenizerFingerprint, profileId, profileRevision, runtime)
    }
internal suspend fun TextualInversionStore.acquireSelectionLeaseImpl(
        ids: List<String>,
        modelFingerprint: String,
        tokenizerFingerprint: String,
        profileId: String,
        profileRevision: Int,
        runtime: TextualInversionRuntime = TextualInversionRuntime.STABLE_DIFFUSION_CPP,
        executionAssetBinding: TextualInversionExecutionAssetBinding? = null
    ): TextualInversionSelectionLease {
        val callerContext = currentCoroutineContext()
        val undeliveredLease = AtomicReference<TextualInversionSelectionLease?>(null)
        return try {
            val lease = withContext(ioDispatcher + NonCancellable) {
                val checkCancelled = callerContext::ensureActive
                checkCancelled()
                val normalizedIds = normalizeSelectionIds(ids)
                processFileLockMutex.lock()
                var channel: java.nio.channels.FileChannel? = null
                var fileLock: java.nio.channels.FileLock? = null
                val artifactHandles = mutableListOf<RandomAccessFile>()
                val executionAssetHandles = mutableListOf<RandomAccessFile>()
                val acquiredLease = try {
                    checkCancelled()
                    ensureRoot()
                    val openedChannel = RandomAccessFile(lockFile, "rw").channel
                    channel = openedChannel
                    val acquiredLock = openedChannel.lock(0L, Long.MAX_VALUE, true)
                    fileLock = acquiredLock
                    checkCancelled()
                    val installed = loadLocked(checkCancelled)
                        .associateBy(TextualInversionArtifact::id)
                    val selectedArtifacts = normalizedIds.map { id ->
                        installed[id]
                            ?: throw TextualInversionStoreException(
                                "Textual inversion artifact $id is not installed."
                            )
                    }
                    val initialVerifications = mutableListOf<ArtifactFileVerification>()
                    selectedArtifacts.forEach { artifact ->
                        checkCancelled()
                        val handle = RandomAccessFile(File(artifact.path), "r")
                        try {
                            initialVerifications += verifyRecordDescriptor(
                                artifact = artifact,
                                handle = handle,
                                checkCancelled = checkCancelled
                            )
                            artifactHandles += handle
                        } catch (failure: Throwable) {
                            handle.close()
                            throw failure
                        }
                    }
                    val initialExecutionAssets = executionAssetBinding?.assets.orEmpty().map { asset ->
                        checkCancelled()
                        val handle = RandomAccessFile(File(asset.path), "r")
                        try {
                            verifyExecutionAssetDescriptor(asset, handle, checkCancelled).also {
                                executionAssetHandles += handle
                            }
                        } catch (failure: Throwable) {
                            handle.close()
                            throw failure
                        }
                    }
                    val bindings = normalizedIds.map { id ->
                        requireNotNull(installed[id]).bind(
                            modelFingerprint.trim().lowercase(),
                            tokenizerFingerprint.trim().lowercase(),
                            profileId.trim(),
                            profileRevision,
                            runtime
                        )
                    }
                    val selection = TextualInversionSelection(bindings, executionAssetBinding)
                    TextualInversionSelectionLease(
                        selection = selection,
                        rootPath = root.path,
                        verify = {
                            selectedArtifacts.forEachIndexed { index, artifact ->
                                val descriptorVerification = verifyRecordDescriptor(
                                    artifact = artifact,
                                    handle = artifactHandles[index],
                                    checkCancelled = {}
                                )
                                val pathVerification = requireRecordFileIsCurrent(
                                    artifact = artifact,
                                    checkCancelled = {},
                                    forceHash = true
                                )
                                require(descriptorVerification.sameIdentity(initialVerifications[index]) &&
                                    pathVerification.sameIdentity(initialVerifications[index])
                                ) {
                                    "Textual inversion artifact ${artifact.id} changed during native execution."
                                }
                            }
                            executionAssetBinding?.assets.orEmpty().forEachIndexed { index, asset ->
                                val descriptorVerification = verifyExecutionAssetDescriptor(
                                    expected = asset,
                                    handle = executionAssetHandles[index],
                                    checkCancelled = {}
                                )
                                val pathVerification = verifyExecutionAssetPath(asset, checkCancelled = {})
                                require(descriptorVerification.sameIdentity(initialExecutionAssets[index]) &&
                                    pathVerification.sameIdentity(initialExecutionAssets[index])
                                ) {
                                    "Prompt execution asset ${asset.label} changed during native execution."
                                }
                            }
                        }
                    ) {
                        try {
                            executionAssetHandles.asReversed().forEach { handle ->
                                runCatching { handle.close() }
                            }
                            artifactHandles.asReversed().forEach { handle ->
                                runCatching { handle.close() }
                            }
                        } finally {
                            try {
                                acquiredLock.release()
                            } finally {
                                try {
                                    openedChannel.close()
                                } finally {
                                    processFileLockMutex.unlock()
                                }
                            }
                        }
                    }.also {
                        channel = null
                        fileLock = null
                    }
                } catch (failure: Throwable) {
                    try {
                        executionAssetHandles.asReversed().forEach { handle ->
                            runCatching { handle.close() }
                        }
                        artifactHandles.asReversed().forEach { handle ->
                            runCatching { handle.close() }
                        }
                    } finally {
                        try {
                            fileLock?.release()
                        } finally {
                            try {
                                channel?.close()
                            } finally {
                                processFileLockMutex.unlock()
                            }
                        }
                    }
                    throw failure
                }
                undeliveredLease.set(acquiredLease)
                afterSelectionLeaseAcquired()
                acquiredLease
            }
            undeliveredLease.set(null)
            lease
        } catch (failure: Throwable) {
            val lease = undeliveredLease.getAndSet(null)
            if (lease != null) {
                try {
                    lease.close()
                } catch (releaseFailure: Throwable) {
                    failure.addSuppressed(releaseFailure)
                }
            }
            throw failure
        }
    }
internal suspend fun TextualInversionStore.commitSuccessfulBindingsImpl(
        selection: TextualInversionSelection,
        nativeBindingFingerprint: String,
        nativeBindingStage: String
    ): TextualInversionSelection = withCommittedMutation { checkCancelled, markCommitted ->
        require(selection.bindings.isNotEmpty()) { "A textual inversion selection is required." }
        require(nativeBindingStage == "conditioning_consumed") {
            "Textual inversion binding cannot be committed before native conditioning consumption."
        }
        require(
            nativeBindingFingerprint.trim().lowercase() == selection.bindingFingerprint
        ) { "Native textual inversion binding evidence does not match the requested selection." }

        val bindingsById = selection.bindings.associateBy { it.artifact.id }
        val current = loadLocked(checkCancelled)
        selection.executionAssetBinding?.assets.orEmpty().forEach { asset ->
            verifyExecutionAssetPath(asset, checkCancelled)
        }
        bindingsById.forEach { (id, binding) ->
            val installed = findInstalledArtifact(id, current)
            requireRecordFileIsCurrent(installed, checkCancelled, forceHash = true)
            require(
                installed.sha256 == binding.artifact.sha256 &&
                    installed.sizeBytes == binding.artifact.sizeBytes &&
                    installed.trigger.equals(binding.artifact.trigger, ignoreCase = true)
            ) { "Textual inversion artifact changed before the successful binding commit." }
        }
        val updated = current.map { artifact ->
            val binding = bindingsById[artifact.id] ?: return@map artifact
            artifact.copy(
                modelFingerprint = binding.modelFingerprint.lowercase(),
                tokenizerFingerprint = binding.tokenizerFingerprint.lowercase()
            )
        }
        checkCancelled()
        commitManifest(
            records = updated,
            checkCancelled = checkCancelled,
            onCommitted = { markCommitted(selection) }
        )
        selection
    }

    private fun normalizeSelectionIds(ids: List<String>): List<String> {
        require(ids.isNotEmpty()) { "At least one textual inversion id is required." }
        require(ids.size <= TextualInversionContract.MAX_COUNT) {
            "At most ${TextualInversionContract.MAX_COUNT} textual inversions may be active."
        }
        val normalized = ids.map { id ->
            id.trim().lowercase().also {
                require(TextualInversionContract.UUID_PATTERN.matches(it)) {
                    "Textual inversion ids must be UUIDs."
                }
            }
        }
        require(normalized.distinct().size == normalized.size) {
            "Textual inversion ids must be unique per request."
        }
        return normalized
    }

    private fun findInstalledArtifact(
        id: String,
        artifacts: List<TextualInversionArtifact>
    ): TextualInversionArtifact {
        val normalizedId = id.trim().lowercase()
        require(TextualInversionContract.UUID_PATTERN.matches(normalizedId)) {
            "Textual inversion id must be a UUID."
        }
        return artifacts.firstOrNull { it.id == normalizedId }
            ?: throw TextualInversionStoreException("Textual inversion artifact is not installed.")
    }
