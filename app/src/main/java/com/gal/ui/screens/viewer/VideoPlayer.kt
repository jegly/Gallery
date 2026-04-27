package com.gal.ui.screens.viewer

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.gal.model.Media
import com.gal.security.UriValidator
import kotlinx.coroutines.delay

@Composable
fun LocalVideoPlayer(
    media: Media,
    modifier: Modifier = Modifier,
    onControlsToggled: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val validator = remember { UriValidator() }
    val safeUri = remember(media.uri) { validator.requireLocal(media.uri) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }

    LaunchedEffect(safeUri) {
        exoPlayer.setMediaItem(MediaItem.fromUri(safeUri))
        exoPlayer.prepare()
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    // Player state for custom controls
    var isPlaying by remember { mutableStateOf(true) }
    var isEnded by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(1L) }
    var showControls by remember { mutableStateOf(true) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }
    var isMuted by remember { mutableStateOf(false) }

    LaunchedEffect(isMuted) { exoPlayer.volume = if (isMuted) 0f else 1f }

    // Poll player state every 500ms while playing
    LaunchedEffect(isPlaying, isScrubbing) {
        while (isPlaying && !isScrubbing) {
            position = exoPlayer.currentPosition.coerceAtLeast(0L)
            duration = exoPlayer.duration.coerceAtLeast(1L)
            isEnded = exoPlayer.playbackState == Player.STATE_ENDED
            delay(500)
        }
    }

    // Auto-hide controls after 3s
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(3000)
            showControls = false
            onControlsToggled(false)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) {
                val next = !showControls
                showControls = next
                onControlsToggled(next)
            },
    ) {
        // Video surface — no built-in controller
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false // we draw our own
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Custom controls overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                // Scrub bar
                Slider(
                    value = if (isScrubbing) scrubPosition
                            else position.toFloat() / duration.toFloat(),
                    onValueChange = { v ->
                        isScrubbing = true
                        scrubPosition = v
                    },
                    onValueChangeFinished = {
                        exoPlayer.seekTo((scrubPosition * duration).toLong())
                        position = (scrubPosition * duration).toLong()
                        isScrubbing = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )

                // Controls: Mute | Position | -10s | Play/Pause | +10s | Duration
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = { isMuted = !isMuted }) {
                        Icon(
                            imageVector = if (isMuted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                            contentDescription = if (isMuted) "Unmute" else "Mute",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Text(
                        text = formatMs(position),
                        color = Color.White,
                        fontSize = 12.sp,
                    )
                    IconButton(onClick = {
                        exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L))
                    }) {
                        Icon(
                            Icons.Outlined.Replay10, "Rewind 10s",
                            tint = Color.White, modifier = Modifier.size(26.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            when {
                                isEnded -> {
                                    exoPlayer.seekTo(0)
                                    exoPlayer.play()
                                    isEnded = false
                                    isPlaying = true
                                }
                                exoPlayer.isPlaying -> {
                                    exoPlayer.pause()
                                    isPlaying = false
                                }
                                else -> {
                                    exoPlayer.play()
                                    isPlaying = true
                                }
                            }
                        },
                    ) {
                        Icon(
                            imageVector = when {
                                isEnded -> Icons.Outlined.Replay
                                isPlaying -> Icons.Outlined.Pause
                                else -> Icons.Outlined.PlayArrow
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    IconButton(onClick = {
                        exoPlayer.seekTo((exoPlayer.currentPosition + 10_000L).coerceAtMost(exoPlayer.duration))
                    }) {
                        Icon(
                            Icons.Outlined.Forward10, "Forward 10s",
                            tint = Color.White, modifier = Modifier.size(26.dp),
                        )
                    }
                    Text(
                        text = formatMs(duration),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    val m = s / 60
    val h = m / 60
    return if (h > 0) "%d:%02d:%02d".format(h, m % 60, s % 60)
    else "%d:%02d".format(m, s % 60)
}
