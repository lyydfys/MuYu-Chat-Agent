package com.muyuchat.feature.video.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muyuchat.core.video.contract.VideoRunProjection
import com.muyuchat.core.video.contract.VideoRunState
import com.muyuchat.feature.video.workspace.VideoWorkspaceViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Newest-first list of the owner's runs. The page edits the graph; this is the only place a caller
 * (UI or Local API) can see that a submitted run exists and how it ended, without leaving the page.
 */
@Composable
internal fun VideoRecentRunsSection(viewModel: VideoWorkspaceViewModel) {
    val runs by viewModel.runs.collectAsState()
    if (runs.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .testTag("video.recent_runs"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "最近运行",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        runs.take(6).forEach { run -> RecentRunRow(run) }
    }
}

@Composable
private fun RecentRunRow(run: VideoRunProjection) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            run.state.name,
            style = MaterialTheme.typography.labelMedium,
            color = when (run.state) {
                VideoRunState.COMPLETED -> MaterialTheme.colorScheme.primary
                VideoRunState.FAILED, VideoRunState.INTERRUPTED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            recentRunSummary(run),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One line of facts for a run row; pure so JVM tests can pin the format. */
internal fun recentRunSummary(run: VideoRunProjection): String {
    val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(run.createdAtMs))
    val step = run.step?.let { current ->
        run.stepTotal?.let { total -> "$current/$total" } ?: current.toString()
    }
    val errorCode = run.errorCode
    val outcome = when {
        errorCode != null -> errorCode.name
        run.outputMediaId != null -> "已出片"
        else -> run.stage.name
    }
    return buildString {
        append(time)
        append(" · ").append(run.stage.name)
        if (step != null) append(" · ").append(step)
        append(" · ").append(outcome)
        run.runId.value.takeLast(8).let { append(" · ").append(it) }
    }
}
