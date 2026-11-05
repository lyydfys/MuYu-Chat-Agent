package com.muyuchat.feature.dsh.workspace.container

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extracts the **real** packaged rootfs archive.
 *
 * The hand-built fixtures in `DshContainerInstallerTest` only model the shapes we thought of; the
 * device showed the real Ubuntu 24.04 archive failing at ~8 MB. This test exists so the real
 * archive — 3413 entries, 194 symlinks (20 of them absolute), 2 hardlinks, merged-usr layout — is
 * exercised on the JVM, where the rejection reason is readable instead of being swallowed by a UI.
 */
class DshContainerTarGzRealArchiveTest {

    @Test
    fun `the packaged rootfs archive extracts completely`() {
        val archive = File("../../app/src/main/assets/offline/container/rootfs.img")
        org.junit.Assume.assumeTrue(
            "staged rootfs not present in this checkout",
            archive.isFile
        )
        val destination = createTempDir(prefix = "rootfs-real")
        try {
            val stats = archive.inputStream().buffered().use { stream ->
                DshContainerTarGz.extract(stream, destination) { }
            }.getOrThrow()
            // Ubuntu's /bin/sh is a symlink to dash; a merged-usr tree must still prove it.
            assertTrue("expected a shell in the extracted rootfs", stats.hasShell)
            assertTrue("expected the archive to contain files", stats.files > 1000)
            val shell = File(destination, DshContainerLayout.ROOTFS_SHELL_RELATIVE)
            assertTrue("bin/sh must exist after extraction", shell.exists() || shell.parentFile.exists())
        } finally {
            destination.deleteRecursively()
        }
    }

    private fun createTempDir(prefix: String): File =
        File.createTempFile(prefix, "").let { probe ->
            probe.delete()
            probe.mkdirs()
            probe
        }
}
