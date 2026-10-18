package com.muyuchat.feature.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the compact status ball's lifecycle ownership without faking lifecycle execution. */
class ChatDeviceStatusSamplingContractTest {
    @Test
    fun `compact sampling is resumed bounded and refreshes immediately`() {
        val source = sourceFile(SOURCE_PATH)
        assertTrue(source.contains("val lifecycleOwner = LocalLifecycleOwner.current"))
        assertTrue(source.contains(RESUMED_GATE))
        assertFalse(source.contains("LaunchedEffect(context) {"))

        val gate = source.indexOf(RESUMED_GATE)
        val loop = source.indexOf("while (true)", gate)
        val read = source.indexOf("readChatDeviceMemorySample()", loop)
        val delay = source.indexOf("delay(5_000L)", read)
        assertTrue(loop > gate)
        assertTrue(read > loop)
        assertTrue(delay > read)
    }

    @Test
    fun `there remains one memory sample owner and one interval`() {
        val source = sourceFile(SOURCE_PATH)
        assertEquals(1, occurrences(source, "readChatDeviceMemorySample()"))
        assertEquals(1, occurrences(source, "delay(5_000L)"))
    }

    @Test
    fun `large text reduces status ball detail instead of clipping`() {
        assertEquals(
            FloatingDeviceStatusBallTier.STANDARD,
            floatingDeviceStatusBallTierForFontScale(FloatingDeviceStatusBallTier.FULL, 1.5f)
        )
        assertEquals(
            FloatingDeviceStatusBallTier.COMPACT,
            floatingDeviceStatusBallTierForFontScale(FloatingDeviceStatusBallTier.FULL, 2f)
        )
        assertEquals(
            FloatingDeviceStatusBallTier.STANDARD,
            floatingDeviceStatusBallTierForFontScale(FloatingDeviceStatusBallTier.STANDARD, 1.3f)
        )
    }

    @Test
    fun `chat module declares lifecycle sampling dependencies explicitly`() {
        val build = sourceFile(BUILD_PATH)
        assertTrue(build.contains("implementation(libs.androidx.lifecycle.runtime.compose)"))
        assertTrue(build.contains("implementation(libs.androidx.lifecycle.runtime.ktx)"))
    }

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
        const val BUILD_PATH = "feature/chat/build.gradle.kts"
        const val SOURCE_PATH =
            "feature/chat/src/main/java/com/muyuchat/feature/chat/ChatDeviceStatusScreens.kt"
        const val RESUMED_GATE =
            "lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED)"
    }
}
