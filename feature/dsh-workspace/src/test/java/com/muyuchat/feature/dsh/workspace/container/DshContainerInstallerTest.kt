package com.muyuchat.feature.dsh.workspace.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * The packaged rootfs is third-party data, so the extractor's guarantees are pinned here rather
 * than on a device: entry types an Ubuntu base image actually uses, mode bits that make `bin/sh`
 * usable inside the container, and the refusals that keep a hostile archive inside the staging
 * directory.
 */
class DshContainerTarGzTest {

    @Test
    fun `extracts regular files directories and symlinks with mode bits`() {
        val archive = TarFixture()
            .directory("bin/")
            .file("bin/dash", "#!/bin/sh\n", mode = "755")
            .symlink("bin/sh", "dash")
            .file("etc/hostname", "mca\n", mode = "644")
            .toTarGz()
        val destination = Files.createTempDirectory("mca-rootfs").toFile()

        val stats = DshContainerTarGz.extract(ByteArrayInputStream(archive), destination).getOrThrow()

        assertTrue(File(destination, "bin/dash").isFile)
        assertEquals("#!/bin/sh\n", File(destination, "bin/dash").readText())
        val shell = File(destination, "bin/sh")
        if (Files.isSymbolicLink(shell.toPath())) {
            assertEquals("dash", Files.readSymbolicLink(shell.toPath()).toString())
        } else {
            // A host without symlink support (Windows without the privilege) emulates a
            // same-directory link by copying the target; the path still resolves to dash.
            assertEquals("#!/bin/sh\n", shell.readText())
        }
        assertTrue("the guest shell must stay executable inside the container", File(destination, "bin/dash").canExecute())
        assertEquals("mca\n", File(destination, "etc/hostname").readText())
        assertTrue(stats.hasShell)
        assertTrue(stats.entries >= 4)
    }

    @Test
    fun `refuses an absolute entry path`() {
        val archive = TarFixture().file("/etc/evil", "x").toTarGz()
        val destination = Files.createTempDirectory("mca-rootfs-abs").toFile()

        val result = DshContainerTarGz.extract(ByteArrayInputStream(archive), destination)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("绝对路径"))
        assertFalse(File(destination, "etc/evil").exists())
    }

    @Test
    fun `refuses a traversal entry path`() {
        val archive = TarFixture().file("../escape", "x").toTarGz()
        val destination = Files.createTempDirectory("mca-rootfs-dotdot").toFile()

        val result = DshContainerTarGz.extract(ByteArrayInputStream(archive), destination)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains(".."))
        assertFalse(File(destination.parentFile, "escape").exists())
    }

    @Test
    fun `fails when the archive carries no bin sh`() {
        val archive = TarFixture().file("etc/hostname", "mca\n").toTarGz()
        val destination = Files.createTempDirectory("mca-rootfs-noshell").toFile()

        val result = DshContainerTarGz.extract(ByteArrayInputStream(archive), destination)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("bin/sh"))
    }

    @Test
    fun `rejects entries that escape through a symlinked parent`() {
        val archive = TarFixture()
            .symlink("link", "/")
            .file("link/escape", "x")
            .toTarGz()
        val destination = Files.createTempDirectory("mca-rootfs-symlink").toFile()

        val result = DshContainerTarGz.extract(ByteArrayInputStream(archive), destination)

        assertTrue(result.isFailure)
        assertFalse(File("/escape").exists())
    }
}

/** Minimal ustar writer: only the entry types an Ubuntu base image uses. */
private class TarFixture {
    private val bytes = ByteArrayOutputStream()

    fun directory(name: String, mode: String = "755") = entry(name, mode, '5', null, ByteArray(0))

    fun file(name: String, content: String, mode: String = "644") =
        entry(name, mode, '0', null, content.toByteArray())

    fun symlink(name: String, target: String) = entry(name, "777", '2', target, ByteArray(0))

    private fun entry(name: String, mode: String, type: Char, link: String?, data: ByteArray): TarFixture {
        val header = ByteArray(512)
        put(header, 0, 100, name)
        put(header, 100, 8, mode.padStart(7, '0') + "\u0000")
        put(header, 108, 8, "0000000\u0000")
        put(header, 116, 8, "0000000\u0000")
        put(header, 124, 12, data.size.toString(8).padStart(11, '0') + "\u0000")
        put(header, 136, 12, "00000000000\u0000")
        put(header, 148, 8, "        ")
        header[156] = type.code.toByte()
        put(header, 157, 100, link ?: "")
        put(header, 257, 6, "ustar\u0000")
        put(header, 263, 2, "00")
        var checksum = 0
        for (b in header) checksum += (b.toInt() and 0xff)
        put(header, 148, 8, checksum.toString(8).padStart(6, '0') + "\u0000 ")
        bytes.write(header)
        bytes.write(data)
        val pad = (512 - data.size % 512) % 512
        bytes.write(ByteArray(pad))
        return this
    }

    private fun put(target: ByteArray, offset: Int, length: Int, value: String) {
        val raw = value.toByteArray()
        val count = minOf(raw.size, length)
        System.arraycopy(raw, 0, target, offset, count)
    }

    fun toTarGz(): ByteArray {
        val tar = bytes.toByteArray() + ByteArray(1024)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(tar) }
        return out.toByteArray()
    }
}

/** Fixed probe runner the installer tests use: success unless a test overrides it. */
private fun okProbe(argv: List<String>, timeoutMillis: Long): DshContainerRun {
    check(argv.first().endsWith("libproot.so")) { "the installer must run the packaged proot" }
    return DshContainerRun(exitCode = 0, output = "ok\n", timedOut = false, failure = null)
}

class DshContainerInstallerTest {

    private fun layout(root: File): DshContainerLayout {
        val container = File(root, "container")
        val prootDir = File(root, "lib").apply { mkdirs() }
        File(prootDir, "libproot.so").writeText("proot")
        File(prootDir, DshContainerLayout.PACKAGED_TALLOC_LIBRARY_NAME).writeText("talloc")
        return DshContainerLayout(
            containerDir = container.absolutePath,
            rootfsDir = File(container, "rootfs").absolutePath,
            executableProotCandidates = listOf(File(prootDir, "libproot.so").absolutePath),
            nonExecutableProotCandidates = emptyList(),
            appFilesMount = root.absolutePath,
            libraryDir = File(container, DshContainerLayout.LIBRARY_DIR_NAME).absolutePath
        )
    }

    private fun manifestFor(archive: ByteArray, shaOverride: String? = null): String {
        val sha = shaOverride ?: MessageDigest.getInstance("SHA-256").digest(archive)
            .joinToString("") { "%02x".format(it) }
        return """
            {
              "schema": "mca.dsh.container.v1",
              "rootfs": {
                "archive": "rootfs.tar.gz",
                "bytes": ${archive.size},
                "sha256": "$sha",
                "distribution": "Ubuntu 24.04.5 LTS (noble) base, arm64",
                "sourceUrl": "https://cdimage.ubuntu.com/",
                "license": "Ubuntu base image"
              },
              "proot": {
                "library": "libproot.so",
                "bytes": 5,
                "sha256": "${"0".repeat(64)}",
                "version": "5.1.107.102",
                "license": "GPL-2.0-or-later"
              }
            }
        """.trimIndent()
    }

    private fun assets(archive: ByteArray, manifest: String): DshContainerAssetSource =
        object : DshContainerAssetSource {
            override val description: String = "test"
            override fun openManifest(): InputStream = ByteArrayInputStream(manifest.toByteArray())
            override fun openRootfsArchive(): InputStream = ByteArrayInputStream(archive)
        }

    private fun archive(): ByteArray = TarFixture()
        .directory("bin/")
        .file("bin/dash", "#!/bin/sh\n", mode = "755")
        .symlink("bin/sh", "dash")
        .toTarGz()

    @Test
    fun `runs the first five steps and commits the rootfs`() {
        val root = Files.createTempDirectory("mca-container-install").toFile()
        val layout = layout(root)
        val archive = archive()
        val events = mutableListOf<DshContainerInstaller.Event>()

        val result = DshContainerInstaller.install(
            assets = assets(archive, manifestFor(archive)),
            layout = layout,
            probe = ::okProbe
        ) { events.add(it) }

        val started = events.filterIsInstance<DshContainerInstaller.Event.Started>()
            .map { it.step }
            .distinct()
        assertEquals(DshContainerInstaller.Step.entries.toList(), started)
        val succeeded = events.filterIsInstance<DshContainerInstaller.Event.Succeeded>().map { it.step }
        assertTrue(succeeded.containsAll(DshContainerInstaller.Step.entries.take(5)))
        assertTrue(File(layout.rootfsDir, "bin/sh").exists())
        assertTrue(File(layout.containerDir, DshContainerInstaller.INSTALL_STATE_NAME).isFile)
        assertEquals(
            "proot asks the loader for the SONAME, so the packaged copy must exist under that name",
            "talloc",
            File(layout.libraryDir, DshContainerLayout.TALLOC_SONAME).readText()
        )
        assertFalse(File(layout.containerDir, DshContainerInstaller.STAGING_DIR_NAME).exists())
        // Step 6 is the only unsatisfiable step off-device: the argv gate is deliberately
        // POSIX-only (Android paths start with "/"), so a Windows temp path is rejected before
        // anything is executed. Both outcomes are correct statements about that gate.
        if (File.separatorChar == '/') {
            assertTrue("result=$result", result is DshContainerInstaller.Result.Installed)
        } else {
            assertTrue("result=$result", result is DshContainerInstaller.Result.Failed)
            assertEquals(
                DshContainerInstaller.Step.PROBE,
                (result as DshContainerInstaller.Result.Failed).step
            )
        }
    }

    @Test
    fun `reports missing assets instead of installing`() {
        val root = Files.createTempDirectory("mca-container-missing").toFile()
        val layout = layout(root)
        val missing = object : DshContainerAssetSource {
            override val description: String = "empty"
            override fun openManifest(): InputStream? = null
            override fun openRootfsArchive(): InputStream? = null
        }

        val result = DshContainerInstaller.install(missing, layout, ::okProbe)

        assertTrue(result is DshContainerInstaller.Result.AssetsMissing)
        assertFalse(File(layout.rootfsDir).exists())
    }

    @Test
    fun `hash mismatch fails before anything is unpacked`() {
        val root = Files.createTempDirectory("mca-container-integrity").toFile()
        val layout = layout(root)
        val archive = archive()

        val result = DshContainerInstaller.install(
            assets(archive, manifestFor(archive, shaOverride = "f".repeat(64))),
            layout,
            ::okProbe
        )

        assertTrue(result is DshContainerInstaller.Result.Integrity)
        assertFalse("a rejected archive must not be extracted", File(layout.rootfsDir).exists())
    }

    @Test
    fun `a second run reuses the installed tree`() {
        val root = Files.createTempDirectory("mca-container-reuse").toFile()
        val layout = layout(root)
        val archive = archive()
        val assets = assets(archive, manifestFor(archive))

        DshContainerInstaller.install(assets, layout, ::okProbe)
        val events = mutableListOf<DshContainerInstaller.Event>()
        DshContainerInstaller.install(assets, layout, ::okProbe) { events.add(it) }

        // Reuse is visible in the events, not in the final probe verdict (which off-device is the
        // same argv rejection a first run gets).
        val extractDetail = events
            .filterIsInstance<DshContainerInstaller.Event.Succeeded>()
            .first { it.step == DshContainerInstaller.Step.EXTRACT_ROOTFS }
            .detail
        assertEquals("复用已有 rootfs", extractDetail)
        assertFalse(
            "a reuse run must not re-unpack into a staging directory",
            File(layout.containerDir, DshContainerInstaller.STAGING_DIR_NAME).exists()
        )
        assertTrue(File(layout.rootfsDir, "bin/sh").exists())
    }

    @Test
    fun `a failed probe keeps the tree and names the probe step`() {
        val root = Files.createTempDirectory("mca-container-probe").toFile()
        val layout = layout(root)
        val archive = archive()

        val result = DshContainerInstaller.install(
            assets = assets(archive, manifestFor(archive)),
            layout = layout,
            probe = { _, _ ->
                DshContainerRun(exitCode = 1, output = "denied", timedOut = false, failure = "exec denied")
            }
        )

        assertTrue(result is DshContainerInstaller.Result.Failed)
        assertEquals(
            DshContainerInstaller.Step.PROBE,
            (result as DshContainerInstaller.Result.Failed).step
        )
        assertTrue("the installed tree stays so a retry need not unpack again", File(layout.rootfsDir, "bin/sh").exists())
    }

    @Test
    fun `manifest parser rejects an unknown schema and a malformed hash`() {
        val archive = archive()
        assertTrue(DshContainerManifest.parse("""{"schema":"other"}""").isFailure)
        assertTrue(
            DshContainerManifest.parse(manifestFor(archive, shaOverride = "zz")).isFailure
        )
        val parsed = DshContainerManifest.parse(manifestFor(archive)).getOrThrow()
        assertEquals("Ubuntu 24.04.5 LTS (noble) base, arm64", parsed.distribution)
        assertTrue(parsed.matchesArchive(archive.size.toLong(), parsed.rootfsSha256))
        assertFalse(parsed.matchesArchive(archive.size.toLong() + 1, parsed.rootfsSha256))
        assertNull(parsed.sourceUrl.takeIf { it.isBlank() })
    }
}
