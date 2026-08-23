@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.gal.ui.screens.viewer

import android.app.Activity
import android.app.WallpaperManager
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.gal.ui.theme.Motion
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.gal.data.repository.MediaRepository
import com.gal.model.Media
import com.gal.model.MediaType
import com.gal.ui.actions.MediaActionsViewModel
import com.gal.ui.components.AlbumPickerSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MediaViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: MediaRepository,
) : ViewModel() {
    private val initialMediaId: Long = checkNotNull(savedStateHandle["mediaId"])
    private val source: String = savedStateHandle["source"] ?: "timeline"
    private val albumId: Long? = savedStateHandle["albumId"]

    val mediaList = when (source) {
        "trash" -> repository.trash()
        "album" -> repository.album(albumId ?: -1L)
        "favourites" -> repository.favourites()
        else    -> repository.timeline()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val initialIndex get() =
        mediaList.value.indexOfFirst { it.id == initialMediaId }.coerceAtLeast(0)
}

@Composable
fun MediaViewerScreen(
    onBack: () -> Unit,
    onEdit: (Media) -> Unit,
    viewModel: MediaViewerViewModel = hiltViewModel(),
    actions: MediaActionsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = context as Activity
    val mediaList by viewModel.mediaList.collectAsStateWithLifecycle()
    var showControls by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showWallpaperDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Media?>(null) }
    var pickerMode by remember { mutableStateOf<PickerMode?>(null) }

    // Two-phase write requests (rename + rotate)
    var pending by remember { mutableStateOf<PendingWrite?>(null) }
    val writeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val p = pending; pending = null
        if (result.resultCode == Activity.RESULT_OK && p != null) {
            when (p) {
                is PendingWrite.Rename -> actions.applyRename(p.media, p.name) {}
                is PendingWrite.Rotate -> actions.applyRotate(p.media, p.degrees) {}
            }
        }
    }
    val plainLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {}
    val launchWrite: (IntentSenderRequest?) -> Unit = { req ->
        if (req != null) writeLauncher.launch(req) else pending = null
    }
    val launchPlain: (IntentSenderRequest?) -> Unit = { req ->
        req?.let { plainLauncher.launch(it) }
    }

    LaunchedEffect(showControls) {
        if (showControls && !showMenu) {
            delay(3_000)
            if (!showMenu) showControls = false
        }
    }

    val settingsViewModel: com.gal.ui.screens.settings.SettingsViewModel = hiltViewModel()
    val settings by settingsViewModel.state.collectAsStateWithLifecycle()

    // Immersive + always-on FLAG_SECURE while viewing private media, independent of the
    // app-wide "block screenshots" setting (MainActivity only applies FLAG_SECURE when that
    // setting is on — the viewer must not depend on it). On exit, only clear the flag if the
    // app-wide setting doesn't also want it on, so we don't fight MainActivity's own effect.
    DisposableEffect(settings.blockScreenshots) {
        val window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val ctrl = WindowInsetsControllerCompat(window, window.decorView)
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            if (!settings.blockScreenshots) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(settings.keepScreenOn) {
        if (settings.keepScreenOn) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    if (mediaList.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = viewModel.initialIndex,
        pageCount = { mediaList.size },
    )
    val currentMedia by remember {
        derivedStateOf { mediaList.getOrNull(pagerState.currentPage) }
    }
    val media = currentMedia ?: return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { showControls = true },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = true,
        ) { page ->
            val item = mediaList[page]
            when (item.type) {
                MediaType.IMAGE -> {
                    var scale by remember { mutableFloatStateOf(1f) }
                    var offset by remember { mutableStateOf(Offset.Zero) }

                    LaunchedEffect(page) { scale = 1f; offset = Offset.Zero }

                    AsyncImage(
                        model = item.uri,
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale, scaleY = scale,
                                translationX = offset.x, translationY = offset.y,
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
                    media = item,
                    modifier = Modifier.fillMaxSize(),
                    onControlsToggled = { visible -> showControls = visible },
                )
            }
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }

        // Title chip — top center, below status bar AND display cutout
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(Motion.fastEffects()) + slideInVertically(Motion.defaultSpatial()) { -it / 2 },
            exit = fadeOut(Motion.fastEffects()) + slideOutVertically(Motion.defaultSpatial()) { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .displayCutoutPadding()
                .padding(top = 12.dp, start = 56.dp, end = 56.dp),
        ) {
            androidx.compose.material3.Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
            ) {
                Text(
                    text = media.displayName ?: "Unknown",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // Overflow FAB — top end
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
                    onClick = { showMenu = true },
                    shape = CircleShape,
                    containerColor = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                ) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            actions.share(listOf(media)) { intent -> context.startActivity(intent) }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (media.isFavourite) "Remove from favourites" else "Add to favourites") },
                        leadingIcon = {
                            Icon(
                                if (media.isFavourite) Icons.Outlined.Favorite
                                else Icons.Outlined.FavoriteBorder,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            showMenu = false
                            actions.setFavorite(listOf(media), !media.isFavourite, launchPlain)
                        },
                    )
                    if (media.type == MediaType.IMAGE) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = { showMenu = false; onEdit(media) },
                        )
                        DropdownMenuItem(
                            text = { Text("Rotate") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.RotateRight, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                pending = PendingWrite.Rotate(media, 90)
                                actions.requestWrite(listOf(media), launchWrite)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Set as wallpaper") },
                            leadingIcon = { Icon(Icons.Outlined.Wallpaper, contentDescription = null) },
                            onClick = {
                                // Go straight to the in-app Home/Lock/Both picker — it calls
                                // WallpaperManager.setStream() directly with no external app
                                // involved. WallpaperManager.getCropAndSetWallpaperIntent()'s
                                // ACTION_CROP_AND_SET_WALLPAPER intent is notoriously unreliable
                                // across OEMs: resolveActivity() can match an unrelated generic
                                // image-crop handler (e.g. the Contacts app's attach-photo
                                // cropper) instead of a real wallpaper picker, silently sending
                                // the user to the wrong app. That system intent is still reachable
                                // as the dialog's manual "System picker…" button for anyone who
                                // wants the OEM crop UI.
                                showMenu = false
                                showWallpaperDialog = true
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                        onClick = { showMenu = false; renameTarget = media },
                    )
                    DropdownMenuItem(
                        text = { Text("Copy to album") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                        onClick = { showMenu = false; pickerMode = PickerMode.COPY },
                    )
                    DropdownMenuItem(
                        text = { Text("Move to album") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null) },
                        onClick = { showMenu = false; pickerMode = PickerMode.MOVE },
                    )
                    DropdownMenuItem(
                        text = { Text("Open with…") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                        onClick = { showMenu = false; actions.openWith(media) },
                    )
                    DropdownMenuItem(
                        text = { Text("Info") },
                        leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                        onClick = { showMenu = false; showInfo = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            actions.trash(listOf(media)) { req ->
                                req?.let { plainLauncher.launch(it) } ?: onBack()
                            }
                        },
                    )
                }
            }
        }

        // Bottom safe-area filler so video controls don't overlap nav bar when shown
        Spacer(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }

    if (showInfo) {
        InfoSheet(media = media, onDismiss = { showInfo = false })
    }

    renameTarget?.let { target ->
        RenameDialog(
            initial = target.displayName ?: "",
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                renameTarget = null
                pending = PendingWrite.Rename(target, newName)
                actions.requestWrite(listOf(target), launchWrite)
            },
        )
    }

    pickerMode?.let { mode ->
        AlbumPickerSheet(
            albumsFlow = actions.albums,
            title = if (mode == PickerMode.COPY) "Copy to album" else "Move to album",
            onDismiss = { pickerMode = null },
            onAlbumPicked = { album ->
                if (mode == PickerMode.COPY) {
                    actions.copyToAlbum(listOf(media), album)
                } else {
                    actions.moveToAlbum(listOf(media), album, launchPlain)
                }
                pickerMode = null
            },
        )
    }

    if (showWallpaperDialog) {
        WallpaperPickerDialog(
            media = media,
            onDismiss = { showWallpaperDialog = false },
        )
    }
}

private enum class PickerMode { COPY, MOVE }

private sealed class PendingWrite {
    data class Rename(val media: Media, val name: String) : PendingWrite()
    data class Rotate(val media: Media, val degrees: Int) : PendingWrite()
}

@Composable
private fun InfoSheet(media: Media, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current
    var exif by remember(media.id) { mutableStateOf<ExifData?>(null) }

    LaunchedEffect(media.id) {
        if (media.type == MediaType.IMAGE) {
            exif = withContext(Dispatchers.IO) { readExif(context, media.uri) }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(media.displayName ?: "Unknown", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            InfoRow("Size", formatBytes(media.sizeBytes))
            InfoRow("Album", media.albumName)
            if (media.width > 0) InfoRow("Resolution", "${media.width} × ${media.height}")
            media.duration?.let { InfoRow("Duration", formatDuration(it)) }
            InfoRow("Modified", java.text.DateFormat.getDateTimeInstance().format(media.dateModified))
            exif?.let { e ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "EXIF",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                e.dateTaken?.let { InfoRow("Date taken", it) }
                val camera = listOfNotNull(e.cameraMake, e.cameraModel).joinToString(" ").ifBlank { null }
                camera?.let { InfoRow("Camera", it) }
                e.lens?.let { InfoRow("Lens", it) }
                e.iso?.let { InfoRow("ISO", it) }
                e.fNumber?.let { InfoRow("Aperture", it) }
                e.shutter?.let { InfoRow("Shutter", it) }
                e.focalLength?.let { InfoRow("Focal length", it) }
                e.gpsLatLon?.let { (lat, lon) ->
                    InfoRow(
                        label = "Location",
                        value = "%.5f, %.5f".format(lat, lon),
                        onClick = {
                            val geoUri = Uri.parse("geo:$lat,$lon?q=$lat,$lon")
                            val intent = Intent(Intent.ACTION_VIEW, geoUri)
                            if (intent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(intent)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else Color.Unspecified,
        )
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("New name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && name != initial,
                onClick = { onConfirm(name.trim()) },
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WallpaperPickerDialog(media: Media, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val wm = WallpaperManager.getInstance(context)
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set as wallpaper") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                listOf(
                    "Home screen" to WallpaperManager.FLAG_SYSTEM,
                    "Lock screen" to WallpaperManager.FLAG_LOCK,
                    "Home screen & lock screen" to (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK),
                ).forEach { (label, flags) ->
                    TextButton(
                        onClick = {
                            onDismiss()
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    context.contentResolver.openInputStream(media.uri)
                                        ?.use { wm.setStream(it, null, true, flags) }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(label, modifier = Modifier.fillMaxWidth()) }
                }

                Spacer(Modifier.height(8.dp))

                // Fallback: try system wallpaper-picker intent (covers OEM customisations)
                TextButton(
                    onClick = {
                        onDismiss()
                        runCatching {
                            val intent = Intent(WallpaperManager.ACTION_CROP_AND_SET_WALLPAPER).apply {
                                setDataAndType(media.uri, "image/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            if (intent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(intent)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("System picker…", modifier = Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private data class ExifData(
    val dateTaken: String? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val lens: String? = null,
    val iso: String? = null,
    val fNumber: String? = null,
    val shutter: String? = null,
    val focalLength: String? = null,
    val gpsLatLon: Pair<Double, Double>? = null,
)

private fun readExif(context: android.content.Context, uri: android.net.Uri): ExifData? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val exif = ExifInterface(stream)
        @Suppress("DEPRECATION")
        val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
            ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
        ExifData(
            dateTaken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME),
            cameraMake = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim(),
            cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim(),
            lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim(),
            iso = iso?.trim(),
            fNumber = exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.toDoubleOrNull()
                ?.let { "f/${"%.1f".format(it).trimEnd('0').trimEnd('.')}" },
            shutter = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let { v ->
                when {
                    v >= 1.0 -> "${"%.1f".format(v).trimEnd('0').trimEnd('.')}s"
                    v > 0    -> "1/${(1 / v).toInt()}s"
                    else     -> null
                }
            },
            focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { raw ->
                val parts = raw.split("/")
                val value = if (parts.size == 2) {
                    val n = parts[0].toDoubleOrNull(); val d = parts[1].toDoubleOrNull()
                    if (n != null && d != null && d != 0.0) n / d else null
                } else raw.toDoubleOrNull()
                value?.let { "${"%.1f".format(it).trimEnd('0').trimEnd('.')}mm" }
            },
            gpsLatLon = FloatArray(2).let { out -> if (exif.getLatLong(out)) out[0].toDouble() to out[1].toDouble() else null },
        )
    }
}.getOrNull()

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
