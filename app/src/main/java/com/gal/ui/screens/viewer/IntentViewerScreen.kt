package com.gal.ui.screens.viewer

import android.app.Activity
import android.net.Uri
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import com.gal.model.Media
import com.gal.model.MediaType
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntentViewerScreen(uri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val mimeType = remember(uri) { context.contentResolver.getType(uri) ?: "image/*" }
    val isVideo = mimeType.startsWith("video/")
    var showControls by remember { mutableStateOf(true) }

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
                    id = 0L,
                    uri = uri,
                    type = MediaType.VIDEO,
                    mimeType = mimeType,
                    displayName = null,
                    albumId = 0L,
                    albumName = "",
                    dateAdded = Date(),
                    dateModified = Date(),
                    width = 0,
                    height = 0,
                    orientation = 0,
                    sizeBytes = 0L,
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
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    navigationIconContentColor = Color.White,
                ),
            )
        }
    }
}
