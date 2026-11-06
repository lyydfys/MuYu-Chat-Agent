package com.muyuchat.feature.dsh.workspace.container

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the proot argv translation of the DSH container lane.
 *
 * The argv is the whole contract: one wrong element (a joined shell string, a dropped `--`, a
 * mount rendered with the wrong separator) makes proot run something else. Everything here is a
 * pure function, so this class needs no device, no proot binary and no rootfs.
 */
class DshContainerCommandTest {

    private val proot = "/data/app/~~abc==/com.muyuchat.mca-xyz/lib/arm64/libproot.so"
    private val rootfs = "/data/user/0/com.muyuchat.mca/files/dsh/container/rootfs"
    private val appFiles = "/data/user/0/com.muyuchat.mca/files"

    @Test
    fun `probe argv has the documented proot shape`() {
        val spec = DshContainerSpec(
            rootfsPath = rootfs,
            prootPath = proot,
            mounts = listOf(DshContainerMount(appFiles))
        )

        val argv = readyArgv(spec, probe = true)

        assertEquals(
            listOf(
                proot,
                "-r", rootfs,
                "-0",
                "-w", "/root",
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", appFiles,
                // No `--`: Termux's proot rejects it ("unknown option '--'"). The guest command
                // is a fixed absolute path, so the separator never protected anything.
                "/bin/sh",
                "-c",
                DshContainerCommand.PROBE_ENTRY_COMMAND
            ),
            argv
        )
        assertEquals("probe is the constant entry command", 17, argv.size)
    }

    @Test
    fun `paths with spaces quotes and cjk stay single argv tokens`() {
        val spacedRootfs = "/data/user/0/com.muyuchat.mca/files/我的 rootfs/ubuntu'24"
        val spacedProot = "/data/app/lib dir/arm64/libproot.so"
        val spacedMount = "/data/user/0/com.muyuchat.mca/files/a b/中文\"q\""

        val argv = readyArgv(
            DshContainerSpec(
                rootfsPath = spacedRootfs,
                prootPath = spacedProot,
                mounts = listOf(DshContainerMount(spacedMount, target = "/root/mca data"))
            )
        )

        assertTrue("rootfs must be one token", spacedRootfs in argv)
        assertTrue("proot must be one token", spacedProot in argv)
        assertTrue("mount must be one bind token", "$spacedMount:/root/mca data" in argv)
        // No element may be a concatenation of the path plus a flag or another path.
        assertFalse(argv.any { element -> element.contains(" -b ") })
        assertFalse(argv.any { element -> element.contains(" -- ") })
    }

    @Test
    fun `entry command stays one token after dash dash c`() {
        val command = "echo \"ok\" 'x y' 中文"

        val argv = readyArgv(
            DshContainerSpec(rootfsPath = rootfs, prootPath = proot, entryCommand = command)
        )

        assertEquals("-c", argv[argv.size - 2])
        assertEquals(command, argv.last())
        assertEquals("/bin/sh", argv[argv.size - 3])
    }

    @Test
    fun `base mounts come first and explicit binds override by source`() {
        val merged = DshContainerCommand.mountsFor(
            listOf(
                DshContainerMount("/dev", "/dev-override"),
                DshContainerMount("/data/user/0/com.muyuchat.mca/files")
            )
        )

        assertEquals(
            listOf("/dev:/dev-override", "/proc", "/sys", "/data/user/0/com.muyuchat.mca/files"),
            merged.map(DshContainerMount::bindSpec)
        )
    }

    @Test
    fun `rootfs traversal is rejected`() {
        val result = DshContainerCommand.argv(
            DshContainerSpec(rootfsPath = "/data/user/0/app/files/../etc", prootPath = proot)
        )

        assertRejected(DshContainerRejectionReason.ROOTFS_TRAVERSAL, result)
    }

    @Test
    fun `relative rootfs path is rejected`() {
        val result = DshContainerCommand.argv(
            DshContainerSpec(rootfsPath = "dsh/container/rootfs", prootPath = proot)
        )

        assertRejected(DshContainerRejectionReason.ROOTFS_NOT_ABSOLUTE, result)
    }

    @Test
    fun `saf content uri cannot be a proot root`() {
        val result = DshContainerCommand.argv(
            DshContainerSpec(
                rootfsPath = "content://com.android.externalstorage.documents/tree/rootfs",
                prootPath = proot
            )
        )

        assertRejected(DshContainerRejectionReason.ROOTFS_NOT_A_REAL_PATH, result)
        assertTrue(
            "the rejection must name the SAF limitation",
            (result as DshContainerCommandResult.Rejected).detail.contains("content://")
        )
    }

    @Test
    fun `blank and control character entry commands are rejected`() {
        assertRejected(
            DshContainerRejectionReason.ENTRY_COMMAND_BLANK,
            DshContainerCommand.argv(
                DshContainerSpec(rootfsPath = rootfs, prootPath = proot, entryCommand = "   ")
            )
        )
        assertRejected(
            DshContainerRejectionReason.ENTRY_COMMAND_NOT_CONTROL_FREE,
            DshContainerCommand.argv(
                DshContainerSpec(rootfsPath = rootfs, prootPath = proot, entryCommand = "echo\u0000ok")
            )
        )
    }

    @Test
    fun `non absolute mount target is rejected`() {
        val result = DshContainerCommand.argv(
            DshContainerSpec(
                rootfsPath = rootfs,
                prootPath = proot,
                mounts = listOf(DshContainerMount("/data/files", target = "root/mca"))
            )
        )

        assertRejected(DshContainerRejectionReason.MOUNT_TARGET_NOT_ABSOLUTE, result)
    }

    @Test
    fun `only the constant probe passes the container command gate`() {
        val spec = DshContainerSpec(rootfsPath = rootfs, prootPath = proot)
        val probe = readyArgv(spec, probe = true)

        assertTrue(DshContainerCommandPolicy.allowsProbe(probe, spec))

        val userCommand = readyArgv(spec.copy(entryCommand = "rm -rf /root"))
        assertFalse(
            "an argv carrying a different entry command must never pass the gate",
            DshContainerCommandPolicy.allowsProbe(userCommand, spec)
        )
        assertFalse(
            "a truncated argv must not pass the gate",
            DshContainerCommandPolicy.allowsProbe(probe.dropLast(1), spec)
        )
    }

    private fun readyArgv(spec: DshContainerSpec, probe: Boolean = false): List<String> {
        val result = if (probe) DshContainerCommand.probeArgv(spec) else DshContainerCommand.argv(spec)
        assertTrue("expected a Ready argv, was $result", result is DshContainerCommandResult.Ready)
        return (result as DshContainerCommandResult.Ready).argv
    }

    private fun assertRejected(
        expected: DshContainerRejectionReason,
        result: DshContainerCommandResult
    ) {
        assertTrue("expected Rejected, was $result", result is DshContainerCommandResult.Rejected)
        val rejected = result as DshContainerCommandResult.Rejected
        assertEquals(expected, rejected.reason)
        assertTrue("the rejection needs a human detail", rejected.detail.isNotBlank())
    }
}

/**
 * Pins the four probe states, their human reasons, and the fact that SAF/Termux are hints only.
 */
class DshContainerProbeTest {

    private val proot = "/data/app/~~abc==/com.muyuchat.mca-xyz/lib/arm64/libproot.so"
    private val rootfs = "/data/user/0/com.muyuchat.mca/files/dsh/container/rootfs"

    @Test
    fun `missing proot explains where a usable binary must live`() {
        val probe = dshContainerProbeFor(DshContainerFacts())

        assertEquals(DshContainerProbeState.MISSING_PROOT, probe.state)
        assertTrue(probe.reason.contains("proot"))
        assertTrue(probe.detail.contains("libproot.so"))
        assertTrue(probe.detail.contains("nativeLibraryDir"))
        assertTrue(
            "the detail must name the staging script, because the runtime ships with the release",
            probe.detail.contains("stage-container.ps1")
        )
    }

    @Test
    fun `proot in app data maps to not permitted`() {
        val probe = dshContainerProbeFor(
            DshContainerFacts(
                prootPath = "/data/user/0/com.muyuchat.mca/files/dsh/container/proot",
                prootExecutable = false,
                rootfsPath = rootfs,
                rootfsHasShell = true
            )
        )

        assertEquals(DshContainerProbeState.NOT_PERMITTED, probe.state)
        assertTrue(probe.detail.contains("nativeLibraryDir"))
        assertTrue("the reason must name the SELinux rule", probe.detail.contains("exec_no_trans"))
    }

    @Test
    fun `missing rootfs explains the directory layout`() {
        val probe = dshContainerProbeFor(
            DshContainerFacts(prootPath = proot, prootExecutable = true)
        )

        assertEquals(DshContainerProbeState.MISSING_ROOTFS, probe.state)
        assertTrue(probe.detail.contains("rootfs/bin/sh"))
    }

    @Test
    fun `rootfs without bin sh is still a missing rootfs`() {
        val probe = dshContainerProbeFor(
            DshContainerFacts(
                prootPath = proot,
                prootExecutable = true,
                rootfsPath = rootfs,
                rootfsHasShell = false
            )
        )

        assertEquals(DshContainerProbeState.MISSING_ROOTFS, probe.state)
        assertTrue(probe.reason.contains("bin/sh"))
        assertTrue(probe.detail.contains(rootfs))
    }

    @Test
    fun `supported needs both an executable proot and a rootfs shell`() {
        val probe = dshContainerProbeFor(
            DshContainerFacts(
                prootPath = proot,
                prootExecutable = true,
                rootfsPath = rootfs,
                rootfsHasShell = true
            )
        )

        assertEquals(DshContainerProbeState.SUPPORTED, probe.state)
        assertTrue(probe.detail.contains(DshContainerCommand.PROBE_ENTRY_COMMAND))
    }

    @Test
    fun `saf and termux only change the detail never the state`() {
        val base = DshContainerFacts(
            prootPath = proot,
            prootExecutable = true
        )
        val withHints = base.copy(
            safTreeUris = listOf("content://com.android.externalstorage.documents/tree/primary%3ADCIM"),
            termuxInstalled = true
        )

        assertEquals(
            "device/app facts must never gate the state",
            dshContainerProbeFor(base).state,
            dshContainerProbeFor(withHints).state
        )
        assertTrue(dshContainerProbeFor(withHints).detail.contains("SAF"))
        assertTrue(dshContainerProbeFor(withHints).detail.contains("Termux"))
        assertFalse(dshContainerProbeFor(base).detail.contains("Termux"))
    }

    @Test
    fun `facts prefer the executable candidate and find the rootfs shell`() {
        val root = Files.createTempDirectory("mca-dsh-container").toFile()
        try {
            val nativeDir = File(root, "native").apply { mkdirs() }
            val containerDir = File(root, "container").apply { mkdirs() }
            File(nativeDir, DshContainerLayout.PACKAGED_PROOT_LIBRARY_NAME).createNewFile()
            File(containerDir, DshContainerLayout.PROOT_FILE_NAME).createNewFile()
            File(containerDir, "rootfs/bin").mkdirs()
            File(containerDir, "rootfs/bin/sh").createNewFile()

            val layout = DshContainerLayout(
                containerDir = containerDir.absolutePath,
                rootfsDir = File(containerDir, "rootfs").absolutePath,
                executableProotCandidates = listOf(File(nativeDir, "libproot.so").absolutePath),
                nonExecutableProotCandidates = listOf(File(containerDir, "proot").absolutePath),
                appFilesMount = root.absolutePath
            )

            val facts = dshContainerFactsFor(layout)

            assertEquals(File(nativeDir, "libproot.so").absolutePath, facts.prootPath)
            assertTrue("the packaged candidate is the executable one", facts.prootExecutable)
            assertNotNull(facts.rootfsPath)
            assertTrue("bin/sh is the rootfs proof", facts.rootfsHasShell)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `facts report an app data proot as non executable`() {
        val root = Files.createTempDirectory("mca-dsh-container-nonx").toFile()
        try {
            val nativeDir = File(root, "native").apply { mkdirs() }
            val containerDir = File(root, "container").apply { mkdirs() }
            File(containerDir, DshContainerLayout.PROOT_FILE_NAME).createNewFile()

            val layout = DshContainerLayout(
                containerDir = containerDir.absolutePath,
                rootfsDir = File(containerDir, "rootfs").absolutePath,
                executableProotCandidates = listOf(File(nativeDir, "libproot.so").absolutePath),
                nonExecutableProotCandidates = listOf(File(containerDir, "proot").absolutePath),
                appFilesMount = root.absolutePath
            )

            val facts = dshContainerFactsFor(layout)

            assertEquals(File(containerDir, "proot").absolutePath, facts.prootPath)
            assertFalse("app-data ELF is data only", facts.prootExecutable)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `layout keeps the container convention`() {
        val layout = DshContainerLayout.forApp(
            filesDir = "/data/user/0/com.muyuchat.mca/files",
            nativeLibraryDir = "/data/app/lib/arm64"
        )

        assertEquals(
            "/data/user/0/com.muyuchat.mca/files/dsh/container/rootfs",
            layout.rootfsDir
        )
        assertEquals(
            "/data/app/lib/arm64/libproot.so",
            layout.executableProotCandidates.single()
        )
        assertEquals(
            "/data/user/0/com.muyuchat.mca/files/dsh/container/proot",
            layout.nonExecutableProotCandidates.single()
        )
    }
}

/**
 * Source contract of the additive block: the console route renders it, and it offers no text
 * input (the only container command is the constant probe).
 */
class DshContainerSectionWiringTest {

    @Test
    fun `console route renders the container block without adding a destination`() {
        val route = sourceFile("ui/DshConsoleRoute.kt")
        assertEquals(1, route.countOccurrences("DshContainerSection()"))
        // The only mention is the call itself: no new route, tab or screen wiring.
        assertEquals(1, route.countOccurrences("DshContainer"))
    }

    @Test
    fun `container block reuses console rows and buttons and takes no user input`() {
        val section = sourceFile("ui/DshContainerSection.kt")
        assertTrue(section.contains("DshConsoleRowView("))
        assertTrue(section.contains("McaButton("))
        assertFalse("no text field may feed the container command", section.contains("TextField"))
        assertFalse(section.contains("ProcessBuilder"))
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(
                directory,
                "feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/$relativePath"
            )
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate $relativePath")
    }

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val index = indexOf(needle, from)
            if (index < 0) return count
            count += 1
            from = index + needle.length
        }
    }
}
