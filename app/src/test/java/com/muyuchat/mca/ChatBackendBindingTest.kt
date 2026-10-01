package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the persisted encoding of a session's chat backend, and proves the P2 extraction
 * changed nothing.
 *
 * `ChatBackend.bindingValue()` is written into the `chat_sessions.modelMode` column from
 * about twenty call sites and read back when a session is restored. The strings are
 * therefore a stored format: changing one would stop existing rows from resolving their
 * backend, and a session would quietly open on the wrong backend instead of failing.
 *
 * The tests below treat the literals as a compatibility contract rather than as
 * implementation detail.
 */
class ChatBackendBindingTest {

    @Test
    fun `the stored strings are exactly local and cloud`() {
        // Do not "tidy" these. They are already in users' databases.
        assertEquals("local", ChatBackend.LOCAL.bindingValue())
        assertEquals("cloud", ChatBackend.CLOUD.bindingValue())
    }

    @Test
    fun `every backend survives a write and read round trip`() {
        ChatBackend.entries.forEach { backend ->
            assertEquals(
                "$backend must be restorable from its own stored value",
                backend,
                backend.bindingValue().toChatBackendOrNull()
            )
        }
    }

    @Test
    fun `the stored vocabulary is the lowercase enum names`() {
        // The reader matches named literals, so this is what keeps the two halves aligned:
        // if a constant is renamed, the round trip above fails and points here.
        ChatBackend.entries.forEach { backend ->
            assertEquals(backend.name.lowercase(), backend.bindingValue())
        }
    }

    @Test
    fun `reading ignores surrounding whitespace and letter case`() {
        listOf("local", "LOCAL", "Local", "  local  ", "\tLocal\n").forEach { stored ->
            assertEquals("'$stored' must resolve to LOCAL", ChatBackend.LOCAL, stored.toChatBackendOrNull())
        }
        listOf("cloud", "CLOUD", "Cloud", "  cloud ").forEach { stored ->
            assertEquals("'$stored' must resolve to CLOUD", ChatBackend.CLOUD, stored.toChatBackendOrNull())
        }
    }

    @Test
    fun `an unrecognised column value resolves to no backend at all`() {
        // Callers treat null as "no stored preference" and fall back to the current
        // selection. Returning a default here would silently switch a restored session.
        listOf(
            null,
            "",
            "   ",
            "\t",
            // The assistant model-mode sentinel means "follow the current selection".
            "follow_current",
            "FOLLOW_CURRENT",
            // Values a future version might write, or a corrupted row.
            "hybrid",
            "local2",
            "cloudy",
            "LOCAL_CLOUD",
            "{}"
        ).forEach { stored ->
            assertNull("'$stored' must not resolve to a backend", stored.toChatBackendOrNull())
        }
    }

    @Test
    fun `the assistant bind vocabulary and the backend vocabulary agree`() {
        // MainViewModel normalises an assistant's default model mode to these literals, and
        // stores the result in the same column. A single reader serves both encodings, which
        // only works while the vocabularies match.
        val assistantModes = listOf("local" to ChatBackend.LOCAL, "cloud" to ChatBackend.CLOUD)
        assistantModes.forEach { (stored, expected) ->
            assertEquals(
                "the assistant mode '$stored' must resolve to $expected",
                expected,
                stored.toChatBackendOrNull()
            )
        }
        assertNull(
            "the follow-current sentinel is never stored and must not resolve",
            "follow_current".toChatBackendOrNull()
        )
    }

    @Test
    fun `the extraction matches the original mapping on every sample`() {
        val samples = listOf<String?>(
            null, "", " ", "  ", "\t", "local", "LOCAL", "Local", " local ", "cloud", "CLOUD",
            "Cloud", " cloud ", "follow_current", "FOLLOW_CURRENT", "hybrid", "local2",
            "cloudy", "LOCAL_CLOUD", "{}", "0", "true"
        )

        samples.forEach { stored ->
            assertEquals(
                "parsing diverged for '$stored'",
                originalToChatBackendOrNull(stored),
                stored.toChatBackendOrNull()
            )
        }

        ChatBackend.entries.forEach { backend ->
            assertEquals(
                "writing diverged for $backend",
                originalBindingValue(backend),
                backend.bindingValue()
            )
        }
    }

    /** Verbatim copy of the pre-extraction `MainViewModel.ChatBackend.bindingValue`. */
    private fun originalBindingValue(backend: ChatBackend): String =
        backend.name.lowercase()

    /** Verbatim copy of the pre-extraction `MainViewModel.String?.toChatBackendOrNull`. */
    private fun originalToChatBackendOrNull(stored: String?): ChatBackend? =
        when (stored?.trim()?.lowercase()) {
            "local" -> ChatBackend.LOCAL
            "cloud" -> ChatBackend.CLOUD
            else -> null
        }
}
