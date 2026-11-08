package com.muyuchat.core.designsystem.golden

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.muyuchat.core.designsystem.mcaColorScheme
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The one device geometry every golden is rendered at.
 *
 * These values are part of the baseline contract, not a preference: changing any of them changes
 * every pixel of every golden, so a change here is a baseline change and must be recorded and
 * reviewed as one.
 */
public object GoldenDevice {
    /** Portrait width in dp. A 411dp-wide phone is the narrowest current MCA target layout. */
    public const val WIDTH_DP: Int = 411

    /** Portrait height in dp. Long enough that a first-run page fits without scrolling. */
    public const val HEIGHT_DP: Int = 891

    /** Screen density. 420dpi is the Pixel-class default and keeps vector glyphs at their design size. */
    public const val DENSITY_DPI: Int = 420

    /**
     * Font scale. Pinned to the platform default so text metrics do not follow the host machine.
     * Large-font goldens are a separate, explicit set rather than an accident of the runner.
     */
    public const val FONT_SCALE: Float = 1.0f

    /** Robolectric qualifier string built from the three values above. */
    public const val QUALIFIERS: String = "w${WIDTH_DP}dp-h${HEIGHT_DP}dp-${DENSITY_DPI}dpi"
}

/**
 * Renders [content] and writes (record) or compares (verify) one golden PNG called [name].
 *
 * Why each pin exists — these are the three classic sources of "it passed on my machine":
 *
 *  * **Font scale** decides every text line box, so an unpinned runner (or an emulator a human
 *    enlarged fonts on) reflows copy and shifts everything below it. [GoldenDevice.FONT_SCALE]
 *    fixes it at the platform default.
 *  * **Density** decides how many pixels one dp becomes. At a different density the same layout
 *    produces a different bitmap size, so the baseline would not even be the same image
 *    dimensions. [GoldenDevice.DENSITY_DPI] fixes it.
 *  * **Animation progress** decides *which frame* is captured. A fade or a slide that is 30%
 *    through on a fast machine and 80% through on a loaded one produces two different, both
 *    "correct" images, and the diff then flaps forever. The harness sets the platform animation
 *    scales to zero before the surface is composed, so every animation is captured at its settled
 *    state. Compose 1.11 reads exactly these scales for `MotionDurationScale` (it no longer exposes
 *    a `LocalMotionDurationScale` CompositionLocal), which is why the pin is done through
 *    `Settings.Global` rather than a CompositionLocal.
 *
 * The capture is the Compose surface only. Window chrome (status and navigation bars) is not part
 * of the bitmap and is pinned separately by `McaThemeSystemBarContractTest`.
 *
 * @param name baseline file name without the directory; `.png` is appended when absent.
 * @param dark `true` renders the dark colour scheme, `false` the light one.
 */
public fun golden(
    name: String,
    dark: Boolean,
    content: @Composable () -> Unit
): Unit {
    pinGoldenEnvironment()
    captureRoboImage(file = goldenFile(name)) {
        // The host activity uses a translucent theme, so the golden paints its own opaque backdrop
        // in the theme's background colour. A transparent capture would make every diff depend on
        // what the image viewer fills behind it.
        MaterialTheme(colorScheme = mcaColorScheme(darkTheme = dark)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                content()
            }
        }
    }
}

/**
 * Pins the runner environment. Idempotent, and called before every capture because a single test
 * class records several goldens and the values must not drift between them.
 */
private fun pinGoldenEnvironment() {
    // "+" adds these qualifiers to the ones already configured instead of replacing the whole
    // qualifier string, so the SDK/theme qualifiers Robolectric set up survive.
    RuntimeEnvironment.setQualifiers("+${GoldenDevice.QUALIFIERS}")
    RuntimeEnvironment.setFontScale(GoldenDevice.FONT_SCALE)

    val resolver = RuntimeEnvironment.getApplication().contentResolver
    Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    Settings.Global.putFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 0f)
    Settings.Global.putFloat(resolver, Settings.Global.WINDOW_ANIMATION_SCALE, 0f)
}

/**
 * Resolves the baseline file for [name].
 *
 * The Roborazzi Gradle plugin hands the configured output directory (`src/test/screenshots`) to the
 * test JVM as an absolute path. Reading it keeps this harness independent of the JVM working
 * directory and of the `roborazzi.record.filePathStrategy` property, and it means the same file is
 * read back during verify. The fallback only exists for a run without the plugin's properties.
 */
private fun goldenFile(name: String): File {
    val fileName = if (name.endsWith(".png", ignoreCase = true)) name else "$name.png"
    val directory = goldensDirectory()
    directory.mkdirs()
    return File(directory, fileName)
}

private fun goldensDirectory(): File {
    val configured = System.getProperty("roborazzi.output.dir")
    if (!configured.isNullOrBlank()) {
        return File(configured)
    }
    // No plugin properties: walk up from the working directory until the designsystem module root
    // is found. Keeps the same directory when the tests are driven by an IDE runner.
    var candidate: File? = File("").absoluteFile
    while (candidate != null) {
        if (File(candidate, "src/main/java/com/muyuchat/core/designsystem/McaTheme.kt").isFile) {
            return File(candidate, "src/test/screenshots")
        }
        candidate = candidate.parentFile
    }
    return File("src/test/screenshots")
}
