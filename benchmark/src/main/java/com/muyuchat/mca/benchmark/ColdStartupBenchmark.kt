package com.muyuchat.mca.benchmark

import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S00: cold start of the real production MainActivity.
 *
 * Measures time-to-first-frame (and ttid) with [StartupTimingMetric]. The benchmark variant is
 * non-debuggable and R8-minified, so this is the closest local proxy to a shipped release build.
 * It does not measure model load, first token or readiness of the chat backend; those are
 * separate stages (V9 10 / 7.4).
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class ColdStartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() {
        // Recorded in the instrumentation output so a trace cannot be attributed to a different
        // compile state later.
        println("ColdStartupBenchmark: ${BenchmarkArguments.describe()}")

        benchmarkRule.measureRepeated(
            packageName = BenchmarkTarget.PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = BenchmarkArguments.compilationMode(),
            startupMode = StartupMode.COLD,
            iterations = BenchmarkArguments.iterations(),
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            // The startup metric itself ends at the first drawn frame; this guard only refuses
            // to report a launch that never reached an interactive chat surface.
            waitForChatSurface()
        }
    }
}
