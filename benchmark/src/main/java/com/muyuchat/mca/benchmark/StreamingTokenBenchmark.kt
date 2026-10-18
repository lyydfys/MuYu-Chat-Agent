package com.muyuchat.mca.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S01/S02: one ~500-token local streaming response, measured with [FrameTimingMetric].
 *
 * Fixture contract (not enforceable from this process, therefore documented and checked as far as
 * the UI allows):
 *  - the benchmark target (`com.muyuchat.mca.benchmark`) has a loaded local chat model, or a
 *    configured cloud chat model;
 *  - that backend is configured for roughly 500 output tokens for this prompt;
 *  - no other heavy job is running on the device.
 *
 * The measured window is send-tap -> stop-state disappearance. The harness proves the generation
 * actually started and reached a terminal state; it cannot count tokens or verify the reply text.
 * A run whose response is shorter than the fixture contract is still measured, and must be
 * reported together with the prompt/max-token settings rather than labelled "500 tokens".
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class StreamingTokenBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun streamingFiveHundredTokenResponse() {
        println("StreamingTokenBenchmark: ${BenchmarkArguments.describe()}")

        benchmarkRule.measureRepeated(
            packageName = BenchmarkTarget.PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = BenchmarkArguments.compilationMode(),
            // Non-startup benchmark: keep the process alive between iterations so a loaded local
            // model is not reloaded inside the measured window (V9: do not mix model-load into UI
            // frame numbers). A fresh chat is created in setupBlock instead.
            startupMode = null,
            iterations = BenchmarkArguments.iterations(),
            setupBlock = {
                startActivityAndWait()
                waitForChatSurface()
                requireChatBackendFixture()
                startFreshChatIfAvailable()
                typePrompt(BenchmarkArguments.streamingPrompt())
            },
        ) {
            pressSend()
            check(awaitGenerationStart()) {
                "Tapping 发送 produced no streaming generation within " +
                    "${BenchmarkTarget.GENERATION_START_TIMEOUT_MS}ms even though a ready backend " +
                    "was visible in setup. This iteration is a FAILED run, not a frame sample."
            }
            awaitGenerationTerminal()
        }
    }
}
