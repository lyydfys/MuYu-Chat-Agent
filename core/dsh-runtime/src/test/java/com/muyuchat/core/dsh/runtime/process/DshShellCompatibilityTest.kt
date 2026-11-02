package com.muyuchat.core.dsh.runtime.process

import com.muyuchat.core.dsh.runtime.payload.DshJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell-compatibility note is the typed answer to "can a GNU-bash-dependent plugin run here?".
 * These tests assert the honest mapping from the real closure, and that the note never upgrades a
 * shell into GNU bash without proof. Nothing here executes a shell: the device probe list is data.
 */
class DshShellCompatibilityTest {

    @Test
    fun thePackagedBashNameIsTheDispatcherShimNotGnuBash() {
        // dshTestModel() ships libdshcmd.so, so `bin/bash` resolves through the dispatcher shim.
        val note = DshShellCompatibility.ofModel(dshTestModel())

        assertEquals(DshShellInterpreter.DISPATCHER_SHIM, note.interpreter)
        assertFalse("no Android build may claim GNU bash here", note.gnuBash)
        assertTrue(note.executable)
        assertEquals(
            DshGnuBashFeature.entries.toSet(),
            note.missingGnuFeatures.toSet()
        )
        assertTrue(note.detail.contains("gnuBash=false"))
        assertTrue(note.summary().startsWith("bash=dispatcher-shim gnuBash=false missing="))
    }

    @Test
    fun withoutTheDispatcherThePlatformShellBacksBashAndStillIsNotBash() {
        val model = DshCommandClosureModel(
            payloadDir = "/data/user/0/com.muyuchat.mca/files/dsh/payload",
            nativeLibraryDir = "/data/app/~~x/com.muyuchat.mca-y/lib/arm64",
            payloadFiles = setOf(DshCommandClosure.Payload.BASH),
            nativeLibraries = setOf(DshCommandClosure.Native.NODE),
            platformShells = setOf(DshCommandClosure.PLATFORM_SHELL)
        )

        val note = DshShellCompatibility.ofModel(model)

        assertEquals(DshShellInterpreter.ANDROID_SH, note.interpreter)
        assertFalse(note.gnuBash)
        assertTrue(note.executable)
        assertTrue(note.detail.contains(DshCommandClosure.PLATFORM_SHELL))
        assertTrue(note.missingGnuFeatures.contains(DshGnuBashFeature.ARRAYS))
    }

    @Test
    fun aDispatcherWithoutThePayloadBashNameReportsThePlatformShellNotTheShim() {
        // Device evidence: libdshcmd.so was packaged while the closure payload had no bin/bash.
        // The note must not claim the dispatcher shim for a launch name that does not exist.
        val model = DshCommandClosureModel(
            payloadDir = "/data/user/0/com.muyuchat.mca/files/dsh/payload",
            nativeLibraryDir = "/data/app/~~x/com.muyuchat.mca-y/lib/arm64",
            payloadFiles = emptySet(),
            nativeLibraries = setOf(
                DshCommandClosure.Native.NODE,
                DshCommandClosure.DISPATCHER_LIBRARY
            ),
            platformShells = setOf(DshCommandClosure.PLATFORM_SHELL)
        )

        val note = DshShellCompatibility.ofModel(model)

        assertEquals(DshShellInterpreter.ANDROID_SH, note.interpreter)
        assertFalse(note.gnuBash)
        assertTrue(note.detail.contains(DshCommandClosure.PLATFORM_SHELL))
    }

    @Test
    fun noShellAndNoDispatcherIsTypedAsUnavailable() {
        val model = DshCommandClosureModel(
            payloadDir = "/data/user/0/com.muyuchat.mca/files/dsh/payload",
            nativeLibraryDir = "/data/app/~~x/com.muyuchat.mca-y/lib/arm64",
            payloadFiles = emptySet(),
            nativeLibraries = emptySet(),
            platformShells = emptySet()
        )

        val note = DshShellCompatibility.ofModel(model)

        assertEquals(DshShellInterpreter.NONE, note.interpreter)
        assertFalse(note.executable)
        assertFalse(note.gnuBash)
        assertTrue(note.summary().startsWith("bash=none gnuBash=false"))
        assertTrue(note.detail.startsWith("NO_PLATFORM_SHELL"))
        assertEquals(DshGnuBashFeature.entries.size, note.missingGnuFeatures.size)
    }

    @Test
    fun anUnresolvedRoleIsHonestInsteadOfEmpty() {
        val note = DshShellCompatibility.of(null)

        assertEquals(DshShellInterpreter.NONE, note.interpreter)
        assertEquals("UNRESOLVED", note.launch)
        assertEquals(DshGnuBashFeature.entries.size, note.missingGnuFeatures.size)
    }

    @Test
    fun gnuFeaturesCarryAProbeThatARealBashWouldPass() {
        for (feature in DshGnuBashFeature.entries) {
            assertTrue("${feature.name} must carry a probe", feature.probeArgs.size >= 2)
            assertEquals("-c", feature.probeArgs.first())
            assertTrue(feature.expectedOutput.isNotEmpty())
            assertTrue(feature.detail.isNotBlank())
            // The probe is an exact argv: no NUL, no shell-string interpolation by MCA.
            assertFalse(feature.probeArgs.any { it.contains('\u0000') })
        }
        assertEquals(
            DshGnuBashFeature.entries.size,
            DshGnuBashFeature.entries.map { it.name }.distinct().size
        )
    }

    @Test
    fun theReportSummaryCarriesTheShellFactAndTheRoutePayloadStaysSerializable() {
        val report = DshToolchainVerifier(successfulProbeRunner()) { 0L }
            .verify(dshTestModel(), emptyMap(), null)

        assertTrue(report.summary().contains("bash=dispatcher-shim gnuBash=false"))
        // The route payload is serialized with the module's own small JSON writer.
        val json = DshJson.write(report.shellCompatibility.facts())
        val parsed = DshJson.asObject(DshJson.parse(json), "bashCompatibility")
        assertEquals("DISPATCHER_SHIM", parsed["interpreter"])
        assertEquals(false, parsed["gnuBash"])
        assertNotNull(DshJson.asArray(parsed["missingGnuFeatures"], "missingGnuFeatures").firstOrNull())
    }
}
