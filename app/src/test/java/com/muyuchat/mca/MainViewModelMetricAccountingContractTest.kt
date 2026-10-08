package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins accounting metrics for `MainViewModel.kt` to prevent metric regressions.
 *
 * Specifically verifies:
 * - `this@MainViewModel` count == 0 (preventing regressions like 841fa494 which spiked to 69)
 * - Anonymous `object :` business instances count == 0
 * - `_uiState.update` count == 0
 *
 * Accounting matches `scripts/mca_scorecard.ps1`.
 */
class MainViewModelMetricAccountingContractTest {

    private val mainViewModelLines: List<String> by lazy {
        sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
            .trimEnd('\n')
            .split("\n")
    }

    @Test
    fun thisAtMainViewModelCountIsZero() {
        val matches = mainViewModelLines.filter { it.contains("this@MainViewModel") }
        assertEquals(
            "MainViewModel.kt must not contain 'this@MainViewModel' references. Found: $matches",
            0,
            matches.size
        )
    }

    @Test
    fun anonymousObjectCountIsZero() {
        val matches = mainViewModelLines.filter { it.contains("object :") }
        assertEquals(
            "MainViewModel.kt must not contain anonymous 'object :' instances. Found: $matches",
            0,
            matches.size
        )
    }

    @Test
    fun uiStateUpdateCountIsZero() {
        val regex = Regex("""_uiState\.update""")
        val matches = mainViewModelLines.filter { regex.containsMatchIn(it) }
        assertEquals(
            "MainViewModel.kt must not contain '_uiState.update' calls. Found: $matches",
            0,
            matches.size
        )
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            File(root, relativePath).takeIf(File::isFile)?.let { return it.readText(Charsets.UTF_8) }
            directory = root.parentFile
        }
        error("Unable to locate $relativePath")
    }
}
