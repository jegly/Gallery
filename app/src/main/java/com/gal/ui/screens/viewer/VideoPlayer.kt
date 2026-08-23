package com.gal.ui.screens.viewer

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.Rational
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.BrightnessHigh
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.ClosedCaptionOff
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOn
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.gal.ui.theme.Motion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.gal.model.Media
import com.gal.security.UriValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
fun LocalVideoPlayer(
    media: Media,
    modifier: Modifier = Modifier,
    onControlsToggled: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val validator = remember { UriValidator() }
    val safeUri = remember(media.uri) { validator.requireLocal(media.uri) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            repeatMode = Player.REPEAT_MODE_OFF
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

    // ── Player state ────────────────────────────────────────────────────────
    var isPlaying    by remember { mutableStateOf(true) }
    var isEnded      by remember { mutableStateOf(false) }
    var position     by remember { mutableLongStateOf(0L) }
    var duration     by remember { mutableLongStateOf(1L) }
    var showControls by remember { mutableStateOf(true) }
    var isScrubbing  by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }
    var isMuted      by remember { mutableStateOf(false) }
    var speed        by remember { mutableFloatStateOf(1f) }
    var repeatMode   by remember { mutableStateOf(Player.REPEAT_MODE_OFF) }
    var aspectMode   by remember {
        mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    }
    var tracks       by remember { mutableStateOf<Tracks?>(null) }
    var trackParams  by remember { mutableStateOf(TrackSelectionParameters.DEFAULT) }
    var isInPip      by remember { mutableStateOf(false) }

    // Apply mute / speed / repeat / track params side-effectfully
    LaunchedEffect(isMuted)    { exoPlayer.volume = if (isMuted) 0f else 1f }
    LaunchedEffect(speed)      { exoPlayer.setPlaybackSpeed(speed) }
    LaunchedEffect(repeatMode) { exoPlayer.repeatMode = repeatMode }
    LaunchedEffect(trackParams) { exoPlayer.trackSelectionParameters = trackParams }

    // Observe track info
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) { tracks = t }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Poll player state every 500ms while playing
    LaunchedEffect(isPlaying, isScrubbing) {
        while (isPlaying && !isScrubbing) {
            position = exoPlayer.currentPosition.coerceAtLeast(0L)
            duration = exoPlayer.duration.coerceAtLeast(1L)
            isEnded = exoPlayer.playbackState == Player.STATE_ENDED
            delay(500)
        }
    }

    // Auto-hide controls
    LaunchedEffect(showControls, isPlaying, isInPip) {
        if (showControls && isPlaying && !isInPip) {
            delay(3000)
            showControls = false
            onControlsToggled(false)
        }
    }

    // Observe PiP mode changes
    DisposableEffect(activity) {
        if (activity == null) return@DisposableEffect onDispose { }
        val listener = androidx.core.util.Consumer<android.app.PictureInPictureUiState> { }
        // Use Activity.onPictureInPictureModeChanged via a Consumer<PictureInPictureModeChangedInfo>
        val pipListener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
            isInPip = info.isInPictureInPictureMode
            if (!info.isInPictureInPictureMode) showControls = true
        }
        if (activity is androidx.activity.ComponentActivity) {
            activity.addOnPictureInPictureModeChangedListener(pipListener)
            onDispose { activity.removeOnPictureInPictureModeChangedListener(pipListener) }
        } else {
            onDispose { }
        }
    }

    // Gesture overlay state — brightness / volume indicators
    var brightnessOverlay by remember { mutableStateOf<Float?>(null) }
    var volumeOverlay     by remember { mutableStateOf<Float?>(null) }
    var seekToast         by remember { mutableStateOf<String?>(null) }

    // Single-tap → toggle controls. Pulled out so it can be triggered by the gesture
    // layer below (whose detectTapGestures otherwise eats single taps while waiting
    // for a double-tap, leaving the outer Box.clickable starved).
    val toggleControls: () -> Unit = {
        val next = !showControls
        showControls = next
        onControlsToggled(next)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Video surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = aspectMode
                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                }
            },
            update = { it.resizeMode = aspectMode },
            modifier = Modifier.fillMaxSize(),
        )

        // Hide everything in PiP mode
        if (!isInPip) {
            // Gesture layer (single tap toggle + double-tap seek + vertical drag brightness/volume)
            GestureLayer(
                onTap = toggleControls,
                onSeekBy = { delta ->
                    val newPos = (exoPlayer.currentPosition + delta).coerceIn(0L, exoPlayer.duration)
                    exoPlayer.seekTo(newPos)
                    seekToast = if (delta > 0) "+${delta / 1000}s" else "${delta / 1000}s"
                    scope.launch { delay(700); seekToast = null }
                },
                onBrightnessDelta = { dy ->
                    if (activity == null) return@GestureLayer
                    val attrs = activity.window.attributes
                    val current = if (attrs.screenBrightness < 0f) 0.5f else attrs.screenBrightness
                    val next = (current + dy).coerceIn(0f, 1f)
                    attrs.screenBrightness = next
                    activity.window.attributes = attrs
                    brightnessOverlay = next
                    scope.launch { delay(700); brightnessOverlay = null }
                },
                onVolumeDelta = { dy ->
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val target = (cur + (dy * max).toInt()).coerceIn(0, max)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                    volumeOverlay = target / max.toFloat()
                    scope.launch { delay(700); volumeOverlay = null }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // ── Right-side icon column ──────────────────────────────────────
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(end = 12.dp),
            ) {
                RightControlColumn(
                    speed = speed,
                    onSpeedChange = { speed = it },
                    aspectMode = aspectMode,
                    onAspectCycle = {
                        aspectMode = when (aspectMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT  -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else                                    -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    repeatMode = repeatMode,
                    onRepeatCycle = {
                        repeatMode = when (repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                            else                   -> Player.REPEAT_MODE_OFF
                        }
                    },
                    onPip = { activity?.enterPip(exoPlayer) },
                    tracks = tracks,
                    onTrackToggle = { override ->
                        val current = exoPlayer.trackSelectionParameters
                        trackParams = if (override == null) {
                            current.buildUpon().clearOverrides().build()
                        } else {
                            current.buildUpon().setOverrideForType(override).build()
                        }
                    },
                    onCaptureFrame = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                captureFrame(context, safeUri, exoPlayer.currentPosition)
                            }
                            Toast.makeText(
                                context,
                                if (ok) "Frame saved" else "Capture failed",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }

            // ── Bottom transport bar (existing) ─────────────────────────────
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                BottomBar(
                    isMuted = isMuted,
                    onMuteToggle = { isMuted = !isMuted },
                    isPlaying = isPlaying,
                    isEnded = isEnded,
                    onPlayPause = {
                        when {
                            isEnded -> {
                                exoPlayer.seekTo(0); exoPlayer.play()
                                isEnded = false; isPlaying = true
                            }
                            exoPlayer.isPlaying -> { exoPlayer.pause(); isPlaying = false }
                            else -> { exoPlayer.play(); isPlaying = true }
                        }
                    },
                    onSkipBack  = { exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)) },
                    onSkipFwd   = { exoPlayer.seekTo((exoPlayer.currentPosition + 10_000L).coerceAtMost(exoPlayer.duration)) },
                    position = position,
                    duration = duration,
                    sliderValue = if (isScrubbing) scrubPosition
                                  else position.toFloat() / duration.toFloat(),
                    onSliderChange = { v -> isScrubbing = true; scrubPosition = v },
                    onSliderFinished = {
                        exoPlayer.seekTo((scrubPosition * duration).toLong())
                        position = (scrubPosition * duration).toLong()
                        isScrubbing = false
                    },
                )
            }
        }

        // Gesture overlays — always visible while a gesture is active
        brightnessOverlay?.let {
            CenterOverlay(icon = Icons.Outlined.BrightnessHigh, label = "Brightness", value = it)
        }
        volumeOverlay?.let {
            CenterOverlay(icon = Icons.AutoMirrored.Outlined.VolumeUp, label = "Volume", value = it)
        }
        seekToast?.let { label ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GestureLayer(
    onTap: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onBrightnessDelta: (Float) -> Unit,
    onVolumeDelta: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            // Single-tap → toggle controls. Double-tap left/right → seek ±10s.
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { offset ->
                        val isLeft = offset.x < size.width / 2f
                        onSeekBy(if (isLeft) -10_000L else 10_000L)
                    },
                )
            }
            // Vertical drag: left half → brightness, right half → volume
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { },
                    onDragEnd = { },
                    onVerticalDrag = { change, dy ->
                        val frac = -dy / size.height.toFloat()
                        if (change.position.x < size.width / 2f) onBrightnessDelta(frac)
                        else onVolumeDelta(frac)
                        change.consume()
                    },
                )
            },
    )
}

@Composable
private fun RightControlColumn(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    aspectMode: Int,
    onAspectCycle: () -> Unit,
    repeatMode: Int,
    onRepeatCycle: () -> Unit,
    onPip: () -> Unit,
    tracks: Tracks?,
    onTrackToggle: (TrackSelectionOverride?) -> Unit,
    onCaptureFrame: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Speed
            var showSpeedMenu by remember { mutableStateOf(false) }
            Box {
                ControlButton(
                    icon = Icons.Outlined.Speed,
                    label = if (speed == 1f) "Speed" else "${trimZeros(speed)}x",
                    active = speed != 1f,
                    onClick = { showSpeedMenu = true },
                )
                DropdownMenu(
                    expanded = showSpeedMenu,
                    onDismissRequest = { showSpeedMenu = false },
                ) {
                    listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { s ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "${trimZeros(s)}x",
                                    color = if (s == speed) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = { onSpeedChange(s); showSpeedMenu = false },
                        )
                    }
                }
            }

            // Aspect
            ControlButton(
                icon = Icons.Outlined.AspectRatio,
                label = when (aspectMode) {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT  -> "Fit"
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Fill"
                    else                                    -> "Fit"
                },
                active = aspectMode != AspectRatioFrameLayout.RESIZE_MODE_FIT,
                onClick = onAspectCycle,
            )

            // Repeat
            ControlButton(
                icon = when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> Icons.Outlined.RepeatOne
                    Player.REPEAT_MODE_ALL -> Icons.Outlined.RepeatOn
                    else                   -> Icons.Outlined.Repeat
                },
                label = when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Once"
                    Player.REPEAT_MODE_ALL -> "All"
                    else                   -> "Off"
                },
                active = repeatMode != Player.REPEAT_MODE_OFF,
                onClick = onRepeatCycle,
            )

            // Picture in Picture
            ControlButton(
                icon = Icons.Outlined.PictureInPictureAlt,
                label = "PiP",
                onClick = onPip,
            )

            // Tracks (CC + audio) — only show if there is more than one of either
            val hasAlternateTracks = remember(tracks) {
                tracks?.groups?.any {
                    (it.type == C.TRACK_TYPE_TEXT || it.type == C.TRACK_TYPE_AUDIO) && it.length > 0
                } == true
            }
            if (hasAlternateTracks) {
                var showTrackMenu by remember { mutableStateOf(false) }
                Box {
                    ControlButton(
                        icon = Icons.Outlined.ClosedCaption,
                        label = "Tracks",
                        onClick = { showTrackMenu = true },
                    )
                    DropdownMenu(
                        expanded = showTrackMenu,
                        onDismissRequest = { showTrackMenu = false },
                    ) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Outlined.ClosedCaptionOff, contentDescription = null) },
                            text = { Text("Off") },
                            onClick = { onTrackToggle(null); showTrackMenu = false },
                        )
                        tracks?.groups?.forEachIndexed { gi, group ->
                            val typeLabel = when (group.type) {
                                C.TRACK_TYPE_AUDIO -> "Audio"
                                C.TRACK_TYPE_TEXT  -> "Subtitle"
                                else               -> return@forEachIndexed
                            }
                            for (ti in 0 until group.length) {
                                val format = group.getTrackFormat(ti)
                                val name = listOfNotNull(
                                    format.label, format.language,
                                ).joinToString(" · ").ifEmpty { "Track ${gi}/${ti}" }
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Outlined.Cameraswitch, contentDescription = null) },
                                    text = { Text("$typeLabel: $name") },
                                    onClick = {
                                        onTrackToggle(TrackSelectionOverride(group.mediaTrackGroup, ti))
                                        showTrackMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Frame capture
            ControlButton(
                icon = Icons.Outlined.PhotoCamera,
                label = "Frame",
                onClick = onCaptureFrame,
            )
        }
    }
}

/**
 * Stateful chip-like button used in the right-side control column. When `active`, fills
 * with `secondaryContainer` — matches the dynamic-colour treatment Material You picks
 * for "an option is engaged" indicators (same role used in the nav bar selected pill).
 */
@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val container by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.secondaryContainer
                      else Color.Transparent,
        animationSpec = Motion.defaultEffects(),
        label = "controlContainer",
    )
    val content = if (active) MaterialTheme.colorScheme.onSecondaryContainer
                  else LocalContentColor.current
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = content,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

/**
 * Floating Material You bottom bar. Surface-tinted (picks up the wallpaper accent on
 * "wallpaper" / dynamic themes), shaped, elevated. Position/duration labels flank the
 * scrub track; a filled primary play/pause button anchors the transport row.
 */
@Composable
private fun BottomBar(
    isMuted: Boolean,
    onMuteToggle: () -> Unit,
    isPlaying: Boolean,
    isEnded: Boolean,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipFwd: () -> Unit,
    position: Long,
    duration: Long,
    sliderValue: Float,
    onSliderChange: (Float) -> Unit,
    onSliderFinished: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // Scrub row: current time | slider | duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatMs(position),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Slider(
                    value = sliderValue,
                    onValueChange = onSliderChange,
                    onValueChangeFinished = onSliderFinished,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                )
                Text(
                    text = formatMs(duration),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Transport row — symmetric around the prominent play/pause.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(onClick = onMuteToggle) {
                    Icon(
                        if (isMuted) Icons.AutoMirrored.Outlined.VolumeOff
                        else Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(onClick = onSkipBack) {
                    Icon(
                        Icons.Outlined.Replay10,
                        contentDescription = "Rewind 10s",
                        modifier = Modifier.size(28.dp),
                    )
                }
                FilledIconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    AnimatedContent(
                        targetState = when {
                            isEnded -> 0
                            isPlaying -> 1
                            else -> 2
                        },
                        transitionSpec = {
                            (fadeIn(Motion.fastEffects()) togetherWith fadeOut(Motion.fastEffects()))
                        },
                        label = "playPauseIcon",
                    ) { state ->
                        Icon(
                            when (state) {
                                0 -> Icons.Outlined.Replay
                                1 -> Icons.Outlined.Pause
                                else -> Icons.Outlined.PlayArrow
                            },
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                IconButton(onClick = onSkipFwd) {
                    Icon(
                        Icons.Outlined.Forward10,
                        contentDescription = "Forward 10s",
                        modifier = Modifier.size(28.dp),
                    )
                }
                // Balancing slot so the play button stays centred.
                Spacer(Modifier.size(40.dp))
            }
        }
    }
}

@Composable
private fun CenterOverlay(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: Float,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(label, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { value.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .width(160.dp)
                        .height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        }
    }
}

private fun Activity.enterPip(player: ExoPlayer) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val w = player.videoSize.width
    val h = player.videoSize.height
    // If the video metadata hasn't loaded yet (w/h == 0), fall back to 16:9 instead
    // of feeding 0/0 (Android coerces to 1:1, which looks square and wrong).
    val ratio = if (w > 0 && h > 0) Rational(w, h).coerceInPipRatio()
                else Rational(16, 9)
    val params = PictureInPictureParams.Builder()
        .setAspectRatio(ratio)
        .build()
    runCatching { enterPictureInPictureMode(params) }
}

private fun Rational.coerceInPipRatio(): Rational {
    // PiP requires ratio between 0.418 and 2.39 (Android docs).
    val v = toFloat()
    return when {
        v < 0.5f -> Rational(1, 2)
        v > 2.3f -> Rational(23, 10)
        else -> this
    }
}

private fun captureFrame(context: Context, uri: android.net.Uri, positionMs: Long): Boolean {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val bm = retriever.getFrameAtTime(positionMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: return false
        val name = "frame_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Gal")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val out = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
        ) ?: return false
        context.contentResolver.openOutputStream(out)?.use { os ->
            bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, os)
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        context.contentResolver.update(out, values, null, null)
        bm.recycle()
        true
    } catch (e: Exception) {
        false
    } finally {
        runCatching { retriever.release() }
    }
}

private fun trimZeros(f: Float): String =
    if (f == f.toInt().toFloat()) f.toInt().toString()
    else "%.2f".format(f).trimEnd('0').trimEnd('.')

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    val m = s / 60
    val h = m / 60
    return if (h > 0) "%d:%02d:%02d".format(h, m % 60, s % 60)
    else "%d:%02d".format(m, s % 60)
}
