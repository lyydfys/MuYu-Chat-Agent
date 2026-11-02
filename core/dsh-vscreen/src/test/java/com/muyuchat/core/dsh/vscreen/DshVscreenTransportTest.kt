package com.muyuchat.core.dsh.vscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transport probe matrix, honest selection and secret-free privileged command construction. */
class DshVscreenTransportTest {

    private val targets = DshVscreenPrivilegedTargets(
        privilegedDexPath = "/data/local/tmp/dsh-vscreen.jar",
        shizukuAppId = "com.muyuchat.mca"
    )

    @Test
    fun selectorUsesTheCaptureConsentForTheDisplayPathAndNeverClaimsVerification() {
        val selection = DshVscreenTransportSelector.select(
            probeReport(rootProven = true, shizukuProven = true)
        )
        assertEquals(DshVscreenTransport.ROOT, selection.shell)
        assertEquals(DshVscreenDisplaySource.PROJECTION, selection.display)
        assertEquals(DshVscreenProof.UNPROVEN, selection.displayProof)
        assertFalse(selection.fullyVerified)
        assertTrue(selection.warning.orEmpty().contains("unverified"))
    }

    @Test
    fun rootOnlySelectionKeepsTheUnverifiedDisplayWarning() {
        val selection = DshVscreenTransportSelector.select(probeReport(rootProven = true))
        assertEquals(DshVscreenTransport.ROOT, selection.shell)
        assertEquals(DshVscreenDisplaySource.PROJECTION, selection.display)
        assertEquals(DshVscreenProof.UNPROVEN, selection.displayProof)
        assertFalse(selection.fullyVerified)
        assertTrue(selection.warning.orEmpty().contains("unverified"))
    }

    @Test
    fun noProvenTransportAndNoCaptureYieldsTheUnavailableSelection() {
        val selection = DshVscreenTransportSelector.select(
            probeReport(capture = captureEvidence(DshVscreenProof.UNSUPPORTED))
        )
        assertEquals(DshVscreenTransport.NONE, selection.shell)
        assertEquals(DshVscreenDisplaySource.NONE, selection.display)
        assertEquals(DshVscreenProof.UNSUPPORTED, selection.displayProof)
        assertFalse(selection.privileged)
        assertFalse(selection.canCreateDisplay)
        assertFalse(selection.fullyVerified)
        assertTrue(selection.warning.orEmpty().contains("unavailable"))
    }

    @Test
    fun aShellWithoutACapturePathIsNotSelectableForCreation() {
        val selection = DshVscreenTransportSelector.select(
            probeReport(
                rootProven = false,
                shizukuProven = true,
                capture = captureEvidence(DshVscreenProof.UNSUPPORTED)
            )
        )
        assertEquals(DshVscreenTransport.SHIZUKU, selection.shell)
        assertEquals(DshVscreenDisplaySource.NONE, selection.display)
        assertFalse(selection.canCreateDisplay)
        assertTrue(selection.warning.orEmpty().contains("no screen-capture path"))
    }

    @Test
    fun onlyAProvenCapturePathSetsFullyVerified() {
        val selection = DshVscreenTransportSelector.select(
            probeReport(
                shizukuProven = true,
                capture = captureEvidence(DshVscreenProof.PROVEN)
            )
        )
        assertEquals(DshVscreenDisplaySource.PROJECTION, selection.display)
        assertTrue(selection.fullyVerified)
        assertEquals(null, selection.warning)
    }

    @Test
    fun aVerifiedCaptureWithoutAShellStillWarnsAboutLaunchAndInput() {
        val selection = DshVscreenTransportSelector.select(
            probeReport(capture = captureEvidence(DshVscreenProof.PROVEN))
        )
        assertTrue(selection.fullyVerified)
        assertEquals(DshVscreenTransport.NONE, selection.shell)
        assertTrue(selection.warning.orEmpty().contains("no privileged shell"))
    }

    @Test
    fun sanitizeEnvironmentDropsLoaderVariablesAndInvalidNames() {
        val sanitized = DshVscreenCommandFactory.sanitizeEnvironment(
            mapOf(
                "LD_LIBRARY_PATH" to "/system/lib",
                "LD_PRELOAD" to "hook.so",
                "LD_DEBUG" to "all",
                "PATH" to "/system/bin",
                "BAD KEY" to "ignored",
                "RISH_APPLICATION_ID" to "com.muyuchat.mca"
            )
        )
        assertEquals(setOf("PATH", "RISH_APPLICATION_ID"), sanitized.keys)
        assertFalse(sanitized.values.any { it.contains("hook") })
    }

    @Test
    fun rootCommandUsesSuAndNeverTheShizukuLoader() {
        val command = DshVscreenCommandFactory.inputTap(
            transport = DshVscreenTransport.ROOT,
            targets = targets,
            displayId = 7,
            x = 10,
            y = 20
        )
        assertEquals(listOf("su", "-c", "input -d 7 tap 10 20"), command.argv)
        assertTrue(command.environment.isEmpty())
        assertEquals(DshVscreenTransport.ROOT, command.transport)
    }

    @Test
    fun shizukuCommandUsesTheRishLoaderAndApplicationId() {
        val command = DshVscreenCommandFactory.inputKey(
            transport = DshVscreenTransport.SHIZUKU,
            targets = targets,
            displayId = 7,
            key = DshVscreenKey.BACK
        )
        assertEquals("/system/bin/app_process", command.argv.first())
        assertTrue(command.argv.contains("rikka.shizuku.shell.ShizukuShellLoader"))
        assertEquals("input -d 7 keyevent 4", command.argv.last())
        assertEquals("com.muyuchat.mca", command.environment["RISH_APPLICATION_ID"])
        assertFalse(command.argv.joinToString(" ").contains("LD_LIBRARY_PATH"))
    }

    @Test
    fun shizukuWithoutADexFailsClosed() {
        try {
            DshVscreenCommandFactory.inputTap(
                transport = DshVscreenTransport.SHIZUKU,
                targets = DshVscreenPrivilegedTargets(null, "com.muyuchat.mca"),
                displayId = 7,
                x = 1,
                y = 2
            )
            throw AssertionError("expected the missing dex to fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("SHIZUKU_DEX"))
        }
    }

    @Test
    fun bridgeLaunchKeepsTheTokenInStdinAndOutOfArgvEnvironmentAndLogs() {
        val token = "dshc_" + "A".repeat(43)
        val command = DshVscreenCommandFactory.startDisplayBridge(
            transport = DshVscreenTransport.SHIZUKU,
            targets = targets,
            config = DshVscreenBridgeLaunchConfig(
                bridgePort = 8_765,
                localToken = token,
                geometry = DshVscreenGeometry.canonical(DshVscreenOrientation.PORTRAIT)
            )
        )
        assertNotNull(command.stdin)
        assertTrue(command.stdin!!.contains(token))
        assertFalse(command.carriesSecretOnCommandLine(token))
        assertFalse(command.argv.joinToString(" ").contains(token))
        assertFalse(command.redacted.contains(token))
        assertFalse(command.toString().contains(token))
        assertTrue(command.redacted.contains("stdinChars="))
        assertTrue(command.redacted.contains("argv=["))
    }

    @Test
    fun privilegedCommandRejectsMalformedArguments() {
        assertThrowsIllegalArgument {
            DshVscreenPrivilegedCommand(
                transport = DshVscreenTransport.ROOT,
                argv = emptyList()
            )
        }
        assertThrowsIllegalArgument {
            DshVscreenPrivilegedCommand(
                transport = DshVscreenTransport.ROOT,
                argv = listOf("su", "line\nbreak")
            )
        }
        assertThrowsIllegalArgument {
            DshVscreenCommandFactory.inputTap(
                transport = DshVscreenTransport.ROOT,
                targets = targets,
                displayId = -1,
                x = 1,
                y = 2
            )
        }
    }

    @Test
    fun bridgeConfigRejectsTokensOutsideTheContractAlphabet() {
        assertThrowsIllegalArgument {
            DshVscreenBridgeLaunchConfig(
                bridgePort = 8_765,
                localToken = "short",
                geometry = DshVscreenGeometry.canonical(DshVscreenOrientation.PORTRAIT)
            )
        }
        assertThrowsIllegalArgument {
            DshVscreenBridgeLaunchConfig(
                bridgePort = 8_765,
                localToken = "dshc_" + "!".repeat(43),
                geometry = DshVscreenGeometry.canonical(DshVscreenOrientation.PORTRAIT)
            )
        }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // Expected branch.
        }
    }
}
