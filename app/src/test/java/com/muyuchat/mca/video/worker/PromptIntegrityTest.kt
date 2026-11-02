package com.muyuchat.mca.video.worker

import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The worker must only execute the prompt the user approved. Run snapshots store the prompt as a
 * SHA-256 (privacy), while the text itself is read from the workflow document, so any drift
 * between the two would silently run a different prompt under the approved hash.
 */
class PromptIntegrityTest {

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun `the exact approved prompt matches its snapshot pin`() {
        val prompt = "a red ball bouncing on a wooden table"
        assertTrue(promptMatchesSnapshot(prompt, sha256(prompt)))
    }

    @Test
    fun `an edited prompt is rejected`() {
        val approved = "a red ball bouncing on a wooden table"
        val edited = "a blue sports car on a highway at night"
        assertFalse(promptMatchesSnapshot(edited, sha256(approved)))
    }

    @Test
    fun `the pin comparison is case insensitive`() {
        val prompt = "prompt"
        assertTrue(promptMatchesSnapshot(prompt, sha256(prompt).uppercase()))
    }

    @Test
    fun `an absent pin does not block execution`() {
        assertTrue(promptMatchesSnapshot("anything", ""))
    }
}
