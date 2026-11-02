package com.muyuchat.core.dsh.runtime.process

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D11 acceptance: the real command closure matrix on targetSdk 36.
 *
 * The matrix is what the payload actually contains (pinned lock + R01 exec probe), not an
 * assumption: native-library ELFs are startable, writable-home shell wrappers are not, and `bash`
 * is only ever a sh compatibility layer. No Android APIs are involved, so the matrix runs on the JVM.
 */
class DshCommandClosureTest {

    // The resolver joins Android-style paths with '/', so these expected argv values are exactly
    // what a device receives (no Windows separators leak into the evidence).
    private val payloadRoot = "/data/user/0/com.muyuchat.mca/files/dsh/payload"
    private val nativeRoot = "/data/app/~~x/com.muyuchat.mca-y/lib/arm64"

    private val allNatives = setOf(
        DshCommandClosure.Native.NODE,
        DshCommandClosure.Native.RIPGREP,
        DshCommandClosure.Native.CURL,
        DshCommandClosure.Native.GIT,
        DshCommandClosure.Native.GIT_REMOTE_HTTP,
        DshCommandClosure.Native.GIT_REMOTE_HTTPS,
        DshCommandClosure.Native.PYTHON,
        DshCommandClosure.DISPATCHER_LIBRARY,
        DshCommandClosure.PTY_LIBRARY
    )

    private val allPayloadFiles = setOf(
        DshCommandClosure.Payload.NPM_CLI,
        DshCommandClosure.Payload.NPX_CLI,
        DshCommandClosure.Payload.PNPM_CLI,
        DshCommandClosure.Payload.PYTHON_LAUNCHER,
        DshCommandClosure.Payload.GIT,
        DshCommandClosure.Payload.RG,
        DshCommandClosure.Payload.CURL,
        DshCommandClosure.Payload.BASH
    )

    private fun model(
        nativeLibraries: Set<String> = allNatives,
        payloadFiles: Set<String> = allPayloadFiles,
        payloadDirectories: Set<String> = setOf(DshCommandClosure.Payload.PYTHON_STDLIB),
        platformShells: Set<String> = setOf(DshCommandClosure.PLATFORM_SHELL),
        nodeNeedsLinker: Boolean = false
    ) = DshCommandClosureModel(
        payloadDir = payloadRoot,
        nativeLibraryDir = nativeRoot,
        payloadFiles = payloadFiles,
        payloadDirectories = payloadDirectories,
        nativeLibraries = nativeLibraries,
        platformShells = platformShells,
        nodeNeedsLinker = nodeNeedsLinker
    )

    // ------------------------------------------------------------------ full closure

    @Test
    fun fullPayloadResolvesEveryRoleWithoutBlockedEntries() {
        val resolved = DshCommandClosure.resolve(model())
        DshCommandRole.entries.forEach { role ->
            val availability = resolved.getValue(role)
            assertFalse("${role.name} must be startable: ${availability.detail}", availability.launch is DshCommandLaunch.Blocked)
            assertTrue("${role.name} must be executable", availability.executable)
        }
    }

    @Test
    fun npmResolvesToNodePlusTheRealPayloadEntryPoint() {
        val npm = DshCommandClosure.resolve(model()).getValue(DshCommandRole.NPM)
        val launch = npm.launch as DshCommandLaunch.NodeScript
        assertEquals("$nativeRoot/${DshCommandClosure.Native.NODE}", launch.node)
        assertEquals("$payloadRoot/${DshCommandClosure.Payload.NPM_CLI}", launch.script)
        assertEquals(
            listOf(
                "$nativeRoot/${DshCommandClosure.Native.NODE}",
                "$payloadRoot/${DshCommandClosure.Payload.NPM_CLI}",
                "install",
                "left-pad"
            ),
            DshCommandClosure.argv(launch, listOf("install", "left-pad"))
        )
    }

    @Test
    fun pnpmKeepsTheCopyImportFlagBeforeUserArguments() {
        val pnpm = DshCommandClosure.resolve(model()).getValue(DshCommandRole.PNPM)
        val argv = DshCommandClosure.argv(pnpm.launch, listOf("install", "--frozen-lockfile"))
        assertEquals(
            listOf(
                "$nativeRoot/${DshCommandClosure.Native.NODE}",
                "$payloadRoot/${DshCommandClosure.Payload.PNPM_CLI}",
                DshCommandClosure.PNPM_IMPORT_ARG,
                "install",
                "--frozen-lockfile"
            ),
            argv
        )
    }

    @Test
    fun pipRunsThroughTheBundledPythonModule() {
        val pip = DshCommandClosure.resolve(model()).getValue(DshCommandRole.PIP)
        assertEquals(
            listOf("$nativeRoot/${DshCommandClosure.Native.PYTHON}", "-m", "pip", "install", "wheel==0.44"),
            DshCommandClosure.argv(pip.launch, listOf("install", "wheel==0.44"))
        )
    }

    @Test
    fun bashIsNeverGnuBash() {
        val bash = DshCommandClosure.resolve(model()).getValue(DshCommandRole.BASH)
        assertTrue(bash.launch is DshCommandLaunch.DispatcherLink)
        assertTrue("the detail must state the sh-compat limitation", bash.detail.contains("gnuBash=false"))
        val argv = DshCommandClosure.argv(bash.launch, listOf("--noprofile", "--norc", "-i"))
        assertEquals(listOf("$payloadRoot/bin/bash", "--noprofile", "--norc", "-i"), argv)
    }

    @Test
    fun aDispatcherWithoutThePayloadBashNameFallsBackToThePlatformShell() {
        // Device evidence: the APK packages libdshcmd.so but a closure payload may carry no
        // bin/bash launcher; exec()ing the absent link exits 127, so the launch must not exist.
        val bash = DshCommandClosure.resolve(
            model(payloadFiles = allPayloadFiles - DshCommandClosure.Payload.BASH)
        ).getValue(DshCommandRole.BASH)
        assertTrue(bash.launch is DshCommandLaunch.PlatformShell)
        assertFalse("no silently-broken link may be reported as startable", bash.report().contains("DISPATCHER"))
        val argv = DshCommandClosure.argv(bash.launch, listOf("--noprofile", "-i"))
        assertEquals(listOf(DshCommandClosure.PLATFORM_SHELL, "-i"), argv)
        assertTrue(bash.detail, bash.detail.contains("gnuBash=false"))
    }

    @Test
    fun bashOnlyLeadingOptionsAreStrippedForTheShCompatibilityLayer() {
        assertEquals(
            listOf("-i"),
            DshCommandClosure.stripBashOnlyOptions(listOf("--noprofile", "--norc", "-i"))
        )
        assertEquals(
            listOf("-c", "echo hi"),
            DshCommandClosure.stripBashOnlyOptions(listOf("--login", "--posix", "-c", "echo hi"))
        )
        // Only *leading* bash options are dropped; later arguments belong to the user command.
        assertEquals(
            listOf("-c", "--noprofile"),
            DshCommandClosure.stripBashOnlyOptions(listOf("-c", "--noprofile"))
        )
    }

    @Test
    fun shellCompatibilityLaunchUsesPlatformShellWhenDispatcherIsAbsent() {
        val resolved = DshCommandClosure.resolve(model(nativeLibraries = allNatives - DshCommandClosure.DISPATCHER_LIBRARY))
        val bash = resolved.getValue(DshCommandRole.BASH)
        val launch = bash.launch as DshCommandLaunch.PlatformShell
        assertEquals(DshCommandClosure.PLATFORM_SHELL, launch.shell)
        assertTrue(launch.stripBashOnlyOptions)
        assertEquals(
            listOf(DshCommandClosure.PLATFORM_SHELL, "-i"),
            DshCommandClosure.argv(launch, listOf("--noprofile", "--norc", "-i"))
        )
        assertEquals(DshPathStatus.EXECUTABLE_LINK, bash.pathStatus)
    }

    // ------------------------------------------------------------------ honest blocking

    @Test
    fun emptyDeviceBlocksEverythingWithConcreteReasons() {
        val resolved = DshCommandClosure.resolve(
            model(
                nativeLibraries = emptySet(),
                payloadFiles = emptySet(),
                payloadDirectories = emptySet(),
                platformShells = emptySet()
            )
        )
        val npm = resolved.getValue(DshCommandRole.NPM)
        assertEquals(
            DshCommandBlockReason.MISSING_NATIVE_LIBRARY,
            (npm.launch as DshCommandLaunch.Blocked).reason
        )
        assertEquals(
            DshCommandBlockReason.NO_PLATFORM_SHELL,
            (resolved.getValue(DshCommandRole.SHELL).launch as DshCommandLaunch.Blocked).reason
        )
        DshCommandRole.entries.forEach { role ->
            val availability = resolved.getValue(role)
            assertFalse("${role.name} must not be startable without the payload", availability.executable)
            assertTrue("${role.name} report must stay bounded", availability.report().length < 512)
        }
    }

    @Test
    fun writableHomeScriptsAreStartableProgrammaticallyButNotOnPath() {
        // Scripts present, native libraries absent: MCA can still build the node command only if
        // node exists, so NPM must be blocked; PIP likewise. The PATH fact stays explicit.
        val resolved = DshCommandClosure.resolve(
            model(nativeLibraries = emptySet(), payloadFiles = allPayloadFiles)
        )
        val npm = resolved.getValue(DshCommandRole.NPM)
        assertEquals(
            DshCommandBlockReason.MISSING_NATIVE_LIBRARY,
            (npm.launch as DshCommandLaunch.Blocked).reason
        )
        assertEquals(DshPathStatus.SCRIPT_NOT_EXECUTABLE, npm.pathStatus)
    }

    @Test
    fun npmStaysStartableProgrammaticallyButReportsThePathLimitation() {
        val withoutDispatcher = allNatives - DshCommandClosure.DISPATCHER_LIBRARY
        val npm = DshCommandClosure.resolve(model(nativeLibraries = withoutDispatcher))
            .getValue(DshCommandRole.NPM)
        assertTrue(npm.launch is DshCommandLaunch.NodeScript)
        assertEquals(DshPathStatus.SCRIPT_NOT_EXECUTABLE, npm.pathStatus)
        assertTrue(npm.detail.contains("exec is denied"))
        val pip = DshCommandClosure.resolve(model(nativeLibraries = withoutDispatcher)).getValue(DshCommandRole.PIP)
        assertTrue(pip.detail.contains("-m pip"))
    }

    @Test
    fun missingStdlibBlocksPipEvenWhenPythonIsPackaged() {
        val resolved = DshCommandClosure.resolve(model(payloadDirectories = emptySet()))
        val pip = resolved.getValue(DshCommandRole.PIP)
        assertEquals(
            DshCommandBlockReason.MISSING_PAYLOAD_ENTRY,
            (pip.launch as DshCommandLaunch.Blocked).reason
        )
    }

    @Test
    fun linkerStrategyIsCarriedIntoEveryNativeLaunch() {
        val resolved = DshCommandClosure.resolve(model(nodeNeedsLinker = true))
        val node = resolved.getValue(DshCommandRole.NODE)
        assertTrue(node.launch is DshCommandLaunch.LinkerExecutable)
        assertEquals(
            listOf(DshCommandClosure.PLATFORM_LINKER, "$nativeRoot/${DshCommandClosure.Native.NODE}", "--version"),
            DshCommandClosure.argv(node.launch, listOf("--version"))
        )
        val npm = resolved.getValue(DshCommandRole.NPM)
        val launch = npm.launch as DshCommandLaunch.NodeScript
        assertEquals(listOf(DshCommandClosure.PLATFORM_LINKER), launch.execPrefix)
        assertEquals(
            listOf(
                DshCommandClosure.PLATFORM_LINKER,
                "$nativeRoot/${DshCommandClosure.Native.NODE}",
                "$payloadRoot/${DshCommandClosure.Payload.NPM_CLI}"
            ),
            DshCommandClosure.argv(launch, emptyList())
        )
    }

    // ------------------------------------------------------------------ argv safety

    @Test
    fun argumentsAreNeverJoinedIntoAShellString() {
        val rg = DshCommandClosure.resolve(model()).getValue(DshCommandRole.RIPGREP)
        val argv = DshCommandClosure.argv(rg.launch, listOf("a pattern with spaces", "-e", "\$HOME/*.txt"))
        assertEquals(
            listOf("$nativeRoot/${DshCommandClosure.Native.RIPGREP}", "a pattern with spaces", "-e", "\$HOME/*.txt"),
            argv
        )
    }

    @Test(expected = IllegalStateException::class)
    fun blockedLaunchCannotProduceArgv() {
        val blocked = DshCommandLaunch.Blocked(DshCommandBlockReason.NEEDS_NATIVE_DISPATCHER, "test")
        DshCommandClosure.argv(blocked, emptyList())
    }

    @Test
    fun reportLinesAreBoundedAndNamed() {
        val resolved = DshCommandClosure.resolve(model(nativeLibraries = emptySet(), payloadFiles = emptySet()))
        val line = resolved.getValue(DshCommandRole.NODE).report()
        assertTrue(line, line.startsWith("NODE=BLOCKED("))
        assertTrue(line, "path=" in line)
    }

    // ------------------------------------------------------------------ production scanner

    @Test
    fun scannerReadsOnlyTheBoundedCandidateSet() {
        val payload = Files.createTempDirectory("dsh-payload").toFile()
        val natives = Files.createTempDirectory("dsh-natives").toFile()
        File(payload, "npm/bin").mkdirs()
        File(payload, "npm/bin/npm-cli.js").writeText("// npm")
        File(payload, "python/lib/python3.14").mkdirs()
        File(natives, DshCommandClosure.Native.NODE).writeText("elf")
        File(natives, "unrelated.so").writeText("elf")
        val shell = File(payload, "platform-sh").apply { writeText("sh") }

        val scanned = DshCommandProbe.scan(
            payloadDir = payload,
            nativeLibraryDir = natives,
            payloadEntries = listOf(
                "npm/bin/npm-cli.js",
                "python/lib/python3.14",
                "bin/bash",
                "not-present.js"
            ),
            platformShells = listOf(shell.absolutePath)
        )

        assertTrue(scanned.payloadFile("npm/bin/npm-cli.js"))
        assertTrue(scanned.payloadDirectory("python/lib/python3.14"))
        assertFalse(scanned.payloadFile("bin/bash"))
        assertFalse(scanned.payloadFile("not-present.js"))
        assertEquals(setOf(DshCommandClosure.Native.NODE, "unrelated.so"), scanned.nativeLibraries)
        assertEquals(setOf(shell.absolutePath), scanned.platformShells)
        // The scanner never invents commands: NPM is startable (node + script), bash is a sh compat.
        val resolved = DshCommandClosure.resolve(scanned)
        assertTrue(resolved.getValue(DshCommandRole.NPM).launch is DshCommandLaunch.NodeScript)
        assertTrue(resolved.getValue(DshCommandRole.BASH).launch is DshCommandLaunch.PlatformShell)
    }
}





