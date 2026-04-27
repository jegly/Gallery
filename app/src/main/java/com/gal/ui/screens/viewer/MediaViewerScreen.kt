@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.gal.ui.screens.viewer

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.gal.data.repository.MediaRepository
import com.gal.model.Media
import com.gal.model.MediaType
import com.gal.security.ExifScrubber
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MediaViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MediaRepository,
    val exifScrubber: ExifScrubber,
) : ViewModel() {
    private val initialMediaId: Long = checkNotNull(savedStateHandle["mediaId"])
    private val source: String = savedStateHandle["source"] ?: "timeline"
    private val albumId: Long? = savedStateHandle["albumId"]

    val mediaList = when (source) {
        "trash" -> repository.trash()
        "album" -> repository.album(albumId ?: -1L)
        else    -> repository.timeline()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val initialIndex get() =
        mediaList.value.indexOfFirst { it.id == initialMediaId }.coerceAtLeast(0)

    fun moveToTrash(
        media: Media,
        onRequest: (androidx.activity.result.IntentSenderRequest?) -> Unit,
    ) = viewModelScope.launch {
        onRequest(repository.moveToTrash(media))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    onBack: () -> Unit,
    onEdit: (Media) -> Unit,
    viewModel: MediaViewerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = context as Activity
    val mediaList by viewModel.mediaList.collectAsStateWithLifecycle()
    var showControls by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {}

    // FLAG_SECURE & immersive mode — setDecorFitsSystemWindows is not used because
    // Android 15+ enforces edge-to-edge for apps targeting SDK 35+ and ignores that call.
    DisposableEffect(Unit) {
        val window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val ctrl = WindowInsetsControllerCompat(window, window.decorView)
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    if (mediaList.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = viewModel.initialIndex,
        pageCount = { mediaList.size },
    )
    val currentMedia = mediaList.getOrNull(pagerState.currentPage) ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { showControls = !showControls },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = true 
        ) { page ->
            val media = mediaList[page]
            when (media.type) {
                MediaType.IMAGE -> {
                    var scale by remember { mutableFloatStateOf(1f) }
                    var offset by remember { mutableStateOf(Offset.Zero) }
                    
                    LaunchedEffect(page) { scale = 1f; offset = Offset.Zero }
                    
                    AsyncImage(
                        model = media.uri,
                        contentDescription = media.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y,
                            )
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown()
                                    do {
                                        val event = awaitPointerEvent()
                                        val zoom = event.calculateZoom()
                                        val pan = event.calculatePan()

                                        if (scale > 1f || zoom != 1f) {
                                            event.changes.forEach { it.consume() }
                                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                                            offset = if (newScale == 1f) Offset.Zero
                                                      else offset + pan * scale
                                            scale = newScale
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            },
                    )
                }
                MediaType.VIDEO -> LocalVideoPlayer(
                    media = media,
                    modifier = Modifier.fillMaxSize(),
                    onControlsToggled = { visible -> showControls = visible },
                )
            }
        }

        // Back FAB — top start
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            SmallFloatingActionButton(
                onClick = onBack,
                shape = CircleShape,
                containerColor = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
        }

        // Actions FAB — top end, expands into DropdownMenu
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                SmallFloatingActionButton(
                    onClick = { menuExpanded = true },
                    shape = CircleShape,
                    containerColor = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                ) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More actions")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    if (currentMedia.type == MediaType.IMAGE) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { menuExpanded = false; onEdit(currentMedia) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Outlined.Share, null) },
                        onClick = {
                            menuExpanded = false
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val uri = viewModel.exifScrubber.scrubAndShare(context, currentMedia.uri)
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = currentMedia.mimeType
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    withContext(Dispatchers.Main) {
                                        context.startActivity(Intent.createChooser(intent, "Share"))
                                    }
                                } catch (e: Exception) {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = currentMedia.mimeType
                                        putExtra(Intent.EXTRA_STREAM, currentMedia.uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    withContext(Dispatchers.Main) {
                                        context.startActivity(Intent.createChooser(intent, "Share"))
                                    }
                                }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Info") },
                        leadingIcon = { Icon(Icons.Outlined.Info, null) },
                        onClick = { menuExpanded = false; showInfo = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = {
                            menuExpanded = false
                            viewModel.moveToTrash(currentMedia) { req ->
                                req?.let { permissionLauncher.launch(it) } ?: onBack()
                            }
                        },
                    )
                }
            }
        }
    }

    if (showInfo) {
        ModalBottomSheet(
            onDismissRequest = { showInfo = false },
            sheetState = sheetState,
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            ) {
                Text(
                    currentMedia.displayName ?: "Unknown",
                    style = MaterialTheme.typography.titleLarge,
                )
                androidx.compose.foundation.layout.Spacer(
                    Modifier.height(16.dp)
                )
                listOfNotNull(
                    "Size"   to formatBytes(currentMedia.sizeBytes),
                    "Album"  to currentMedia.albumName,
                    if (currentMedia.width > 0)
                        "Resolution" to "${currentMedia.width} × ${currentMedia.height}"
                    else null,
                    currentMedia.duration?.let { "Duration" to formatDuration(it) },
                ).forEach { (label, value) ->
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(100.dp),
                        )
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(32.dp))
            }
        }
    }
}

private fun formatBytes(b: Long) = when {
    b < 1024    -> "$b B"
    b < 1048576 -> "%.1f KB".format(b / 1024.0)
    else        -> "%.1f MB".format(b / 1048576.0)
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000; val m = s / 60; val h = m / 60
    return if (h > 0) "%d:%02d:%02d".format(h, m % 60, s % 60)
    else "%d:%02d".format(m, s % 60)
}
