package com.muyuchat.feature.video.workspace.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaAdaptiveTwoPane

/**
 * The page's outer layout for the current window class.
 *
 * The page body itself is passed in as two slots — the settings half (mode, engine/decode, model
 * assets, onboarding) and the workflow half (recent runs, input/import, canvas or chain, output
 * preview) — so this file only decides *where* those halves go and never duplicates their content.
 *
 * COMPACT (the normal phone) takes the layout it always had: one scrolling column, same padding,
 * same tag, the settings half followed by the workflow half, and no extra wrapper node.
 * MEDIUM keeps that single column but caps and centres only the settings half, so the canvas keeps
 * the full window width. EXPANDED puts the settings half beside the workflow half through the
 * shared [McaAdaptiveTwoPane]; each side scrolls on its own and the canvas/output side carries no
 * reading ceiling, so it keeps every dp the window has beyond the settings rail.
 */
@Composable
internal fun VideoWorkspacePanes(
    layout: VideoWorkspaceAdaptiveLayout,
    settings: @Composable ColumnScope.() -> Unit,
    workflow: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    if (layout.twoPane) {
        McaAdaptiveTwoPane(
            modifier = modifier.testTag("video.workspace"),
            twoPane = layout.twoPane,
            railMaxWidth = layout.settingsRailMaxWidth,
            minContentWidth = layout.canvasMinWidth,
            rail = { ScrollingPane(content = settings) },
            content = { ScrollingPane(content = workflow) },
        )
        return
    }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .testTag("video.workspace")
            .padding(horizontal = 16.dp),
    ) {
        if (layout.limitsFormWidth) {
            Column(
                // widthIn before fillMaxWidth: the other order would hand the child an already
                // fixed 0..window min/max, and the ceiling would never bind.
                modifier = Modifier
                    .widthIn(max = layout.formMaxWidth)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally),
            ) {
                settings()
            }
        } else {
            settings()
        }
        workflow()
    }
}

/** One independently scrolling pane of the two-pane layout, inset like the phone page. */
@Composable
private fun ScrollingPane(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        content()
    }
}
