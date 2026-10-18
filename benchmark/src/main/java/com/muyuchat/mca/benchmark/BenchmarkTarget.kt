package com.muyuchat.mca.benchmark

import android.os.Bundle
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Fixed inputs for the UI Macrobenchmark harness.
 *
 * [PACKAGE_NAME] must stay in sync with :app's `benchmark` build type:
 * `applicationId = com.muyuchat.mca` + `applicationIdSuffix = ".benchmark"`.
 */
internal object BenchmarkTarget {
    const val PACKAGE_NAME = "com.muyuchat.mca.benchmark"

    /** Plain UI waits. */
    const val UI_TIMEOUT_MS = 10_000L

    /** Time allowed for tapping 发送 to produce the stop state. */
    const val GENERATION_START_TIMEOUT_MS = 5_000L

    /** Default upper bound for one ~500-token stream; override with `-e mca.generationTimeoutMs`. */
    const val GENERATION_TIMEOUT_MS = 180_000L

    /** V9 7.5: at least 10 valid iterations for a stable scene. Override with `-e mca.iterations`. */
    const val DEFAULT_ITERATIONS = 10

    /** Scrolling scenario: how many full-screen flings make one measured pass. */
    const val GALLERY_FLINGS_PER_ITERATION = 3
}

/**
 * Instrumentation-argument controlled knobs.
 *
 * Examples:
 *   adb shell am instrument -w \
 *     -e mca.iterations 10 \
 *     -e mca.compilationMode full \
 *     com.muyuchat.mca.benchmark/androidx.test.runner.AndroidJUnitRunner
 *
 * The values are recorded by the caller; a run must not be compared across different
 * compilation modes (V9 23.3).
 */
internal object BenchmarkArguments {
    private const val DEFAULT_STREAMING_PROMPT =
        "请用大约五百个 token 写一段关于山间清晨的中文散文，句子连贯，不要列表，不要提前结束。"

    private val arguments: Bundle
        get() = InstrumentationRegistry.getArguments()

    fun iterations(): Int = arguments.getString("mca.iterations")
        ?.trim()
        ?.toIntOrNull()
        ?.coerceAtLeast(1)
        ?: BenchmarkTarget.DEFAULT_ITERATIONS

    fun generationTimeoutMs(): Long = arguments.getString("mca.generationTimeoutMs")
        ?.trim()
        ?.toLongOrNull()
        ?.coerceAtLeast(BenchmarkTarget.GENERATION_START_TIMEOUT_MS)
        ?: BenchmarkTarget.GENERATION_TIMEOUT_MS

    fun streamingPrompt(): String = arguments.getString("mca.streamingPrompt")
        ?.takeIf { it.isNotBlank() }
        ?: DEFAULT_STREAMING_PROMPT

    /**
     * `default` (fresh-install-like partial compile), `full`, `none` or `partial`.
     * Anything else is rejected instead of silently measuring a different compile state.
     * (`CompilationMode.Ignore` is intentionally not exposed: it is still an experimental
     * Macrobenchmark API and opt-in annotations in a measurement harness hide real compile
     * state differences, which is exactly what this argument exists to control.)
     */
    fun compilationMode(): CompilationMode = when (
        arguments.getString("mca.compilationMode")?.trim()?.lowercase() ?: "default"
    ) {
        "default" -> CompilationMode.DEFAULT
        "partial" -> CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Disable)
        "full" -> CompilationMode.Full()
        "none" -> CompilationMode.None()
        else -> error(
            "Unknown -e mca.compilationMode value; expected default|partial|full|none."
        )
    }

    fun describe(): String =
        "iterations=${iterations()}, compilationMode=${compilationMode()}, " +
            "generationTimeoutMs=${generationTimeoutMs()}"
}
