package com.muyuchat.feature.settings.golden

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.muyuchat.core.designsystem.McaTheme
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The one device geometry every settings golden is rendered at.
 *
 * These values are copied from `:core:designsystem`'s `GoldenDevice` **on purpose**: the two
 * modules' baselines must be visually comparable, so a settings screenshot and a design-system
 * screenshot of the same surface have to be the same pixels-per-dp, the same window size and the
 * same font scale. Changing a value here without changing it there makes the two sets incomparable.
 */
internal object SettingsGoldenDevice {
    /** Portrait width in dp. Same narrowest-target width as the design-system baselines. */
    const val WIDTH_DP: Int = 411

    /** Portrait height in dp. */
    const val HEIGHT_DP: Int = 891

    /** Screen density; 420dpi is the Pixel-class default used by the design-system baselines. */
    const val DENSITY_DPI: Int = 420

    /** Font scale, pinned to the platform default so text metrics do not follow the host machine. */
    const val FONT_SCALE: Float = 1.0f

    /** Robolectric qualifier string built from the three values above. */
    const val QUALIFIERS: String = "w${WIDTH_DP}dp-h${HEIGHT_DP}dp-${DENSITY_DPI}dpi"
}

/**
 * Renders [content] and writes (record) or compares (verify) one settings golden PNG called [name].
 *
 * Why this file is a copy of `:core:designsystem`'s `GoldenHarness` rather than an import:
 * that harness lives in the design-system module's **test** source set. A Kotlin test source set is
 * not published and is not visible to any other module, so `:feature:settings`'s tests cannot
 * reference it at all -- not even as an `internal` API. Sharing it would require a dedicated
 * test-fixture module (a `java-test-fixtures`-style or `testFixtures` source set published as an
 * artifact), which is a real build change and was out of scope for this task. When a third module
 * needs goldens, that extraction is the right move; until then two small duplicated harnesses are
 * cheaper than a new module, and the duplication is bounded to this file.
 *
 * The pins and their reasons are identical to the design-system harness:
 *  * **font scale** decides every text line box, so an unpinned runner reflows copy;
 *  * **density** decides how many pixels one dp becomes, so an unpinned runner changes the image
 *    dimensions and the baseline stops being the same image;
 *  * **animation progress** decides which frame is captured. This module needs that pin even more
 *    than the design system did: `SettingsHubScreen`'s hero draws an infinite orbit animation
 *    (`rememberInfiniteTransition`, 28s), which would otherwise be captured at an arbitrary phase
 *    and make the diff flap. Setting the platform animation scales to zero freezes it at its
 *    initial value.
 *
 * The theme is applied through the production entry point [McaTheme], not by rebuilding a colour
 * scheme here: `mcaColorScheme` is `internal` to `:core:designsystem` and therefore not reachable
 * from this module. [McaTheme] applies that same scheme, so the pixels match the design-system
 * goldens while this module never gets a second colour authority.
 *
 * The capture is the Compose surface only; window chrome (status/navigation bars) is not part of the
 * bitmap and is covered by `McaThemeSystemBarContractTest` in `:core:designsystem`.
 *
 * @param name baseline file name without the directory; `.png` is appended when absent.
 * @param dark `true` renders the dark colour scheme, `false` the light one.
 */
internal fun settingsGolden(
    name: String,
    dark: Boolean,
    content: @Composable () -> Unit
) {
    pinGoldenEnvironment()
    captureRoboImage(file = goldenFile(name)) {
        // The host activity uses a translucent theme, so paint an opaque backdrop: a transparent
        // capture would make every diff depend on what the image viewer puts behind it.
        McaTheme(darkTheme = dark) {
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
 * Pins the runner environment. Idempotent; called before every capture because a single test class
 * records several goldens and the values must not drift between them.
 */
private fun pinGoldenEnvironment() {
    // "+" adds these qualifiers to the ones already configured instead of replacing the string.
    RuntimeEnvironment.setQualifiers("+${SettingsGoldenDevice.QUALIFIERS}")
    RuntimeEnvironment.setFontScale(SettingsGoldenDevice.FONT_SCALE)

    val resolver = RuntimeEnvironment.getApplication().contentResolver
    Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    Settings.Global.putFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 0f)
    Settings.Global.putFloat(resolver, Settings.Global.WINDOW_ANIMATION_SCALE, 0f)
}

/**
 * Resolves the baseline file for [name] under the module's `src/test/screenshots`.
 *
 * The Roborazzi Gradle plugin passes the configured output directory as an absolute path in
 * `roborazzi.output.dir`; reading it keeps this harness independent of the JVM working directory
 * and of `roborazzi.record.filePathStrategy`, and makes verify read back the same file.
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
    // No plugin properties (e.g. an IDE runner): walk up until this module's root is found.
    var candidate: File? = File("").absoluteFile
    while (candidate != null) {
        if (File(candidate, "src/main/java/com/muyuchat/feature/settings/SettingsScreens.kt").isFile) {
            return File(candidate, "src/test/screenshots")
        }
        candidate = candidate.parentFile
    }
    return File("src/test/screenshots")
}
