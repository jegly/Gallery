package com.gal.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

val GalShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun GalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: String = "system",
    catppuccinAccent: String = "mauve",
    draculaAccent: String = "purple",
    ptyxisPalette: String = "nord",
    appFont: String = "dotgothic16",
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dynamicScheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

    val colorScheme = when (themeMode) {
        "catppuccin" -> {
            val dynAccent = if (catppuccinAccent == "dynamic") dynamicScheme.primary else null
            catppuccinColorScheme(catppuccinAccent, dynAccent)
        }
        "dracula" -> {
            val dynAccent = if (draculaAccent == "dynamic") dynamicScheme.primary else null
            draculaColorScheme(draculaAccent, dynAccent)
        }
        "ptyxis" -> ptyxisColorScheme(ptyxisPalette)
        "wallpaper" -> dynamicScheme
        else -> if (darkTheme) darkColorScheme() else lightColorScheme()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = when (themeMode) {
                "catppuccin", "dracula", "ptyxis" -> false
                else -> !darkTheme
            }
        }
    }

    val typography = remember(appFont) { buildGalTypography(appFontFromKey(appFont).family) }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = GalShapes,
        typography = typography,
        content = content,
    )
}
