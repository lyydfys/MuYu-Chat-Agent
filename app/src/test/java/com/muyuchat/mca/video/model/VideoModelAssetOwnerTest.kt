package com.muyuchat.mca.video.model

import com.muyuchat.core.download.DownloadStatus
import com.muyuchat.core.download.DownloadTaskSnapshot
import com.muyuchat.core.download.ModelRepositoryProvider
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.video.contract.VideoModelAssetPhase
import com.muyuchat.core.video.contract.VideoModelAssetRole
import com.muyuchat.core.video.contract.VideoModelAssetState
import com.muyuchat.mca.managedRemoteIdentity
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Owner-level contract of the production model acquisition path: role/name mapping, pinned
 * LFS-oid verification, skipping bytes that already match, resuming a `.part`, refusing to start
 * without disk headroom, and preserving partial bytes on cancellation.
 *
 * The fake downloader only replaces the HTTP transport; the real [VideoModelAssetOwner] logic,
 * the real [VideoModelAssetVerifier] hashing and the real on-disk paths are exercised.
 */
class VideoModelAssetOwnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ditBytes = ByteArray(32) { (it + 1).toByte() }
    private val encoderBytes = ByteArray(48) { (it + 11).toByte() }
    private val vaeBytes = ByteArray(64) { (it + 31).toByte() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun component(role: VideoModelComponentRole, path: String, bytes: ByteArray): VideoModelComponentFile =
        VideoModelComponentFile(
            role = role,
            relativePath = path,
            format = "gguf",
            quantization = "TEST",
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256(bytes),
            sourceUrl = "https://huggingface.co/test/repo/resolve/main/$path",
            license = "test",
            provenance = "owner test fixture",
        )

    private fun manifest(): VideoModelManifest = VideoModelManifest(
        modelId = "wan2.2-ti2v-5b",
        family = "WAN2_2",
        variant = "TI2V_5B",
        revision = "owner-test",
        components = listOf(
            component(VideoModelComponentRole.DIT, "dit/a.gguf", ditBytes),
            component(VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL, "text_encoder/b.gguf", encoderBytes),
            component(VideoModelComponentRole.VAE_WAN22_5B, "vae/c.safetensors", vaeBytes),
        ),
        referenceSamplerRecipe = Wan22Ti2v5bProfiles.REFERENCE_RECIPE,
    )

    private class FakeDownloader(
        private val payload: Map<String, ByteArray>,
        private val hold: CompletableDeferred<Unit>? = null,
    ) : VideoModelAssetDownloader {
        val calls = mutableListOf<String>()
        var partialBytesSeen = 0L

        override suspend fun download(
            remote: RemoteModelFile,
            tempFile: File,
            finalFile: File,
            onProgress: (DownloadTaskSnapshot) -> Unit,
        ): DownloadTaskSnapshot {
            calls += remote.path
            val bytes = payload.getValue(remote.path)
            tempFile.parentFile?.mkdirs()
            val existing = if (tempFile.isFile) tempFile.readBytes() else ByteArray(0)
            partialBytesSeen += existing.size
            if (existing.isEmpty()) {
                tempFile.writeBytes(bytes.copyOfRange(0, minOf(4, bytes.size)))
            }
            hold?.await()
            val merged = if (tempFile.isFile && tempFile.length() in 1 until bytes.size.toLong()) {
                val prefix = tempFile.readBytes()
                prefix + bytes.copyOfRange(prefix.size, bytes.size)
            } else {
                bytes
            }
            tempFile.writeBytes(merged)
            finalFile.parentFile?.mkdirs()
            if (finalFile.exists()) finalFile.delete()
            check(tempFile.renameTo(finalFile)) { "fake rename failed" }
            val snapshot = DownloadTaskSnapshot(
                repoId = remote.repoId,
                revision = remote.revision,
                fileName = remote.name,
                url = remote.downloadUrl,
                expectedLength = bytes.size.toLong(),
                downloadedBytes = bytes.size.toLong(),
                status = DownloadStatus.DONE,
                tempFile = tempFile,
                finalFile = finalFile,
            )
            onProgress(snapshot)
            return snapshot
        }
    }

    private fun owner(
        scope: TestScope,
        root: File,
        downloader: VideoModelAssetDownloader,
        available: Long? = 1_000_000_000L,
    ): VideoModelAssetOwner = VideoModelAssetOwner(
        root = root,
        scope = scope.backgroundScope,
        manifest = manifest(),
        downloader = downloader,
        availableSpaceBytes = { available },
        ioDispatcher = StandardTestDispatcher(scope.testScheduler),
    )

    /**
     * Waits for a settled owner state instead of calling `advanceUntilIdle()` and reading the flow
     * once.
     *
     * `TestScope.advanceUntilIdle()` deliberately stops as soon as only `backgroundScope` work is
     * left pending (kotlinx-coroutines-test documents this: "advanceUntilIdle ... will stop
     * advancing the virtual time once only the coroutines in this scope are left unprocessed"),
     * and this owner runs in `backgroundScope`. A single `advanceUntilIdle()` therefore returned
     * the initial UNKNOWN state for every asynchronous test — a harness defect, not an owner bug.
     * Collecting the StateFlow from the test body is driven by `runTest`'s background-work runner,
     * so the wait is deterministic; a genuinely non-settling owner still fails via the bounded
     * timeout instead of a misleading assertion.
     */
    private suspend fun awaitSettled(owner: VideoModelAssetOwner): VideoModelAssetState =
        withTimeout(30_000) { owner.observe().first { it.phase in SETTLED_PHASES } }

    @Test
    fun `ready components are verified without any download`() = runTest {
        val root = tmp.newFolder("ready")
        val manifest = manifest()
        manifest.components.forEach { component ->
            val file = File(root, component.relativePath)
            file.parentFile?.mkdirs()
            file.writeBytes(
                when (component.role) {
                    VideoModelComponentRole.DIT -> ditBytes
                    VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL -> encoderBytes
                    VideoModelComponentRole.VAE_WAN22_5B -> vaeBytes
                    VideoModelComponentRole.LORA_TURBO,
                    VideoModelComponentRole.TAE_HEAD -> ByteArray(0)
                },
            )
        }
        val fake = FakeDownloader(emptyMap())
        val owner = owner(this, root, fake)
        owner.refresh()
        val state = awaitSettled(owner)
        assertEquals(VideoModelAssetPhase.READY, state.phase)
        assertTrue(state.ready)
        assertTrue(state.components.all { it.verified })
        assertTrue("ready bytes must not be downloaded again", fake.calls.isEmpty())
    }

    @Test
    fun `only missing components are downloaded and roles map to pinned paths`() = runTest {
        val root = tmp.newFolder("partial")
        val manifest = manifest()
        val dit = manifest.components.first { it.role == VideoModelComponentRole.DIT }
        File(root, dit.relativePath).apply { parentFile?.mkdirs(); writeBytes(ditBytes) }
        val fake = FakeDownloader(
            mapOf(
                "text_encoder/b.gguf" to encoderBytes,
                "vae/c.safetensors" to vaeBytes,
            ),
        )
        val owner = owner(this, root, fake)
        owner.download()
        val state = awaitSettled(owner)
        assertEquals(VideoModelAssetPhase.READY, state.phase)
        assertEquals(listOf("text_encoder/b.gguf", "vae/c.safetensors"), fake.calls)
        assertEquals(setOf(VideoModelAssetRole.DIT, VideoModelAssetRole.TEXT_ENCODER, VideoModelAssetRole.VAE), state.components.map { it.role }.toSet())
    }

    @Test
    fun `a pre-existing part file is resumed without restarting the component`() = runTest {
        val root = tmp.newFolder("resume")
        val manifest = manifest()
        val encoder = manifest.components.first { it.role == VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL }
        val remote = owner(this, root, FakeDownloader(emptyMap())).remoteFor(encoder)
        val temp = File(root, encoder.relativePath).parentFile!!.resolve(".${managedRemoteIdentity(remote)}.part")
        temp.parentFile?.mkdirs()
        temp.writeBytes(encoderBytes.copyOfRange(0, 11))
        File(root, "dit/a.gguf").apply { parentFile?.mkdirs(); writeBytes(ditBytes) }
        File(root, "vae/c.safetensors").apply { parentFile?.mkdirs(); writeBytes(vaeBytes) }
        val fake = FakeDownloader(mapOf("text_encoder/b.gguf" to encoderBytes))
        val owner = owner(this, root, fake)
        owner.download()
        val state = awaitSettled(owner)
        assertEquals(VideoModelAssetPhase.READY, state.phase)
        assertTrue("the owner must pass the existing .part to the downloader", fake.partialBytesSeen >= 11L)
        assertTrue(File(root, encoder.relativePath).readBytes().contentEquals(encoderBytes))
    }

    @Test
    fun `same-size foreign bytes are rejected and replaced`() = runTest {
        val root = tmp.newFolder("foreign")
        val manifest = manifest()
        val dit = manifest.components.first { it.role == VideoModelComponentRole.DIT }
        File(root, dit.relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(ditBytes.size) { 7 })
        }
        File(root, "text_encoder/b.gguf").apply { parentFile?.mkdirs(); writeBytes(encoderBytes) }
        File(root, "vae/c.safetensors").apply { parentFile?.mkdirs(); writeBytes(vaeBytes) }
        val fake = FakeDownloader(mapOf("dit/a.gguf" to ditBytes))
        val owner = owner(this, root, fake)
        owner.download()
        val state = awaitSettled(owner)
        assertEquals(VideoModelAssetPhase.READY, state.phase)
        assertEquals(listOf("dit/a.gguf"), fake.calls)
        assertTrue(File(root, dit.relativePath).readBytes().contentEquals(ditBytes))
    }

    @Test
    fun `insufficient free space fails before any download`() = runTest {
        val root = tmp.newFolder("disk")
        val fake = FakeDownloader(emptyMap())
        val owner = owner(this, root, fake, available = 0L)
        owner.download()
        val state = awaitSettled(owner)
        assertEquals(VideoModelAssetPhase.FAILED, state.phase)
        assertEquals("DISK_FULL", state.errorCode)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `space precheck only requires the missing tail of a partially downloaded component`() = runTest {
        val root = tmp.newFolder("resume-space")
        val manifest = manifest()
        val encoder = manifest.components.first { it.role == VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL }
        val remote = owner(this, root, FakeDownloader(emptyMap())).remoteFor(encoder)
        val temp = File(root, encoder.relativePath).parentFile!!.resolve(".${managedRemoteIdentity(remote)}.part")
        temp.parentFile?.mkdirs()
        temp.writeBytes(encoderBytes.copyOfRange(0, 11))
        File(root, "dit/a.gguf").apply { parentFile?.mkdirs(); writeBytes(ditBytes) }
        File(root, "vae/c.safetensors").apply { parentFile?.mkdirs(); writeBytes(vaeBytes) }
        // 48-byte component with 11 bytes already on disk leaves 37 bytes to fetch. Counting the
        // whole component (48 + margin) would reject this; the resume only needs 37 + margin.
        val fake = FakeDownloader(mapOf("text_encoder/b.gguf" to encoderBytes))
        val owner = owner(this, root, fake, available = FREE_SPACE_MARGIN + 40L)
        owner.download()
        val state = awaitSettled(owner)
        assertEquals(VideoModelAssetPhase.READY, state.phase)
        assertTrue("the resumed .part must be reused", fake.partialBytesSeen >= 11L)
        assertTrue(File(root, encoder.relativePath).readBytes().contentEquals(encoderBytes))
    }

    @Test
    fun `space precheck still rejects a resume that does not fit`() = runTest {
        val root = tmp.newFolder("resume-space-tight")
        val manifest = manifest()
        val encoder = manifest.components.first { it.role == VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL }
        val remote = owner(this, root, FakeDownloader(emptyMap())).remoteFor(encoder)
        val temp = File(root, encoder.relativePath).parentFile!!.resolve(".${managedRemoteIdentity(remote)}.part")
        temp.parentFile?.mkdirs()
        temp.writeBytes(encoderBytes.copyOfRange(0, 11))
        File(root, "dit/a.gguf").apply { parentFile?.mkdirs(); writeBytes(ditBytes) }
        File(root, "vae/c.safetensors").apply { parentFile?.mkdirs(); writeBytes(vaeBytes) }
        // 37 bytes still needed + margin, but only 30 + margin is free: the honest answer is
        // DISK_FULL before any network call, not a half-written download.
        val fake = FakeDownloader(mapOf("text_encoder/b.gguf" to encoderBytes))
        val owner = owner(this, root, fake, available = FREE_SPACE_MARGIN + 30L)
        owner.download()
        val state = awaitSettled(owner)
        assertEquals(VideoModelAssetPhase.FAILED, state.phase)
        assertEquals("DISK_FULL", state.errorCode)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `cancellation preserves the partial file and reports paused`() = runTest {
        val root = tmp.newFolder("cancel")
        val gate = CompletableDeferred<Unit>()
        val fake = FakeDownloader(mapOf("dit/a.gguf" to ditBytes), hold = gate)
        val owner = owner(this, root, fake)
        owner.download()
        testScheduler.runCurrent()
        owner.cancel()
        val state = awaitSettled(owner)
        assertEquals(VideoModelAssetPhase.CANCELLED, state.phase)
        assertTrue("the .part must survive cancellation", root.walkTopDown().any { it.isFile && it.name.endsWith(".part") && it.length() > 0L })
    }

    @Test
    fun `pinned profiles map to LFS oids not xet hashes`() = runTest {
        val root = tmp.newFolder("pins")
        val owner = owner(this, root, FakeDownloader(emptyMap()))
        val byRole = Wan22Ti2v5bProfiles.Q4_0_Q8_0.components.associateBy { it.role }
        val dit = byRole.getValue(VideoModelComponentRole.DIT)
        val encoder = byRole.getValue(VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL)
        val vae = byRole.getValue(VideoModelComponentRole.VAE_WAN22_5B)
        assertEquals("fcf40dd62cb5e15556eef5f975700ab0b071aacd1939607689276e88f81ba463", dit.sha256)
        assertEquals("2521d4de0bf9e1cc6549866463ceae85e4ec3239bc6063f7488810be39033bbc", encoder.sha256)
        assertEquals("e40321bd36b9709991dae2530eb4ac303dd168276980d3e9bc4b6e2b75fed156", vae.sha256)
        assertNotEquals("f68930ef925cee2bc2ad68c07207d542d1fb37a5ff984665f55735732454c635", dit.sha256)
        assertNotEquals("6048e11fd9863de836bc014d9cdde1ac4a2b8ba9a3fa89beb08528400d27d828", encoder.sha256)
        assertNotEquals("53b5f2941a82a2494fd4315151050f9022eefcea4bcc51e0e15b99079f92947f", vae.sha256)
        val remote = owner.remoteFor(dit)
        assertEquals(ModelRepositoryProvider.MODELSCOPE, remote.provider)
        assertEquals("lyydfys/mca-wan22-video-assets", remote.repoId)
        assertEquals(Wan22Ti2v5bProfiles.DIT_Q4_0_REVISION, remote.revision)
        assertNotEquals("main", remote.revision)
        assertTrue(dit.sourceUrl.endsWith("/resolve/${Wan22Ti2v5bProfiles.DIT_Q4_0_REVISION}/dit/Wan2.2-TI2V-5B-Q4_0.gguf"))
        // ModelScope is the transfer host; the immutable revision and the SHA-256 pin still
        // decide acceptance.
        assertTrue(dit.sourceUrl.startsWith("https://modelscope.cn/models/"))
        assertEquals(dit.sourceUrl, remote.downloadUrl)
        assertEquals("dit/Wan2.2-TI2V-5B-Q4_0.gguf", remote.path)
        assertEquals(dit.sizeBytes, remote.sizeBytes)
        assertEquals(dit.sha256, remote.sha256)
        assertEquals("dit/Wan2.2-TI2V-5B-Q4_0.gguf", remote.relativePath)
    }

    @Test
    fun `refresh reports every missing role without touching the network`() = runTest {
        val root = tmp.newFolder("missing")
        val fake = FakeDownloader(emptyMap())
        val owner = owner(this, root, fake)
        owner.refresh()
        val state = awaitSettled(owner)
        assertEquals(VideoModelAssetPhase.MISSING, state.phase)
        assertFalse(state.ready)
        assertEquals(3, state.components.size)
        assertEquals(3, state.components.count { !it.present })
        assertTrue(fake.calls.isEmpty())
    }

    private companion object {
        /** Mirrors `VideoModelAssetOwner.MIN_FREE_MARGIN_BYTES` so the space tests stay explicit. */
        const val FREE_SPACE_MARGIN = 256L * 1024L * 1024L

        /** Progress phases (CHECKING/DOWNLOADING/VERIFYING/UNKNOWN) are not settled states. */
        val SETTLED_PHASES = setOf(
            VideoModelAssetPhase.READY,
            VideoModelAssetPhase.MISSING,
            VideoModelAssetPhase.FAILED,
            VideoModelAssetPhase.CANCELLED,
        )
    }
}
