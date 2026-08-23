package com.gal.ui.components

import android.graphics.Bitmap
import android.util.Size as ThumbnailSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateFloatAsState
import com.gal.ui.theme.Motion
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.gal.ui.navigation.LocalFloatingNavBarPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.gal.model.DateGroup
import com.gal.model.Media
import com.gal.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MediaGrid(
    media: List<Media>,
    columns: Int = 3,
    selectedIds: Set<Long> = emptySet(),
    showVideoDuration: Boolean = true,
    hapticFeedback: Boolean = true,
    onMediaClick: (Media) -> Unit,
    onMediaLongClick: (Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    // Leave room at the bottom for the floating nav pill on tab screens; zero elsewhere.
    val bottomInset = LocalFloatingNavBarPadding.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(bottom = bottomInset),
        modifier = modifier,
    ) {
        items(media, key = { it.id }) { item ->
            MediaCell(
                media = item,
                isSelected = item.id in selectedIds,
                showVideoDuration = showVideoDuration,
                onClick = { onMediaClick(item) },
                onLongClick = {
                    if (hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onMediaLongClick(item)
                },
            )
        }
    }
}

/** Grouped variant: renders a sticky section header above each group's row. */
@Composable
fun MediaSectionedGrid(
    sections: List<DateGroup>,
    columns: Int,
    selectedIds: Set<Long>,
    showVideoDuration: Boolean = true,
    hapticFeedback: Boolean = true,
    onMediaClick: (Media) -> Unit,
    onMediaLongClick: (Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val bottomInset = LocalFloatingNavBarPadding.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(bottom = bottomInset),
        modifier = modifier,
    ) {
        sections.forEach { section ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "h_${section.label}") {
                Text(
                    section.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = 8.dp, top = 14.dp, bottom = 6.dp, end = 8.dp,
                    ),
                )
            }
            items(section.items, key = { it.id }) { item ->
                MediaCell(
                    media = item,
                    isSelected = item.id in selectedIds,
                    showVideoDuration = showVideoDuration,
                    onClick = { onMediaClick(item) },
                    onLongClick = {
                        if (hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMediaLongClick(item)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCell(
    media: Media,
    isSelected: Boolean,
    showVideoDuration: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val videoThumbnail: Bitmap? by produceState<Bitmap?>(null, media.id) {
        if (media.type == MediaType.VIDEO) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.loadThumbnail(
                        media.uri,
                        ThumbnailSize(512, 512),
                        null,
                    )
                }.getOrNull()
            }
        }
    }

    val shape = MaterialTheme.shapes.small

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed || isSelected) 0.93f else 1f,
        animationSpec = Motion.fastSpatial(),
        label = "cellScale",
    )

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
    ) {
        AsyncImage(
            model = if (media.type == MediaType.VIDEO) videoThumbnail else media.uri,
            contentDescription = media.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Video indicator - semi-transparent background for better visibility on any image
        if (media.type == MediaType.VIDEO) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    .padding(2.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayCircle,
                    contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (showVideoDuration && media.duration != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = formatDuration(media.duration),
                        color = Color.White,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                    )
                }
            }
        }

        // Selection overlay (keep last so it renders on top)
        AnimatedVisibility(
            visible = isSelected,
            enter = fadeIn(Motion.fastEffects()) +
                scaleIn(initialScale = 0.85f, animationSpec = Motion.fastSpatial()),
            exit = fadeOut(Motion.fastEffects()) +
                scaleOut(targetScale = 0.85f, animationSpec = Motion.fastSpatial()),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                )
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000; val m = s / 60; val h = m / 60
    return if (h > 0) "%d:%02d:%02d".format(h, m % 60, s % 60)
    else "%d:%02d".format(m, s % 60)
}
