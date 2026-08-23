package com.gal.ui.navigation

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.gal.ui.theme.Motion
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
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
import com.gal.ui.screens.duplicates.DuplicatesScreen
import com.gal.ui.screens.editor.EditActivity
import com.gal.ui.screens.favourites.FavouritesScreen
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
    TopLevelRoute("Photos",     "timeline",   Icons.Filled.PhotoLibrary,  Icons.Outlined.PhotoLibrary),
    TopLevelRoute("Albums",     "albums",     Icons.Filled.GridView,      Icons.Outlined.GridView),
    TopLevelRoute("Favourites", "favourites", Icons.Filled.Favorite,      Icons.Outlined.FavoriteBorder),
    TopLevelRoute("Search",     "search",     Icons.Filled.Search,        Icons.Outlined.Search),
)

private val tabIndex = topLevelRoutes.mapIndexed { i, r -> r.route to i }.toMap()

/**
 * Bottom space that scrollable content should leave for the floating nav pill so the
 * last items aren't hidden behind it. Zero when the pill isn't visible (viewer, settings,
 * trash). Screens read this and append it to their grid's `contentPadding` bottom.
 */
val LocalFloatingNavBarPadding = compositionLocalOf<Dp> { 0.dp }

/** Visual height of the floating pill including breathing room around it. */
private val FLOATING_NAV_BAR_HEIGHT = 72.dp

@Composable
fun GalNavHost(intentUri: Uri? = null, onIntentConsumed: () -> Unit = {}) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showNav = topLevelRoutes.any { it.route == currentRoute }

    LaunchedEffect(intentUri) {
        if (intentUri != null) {
            navController.navigate("intent_viewer/${Uri.encode(intentUri.toString())}")
            onIntentConsumed()
        }
    }

    // The pill genuinely floats — content fills the full screen and the pill is overlaid
    // at the bottom. Screens read [LocalFloatingNavBarPadding] and add it to their
    // scrollable contentPadding so the last row isn't hidden behind the pill.
    Box(modifier = Modifier.fillMaxSize()) {
        val bottomPadding = if (showNav) FLOATING_NAV_BAR_HEIGHT else 0.dp
        CompositionLocalProvider(LocalFloatingNavBarPadding provides bottomPadding) {
            NavHost(
                navController = navController,
                startDestination = "timeline",
                modifier = Modifier.fillMaxSize(),
            // Spring-based transitions following M3 Expressive: spatial springs for
            // movement (slide / scale), effects springs for fade. Default speed for
            // lateral tab switches, slow for full-screen container transforms (push/pop).
            enterTransition = {
                val fromIdx = tabIndex[initialState.destination.route]
                val toIdx   = tabIndex[targetState.destination.route]
                if (fromIdx != null && toIdx != null) {
                    val dir = if (toIdx > fromIdx) 1 else -1
                    slideInHorizontally(Motion.defaultSpatial()) { it * dir / 3 } +
                        fadeIn(Motion.defaultEffects())
                } else {
                    scaleIn(
                        initialScale = 0.92f,
                        animationSpec = Motion.slowSpatial(),
                    ) + fadeIn(Motion.defaultEffects())
                }
            },
            exitTransition = {
                val fromIdx = tabIndex[initialState.destination.route]
                val toIdx   = tabIndex[targetState.destination.route]
                if (fromIdx != null && toIdx != null) {
                    val dir = if (toIdx > fromIdx) 1 else -1
                    slideOutHorizontally(Motion.defaultSpatial()) { -it * dir / 3 } +
                        fadeOut(Motion.defaultEffects())
                } else {
                    fadeOut(Motion.fastEffects())
                }
            },
            popEnterTransition = {
                fadeIn(Motion.defaultEffects()) +
                    scaleIn(initialScale = 0.95f, animationSpec = Motion.defaultSpatial())
            },
            popExitTransition = {
                scaleOut(
                    targetScale = 0.92f,
                    animationSpec = Motion.defaultSpatial(),
                ) + fadeOut(Motion.defaultEffects())
            },
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
                    onTrashClick = { navController.navigate("trash") },
                    onSettingsClick = { navController.navigate("settings") },
                )
            }
            composable("favourites") {
                FavouritesScreen(
                    onMediaClick = { navController.navigate("viewer/${it.id}/favourites") },
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
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onFindDuplicates = { navController.navigate("duplicates") },
                )
            }
            composable("duplicates") {
                DuplicatesScreen(
                    onBack = { navController.popBackStack() },
                    onMediaClick = { navController.navigate("viewer/${it.id}/timeline") },
                )
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
            }   // close NavHost
        }       // close CompositionLocalProvider

        if (showNav) {
            Box(
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                FloatingNavBar(
                    routes = topLevelRoutes,
                    selectedRoute = currentRoute,
                    onSelect = { dest ->
                        navController.navigate(dest.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        }
    }   // close outer Box
}

@Composable
private fun FloatingNavBar(
    routes: List<TopLevelRoute>,
    selectedRoute: String?,
    onSelect: (TopLevelRoute) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp,
            shadowElevation = 10.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                routes.forEach { dest ->
                    val selected = selectedRoute == dest.route
                    NavPill(
                        selected = selected,
                        icon = if (selected) dest.selectedIcon else dest.unselectedIcon,
                        contentDescription = dest.label,
                        onClick = { onSelect(dest) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NavPill(
    selected: Boolean,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
               else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}
