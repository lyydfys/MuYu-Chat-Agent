package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ownership shape of the Local API chat-provider installation (P3 step 2).
 *
 * The two provider assignments moved verbatim from `MainViewModel`; this test reads the moved
 * source directly, so it doubles as the ownership audit: the moved body must not touch ViewModel
 * internals (`_uiState`, `viewModelScope`, `this@MainViewModel`).
 */
class ChatLocalApiProviderOperationsTest {
    @Test
    fun installKeepsTheEpochGuardAndLifecycleMirroring() {
        val body = functionBody(installSource(), "internal fun installChatLocalApiProviders(")

        assertTrue(body.contains("LocalApiRuntime.engine = engine"))
        assertTrue(body.contains("LocalApiRuntime.streamChatWithContextProvider = { request, executionContext ->"))
        assertTrue(body.contains("?.substringBefore('-')"))
        assertTrue(body.contains("chatRuns.matches(uiLifecycleRunId)"))
        assertEquals(
            "the run-id guard must check before and inside each update",
            4,
            Regex("""if \(ownsUiLifecycle\(\)\)""").findAll(body).count()
        )
        assertTrue(body.contains("state.afterGenerationStarted()"))
        assertTrue(body.contains("state.afterGenerationCompleted(engine.stats.value)"))
        assertTrue(body.contains("LocalApiRuntime.stopGenerationProvider = {"))
        assertTrue(body.contains("port.cancelActiveLocalApiImageGeneration("))
        assertTrue(
            body.indexOf("port.cancelActiveLocalApiImageGeneration(") <
                body.indexOf("engine.stopGeneration()")
        )
    }

    @Test
    fun installAlsoOwnsTheStopEntryPointAndTheLoadedModelProjection() {
        val body = functionBody(installSource(), "internal fun installChatLocalApiProviders(")

        assertTrue(body.contains("LocalApiRuntime.stopGenerationIfRequestActiveProvider = { requestId ->"))
        assertTrue(body.contains("engine.activeGenerationStopToken()"))
        assertTrue(body.contains("engine.stopGenerationIfActive(token)"))
        assertTrue(body.contains("LocalApiRuntime.loadedModelJsonProvider = { port.loadedModelJson() }"))
    }

    @Test
    fun installDoesNotReachIntoViewModelInternals() {
        val body = functionBody(installSource(), "internal fun installChatLocalApiProviders(")

        assertFalse(body.contains("_uiState"))
        assertFalse(body.contains("viewModelScope"))
        assertFalse(body.contains("this@MainViewModel"))
    }

    @Test
    fun theOnlyInstallCallSiteIsTheViewModelAssembly() {
        val vm = viewModelSource()

        assertEquals(
            1,
            Regex("""installChatLocalApiProviders\(""").findAll(vm).count()
        )
        assertFalse(vm.contains("LocalApiRuntime.streamChatWithContextProvider = {"))
        assertFalse(vm.contains("LocalApiRuntime.stopGenerationIfRequestActiveProvider ="))
        assertFalse(vm.contains("LocalApiRuntime.loadedModelJsonProvider = {"))
        assertTrue(vm.contains("installChatLocalApiProviders(chatLocalApiProviderPort)"))
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

    private fun installSource(): String = sourceFile("ChatLocalApiProviderOperations.kt")

    private fun viewModelSource(): String = sourceFile("MainViewModel.kt")

    private fun sourceFile(name: String): String {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            val candidates = listOf(
                File(root, "src/main/java/com/muyuchat/mca/$name"),
                File(root, "app/src/main/java/com/muyuchat/mca/$name")
            )
            candidates.firstOrNull { it.isFile }?.let { return it.readText(Charsets.UTF_8) }
            directory = root.parentFile
        }
        error("$name not found from ${System.getProperty("user.dir")}")
    }
}
