package com.muyuchat.feature.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract for the page-private system-load sampler.
 *
 * This intentionally does not fake lifecycle execution. It pins the production wiring that
 * makes the sampler lifecycle-aware and that keeps its resume path single-owned.
 */
class SettingsRuntimeSamplingContractTest {

    @Test
    fun `runtime sampling is gated to resumed and refreshes before its first delay`() {
        val source = runtimeSource()

        assertTrue(source.contains("val lifecycleOwner = LocalLifecycleOwner.current"))
        assertTrue(source.contains("LaunchedEffect(lifecycleOwner)"))
        assertTrue(source.contains(RESUMED_GATE))
        assertFalse(
            "the unrestricted LaunchedEffect(Unit) sampler must not return",
            source.contains("LaunchedEffect(Unit)")
        )

        val gateIndex = source.indexOf(RESUMED_GATE)
        val loopIndex = source.indexOf("while (true)", gateIndex)
        val readIndex = source.indexOf("reader.read()", loopIndex)
        val delayIndex = source.indexOf("delay(1000L)", readIndex)

        assertTrue("sampling loop must be inside the RESUMED block", loopIndex > gateIndex)
        assertTrue("resume must perform an immediate read", readIndex > loopIndex)
        assertTrue("the poll interval must follow the immediate read", delayIndex > readIndex)
    }

    @Test
    fun `runtime sampling has one owner and one polling loop`() {
        val source = runtimeSource()

        assertEquals(1, occurrences(source, "LaunchedEffect("))
        assertEquals(1, occurrences(source, "SystemLoadReader(context.applicationContext)"))
        assertEquals(1, occurrences(source, "while (true)"))
        assertEquals(1, occurrences(source, "delay(1000L)"))
    }

    @Test
    fun `settings module declares lifecycle sampling dependencies explicitly`() {
        val build = sourceFile(BUILD_PATH)

        assertTrue(build.contains("implementation(libs.androidx.lifecycle.runtime.compose)"))
        assertTrue(build.contains("implementation(libs.androidx.lifecycle.runtime.ktx)"))
    }

    @Test
    fun `runtime load defaults and unavailable presentation remain intact`() {
        val source = runtimeSource()

        assertEquals(1, occurrences(source, "SystemLoadSnapshot()"))
        assertEquals(5, occurrences(source, "\"不可用\""))
    }

    private fun runtimeSource(): String = sourceFile(RUNTIME_SOURCE_PATH)

    private fun occurrences(source: String, value: String): Int =
        Regex(Regex.escape(value)).findAll(source).count()

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
        const val BUILD_PATH = "feature/settings/build.gradle.kts"
        const val RUNTIME_SOURCE_PATH =
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsRuntimeScreen.kt"
        const val RESUMED_GATE =
            "lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED)"
    }
}