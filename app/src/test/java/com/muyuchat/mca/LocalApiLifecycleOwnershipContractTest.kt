package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalApiLifecycleOwnershipContractTest {
    @Test
    fun replacementClaimsProcessServiceBeforeRetiringTheOldRuntimeOwner() {
        val source = mainViewModelSource()
        val claimProcess = source.indexOf("claimLocalApiProcessOwnership()")
        val claimRuntime = source.indexOf("LocalApiRuntime.claimOwner(localApiRuntimeOwner)")

        assertTrue(claimProcess >= 0)
        assertTrue(claimRuntime > claimProcess)
        assertTrue(source.contains("private val localApiProcessLifecycleLock = Any()"))
        assertTrue(source.contains("localApiProcessOwnerToken !== localApiRuntimeOwner"))
    }

    @Test
    fun listenerReplacementAndShutdownShareAnExactServerLock() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/LocalApiForegroundService.kt")
        val start = functionBody(source, "fun ensureListener(")
        val stop = functionBody(source, "fun stopListener()")

        assertTrue(source.contains("private val listenerLock = Any()"))
        assertTrue(start.contains("synchronized(listenerLock)"))
        assertTrue(stop.contains("synchronized(listenerLock)"))
        val viewModel = mainViewModelSource()
        assertTrue(viewModel.contains("LocalApiForegroundService.ensureListener("))
        assertTrue(viewModel.contains("LocalApiForegroundService.stopListener()"))
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing function: $signature" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unterminated function: $signature")
    }

    private fun mainViewModelSource(): String =
        sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate $relativePath")
    }
}
