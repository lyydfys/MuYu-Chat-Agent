package com.muyuchat.mca.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S08: vertical scrolling through the image gallery, measured with [FrameTimingMetric].
 *
 * Fixture contract: the benchmark target already contains at least 20 image assets so the gallery
 * is actually scrollable; a missing/short gallery fails the iteration instead of measuring an
 * empty screen. Each iteration restarts the Activity (StartupMode.WARM), navigates through the
 * production history drawer entry, resets the list to the top and then flings down several
 * screens.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class ImageGalleryScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun imageGalleryFling() {
        println("ImageGalleryScrollBenchmark: ${BenchmarkArguments.describe()}")

        benchmarkRule.measureRepeated(
            packageName = BenchmarkTarget.PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = BenchmarkArguments.compilationMode(),
            // WARM restarts the Activity (not the process) each iteration so every measured pass
            // starts from the same navigation state.
            startupMode = StartupMode.WARM,
            iterations = BenchmarkArguments.iterations(),
            setupBlock = {
                startActivityAndWait()
                waitForChatSurface()
                openImageGallery()
                resetGalleryToTop()
            },
        ) {
            flingGalleryDown()
        }
    }
}
