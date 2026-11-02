package com.muyuchat.core.dsh.runtime.payload

import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * D10 acceptance: the no-replace claim + atomic rename protocol, crash durability and the typed
 * failure branches. Everything here runs on a real filesystem with injected faults, so a failing
 * branch proves the previous verified tree was preserved instead of being overwritten.
 */
class DshPublishPrimitivesTest {

    private fun tempDir(prefix: String = "dsh-publish"): File =
        Files.createTempDirectory(prefix).toFile()

    private fun stagedTree(vararg names: String): File {
        val dir = tempDir("dsh-staged")
        names.forEach { name -> File(dir, name).apply { parentFile?.mkdirs(); writeText("staged:$name") } }
        return dir
    }

    private class FakeFileOps(
        private val moveFailure: Throwable? = null,
        private val foreignOnClaim: File? = null
    ) : DshFileOps {
        override fun createDirectory(path: Path) {
            if (foreignOnClaim != null) {
                Files.createDirectories(foreignOnClaim.toPath())
                File(foreignOnClaim, "foreign.txt").writeText("foreign")
                throw FileAlreadyExistsException(path.toString())
            }
            DefaultDshFileOps.createDirectory(path)
        }

        override fun atomicMove(source: Path, target: Path) {
            moveFailure?.let { throw it }
            DefaultDshFileOps.atomicMove(source, target)
        }

        override fun deleteIfExists(path: Path): Boolean = DefaultDshFileOps.deleteIfExists(path)
        override fun existsNoFollow(path: Path): Boolean = DefaultDshFileOps.existsNoFollow(path)
        override fun isDirectoryNoFollow(path: Path): Boolean = DefaultDshFileOps.isDirectoryNoFollow(path)
    }

    private class FakeDurability(
        val file: (File) -> DshSyncOutcome = { DshSyncOutcome.Synced },
        val directory: (File) -> DshSyncOutcome = { DshSyncOutcome.Synced }
    ) : DshDurability {
        val syncedFiles = mutableListOf<File>()
        val syncedDirectories = mutableListOf<File>()

        override fun syncFile(file: File): DshSyncOutcome {
            syncedFiles += file
            return this.file(file)
        }

        override fun syncDirectory(directory: File): DshSyncOutcome {
            syncedDirectories += directory
            return this.directory(directory)
        }
    }

    // ------------------------------------------------------------------ claim

    @Test
    fun claimFailsOnExistingDirectoryAndKeepsItsContent() {
        val root = tempDir()
        val target = File(root, "payload").apply { mkdirs() }
        File(target, "user.txt").writeText("keep")
        val outcome = DshDirectoryPublisher().claimTarget(target)
        assertTrue("claim must fail closed, was $outcome", outcome is DshTargetClaim.AlreadyPresent)
        assertEquals("keep", File(target, "user.txt").readText())
    }

    @Test
    fun claimFailsOnExistingFile() {
        val root = tempDir()
        val target = File(root, "payload").apply { writeText("sentinel") }
        val outcome = DshDirectoryPublisher().claimTarget(target)
        assertTrue(outcome is DshTargetClaim.AlreadyPresent)
        assertEquals("sentinel", target.readText())
    }

    @Test
    fun claimFailsOnSymlinkWithoutTouchingItsTarget() {
        val root = tempDir()
        val outside = tempDir("dsh-outside")
        File(outside, "keep.txt").writeText("keep")
        val link = File(root, "payload")
        Assume.assumeTrue("directory symlinks are unsupported on this host", createDirectoryLink(link, outside))
        val outcome = DshDirectoryPublisher().claimTarget(link)
        assertTrue("claim must not follow a symlink, was $outcome", outcome is DshTargetClaim.AlreadyPresent)
        assertEquals("keep", File(outside, "keep.txt").readText())
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test
    fun claimSucceedsOnAbsentTarget() {
        val target = File(tempDir(), "payload")
        assertTrue(DshDirectoryPublisher().claimTarget(target) is DshTargetClaim.Claimed)
        assertTrue(Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    // ------------------------------------------------------------------ publish

    @Test
    fun publishReplacesOnlyOurOwnEmptyClaim() {
        val staged = stagedTree("a.txt")
        val target = File(tempDir(), "payload").apply { mkdirs() }
        val published = DshDirectoryPublisher().publishOntoClaim(staged, target)
        assertTrue("publish failed: $published", published is DshDirectoryPublish.Published)
        assertEquals("staged:a.txt", File(target, "a.txt").readText())
        assertFalse(staged.exists())
    }

    @Test
    fun publishRefusesForeignNonEmptyDirectoryAndKeepsIt() {
        val staged = stagedTree("a.txt")
        val target = File(tempDir(), "payload").apply { mkdirs() }
        File(target, "other-app.txt").writeText("keep")
        val published = DshDirectoryPublisher().publishOntoClaim(staged, target)
        assertTrue("must fail closed, was $published", published is DshDirectoryPublish.NotPublished)
        assertEquals("keep", File(target, "other-app.txt").readText())
        assertEquals("staged:a.txt", File(staged, "a.txt").readText())
    }

    @Test
    fun crossDeviceRenameIsTypedAndClaimCanBeReleased() {
        val staged = stagedTree("a.txt")
        val target = File(tempDir(), "payload")
        val publisher = DshDirectoryPublisher(
            ops = FakeFileOps(
                moveFailure = AtomicMoveNotSupportedException(
                    staged.absolutePath,
                    target.absolutePath,
                    "Invalid cross-device link"
                )
            )
        )
        assertTrue(publisher.claimTarget(target) is DshTargetClaim.Claimed)
        val published = publisher.publishOntoClaim(staged, target)
        assertTrue(published is DshDirectoryPublish.NotPublished)
        val detail = (published as DshDirectoryPublish.NotPublished).detail.lowercase()
        assertTrue("detail must name the rename limitation: $detail", "cross-device" in detail || "atomic" in detail)
        assertTrue(publisher.releaseClaim(target))
        assertFalse(target.exists())
        assertEquals("staged:a.txt", File(staged, "a.txt").readText())
    }

    @Test
    fun releaseClaimNeverDeletesForeignContent() {
        val target = File(tempDir(), "payload").apply { mkdirs() }
        File(target, "foreign.txt").writeText("keep")
        assertFalse(DshDirectoryPublisher().releaseClaim(target))
        assertEquals("keep", File(target, "foreign.txt").readText())
    }

    // ------------------------------------------------------------------ durability

    @Test
    fun fsyncFailureIsTypedAndPolicyCanSkipIt() {
        val file = File(tempDir(), "a.txt").apply { writeText("x") }
        val failing = DshDirectoryPublisher(durability = FakeDurability(file = { DshSyncOutcome.Failed("EIO") }))
        assertTrue(failing.syncStagedFile(file) is DshSyncOutcome.Failed)
        val skipped = DshDirectoryPublisher(
            durability = FakeDurability(file = { DshSyncOutcome.Failed("EIO") }),
            policy = DshPublishPolicy(fsyncStagedContent = false)
        )
        assertTrue(skipped.syncStagedFile(file) is DshSyncOutcome.Synced)
    }

    @Test
    fun unsupportedDirectorySyncIsRecordedNotFatal() {
        val dir = tempDir()
        val durability = FakeDurability(directory = { DshSyncOutcome.Unsupported("no dir fd") })
        val outcome = DshDirectoryPublisher(durability = durability).syncDirectory(dir)
        assertTrue(outcome is DshSyncOutcome.Unsupported)
        val skipped = DshDirectoryPublisher(
            durability = durability,
            policy = DshPublishPolicy(fsyncDirectories = false)
        )
        assertTrue(skipped.syncDirectory(dir) is DshSyncOutcome.Synced)
    }

    private fun createDirectoryLink(link: File, target: File): Boolean {
        return try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            Files.isSymbolicLink(link.toPath())
        } catch (t: Throwable) {
            runCatching {
                ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.absolutePath, target.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(30, TimeUnit.SECONDS)
            }.getOrDefault(false) && Files.isSymbolicLink(link.toPath())
        }
    }
}

/**
 * D10 acceptance at the production entry point: [DshPayloadInstaller] uses the claim protocol and
 * must leave the previous verified tree untouched on every failure branch.
 */
class DshPayloadPublishHardeningTest {

    private val kernelEntry = "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
    private val files = linkedMapOf(
        kernelEntry to "console.log('kernel')",
        "runtime/lib/libz.so.1" to "zlib-bytes",
        "payload/extra.txt" to "extra"
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun buildZip(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun manifestJson(archive: ByteArray): String {
        val critical = listOf(
            DshPayloadCriticalFile(
                kernelEntry,
                files.getValue(kernelEntry).toByteArray().size.toLong(),
                sha256(files.getValue(kernelEntry).toByteArray())
            ),
            DshPayloadCriticalFile(
                "runtime/lib/libz.so.1",
                files.getValue("runtime/lib/libz.so.1").toByteArray().size.toLong(),
                sha256(files.getValue("runtime/lib/libz.so.1").toByteArray())
            )
        )
        return DshJson.write(
            linkedMapOf(
                "manifestVersion" to 1,
                "payloadVersion" to "dsh-test",
                "archive" to linkedMapOf("asset" to "dsh/payload.zip", "size" to archive.size, "sha256" to sha256(archive)),
                "node" to linkedMapOf("jniLib" to "libdshnode.so", "size" to 1, "sha256" to "b".repeat(64)),
                "kernelEntry" to kernelEntry,
                "runtimeLibraryDir" to "runtime/lib",
                "fileCount" to files.size,
                "criticalFiles" to critical.map { it.toTree() },
                "source" to linkedMapOf("officialCommit" to "x", "referenceRepo" to "y", "referenceVersion" to "z"),
                "licenses" to listOf(linkedMapOf("name" to "DSH", "license" to "MIT"))
            )
        )
    }

    private fun installer(
        root: File,
        manifestJson: String,
        archive: ByteArray,
        publisher: DshDirectoryPublisher = DshDirectoryPublisher()
    ): DshPayloadInstaller = DshPayloadInstaller(
        config = DshRuntimeConfig(dataRoot = root).validated(),
        source = object : DshPayloadSource {
            override val description = "test"
            override fun openManifest() = ByteArrayInputStream(manifestJson.toByteArray())
            override fun openArchive() = ByteArrayInputStream(archive)
        },
        publisher = publisher
    )

    private fun archiveErrorSource(root: File, manifestJson: String): DshPayloadInstaller = DshPayloadInstaller(
        config = DshRuntimeConfig(dataRoot = root).validated(),
        source = object : DshPayloadSource {
            override val description = "broken"
            override fun openManifest() = ByteArrayInputStream(manifestJson.toByteArray())
            override fun openArchive() = object : ByteArrayInputStream(ByteArray(0)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    throw IOException("simulated mid-stream failure after 64 KiB")
            }
        }
    )

    private fun tempDir(prefix: String = "dsh-install"): File = Files.createTempDirectory(prefix).toFile()

    @Test
    fun contentFsyncFailureAbortsWithoutPublishing() {
        val root = tempDir()
        val archive = buildZip()
        val manifest = manifestJson(archive)
        val durability = object : DshDurability {
            override fun syncFile(file: File): DshSyncOutcome =
                if (file.name.endsWith(".txt") || file.name == "bin.js") DshSyncOutcome.Failed("simulated EIO")
                else DshSyncOutcome.Synced
            override fun syncDirectory(directory: File): DshSyncOutcome = DshSyncOutcome.Synced
        }
        val result = installer(root, manifest, archive, DshDirectoryPublisher(durability = durability)).ensureInstalled()
        assertTrue("must fail closed, was $result", result is DshPayloadInstallResult.Integrity)
        assertTrue((result as DshPayloadInstallResult.Integrity).detail.contains("fsync"))
        assertFalse(File(root, "payload").exists())
        assertFalse(File(root, "payload.marker.json").exists())
    }

    @Test
    fun unsupportedFsyncIsRecordedInTheInstallNotes() {
        val root = tempDir()
        val archive = buildZip()
        val manifest = manifestJson(archive)
        val durability = object : DshDurability {
            override fun syncFile(file: File): DshSyncOutcome = DshSyncOutcome.Unsupported("no fsync on this host")
            override fun syncDirectory(directory: File): DshSyncOutcome = DshSyncOutcome.Unsupported("no dir fsync")
        }
        val result = installer(root, manifest, archive, DshDirectoryPublisher(durability = durability))
            .ensureInstalled()
        val installed = result as DshPayloadInstallResult.Installed
        assertTrue(installed.notes.any { "unsupported" in it })
        assertTrue(File(root, "payload/$kernelEntry").isFile)
    }

    @Test
    fun failedDataRootSyncRestoresThePreviousTreeAndPublishesNoMarker() {
        val root = tempDir()
        val archive = buildZip()
        val manifest = manifestJson(archive)
        assertTrue(installer(root, manifest, archive).ensureInstalled() is DshPayloadInstallResult.Installed)
        val markerBefore = File(root, "payload.marker.json").readText()
        // Force a reinstall, then break exactly the dataRoot directory fsync.
        File(root, "payload/$kernelEntry").writeText("tampered")
        val durability = object : DshDurability {
            override fun syncFile(file: File): DshSyncOutcome = DshSyncOutcome.Synced
            override fun syncDirectory(directory: File): DshSyncOutcome =
                if (directory.absolutePath == root.absolutePath) DshSyncOutcome.Failed("simulated EIO")
                else DshSyncOutcome.Synced
        }
        val result = installer(root, manifest, archive, DshDirectoryPublisher(durability = durability)).ensureInstalled()
        assertTrue("must fail closed, was $result", result is DshPayloadInstallResult.Integrity)
        assertEquals("tampered", File(root, "payload/$kernelEntry").readText())
        assertEquals("the previous marker must not be replaced", markerBefore, File(root, "payload.marker.json").readText())
    }

    @Test
    fun foreignTargetAppearingDuringCommitFailsClosedWithoutDeletingIt() {
        val root = tempDir()
        val archive = buildZip()
        val manifest = manifestJson(archive)
        assertTrue(installer(root, manifest, archive).ensureInstalled() is DshPayloadInstallResult.Installed)
        File(root, "payload/$kernelEntry").writeText("tampered")
        val ops = object : DshFileOps by DefaultDshFileOps {
            override fun createDirectory(path: Path) {
                File(path.toFile(), "foreign.txt").apply { parentFile?.mkdirs() }.writeText("foreign")
                throw FileAlreadyExistsException(path.toString())
            }
        }
        val result = installer(root, manifest, archive, DshDirectoryPublisher(ops = ops)).ensureInstalled()
        assertTrue("must fail closed, was $result", result is DshPayloadInstallResult.Integrity)
        assertTrue((result as DshPayloadInstallResult.Integrity).detail.contains("appeared"))
        assertEquals("foreign", File(root, "payload/foreign.txt").readText())
    }

    @Test
    fun brokenArchiveStreamLeavesNoPartialTree() {
        val root = tempDir()
        val archive = buildZip()
        val result = archiveErrorSource(root, manifestJson(archive)).ensureInstalled()
        assertTrue(result is DshPayloadInstallResult.Integrity)
        assertFalse(File(root, "payload").exists())
        assertFalse(File(root, "payload.marker.json").exists())
        // Staging/archive/reservation debris must be cleaned up as well.
        val leftovers = root.listFiles().orEmpty().filter {
            it.name.startsWith("payload.staging.") ||
                it.name.startsWith("payload.archive.") ||
                it.name.startsWith("payload.reservation.")
        }
        assertTrue("install debris must be cleaned: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun symlinkedDataRootIsRejectedBeforeAnythingIsWritten() {
        val real = tempDir("dsh-real-root")
        val linkParent = tempDir("dsh-link-root")
        val link = File(linkParent, "dsh")
        Assume.assumeTrue("directory symlinks are unsupported on this host", createDirectoryLink(link, real))
        val archive = buildZip()
        val result = installer(link, manifestJson(archive), archive).ensureInstalled()
        assertTrue(result is DshPayloadInstallResult.Integrity)
        assertTrue((result as DshPayloadInstallResult.Integrity).detail.contains("symlink"))
        assertFalse(File(real, "payload").exists())
    }

    @Test
    fun lockFileThatIsASymlinkIsUnavailable() {
        val root = tempDir()
        root.mkdirs()
        val outside = File(tempDir("dsh-lock-target"), "real.lock").apply { writeText("x") }
        val lockPath = DshRuntimeConfig(root).payloadInstallLockFile
        Assume.assumeTrue("symlinks are unsupported on this host", createDirectoryLink(lockPath, outside))
        val outcome = DshPayloadLock.acquire(lockPath, 500L, 25L)
        assertTrue("a symlinked lock must not be followed, was $outcome", outcome is DshPayloadLockOutcome.Unavailable)
        assertTrue((outcome as DshPayloadLockOutcome.Unavailable).detail.contains("symlink") || "lock" in outcome.detail)
    }

    @Test
    fun lockPathThatIsADirectoryIsUnavailable() {
        val root = tempDir()
        root.mkdirs()
        val lockPath = DshRuntimeConfig(root).payloadInstallLockFile
        lockPath.mkdirs()
        val outcome = DshPayloadLock.acquire(lockPath, 500L, 25L)
        assertTrue("a non-file lock path must fail closed, was $outcome", outcome is DshPayloadLockOutcome.Unavailable)
    }

    private fun createDirectoryLink(link: File, target: File): Boolean {
        return try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            Files.isSymbolicLink(link.toPath())
        } catch (t: Throwable) {
            runCatching {
                ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.absolutePath, target.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(30, TimeUnit.SECONDS)
            }.getOrDefault(false) && Files.isSymbolicLink(link.toPath())
        }
    }
}

