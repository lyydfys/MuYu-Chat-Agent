package com.muyuchat.core.dsh.runtime.process

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test of the packaged seam asset.
 *
 * There is no JS test harness in this repository (no `package.json`, no Node test runner), so the
 * seam's *source contract* is pinned here instead: the official routes it calls, the two
 * authentication headers, the dependency-free rule, and the two honesty rules (`inspectForeground`
 * returns unknown, `signalForeground` throws instead of inventing a pgid).
 *
 * This does not and cannot prove that the payload's loader imports the file on a device. That is
 * recorded as NOT_RUN in the handoff: the loader resolves `name:` specifiers itself and the
 * `file://` form is only proven for plain Node ESM here.
 */
class DshTerminalSeamSourceTest {

    private val source: String by lazy { seamAsset().readText(Charsets.UTF_8) }

    @Test
    fun itExportsACordisPluginThatInjectsTheSubprocessService() {
        assertTrue(source.contains("export const name = 'mca-terminal-bridge'"))
        assertTrue(source.contains("export const inject = ['subprocess']"))
        assertTrue(source.contains("export function apply(ctx)"))
        // Cordis reads the plugin's named exports; a default export would be applied without
        // `.inject` and fail with `cannot get property "subprocess" without inject` (2026-10-25).
        assertFalse(source.contains("export default"))
        assertTrue(source.contains("service.spawnTerminal ="))
    }

    @Test
    fun itCallsTheFiveOfficialTerminalRoutesOnly() {
        for (path in listOf(
            "/terminal/open",
            "/terminal/read",
            "/terminal/write",
            "/terminal/resize",
            "/terminal/close"
        )) {
            assertTrue("seam must call $path", source.contains("'$path'"))
        }
        // /terminal/attach, /terminal/list and /terminal/control are not part of this seam: attach
        // and list belong to the workspace UI, and ^C control is documented as unavailable here.
        assertFalse(source.contains("'/terminal/attach'"))
        assertFalse(source.contains("'/terminal/control'"))
        assertFalse(source.contains("'/toolchain/verify'"))
    }

    @Test
    fun itAuthenticatesWithTheBridgeTokenAndCarriesARequestId() {
        assertTrue(source.contains("'X-DSH-Token'"))
        assertTrue(source.contains("'X-DSH-Request-Id'"))
        assertTrue(source.contains("APP_NOTIFY_PORT"))
        assertTrue(source.contains("APP_LOCAL_TOKEN"))
        // open/close are the two non-idempotent calls: both must carry a request id.
        assertEquals(2, occurrences(source, "requestId: newRequestId("))
        assertTrue(source.contains("mca-${'$'}{kind}-"))
    }

    @Test
    fun inspectForegroundReportsUnknownInsteadOfAFakePgid() {
        assertTrue(
            "inspectForeground must resolve undefined",
            Regex("async inspectForeground\\(\\)\\s*\\{\\s*return undefined").containsMatchIn(source)
        )
        assertFalse("the seam must never invent a process group", source.contains("processGroupId"))
        assertTrue(
            "signalForeground must throw",
            Regex("async signalForeground\\(signal\\)\\s*\\{").containsMatchIn(source)
        )
        assertTrue(source.contains("cannot signal the foreground process group"))
    }

    @Test
    fun itIsDependencyFreeAndUsesNodeBuiltinsOnly() {
        val imports = Regex("from '([^']+)'").findAll(source).map { it.groupValues[1] }.toList()
        assertTrue("expected at least the stream import", imports.isNotEmpty())
        for (module in imports) {
            assertTrue("the seam must not depend on '$module'", module.startsWith("node:"))
        }
        assertFalse(source.contains("require("))
        assertFalse(source.contains("node-pty"))
    }

    @Test
    fun itWritesTerminalInputVerbatimAndNeverAppendsACarriageReturn() {
        // The official backend already appends "\r" when it submits; a second one would double it.
        assertTrue(source.contains("submit: false"))
    }

    @Test
    fun itNeverClaimsARealPtyWhenTheBridgeRefusedOrIsAbsent() {
        assertTrue(source.contains("APP_NOTIFY_PORT/APP_LOCAL_TOKEN are absent"))
        assertTrue(source.contains("refused the session"))
        assertTrue(source.contains("sandboxArgvByteFaithful: false"))
        assertTrue(source.contains("gnuBash: false"))
    }

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count += 1
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    /** The asset lives in this module's `src/main/assets`; Gradle runs unit tests in the module dir. */
    private fun seamAsset(): File {
        val relative = "src/main/assets/dsh-seam/terminal-bridge.mjs"
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
