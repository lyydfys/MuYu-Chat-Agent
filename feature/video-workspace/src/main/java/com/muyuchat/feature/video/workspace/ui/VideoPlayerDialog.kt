package com.muyuchat.feature.video.workspace.ui

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.McaIconSize
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaShape
import com.muyuchat.core.designsystem.McaSlider
import com.muyuchat.core.video.contract.VideoMediaProjection
import kotlinx.coroutines.delay

/**
 * Playback surface of one published video (V1-05).
 *
 * Uses the platform [VideoView] (no Media3 dependency) bound to the owner's content URI. The
 * player owns nothing of the generation job: closing the dialog, leaving the page or a lifecycle
 * stop only pauses/stops this view. A broken file shows the platform error with close/retry, and
 * every path releases the surface via [AndroidView]'s `onRelease` plus a disposal guard.
 */
@Composable
internal fun VideoPlayerDialog(
    media: VideoMediaProjection,
    onDismiss: () -> Unit,
) {
    val referenceUri = remember(media.reference) {
        runCatching { Uri.parse(media.reference) }.getOrNull()
    }
    val validReference = referenceUri != null &&
        referenceUri.scheme.equals("content", ignoreCase = true)

    var attempt by remember { mutableStateOf(0) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var prepared by remember(attempt) { mutableStateOf(false) }
    var playing by remember(attempt) { mutableStateOf(false) }
    var durationMs by remember(attempt) { mutableLongStateOf(media.durationUs / 1_000L) }
    var positionMs by remember(attempt) { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValueMs by remember { mutableFloatStateOf(0f) }
    var error by remember(attempt) {
        mutableStateOf(
            if (validReference) null else "视频引用无效：播放需要 app 提供的 content URI",
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                videoView?.pause()
                playing = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            videoView?.pause()
            videoView?.stopPlayback()
            videoView = null
        }
    }

    LaunchedEffect(playing, prepared, error) {
        while (playing && prepared && error == null) {
            val view = videoView
            if (view != null && !dragging) {
                positionMs = view.currentPosition.toLong().coerceAtLeast(0L)
                dragValueMs = positionMs.toFloat()
            }
            delay(200)
        }
    }

    fun togglePlayback() {
        val view = videoView ?: return
        if (!prepared || error != null) return
        if (view.isPlaying) {
            view.pause()
            positionMs = view.currentPosition.toLong().coerceAtLeast(0L)
            playing = false
        } else {
            if (durationMs > 0L && view.currentPosition >= durationMs - 250L) {
                view.seekTo(0)
                positionMs = 0L
            }
            view.start()
            playing = true
        }
    }

    Dialog(
        onDismissRequest = {
            videoView?.pause()
            videoView?.stopPlayback()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 24.dp)
                .testTag("video.player"),
            color = MaterialTheme.colorScheme.surface,
            shape = McaShape.Sheet,
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "播放视频",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    McaIconButton(
                        contentDescription = "关闭播放器",
                        onClick = onDismiss,
                        glyphSize = McaIconSize.M,
                        modifier = Modifier.testTag("video.player_close"),
                    ) {
                        Icon(McaIcons.Close, contentDescription = null)
                    }
                }
                Spacer(Modifier.height(8.dp))
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    val aspect = if (media.width > 0L && media.height > 0L) {
                        media.width.toFloat() / media.height.toFloat()
                    } else {
                        16f / 9f
                    }
                    val videoHeight = (maxWidth / aspect.coerceIn(0.45f, 2.2f))
                        .coerceIn(180.dp, 420.dp)
                    if (validReference && error == null) {
                        key(attempt) {
                            AndroidView(
                                factory = { viewContext ->
                                    VideoView(viewContext).apply {
                                        setBackgroundColor(android.graphics.Color.BLACK)
                                        setOnPreparedListener { player ->
                                            prepared = true
                                            durationMs = player.duration.toLong().coerceAtLeast(0L)
                                            seekTo(1)
                                        }
                                        setOnCompletionListener {
                                            playing = false
                                            positionMs = durationMs
                                            dragValueMs = durationMs.toFloat()
                                        }
                                        setOnErrorListener { _, what, extra ->
                                            error = "视频无法播放（播放器错误 $what/$extra）"
                                            prepared = false
                                            playing = false
                                            true
                                        }
                                        referenceUri?.let { uri -> setVideoURI(uri) }
                                    }.also { view -> videoView = view }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(videoHeight),
                                onRelease = { view ->
                                    view.pause()
                                    view.stopPlayback()
                                },
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(videoHeight),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                McaIcons.BrokenImage,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(McaIconSize.L),
                            )
                        }
                    }
                }

                if (error != null) {
                    Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .testTag("video.player_error")
                            .semantics { contentDescription = "播放错误：${error.orEmpty()}" },
                    )
                } else if (!prepared) {
                    Text(
                        "正在准备播放…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                Spacer(Modifier.height(4.dp))
                McaSlider(
                    value = if (dragging) dragValueMs else positionMs.toFloat(),
                    onValueChange = { value ->
                        dragging = true
                        dragValueMs = value
                    },
                    onValueChangeFinished = {
                        val target = dragValueMs.toLong().coerceIn(0L, durationMs)
                        videoView?.seekTo(target.toInt())
                        positionMs = target
                        dragging = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("video.player_seek")
                        .semantics {
                            contentDescription = "播放进度"
                            stateDescription = formatVideoClock(positionMs, durationMs)
                        },
                    enabled = prepared && error == null && durationMs > 0L,
                    valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val canPlay = prepared && error == null
                    A11yLabeledControl(
                        contentDescription = if (playing) "暂停播放" else "开始播放",
                        stateDescription = if (playing) "正在播放" else "已暂停",
                        enabled = canPlay,
                        onClick = { togglePlayback() },
                    ) {
                        McaButton(
                            onClick = { togglePlayback() },
                            style = McaButtonStyle.Tonal,
                            enabled = canPlay,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("video.player_play_pause"),
                        ) {
                            Text(if (playing) "暂停" else "播放")
                        }
                    }
                    Text(
                        formatVideoClock(positionMs, durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .testTag("video.player_progress")
                            .semantics { contentDescription = "播放进度 ${formatVideoClock(positionMs, durationMs)}" },
                    )
                    Spacer(Modifier.weight(1f))
                    if (error != null) {
                        val retryPrepare: () -> Unit = {
                            attempt += 1
                            dragging = false
                            dragValueMs = 0f
                        }
                        A11yLabeledControl(
                            contentDescription = "重新准备视频播放",
                            stateDescription = "当前播放失败",
                            enabled = validReference,
                            onClick = retryPrepare,
                        ) {
                            McaButton(
                                onClick = retryPrepare,
                                style = McaButtonStyle.Text,
                                enabled = validReference,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text("重试")
                            }
                        }
                    }
                }
                Text(
                    "时长 ${formatVideoDuration(media.durationUs)} · ${formatVideoResolution(media.width, media.height)} · ${media.fps} fps",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
