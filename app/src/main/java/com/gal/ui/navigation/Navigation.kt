package com.gal.ui.navigation

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gal.ui.screens.albums.AlbumScreen
import com.gal.ui.screens.albums.AlbumsScreen
import com.gal.ui.screens.editor.EditActivity
import com.gal.ui.screens.search.SearchScreen
import com.gal.ui.screens.settings.SettingsScreen
import com.gal.ui.screens.timeline.TimelineScreen
import com.gal.ui.screens.trash.TrashScreen
import com.gal.ui.screens.viewer.IntentViewerScreen
import com.gal.ui.screens.viewer.MediaViewerScreen

private data class TopLevelRoute(
    val label: String,
    val route: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val topLevelRoutes = listOf(
    TopLevelRoute("Photos", "timeline", Icons.Filled.PhotoLibrary, Icons.Outlined.PhotoLibrary),
    TopLevelRoute("Albums", "albums",   Icons.Filled.GridView,      Icons.Outlined.GridView),
    TopLevelRoute("Search", "search",   Icons.Filled.Search,        Icons.Outlined.Search),
)

@Composable
fun GalNavHost(intentUri: Uri? = null, onIntentConsumed: () -> Unit = {}) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = topLevelRoutes.any { it.route == currentRoute }

    // Navigate to the intent viewer whenever an external VIEW intent arrives.
    // onIntentConsumed() resets the URI to null so the same URI can trigger again later.
    LaunchedEffect(intentUri) {
        if (intentUri != null) {
            navController.navigate("intent_viewer/${Uri.encode(intentUri.toString())}")
            onIntentConsumed()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 48.dp, vertical = 16.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 4.dp,
                        shadowElevation = 8.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        NavigationBar(
                            containerColor = Color.Transparent,
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            modifier = Modifier.height(64.dp)
                        ) {
                            topLevelRoutes.forEach { dest ->
                                val selected = navBackStackEntry?.destination?.hierarchy
                                    ?.any { it.route == dest.route } == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        navController.navigate(dest.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.label,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    },
                                    label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "timeline",
            modifier = Modifier.fillMaxSize(),
            enterTransition = { fadeIn() + scaleIn(initialScale = 0.97f) },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { fadeOut() + scaleOut(targetScale = 0.97f) },
        ) {
            composable("timeline") {
                TimelineScreen(
                    onMediaClick = { navController.navigate("viewer/${it.id}/timeline") },
                    onSettingsClick = { navController.navigate("settings") },
                )
            }
            composable("albums") {
                AlbumsScreen(
                    onAlbumClick = { navController.navigate("album/${it.id}/${it.name}") },
                )
            }
            composable(
                "album/{albumId}/{albumName}",
                arguments = listOf(
                    navArgument("albumId") { type = NavType.LongType },
                    navArgument("albumName") { type = NavType.StringType },
                ),
            ) { back ->
                val albumId = back.arguments?.getLong("albumId") ?: -1L
                AlbumScreen(
                    albumName = back.arguments?.getString("albumName") ?: "",
                    onBack = { navController.popBackStack() },
                    onMediaClick = { media, _ ->
                        navController.navigate("viewer/${media.id}/album?albumId=$albumId")
                    },
                )
            }
            composable("search") {
                SearchScreen(
                    onMediaClick = { navController.navigate("viewer/${it.id}/timeline") },
                )
            }
            composable("trash") {
                TrashScreen(
                    onBack = { navController.popBackStack() },
                    onMediaClick = { navController.navigate("viewer/${it.id}/trash") },
                )
            }
            composable("settings") {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                "viewer/{mediaId}/{source}?albumId={albumId}",
                arguments = listOf(
                    navArgument("mediaId") { type = NavType.LongType },
                    navArgument("source") { type = NavType.StringType },
                    navArgument("albumId") {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                ),
            ) {
                MediaViewerScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { media -> EditActivity.launch(navController.context, media.uri) },
                )
            }
            // Handles ACTION_VIEW intents from external apps (file manager, camera, etc.)
            composable(
                "intent_viewer/{uri}",
                arguments = listOf(navArgument("uri") { type = NavType.StringType }),
            ) { back ->
                val encoded = back.arguments?.getString("uri") ?: return@composable
                IntentViewerScreen(
                    uri = Uri.parse(encoded),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
