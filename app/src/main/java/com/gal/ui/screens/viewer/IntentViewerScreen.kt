package com.gal.ui.screens.viewer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import com.gal.ui.theme.Motion
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.gal.data.repository.MediaRepository
import com.gal.model.MediaType
import com.gal.security.ExifScrubber
import com.gal.security.UriValidator
import com.gal.ui.screens.editor.EditActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.gal.model.Media
import java.util.Date

@HiltViewModel
class IntentViewerViewModel @Inject constructor(
    val exifScrubber: ExifScrubber,
    private val repository: MediaRepository,
) : ViewModel() {
    fun saveToGal(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        repository.importFiles(listOf(uri))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntentViewerScreen(
    uri: Uri,
    onBack: () -> Unit,
    viewModel: IntentViewerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = context as Activity
    // Defence in depth: even though the manifest only registers content/file schemes
    // for ACTION_VIEW, validate again here in case the URI arrives via another path.
    val validator = remember { UriValidator() }
    if (!validator.isLocal(uri)) {
        DisposableEffect(Unit) { onBack(); onDispose { } }
        return
    }
    val mimeType = remember(uri) { context.contentResolver.getType(uri) ?: "image/*" }
    val isVideo = mimeType.startsWith("video/")
    var showControls by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Resolve display name and size from the URI once
    val (displayName, fileSize) = remember(uri) {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val name = runCatching { c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) }.getOrNull()
                val size = runCatching { c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE)) }.getOrDefault(0L)
                name to size
            } else null to 0L
        } ?: (null to 0L)
    }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { if (!isVideo) showControls = !showControls },
    ) {
        if (isVideo) {
            val fakeMedia = remember(uri, mimeType) {
                Media(
                    id = 0L, uri = uri, type = MediaType.VIDEO, mimeType = mimeType,
                    displayName = displayName, albumId = 0L, albumName = "",
                    dateAdded = Date(), dateModified = Date(),
                    width = 0, height = 0, orientation = 0, sizeBytes = fileSize,
                )
            }
            LocalVideoPlayer(
                media = fakeMedia,
                modifier = Modifier.fillMaxSize(),
                onControlsToggled = { visible -> showControls = visible },
            )
        } else {
            AsyncImage(
                model = uri,
                contentDescription = displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Back FAB — top start
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(Motion.fastEffects()) + slideInVertically(Motion.defaultSpatial()) { -it / 2 },
            exit = fadeOut(Motion.fastEffects()) + slideOutVertically(Motion.defaultSpatial()) { -it / 2 },
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

        // Actions FAB — top end
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(Motion.fastEffects()) + slideInVertically(Motion.defaultSpatial()) { -it / 2 },
            exit = fadeOut(Motion.fastEffects()) + slideOutVertically(Motion.defaultSpatial()) { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp),
        ) {
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
                    if (!isVideo) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = {
                                menuExpanded = false
                                EditActivity.launch(context, uri)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Outlined.Share, null) },
                        onClick = {
                            menuExpanded = false
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val scrubbed = viewModel.exifScrubber.scrubAndShare(context, uri)
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = mimeType
                                        putExtra(Intent.EXTRA_STREAM, scrubbed)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    withContext(Dispatchers.Main) {
                                        context.startActivity(Intent.createChooser(intent, "Share"))
                                    }
                                } catch (e: Exception) {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = mimeType
                                        putExtra(Intent.EXTRA_STREAM, uri)
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
                        text = { Text("Save to Gallery") },
                        leadingIcon = { Icon(Icons.Outlined.AddPhotoAlternate, null) },
                        onClick = {
                            menuExpanded = false
                            viewModel.saveToGal(uri)
                            scope.launch {
                                snackbarHostState.showSnackbar("Saved to Gallery")
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Info") },
                        leadingIcon = { Icon(Icons.Outlined.Info, null) },
                        onClick = { menuExpanded = false; showInfo = true },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        )
    }

    if (showInfo) {
        ModalBottomSheet(
            onDismissRequest = { showInfo = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            ) {
                Text(
                    displayName ?: "Unknown",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(16.dp))
                listOfNotNull(
                    "Type" to mimeType,
                    if (fileSize > 0) "Size" to formatBytes(fileSize) else null,
                ).forEach { (label, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(80.dp),
                        )
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

private fun formatBytes(b: Long) = when {
    b < 1024    -> "$b B"
    b < 1048576 -> "%.1f KB".format(b / 1024.0)
    else        -> "%.1f MB".format(b / 1048576.0)
}
