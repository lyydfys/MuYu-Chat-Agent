package com.muyuchat.core.dsh.runtime.process

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test of the shell-env contributor asset.
 *
 * `dsh-shell-env` accepts only `DSH_*` keys and aborts the whole registration when one key is
 * rejected. A device boot with `TMPDIR` declared therefore contributed nothing at all and the
 * bash tool saw an empty `DSH_PAYLOAD_DIR` (plugins.log 2026-10-25: `declared invalid key
 * "TMPDIR"`). This test pins the one-key contract so the mistake cannot come back silently.
 */
class DshShellEnvContributorSourceTest {

    private val source: String by lazy { asset().readText(Charsets.UTF_8) }

    @Test
    fun itExportsACordisPluginThatInjectsTheShellEnvRegistry() {
        assertTrue(source.contains("export const name = 'mca-shell-env'"))
        assertTrue(source.contains("export const inject = ['shellEnv']"))
        assertTrue(source.contains("ctx.shellEnv.register({"))
    }

    @Test
    fun itDeclaresOnlyThePayloadRootAsADshFact() {
        assertTrue(source.contains("DSH_PAYLOAD_DIR: {"))
        assertFalse(
            "TMPDIR is not a DSH_ fact; declaring it aborts the whole registration",
            source.contains("TMPDIR: {")
        )
        assertEquals("exactly one contributed variable", 1, Regex("description:").findAll(source).count())
    }

    @Test
    fun itResolvesTheConfiguredPayloadRootAndDoesNothingWithoutOne() {
        assertTrue(source.contains("resolve: () => ({ DSH_PAYLOAD_DIR: payloadDir })"))
        assertTrue(source.contains("if (payloadDir.length === 0) return"))
    }

    private fun asset(): File {
        val relative = "src/main/assets/dsh-seam/shell-env-contributor.mjs"
        val direct = File(relative)
        if (direct.isFile) return direct
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "core/dsh-runtime/$relative")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("cannot locate $relative from ${File("").absolutePath}")
    }
}
