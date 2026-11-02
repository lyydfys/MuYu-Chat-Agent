package com.muyuchat.core.dsh.runtime.payload

import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * C02a/C02b acceptance: real cross-process install lock + atomic non-overwriting publish.
 *
 * The two-process tests spawn a separate JVM running [DshPayloadTestProcess] so the OS file lock
 * (not just a JVM-local mutex) is exercised, and a killed holder proves the kernel releases it.
 */
class DshPayloadPublishTest {

    private val kernelEntry = "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
    private val files = linkedMapOf(
        kernelEntry to "console.log('kernel')",
        "dshhome/profiles/web/package.json" to "{\"name\":\"dsh-profile-web\"}",
        "runtime/lib/libz.so.1" to "zlib-bytes",
        "runtime/lib/libc++_shared.so" to "c++-bytes",
        "runtime/lib/libsqlite3.so" to "sqlite-bytes",
        "extra.txt" to "extra"
    )

    // ------------------------------------------------------------------ fixtures

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

    private fun buildManifest(archive: ByteArray): Pair<String, DshPayloadManifest> {
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
        val json = DshJson.write(
            linkedMapOf(
                "manifestVersion" to 1,
                "payloadVersion" to "dsh-test",
                "archive" to linkedMapOf(
                    "asset" to "dsh/payload.zip",
                    "size" to archive.size,
                    "sha256" to sha256(archive)
                ),
                "node" to linkedMapOf("jniLib" to "libdshnode.so", "size" to 1, "sha256" to "b".repeat(64)),
                "kernelEntry" to kernelEntry,
                "runtimeLibraryDir" to "runtime/lib",
                "fileCount" to files.size,
                "criticalFiles" to critical.map { it.toTree() },
                "source" to linkedMapOf("officialCommit" to "x", "referenceRepo" to "y", "referenceVersion" to "z"),
                "licenses" to listOf(linkedMapOf("name" to "DSH", "license" to "MIT"))
            )
        )
        return json to DshPayloadManifest.parse(json)
    }

    private fun source(manifestJson: String, archive: ByteArray): DshPayloadSource =
        object : DshPayloadSource {
            override val description = "test"
            override fun openManifest() = ByteArrayInputStream(manifestJson.toByteArray())
            override fun openArchive() = ByteArrayInputStream(archive)
        }

    private fun installer(
        root: File,
        manifestJson: String,
        archive: ByteArray,
        lockTimeoutMillis: Long = 10_000L,
        lockPollMillis: Long = 25L,
        staleArtifactMillis: Long = 0L
    ) = DshPayloadInstaller(
        config = DshRuntimeConfig(dataRoot = root).validated(),
        source = source(manifestJson, archive),
        lockTimeoutMillis = lockTimeoutMillis,
        lockPollMillis = lockPollMillis,
        staleArtifactMillis = staleArtifactMillis
    )

    private fun tempDir(prefix: String = "dsh-publish"): File =
        Files.createTempDirectory(prefix).toFile()

    // ------------------------------------------------------------------ reuse

    @Test
    fun validPayloadIsReusedWithoutRewriting() {
        val root = tempDir()
        val archive = buildZip()
        val (manifestJson, _) = buildManifest(archive)
        val install = installer(root, manifestJson, archive)
        val first = install.ensureInstalled()
        assertTrue(first is DshPayloadInstallResult.Installed)
        assertFalse((first as DshPayloadInstallResult.Installed).reused)
        val markerBefore = File(root, "payload.marker.json").readText()

        val second = install.ensureInstalled()
        assertTrue(second is DshPayloadInstallResult.Installed)
        assertTrue((second as DshPayloadInstallResult.Installed).reused)
        assertEquals(markerBefore, File(root, "payload.marker.json").readText())
        assertEquals(files.getValue(kernelEntry), File(root, "payload/$kernelEntry").readText())
        assertTrue(DshRuntimeConfig(root).payloadInstallLockFile.isFile)
    }

    // ------------------------------------------------------------------ target shape

    @Test
    fun refusesExistingNonDirectoryTargetWithoutFollowingIt() {
        val root = tempDir()
        root.mkdirs()
        val target = File(root, "payload").apply { writeText("sentinel-file") }
        val archive = buildZip()
        val (manifestJson, _) = buildManifest(archive)

        val result = installer(root, manifestJson, archive).ensureInstalled()

        assertTrue(result is DshPayloadInstallResult.Integrity)
        assertTrue((result as DshPayloadInstallResult.Integrity).detail.contains("not a directory"))
        assertEquals("sentinel-file", target.readText())
    }

    @Test
    fun refusesSymlinkTargetWithoutTouchingItsTarget() {
        val root = tempDir()
        val outside = tempDir("dsh-outside")
        File(outside, "keep.txt").writeText("keep")
        val link = File(root, "payload")
        val supported = try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        } catch (t: Throwable) {
            // Windows without Developer Mode can still create a directory junction without
            // elevation; only accept it when the JDK reports it as a symbolic link.
            runCatching {
                ProcessBuilder(
                    "cmd.exe",
                    "/c",
                    "mklink",
                    "/J",
                    link.absolutePath,
                    outside.absolutePath
                ).redirectErrorStream(true).start().waitFor(30, TimeUnit.SECONDS)
            }.getOrDefault(false) && Files.isSymbolicLink(link.toPath())
        }
        Assume.assumeTrue("directory symlinks/junctions are unsupported on this host", supported)

        val archive = buildZip()
        val (manifestJson, _) = buildManifest(archive)
        val result = installer(root, manifestJson, archive).ensureInstalled()

        assertTrue(result is DshPayloadInstallResult.Integrity)
        assertTrue((result as DshPayloadInstallResult.Integrity).detail.contains("symlink", ignoreCase = true))
        assertEquals("keep", File(outside, "keep.txt").readText())
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    // ------------------------------------------------------------------ interrupted publish / stale cleanup

    @Test
    fun interruptedPublishLeavesNoFakeSuccessAndRecovers() {
        val root = tempDir()
        val archive = buildZip()
        val (manifestJson, manifest) = buildManifest(archive)
        val install = installer(root, manifestJson, archive)
        assertTrue(install.ensureInstalled() is DshPayloadInstallResult.Installed)

        // Crash-shaped leftovers: no marker, tampered payload, staging/old/archive/reservation junk.
        File(root, "payload.marker.json").delete()
        File(root, "payload/$kernelEntry").writeText("tampered-after-crash")
        val leftovers = listOf(
            File(root, "payload.staging.dead").apply { mkdirs(); File(this, "junk").writeText("j") },
            File(root, "payload.old.dead").apply { mkdirs(); File(this, "old").writeText("o") },
            File(root, "payload.archive.dead.tmp").apply { writeText("a") },
            File(root, "payload.reservation.dead.json").apply { writeText("{}") },
            File(root, "payload.marker.tmp").apply { writeText("m") }
        )

        val recovered = install.ensureInstalled()

        assertTrue(recovered is DshPayloadInstallResult.Installed)
        assertFalse((recovered as DshPayloadInstallResult.Installed).reused)
        leftovers.forEach { assertFalse("stale artifact must be cleaned: ${it.name}", it.exists()) }
        assertEquals(files.getValue(kernelEntry), File(root, "payload/$kernelEntry").readText())
        val marker = DshPayloadMarker.parse(File(root, "payload.marker.json").readText())
        assertTrue(marker.matches(manifest))
    }

    // ------------------------------------------------------------------ real cross-process lock

    @Test
    fun otherProcessHoldingLockBlocksInstallAndCrashReleasesIt() {
        val root = tempDir()
        val archive = buildZip()
        val (manifestJson, _) = buildManifest(archive)
        val lockFile = DshRuntimeConfig(root).payloadInstallLockFile
        val holder = spawn("hold", lockFile.absolutePath, "60000")
        val output = ChildOutput(holder)
        assertTrue("holder never acquired the OS lock: ${output.text()}", output.awaitLine("LOCKED", 30_000))

        // Contention: the installer must fail closed with the lock detail, never publish.
        val blocked = installer(
            root,
            manifestJson,
            archive,
            lockTimeoutMillis = 400L,
            lockPollMillis = 25L
        ).ensureInstalled()
        assertTrue(blocked is DshPayloadInstallResult.Integrity)
        assertTrue((blocked as DshPayloadInstallResult.Integrity).detail.contains("lock"))
        assertFalse(File(root, "payload").exists())

        // Holder killed without a graceful close: the kernel releases the fcntl lock.
        holder.destroyForcibly()
        assertTrue(holder.waitFor(30, TimeUnit.SECONDS))
        val afterCrash = installer(
            root,
            manifestJson,
            archive,
            lockTimeoutMillis = 10_000L,
            lockPollMillis = 25L
        ).ensureInstalled()
        assertTrue(afterCrash is DshPayloadInstallResult.Installed)
        assertEquals(files.getValue(kernelEntry), File(root, "payload/$kernelEntry").readText())
    }

    @Test
    fun sameProcessDoubleLockIsReportedAndCloseReleases() {
        val root = tempDir()
        val lockFile = DshRuntimeConfig(root).payloadInstallLockFile
        val first = DshPayloadLock.acquire(lockFile, 1_000L, 10L)
        assertTrue(first is DshPayloadLockOutcome.Acquired)
        val second = DshPayloadLock.acquire(lockFile, 1_000L, 10L)
        assertTrue(second is DshPayloadLockOutcome.Busy)
        assertTrue((second as DshPayloadLockOutcome.Busy).detail.contains("already held"))
        (first as DshPayloadLockOutcome.Acquired).lock.close()
        val third = DshPayloadLock.acquire(lockFile, 1_000L, 10L)
        assertTrue(third is DshPayloadLockOutcome.Acquired)
        (third as DshPayloadLockOutcome.Acquired).lock.close()
    }

    // ------------------------------------------------------------------ two real installer processes

    @Test
    fun twoProcessInstallRaceHasExactlyOnePublisher() {
        val root = tempDir()
        val assets = tempDir("dsh-assets")
        val archive = buildZip()
        val (manifestJson, manifest) = buildManifest(archive)
        val manifestFile = File(assets, "MANIFEST.json").apply { writeText(manifestJson) }
        val archiveFile = File(assets, "payload.zip").apply { writeBytes(archive) }

        val first = spawn("install", root.absolutePath, manifestFile.absolutePath, archiveFile.absolutePath)
        val second = spawn("install", root.absolutePath, manifestFile.absolutePath, archiveFile.absolutePath)
        val firstOutput = ChildOutput(first)
        val secondOutput = ChildOutput(second)
        assertTrue(first.waitFor(180, TimeUnit.SECONDS))
        assertTrue(second.waitFor(180, TimeUnit.SECONDS))
        assertEquals("first child failed: ${firstOutput.text()}", 0, first.exitValue())
        assertEquals("second child failed: ${secondOutput.text()}", 0, second.exitValue())

        val lines = (firstOutput.text() + "\n" + secondOutput.text())
            .lines()
            .filter { it.startsWith("RESULT:") }
        assertEquals("both installer processes must report a result: $lines", 2, lines.size)
        assertEquals("exactly one process may publish", 1, lines.count { it == "RESULT:reused=false" })
        assertEquals("the loser must reuse, not overwrite", 1, lines.count { it == "RESULT:reused=true" })

        // The published tree is complete and coherent, and the parent reuses it.
        assertEquals(files.getValue(kernelEntry), File(root, "payload/$kernelEntry").readText())
        val marker = DshPayloadMarker.parse(File(root, "payload.marker.json").readText())
        assertTrue(marker.matches(manifest))
        val reused = installer(root, manifestJson, archive).ensureInstalled()
        assertTrue(reused is DshPayloadInstallResult.Installed)
        assertTrue((reused as DshPayloadInstallResult.Installed).reused)
    }

    // ------------------------------------------------------------------ process plumbing

    private fun javaBinary(): String {
        val suffix = if (File.separatorChar == '\\') ".exe" else ""
        return File(System.getProperty("java.home") ?: "", "bin/java$suffix").absolutePath
    }

    private fun spawn(vararg args: String): Process {
        val classpath = System.getProperty("java.class.path")
        assertNotNull(classpath)
        val command = listOf(
            javaBinary(),
            "-cp",
            classpath,
            DshPayloadTestProcess::class.java.name
        ) + args
        return ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
    }

    private class ChildOutput(private val process: Process) {
        private val monitor = Object()
        private val lines = ArrayList<String>()
        private val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { seq ->
                    seq.forEach { line ->
                        synchronized(monitor) {
                            lines.add(line)
                            monitor.notifyAll()
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        fun awaitLine(prefix: String, timeoutMillis: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMillis
            synchronized(monitor) {
                while (true) {
                    if (lines.any { it.startsWith(prefix) }) return true
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0L) return lines.any { it.startsWith(prefix) }
                    monitor.wait(minOf(remaining, 200L))
                    if (!process.isAlive && lines.none { it.startsWith(prefix) }) return false
                }
            }
        }

        fun text(): String = synchronized(monitor) { lines.joinToString("\n") }
    }
}

/** Separate JVM entry point for the two-process lock/race tests. */
object DshPayloadTestProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.getOrNull(0)) {
            "hold" -> {
                val file = File(args[1])
                when (val outcome = DshPayloadLock.acquire(file, 60_000L, 25L)) {
                    is DshPayloadLockOutcome.Acquired -> {
                        println("LOCKED")
                        runCatching { Thread.sleep(args.getOrNull(2)?.toLongOrNull() ?: 10_000L) }
                        // No explicit close on purpose: JVM exit must release the OS lock.
                    }

                    is DshPayloadLockOutcome.Busy -> println("BUSY:${outcome.detail}")
                    is DshPayloadLockOutcome.Unavailable -> println("UNAVAILABLE:${outcome.detail}")
                }
            }

            "install" -> {
                val root = File(args[1])
                val manifestFile = File(args[2])
                val archiveFile = File(args[3])
                val source = object : DshPayloadSource {
                    override val description = "process"
                    override fun openManifest() = FileInputStream(manifestFile)
                    override fun openArchive() = FileInputStream(archiveFile)
                }
                val result = DshPayloadInstaller(
                    config = DshRuntimeConfig(dataRoot = root).validated(),
                    source = source
                ).ensureInstalled()
                when (result) {
                    is DshPayloadInstallResult.Installed -> println("RESULT:reused=${result.reused}")
                    is DshPayloadInstallResult.Integrity -> println("RESULT:integrity:${result.detail}")
                    is DshPayloadInstallResult.Missing -> println("RESULT:missing:${result.detail}")
                }
            }
        }
    }
}
