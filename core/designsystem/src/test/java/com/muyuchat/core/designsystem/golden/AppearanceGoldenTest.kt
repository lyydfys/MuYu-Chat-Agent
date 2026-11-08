package com.muyuchat.core.designsystem.golden

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muyuchat.core.designsystem.appearance.McaAppearanceSection
import com.muyuchat.core.designsystem.appearance.McaAppearanceThemeChoice
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual baselines for the appearance section inside the settings surface.
 *
 * Two selections are captured, not three: `SYSTEM` is the fresh-install default and `DARK` is the
 * choice whose selected row has to stay legible on a dark surface. `LIGHT` renders the same
 * geometry as `SYSTEM` with a different glyph, so it is covered by the two rows that are pinned
 * rather than by a third near-duplicate image.
 *
 * The section is stateless; the caller owns the selection, so a baseline drives every state without
 * an Activity or a preference store.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
public class AppearanceGoldenTest {

    /** Inset matching the settings surface the section is placed in, so the baseline shows real margins. */
    private val surfaceInset = 24.dp

    @Test
    public fun `外观分区在浅色主题下选中跟随系统的外观`() {
        golden(name = "appearance-section-system-light", dark = false) {
            McaAppearanceSection(
                selected = McaAppearanceThemeChoice.SYSTEM,
                onSelect = {},
                modifier = Modifier
                    .fillMaxSize()
                    .padding(surfaceInset)
            )
        }
    }

    @Test
    public fun `外观分区在深色主题下选中跟随系统的外观`() {
        golden(name = "appearance-section-system-dark", dark = true) {
            McaAppearanceSection(
                selected = McaAppearanceThemeChoice.SYSTEM,
                onSelect = {},
                modifier = Modifier
                    .fillMaxSize()
                    .padding(surfaceInset)
            )
        }
    }

    @Test
    public fun `外观分区在浅色主题下选中深色的外观`() {
        golden(name = "appearance-section-dark-light", dark = false) {
            McaAppearanceSection(
                selected = McaAppearanceThemeChoice.DARK,
                onSelect = {},
                modifier = Modifier
                    .fillMaxSize()
                    .padding(surfaceInset)
            )
        }
    }

    @Test
    public fun `外观分区在深色主题下选中深色的外观`() {
        golden(name = "appearance-section-dark-dark", dark = true) {
            McaAppearanceSection(
                selected = McaAppearanceThemeChoice.DARK,
                onSelect = {},
                modifier = Modifier
                    .fillMaxSize()
                    .padding(surfaceInset)
            )
        }
    }
}
