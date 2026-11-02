package com.muyuchat.core.dsh.runtime.process

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshToolchainVerifierTest {

    private val environment = mapOf("PATH" to "/payload/bin:/system/bin")
    private val workingDir = File(System.getProperty("java.io.tmpdir"))

    @Test
    fun everyProbeIsActuallyExecutedAndAccepted() {
        val calls = mutableListOf<List<String>>()
        val runner = DshProcessRunner { argv, _, _, _ ->
            calls.add(argv)
            when {
                argv.contains(DshToolchainVerifier.MARKER_COMMAND) ->
                    DshCommandResult(0, DshToolchainVerifier.MARKER, "", false)
                argv.any { it.endsWith(DshCommandClosure.Payload.NPM_CLI) } ->
                    DshCommandResult(0, "10.9.2", "", false)
                argv.any { it.endsWith(DshCommandClosure.Payload.NPX_CLI) } ->
                    DshCommandResult(0, "10.9.2", "", false)
                argv.any { it.endsWith(DshCommandClosure.Payload.PNPM_CLI) } ->
                    DshCommandResult(0, "10.12.1", "", false)
                argv.contains("-m") -> DshCommandResult(0, "pip 24.3.1", "", false)
                argv.any { it.endsWith(DshCommandClosure.Native.PYTHON) } ->
                    DshCommandResult(0, "Python 3.14.0", "", false)
                argv.any { it.endsWith(DshCommandClosure.Native.NODE) } ->
                    DshCommandResult(0, "v26.1.0", "", false)
                argv.any { it.endsWith(DshCommandClosure.Native.RIPGREP) } ->
                    DshCommandResult(0, "ripgrep 14.1.1", "", false)
                argv.any { it.endsWith(DshCommandClosure.Native.CURL) } ->
                    DshCommandResult(0, "curl 8.11.1", "", false)
                argv.any { it.endsWith(DshCommandClosure.Native.GIT) } ->
                    DshCommandResult(0, "git version 2.47.1", "", false)
                else -> DshCommandResult(0, "", "", false)
            }
        }
        val verifier = DshToolchainVerifier(runner) { 1_000L }
        val report = verifier.verify(dshTestModel(), environment, workingDir)

        assertTrue(report.usable)
        assertEquals(DshToolchainVerifier.PROBE_ROLES.size, report.probes.size)
        assertEquals(DshToolchainVerifier.PROBE_ROLES.size, calls.size)
        assertTrue(report.probes.all { it.state == DshToolProbeState.PASSED })
        assertTrue(report.missingDependencies.isEmpty())
        assertTrue(report.summary().startsWith("toolchain probes=${report.probes.size}/${report.probes.size}"))
    }

    @Test
    fun packageManagersRunThroughTheBundledNodeAndPipThroughPython() {
        val calls = mutableMapOf<DshCommandRole, List<String>>()
        val recorder = DshProcessRunner { argv, _, _, _ ->
            val role = when {
                argv.contains("-m") -> DshCommandRole.PIP
                argv.any { it.endsWith(DshCommandClosure.Payload.NPM_CLI) } -> DshCommandRole.NPM
                argv.any { it.endsWith(DshCommandClosure.Payload.PNPM_CLI) } -> DshCommandRole.PNPM
                argv.any { it.endsWith(DshCommandClosure.Payload.NPX_CLI) } -> DshCommandRole.NPX
                argv.any { it.endsWith(DshCommandClosure.Native.NODE) } -> DshCommandRole.NODE
                argv.any { it.endsWith(DshCommandClosure.Native.PYTHON) } -> DshCommandRole.PYTHON
                argv.any { it.endsWith(DshCommandClosure.Native.RIPGREP) } -> DshCommandRole.RIPGREP
                argv.any { it.endsWith(DshCommandClosure.Native.CURL) } -> DshCommandRole.CURL
                argv.any { it.endsWith(DshCommandClosure.Native.GIT) } -> DshCommandRole.GIT
                argv.contains(DshToolchainVerifier.MARKER_COMMAND) -> DshCommandRole.BASH
                else -> DshCommandRole.SHELL
            }
            calls[role] = argv
            DshCommandResult(0, "",
                when (role) {
                    DshCommandRole.BASH -> DshToolchainVerifier.MARKER
                    else -> ""
                }, false)
        }
        val verifier = DshToolchainVerifier(recorder) { 0L }
        val report = verifier.verify(dshTestModel(), environment, workingDir)
        assertNotNull(report)
        val npm = calls.getValue(DshCommandRole.NPM)
        assertTrue("npm must run the bundled Node, was $npm", npm.first().endsWith(DshCommandClosure.Native.NODE))
        assertTrue("npm must run npm-cli.js, was $npm", npm.any { it.endsWith(DshCommandClosure.Payload.NPM_CLI) })
        assertFalse("npm must not exec the writable wrapper", npm.any { it.endsWith("/bin/npm") })

        val pip = calls.getValue(DshCommandRole.PIP)
        assertEquals(listOf("-m", "pip", "--version"), pip.takeLast(3))

        val bash = calls.getValue(DshCommandRole.BASH)
        assertEquals(listOf("-c", DshToolchainVerifier.MARKER_COMMAND), bash.takeLast(2))
        assertFalse("bash must not be probed with GNU-only flags", bash.contains("--noprofile"))
        assertNull(calls[DshCommandRole.GIT_REMOTE_HTTP])
        assertTrue(verifier.probeArguments(DshCommandRole.BASH).contains("-c"))
    }

    @Test
    fun aMissingPayloadEntryIsBlockedNotFakedAsPassing() {
        val model = dshTestModel(
            payloadFiles = setOf(
                DshCommandClosure.Payload.NPM_CLI,
                DshCommandClosure.Payload.BASH
            )
        )
        val report = DshToolchainVerifier(successfulProbeRunner()) { 0L }
            .verify(model, environment, workingDir)
        val pnpm = report.probes.single { it.role == DshCommandRole.PNPM }
        assertEquals(DshToolProbeState.BLOCKED, pnpm.state)
        assertFalse(report.usable)
        assertTrue(report.failures.any { it.role == DshCommandRole.PNPM })
        assertTrue(report.missingDependencies.any { it.name == "pnpm.cjs" })
    }

    @Test
    fun aTimeoutIsReportedAsTimeoutNotAsAnExitCode() {
        val runner = DshProcessRunner { argv, _, _, _ ->
            if (argv.any { it.endsWith(DshCommandClosure.Payload.NPM_CLI) }) {
                DshCommandResult(Int.MIN_VALUE, "partial", "", true)
            } else {
                DshCommandResult(0, "v26.1.0", "", false)
            }
        }
        val report = DshToolchainVerifier(runner) { 0L }.verify(
            dshTestModel(),
            environment,
            workingDir,
            timeoutMillis = 25L
        )
        val npm = report.probes.single { it.role == DshCommandRole.NPM }
        assertEquals(DshToolProbeState.TIMED_OUT, npm.state)
        assertNull(npm.exitCode)
        assertFalse(report.usable)
    }

    @Test
    fun aSpawnFailureIsDistinctFromANonZeroExit() {
        val runner = DshProcessRunner { argv, _, _, _ ->
            when {
                argv.any { it.endsWith(DshCommandClosure.Payload.NPM_CLI) } ->
                    DshCommandResult(Int.MIN_VALUE, "", "Cannot run program", false)
                argv.any { it.endsWith(DshCommandClosure.Payload.NPX_CLI) } ->
                    DshCommandResult(1, "", "boom", false)
                else -> DshCommandResult(0, "v26.1.0", "", false)
            }
        }
        val report = DshToolchainVerifier(runner) { 0L }.verify(dshTestModel(), environment, workingDir)
        assertEquals(
            DshToolProbeState.SPAWN_FAILED,
            report.probes.single { it.role == DshCommandRole.NPM }.state
        )
        val npx = report.probes.single { it.role == DshCommandRole.NPX }
        assertEquals(DshToolProbeState.FAILED, npx.state)
        assertEquals(1, npx.exitCode)
        assertTrue(npx.output.contains("boom"))
    }

    @Test
    fun exitZeroWithWrongOutputStillFailsTheRole() {
        val runner = DshProcessRunner { argv, _, _, _ ->
            if (argv.any { it.endsWith(DshCommandClosure.Native.GIT) }) {
                DshCommandResult(0, "not git at all", "", false)
            } else {
                DshCommandResult(0, "v26.1.0\nripgrep 14\ncurl 8\n10.9.2\npip 24\nPython 3.14\n" +
                    DshToolchainVerifier.MARKER, "", false)
            }
        }
        val report = DshToolchainVerifier(runner) { 0L }.verify(dshTestModel(), environment, workingDir)
        val git = report.probes.single { it.role == DshCommandRole.GIT }
        assertEquals(DshToolProbeState.FAILED, git.state)
        assertFalse(report.usable)
    }

    @Test
    fun missingNativeArtifactsAreDependencyFailures() {
        val model = dshTestModel(
            nativeLibraries = setOf(
                DshCommandClosure.Native.NODE,
                DshCommandClosure.DISPATCHER_LIBRARY
            )
        )
        val report = DshToolchainVerifier(successfulProbeRunner()) { 0L }
            .verify(model, environment, workingDir)
        val missing = report.missingDependencies.map { it.name }
        assertTrue(missing.contains(DshCommandClosure.PTY_LIBRARY))
        assertFalse(missing.contains(DshCommandClosure.Native.NODE))
        assertFalse(report.usable)
    }

    @Test
    fun outputIsBoundedAndControlCharactersAreStripped() {
        val runner = DshProcessRunner { argv, _, _, _ ->
            if (argv.any { it.endsWith(DshCommandClosure.Native.CURL) }) {
                DshCommandResult(0, "\u0007curl " + "x".repeat(500), "", false)
            } else {
                DshCommandResult(0, "v26.1.0\nripgrep 14\ngit version 2\n10.9.2\npip 24\nPython 3.14\n" +
                    DshToolchainVerifier.MARKER, "", false)
            }
        }
        val report = DshToolchainVerifier(runner) { 0L }.verify(dshTestModel(), environment, workingDir)
        val curl = report.probes.single { it.role == DshCommandRole.CURL }
        assertFalse(curl.output.contains('\u0007'))
        assertTrue(curl.output.length <= 180)
        assertEquals(DshToolProbeState.PASSED, curl.state)
    }
}
