package com.gal.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Terminal palettes ported from GNOME Ptyxis (the bundled .palette files), adapted to Material 3.
 *
 * Each entry carries the palette's EXACT Background (→ surface) and Foreground (→ font), plus a
 * signature accent set drawn from that palette's own ANSI / cursor colors — nothing is invented.
 * All are dark schemes. Surfaces (cards/containers) are derived by lerping Background toward
 * Foreground/black so every theme keeps its own hue.
 */
enum class PtyxisPalette(
    val key: String,
    val displayName: String,
    val background: Long,
    val foreground: Long,
    val primaryC: Long,
    val secondaryC: Long,
    val tertiaryC: Long,
    val errorC: Long,
) {
    FAIRY_FLOSS("fairy_floss", "Fairy Floss", 0xFF5A5475, 0xFFC2FFDF, 0xFFFFB8D1, 0xFFAE81FF, 0xFFC2FFDF, 0xFFFF857F),
    NORD("nord", "Nord", 0xFF2E3440, 0xFFD8DEE9, 0xFF88C0D0, 0xFF81A1C1, 0xFF8FBCBB, 0xFFBF616A),
    BIM("bim", "Bim", 0xFF012849, 0xFFA9BED8, 0xFF5EA2EC, 0xFFF557A0, 0xFFA9EE55, 0xFFF557A0),
    BORLAND("borland", "Borland", 0xFF0000A4, 0xFFFFFF4E, 0xFFFFFF4E, 0xFFFF73FD, 0xFF96CBFE, 0xFFFF6C60),
    C64("c64", "C64", 0xFF40318D, 0xFF7869C4, 0xFF67B6BD, 0xFFBFCE72, 0xFF8B3F96, 0xFF883932),
    COBALT_NEON("cobalt_neon", "Cobalt Neon", 0xFF142838, 0xFF8FF586, 0xFF8FF586, 0xFF3BA5FF, 0xFFE9E75C, 0xFFFF2320),
    GRASS("grass", "Grass", 0xFF13773D, 0xFFFFF0A5, 0xFFE7B000, 0xFF00BBBB, 0xFFFFF0A5, 0xFFBB0000),
    HOMEBREW_OCEAN("homebrew_ocean", "Homebrew Ocean", 0xFF224FBC, 0xFFFFFFFF, 0xFF00A6B2, 0xFF00A600, 0xFF999900, 0xFF990000),
    MONO_AMBER("mono_amber", "Mono Amber", 0xFF2B1900, 0xFFFF9400, 0xFFFF9400, 0xFFFF9400, 0xFFFF9400, 0xFFFF9400),
    MONO_RED("mono_red", "Mono Red", 0xFF2B0C00, 0xFFFF3600, 0xFFFF3600, 0xFFFF3600, 0xFFFF3600, 0xFFFF3600),
    SYNTHWAVE("synthwave", "Synthwave", 0xFF262335, 0xFFFFFFFF, 0xFFFF7EDB, 0xFF03EDF9, 0xFFFEDE5D, 0xFFFE4450),
}

fun ptyxisPaletteFromKey(key: String): PtyxisPalette? =
    PtyxisPalette.entries.firstOrNull { it.key == key }

/** Builds a Material 3 dark scheme from a Ptyxis palette's background/foreground/accents. */
fun buildPtyxisColorScheme(palette: PtyxisPalette): ColorScheme {
    val bg = Color(palette.background)
    val fg = Color(palette.foreground)
    val primary = Color(palette.primaryC)
    val secondary = Color(palette.secondaryC)
    val tertiary = Color(palette.tertiaryC)
    val error = Color(palette.errorC)
    val black = Color.Black

    fun onColor(c: Color) = if (c.luminance() < 0.5f) Color.White else Color(0xFF0A0A0A)
    fun container(c: Color) = lerp(bg, c, 0.22f)
    fun onContainer(c: Color) = lerp(fg, c, 0.20f)

    return darkColorScheme(
        primary = primary,
        onPrimary = onColor(primary),
        primaryContainer = container(primary),
        onPrimaryContainer = onContainer(primary),
        secondary = secondary,
        onSecondary = onColor(secondary),
        secondaryContainer = container(secondary),
        onSecondaryContainer = onContainer(secondary),
        tertiary = tertiary,
        onTertiary = onColor(tertiary),
        tertiaryContainer = container(tertiary),
        onTertiaryContainer = onContainer(tertiary),
        error = error,
        onError = onColor(error),
        errorContainer = container(error),
        onErrorContainer = onContainer(error),
        background = bg,
        onBackground = fg,
        surface = bg,
        onSurface = fg,
        surfaceVariant = lerp(bg, fg, 0.12f),
        onSurfaceVariant = lerp(fg, bg, 0.25f),
        outline = lerp(bg, fg, 0.40f),
        outlineVariant = lerp(bg, fg, 0.20f),
        scrim = black,
        inverseSurface = fg,
        inverseOnSurface = bg,
        inversePrimary = primary,
        surfaceDim = lerp(bg, black, 0.20f),
        surfaceBright = lerp(bg, fg, 0.14f),
        surfaceContainerLowest = lerp(bg, black, 0.40f),
        surfaceContainerLow = lerp(bg, black, 0.20f),
        surfaceContainer = lerp(bg, fg, 0.05f),
        surfaceContainerHigh = lerp(bg, fg, 0.09f),
        surfaceContainerHighest = lerp(bg, fg, 0.13f),
    )
}
