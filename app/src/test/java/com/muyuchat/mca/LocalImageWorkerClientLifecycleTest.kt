package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageWorkerClientLifecycleTest {
    @Test
    fun terminalOutcomesReleaseTheBindingAndAllowTheNextBind() {
        listOf("success", "failure", "cancelled").forEach { outcome ->
            val lifecycle = LocalImageWorkerBindingLifecycle()

            assertTrue("$outcome must issue the first bind", lifecycle.issueBind())
            assertFalse("$outcome must not duplicate an active bind", lifecycle.issueBind())
            assertTrue("$outcome must unbind its worker", lifecycle.release())
            assertFalse("$outcome must leave no active binding", lifecycle.bindIssued)
            assertTrue("$outcome must allow the next generation to rebind", lifecycle.issueBind())
        }
    }

    @Test
    fun terminalReleaseIsIdempotent() {
        val lifecycle = LocalImageWorkerBindingLifecycle()
        assertFalse(lifecycle.release())
        assertTrue(lifecycle.issueBind())
        assertTrue(lifecycle.release())
        assertFalse(lifecycle.release())
    }

    @Test
    fun generateFinallyReleasesBinderStateBeforeClearingTheActiveRequest() {
        val source = localImageWorkerClientSource()
        val generateStart = source.indexOf("suspend fun generate(")
        require(generateStart >= 0) { "Missing generate()" }
        val generateEnd = source.indexOf("override fun close()", generateStart)
        require(generateEnd > generateStart) { "Missing close() after generate()" }
        val generate = source.substring(generateStart, generateEnd)
        val release = functionBody(source, "private fun releaseBindingAfterRequest(")

        val finallyBlock = generate.substring(generate.indexOf("finally {") + "finally {".length)
        assertTrue(finallyBlock.contains("releaseBindingAfterRequest(request, model.runtime)"))
        assertTrue(release.contains("bindingLifecycle.release()"))
        assertTrue(release.contains("unlinkRemoteDeathRecipientLocked()"))
        assertTrue(release.contains("remote = null"))
        assertTrue(release.contains("remoteBinder = null"))
        assertTrue(release.contains("appContext.unbindService(connection)"))
        assertTrue(
            "unbind must finish before a new request can observe activeRequest=null",
            release.indexOf("appContext.unbindService(connection)") <
                release.lastIndexOf("activeRequest === request")
        )
    }

    private fun localImageWorkerClientSource(): String {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(6) {
            listOf(
                File(root, "src/main/java/com/muyuchat/mca/LocalImageWorkerClient.kt"),
                File(root, "app/src/main/java/com/muyuchat/mca/LocalImageWorkerClient.kt")
            ).firstOrNull(File::isFile)?.let { return it.readText(Charsets.UTF_8) }
            root = root.parentFile ?: return@repeat
        }
        error("Unable to locate LocalImageWorkerClient.kt")
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val openingBrace = source.indexOf('{', start)
        require(openingBrace >= 0) { "Missing function body: $signature" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unterminated function body: $signature")
    }
}
