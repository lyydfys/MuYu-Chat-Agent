package com.muyuchat.core.dsh.runtime.trial

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trial must launch the candidate's own payload tree. Substituting the installed payload would
 * make every trial pass regardless of what the candidate contains.
 */
class DshTrialCandidatePayloadTest {

    private val roots = ArrayList<File>()

    @After
    fun tearDown() {
        roots.forEach { root -> root.deleteRecursively() }
    }

    private fun newCandidate(): File {
        val root = Files.createTempDirectory("dsh-trial-candidate").toFile()
        roots.add(root)
        return root
    }

    private fun write(file: File, text: String = "kernel") {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    @Test
    fun aCandidatePayloadResolvesToItsOwnTree() {
        val candidate = newCandidate()
        val kernel = File(candidate, "payload/$KERNEL_ENTRY")
        write(kernel)

        val resolved = DshTrialCandidatePayload.resolve(candidate, KERNEL_ENTRY)

        assertTrue(resolved is DshTrialCandidatePayload.Resolution.Ok)
        val ok = resolved as DshTrialCandidatePayload.Resolution.Ok
        assertEquals(File(candidate, "payload"), ok.payloadRoot)
        assertEquals(kernel, ok.kernelEntry)
    }

    @Test
    fun aCandidateWithoutPayloadIsRefusedInsteadOfUsingTheInstalledTree() {
        val candidate = newCandidate()

        val resolved = DshTrialCandidatePayload.resolve(candidate, KERNEL_ENTRY)

        assertTrue(resolved is DshTrialCandidatePayload.Resolution.Refused)
        assertTrue(
            (resolved as DshTrialCandidatePayload.Resolution.Refused).reason
                .contains("no executable payload")
        )
    }

    @Test
    fun aPayloadWithoutItsKernelEntryIsRefused() {
        val candidate = newCandidate()
        File(candidate, "payload").mkdirs()
        write(File(candidate, "payload/other.js"))

        val resolved = DshTrialCandidatePayload.resolve(candidate, KERNEL_ENTRY)

        assertTrue(resolved is DshTrialCandidatePayload.Resolution.Refused)
        assertTrue(
            (resolved as DshTrialCandidatePayload.Resolution.Refused).reason
                .contains("kernel entry is missing")
        )
    }

    @Test
    fun aKernelEntryThatEscapesTheCandidatePayloadIsRefused() {
        val candidate = newCandidate()
        File(candidate, "payload").mkdirs()
        write(File(candidate, "outside.js"))

        val resolved = DshTrialCandidatePayload.resolve(candidate, "../outside.js")

        assertTrue(resolved is DshTrialCandidatePayload.Resolution.Refused)
        assertTrue(
            (resolved as DshTrialCandidatePayload.Resolution.Refused).reason
                .contains("escapes its payload tree")
        )
    }

    private companion object {
        const val KERNEL_ENTRY = "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
    }
}
