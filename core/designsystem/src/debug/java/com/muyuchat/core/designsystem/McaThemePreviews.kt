package com.muyuchat.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * State samples for the theme, one per scheme.
 *
 * The repository declared ui-tooling-preview in app and in all four feature modules but had no
 * @Preview anywhere, so there was no way to look at a theme state without building and running
 * the app. These two are the samples the plan asks for: the light and dark schemes rendered
 * side by side as separate previews.
 *
 * They live in src/debug rather than src/main so nothing here reaches a release build. A
 * @Preview function is never called at runtime, but it is still compiled into whatever variant
 * it belongs to, and this module has no reason to carry sample UI in release.
 *
 * The sample shows the colour roles the theme actually sets, rather than a generic gallery.
 * McaTheme sets seventeen roles per scheme and no typography or shape, so those are the
 * seventeen a reviewer needs to see. Text is drawn on each surface so the on-colour roles are
 * visible against the colour they pair with.
 *
 * This is not a rendering test. Nothing asserts on the output; the previews only make the state
 * observable to a person. The plan's screenshot-equivalence exit condition still needs a device.
 */
@Composable
private fun ThemeStateSample() {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxSize(), color = scheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RoleRow("primary", scheme.primary, scheme.onPrimary)
            RoleRow("primaryContainer", scheme.primaryContainer, scheme.onPrimaryContainer)
            RoleRow("secondary", scheme.secondary, scheme.onSecondary)
            RoleRow("secondaryContainer", scheme.secondaryContainer, scheme.onSecondaryContainer)
            RoleRow("surface", scheme.surface, scheme.onSurface)
            RoleRow("surfaceVariant", scheme.surfaceVariant, scheme.onSurfaceVariant)
            RoleRow("error", scheme.error, scheme.onError)
            OutlineRow(scheme.outline)
            Text(
                text = "onBackground",
                color = scheme.onBackground,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun RoleRow(label: String, container: Color, content: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = container,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label,
            color = content,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun OutlineRow(outline: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "outline",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyMedium
        )
        Column(
            modifier = Modifier
                .size(width = 64.dp, height = 24.dp)
                .background(outline)
        ) {}
    }
}

@Preview(name = "McaTheme light", showBackground = true)
@Composable
private fun McaThemeLightPreview() {
    McaTheme(darkTheme = false) { ThemeStateSample() }
}

@Preview(name = "McaTheme dark", showBackground = true)
@Composable
private fun McaThemeDarkPreview() {
    McaTheme(darkTheme = true) { ThemeStateSample() }
}
