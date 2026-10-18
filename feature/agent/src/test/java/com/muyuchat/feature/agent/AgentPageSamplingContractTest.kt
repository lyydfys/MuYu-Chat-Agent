package com.muyuchat.feature.agent

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPageSamplingContractTest {

    @Test
    fun pageDerivationIsLifecycleAwareAndStopsWhenCovered() {
        val source = sourceFile(SCREEN_PATH)

        assertTrue(source.contains("val lifecycleOwner = LocalLifecycleOwner.current"))
        assertTrue(source.contains("lifecycleOwner.lifecycle.currentStateAsState()"))
        assertTrue(source.contains("lifecycleState.isAtLeast(Lifecycle.State.RESUMED)"))
        assertTrue(source.contains("val mainContentActive = pageResumed && infoDialog == null"))
        assertTrue(source.contains("if (mainContentActive) {"))
        assertTrue(source.contains("presentationHolder.refresh("))
        assertTrue(source.contains("presentationHolder.current"))
    }

    @Test
    fun pageDerivationDoesNotCreateOrCancelBusinessJobs() {
        val source = sourceFile(SCREEN_PATH)

        assertFalse(source.contains("LaunchedEffect("))
        assertFalse(source.contains("rememberCoroutineScope"))
        assertFalse(source.contains("CoroutineScope"))
        assertFalse(source.contains("GlobalScope"))
        assertFalse(source.contains("onPauseTuning?.invoke()"))
        assertFalse(source.contains("onResumeTuning?.invoke()"))
        assertFalse(source.contains("onCancelTuning?.invoke()"))
        assertFalse(source.contains("onQueryTuningJob?.invoke()"))
        assertTrue(source.contains("onPauseTuning = onPauseTuning"))
        assertTrue(source.contains("onResumeTuning = onResumeTuning"))
        assertTrue(source.contains("onCancelTuning = onCancelTuning"))
        assertTrue(source.contains("onQueryTuningJob = onQueryTuningJob"))
    }

    @Test
    fun cacheIsFixedBoundedAndOwnedByTheComposition() {
        val screen = sourceFile(SCREEN_PATH)
        val derived = sourceFile(DERIVED_STATE_PATH)

        assertTrue(screen.contains("val presentationHolder = remember { AgentPagePresentationHolder() }"))
        assertTrue(derived.contains("internal const val AGENT_PAGE_PRESENTATION_CACHE_LIMIT = 64"))
        assertTrue(derived.contains("private val entries = LinkedHashMap<K, V>(maxEntries, 0.75f, true)"))
        assertTrue(derived.contains("if (entries.size > maxEntries)"))
    }

    @Test
    fun agentModuleDeclaresItsDirectLifecycleDependency() {
        val build = sourceFile(BUILD_PATH)

        assertTrue(build.contains("implementation(libs.androidx.lifecycle.runtime.compose)"))
    }

    private fun sourceFile(relativePath: String): String = File(projectRoot(), relativePath).readText()

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Unable to locate the project root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val BUILD_PATH = "feature/agent/build.gradle.kts"
        const val SCREEN_PATH = "feature/agent/src/main/java/com/muyuchat/feature/agent/AgentScreen.kt"
        const val DERIVED_STATE_PATH =
            "feature/agent/src/main/java/com/muyuchat/feature/agent/AgentPageDerivedState.kt"
    }
}