package com.gal.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * A second batch of terminal palettes ported from GNOME Ptyxis's bundled .palette resources
 * (originally generated from https://github.com/Gogh-Co/Gogh), companion to [PtyxisPalette]
 * in Ptyxis.kt.
 *
 * Every value here — Background, Foreground, Cursor, and all 16 ANSI slots (Color0-Color15) — is
 * copied verbatim from the real palette; nothing is invented or guessed. For themes that ship
 * separate Light/Dark variants (Belafonte, Everforest, GitHub, Solarized, Xterm) this uses Dark,
 * matching how the rest of this app renders its dark schemes.
 *
 * [buildPtyxisExtendedColorScheme] maps the ANSI slots to Material 3 using the *standard* terminal
 * color convention every one of these palettes was authored against — slot 1 is always "the red",
 * slot 4 is always "the blue", etc. — not a per-theme heuristic:
 *   ansi[4] blue    → primary
 *   ansi[5] magenta → secondary
 *   ansi[6] cyan    → tertiary
 *   ansi[1] red     → error
 */
enum class PtyxisPaletteExtended(
    val key: String,
    val displayName: String,
    val background: Long,
    val foreground: Long,
    val cursor: Long,
    val ansi: LongArray,
) {
    ACI(
        key = "aci",
        displayName = "Aci",
        background = 0xFF0D1926,
        foreground = 0xFFB4E1FD,
        cursor = 0xFFB4E1FD,
        ansi = longArrayOf(0xFF363636, 0xFFFF0883, 0xFF83FF08, 0xFFFF8308, 0xFF0883FF, 0xFF8308FF, 0xFF08FF83, 0xFFB6B6B6, 0xFF424242, 0xFFFF1E8E, 0xFF8EFF1E, 0xFFFF8E1E, 0xFF1E8EFF, 0xFF8E1EFF, 0xFF1EFF8E, 0xFFC2C2C2),
    ),
    AFTERGLOW(
        key = "afterglow",
        displayName = "Afterglow",
        background = 0xFF222222,
        foreground = 0xFFD0D0D0,
        cursor = 0xFFD0D0D0,
        ansi = longArrayOf(0xFF151515, 0xFFA53C23, 0xFF7B9246, 0xFFD3A04D, 0xFF6C99BB, 0xFF9F4E85, 0xFF7DD6CF, 0xFFD0D0D0, 0xFF505050, 0xFFA53C23, 0xFF7B9246, 0xFFD3A04D, 0xFF547C99, 0xFF9F4E85, 0xFF7DD6CF, 0xFFF5F5F5),
    ),
    ARGONAUT(
        key = "argonaut",
        displayName = "Argonaut",
        background = 0xFF0E1019,
        foreground = 0xFFFFFAF4,
        cursor = 0xFFFFFAF4,
        ansi = longArrayOf(0xFF232323, 0xFFFF000F, 0xFF8CE10B, 0xFFFFB900, 0xFF008DF8, 0xFF6D43A6, 0xFF00D8EB, 0xFFFFFFFF, 0xFF444444, 0xFFFF2740, 0xFFABE15B, 0xFFFFD242, 0xFF0092FF, 0xFF9A5FEB, 0xFF67FFF0, 0xFFFFFFFF),
    ),
    AURA(
        key = "aura",
        displayName = "Aura",
        background = 0xFF15141B,
        foreground = 0xFFEDECEE,
        cursor = 0xFFEDECEE,
        ansi = longArrayOf(0xFF110F18, 0xFFFF6767, 0xFF61FFCA, 0xFFFFCA85, 0xFFA277FF, 0xFFA277FF, 0xFF61FFCA, 0xFFEDECEE, 0xFF6D6D6D, 0xFFFFCA85, 0xFFA277FF, 0xFFFFCA85, 0xFFA277FF, 0xFFA277FF, 0xFF61FFCA, 0xFFEDECEE),
    ),
    AYU_MIRAGE(
        key = "ayu_mirage",
        displayName = "Ayu Mirage",
        background = 0xFF1F2430,
        foreground = 0xFFCBCCC6,
        cursor = 0xFFFFCC66,
        ansi = longArrayOf(0xFF1F2430, 0xFFFF3333, 0xFFBAE67E, 0xFFFFA759, 0xFF73D0FF, 0xFFD4BFFF, 0xFF95E6CB, 0xFFCBCCC6, 0xFF707A8C, 0xFFFF3333, 0xFFBAE67E, 0xFFFFA759, 0xFF73D0FF, 0xFFD4BFFF, 0xFF95E6CB, 0xFFCBCCC6),
    ),
    BELAFONTE(
        key = "belafonte",
        displayName = "Belafonte",
        background = 0xFF20111B,
        foreground = 0xFF968C83,
        cursor = 0xFF968C83,
        ansi = longArrayOf(0xFF20111B, 0xFFBE100E, 0xFF858162, 0xFFEAA549, 0xFF426A79, 0xFF97522C, 0xFF989A9C, 0xFF968C83, 0xFF5E5252, 0xFFBE100E, 0xFF858162, 0xFFEAA549, 0xFF426A79, 0xFF97522C, 0xFF989A9C, 0xFFD5CCBA),
    ),
    BIRDS_OF_PARADISE(
        key = "birds_of_paradise",
        displayName = "Birds Of Paradise",
        background = 0xFF2A1F1D,
        foreground = 0xFFE0DBB7,
        cursor = 0xFFE0DBB7,
        ansi = longArrayOf(0xFF573D26, 0xFFBE2D26, 0xFF6BA18A, 0xFFE99D2A, 0xFF5A86AD, 0xFFAC80A6, 0xFF74A6AD, 0xFFE0DBB7, 0xFF9B6C4A, 0xFFE84627, 0xFF95D8BA, 0xFFD0D150, 0xFFB8D3ED, 0xFFD19ECB, 0xFF93CFD7, 0xFFFFF9D5),
    ),
    BLAZER(
        key = "blazer",
        displayName = "Blazer",
        background = 0xFF0D1926,
        foreground = 0xFFD9E6F2,
        cursor = 0xFFD9E6F2,
        ansi = longArrayOf(0xFF000000, 0xFFB87A7A, 0xFF7AB87A, 0xFFB8B87A, 0xFF7A7AB8, 0xFFB87AB8, 0xFF7AB8B8, 0xFFD9D9D9, 0xFF262626, 0xFFDBBDBD, 0xFFBDDBBD, 0xFFDBDBBD, 0xFFBDBDDB, 0xFFDBBDDB, 0xFFBDDBDB, 0xFFFFFFFF),
    ),
    BROGRAMMER(
        key = "brogrammer",
        displayName = "Brogrammer",
        background = 0xFF131313,
        foreground = 0xFFD6DBE5,
        cursor = 0xFFD6DBE5,
        ansi = longArrayOf(0xFF1F1F1F, 0xFFF81118, 0xFF2DC55E, 0xFFECBA0F, 0xFF2A84D2, 0xFF4E5AB7, 0xFF1081D6, 0xFFD6DBE5, 0xFFD6DBE5, 0xFFDE352E, 0xFF1DD361, 0xFFF3BD09, 0xFF1081D6, 0xFF5350B9, 0xFF0F7DDB, 0xFFFFFFFF),
    ),
    CHALKBOARD(
        key = "chalkboard",
        displayName = "Chalkboard",
        background = 0xFF29262F,
        foreground = 0xFFD9E6F2,
        cursor = 0xFFD9E6F2,
        ansi = longArrayOf(0xFF000000, 0xFFC37372, 0xFF72C373, 0xFFC2C372, 0xFF7372C3, 0xFFC372C2, 0xFF72C2C3, 0xFFD9D9D9, 0xFF323232, 0xFFDBAAAA, 0xFFAADBAA, 0xFFDADBAA, 0xFFAAAADB, 0xFFDBAADA, 0xFFAADADB, 0xFFFFFFFF),
    ),
    ESPRESSO_LIBRE(
        key = "espresso_libre",
        displayName = "Espresso Libre",
        background = 0xFF2A211C,
        foreground = 0xFFB8A898,
        cursor = 0xFFB8A898,
        ansi = longArrayOf(0xFF000000, 0xFFCC0000, 0xFF1A921C, 0xFFF0E53A, 0xFF0066FF, 0xFFC5656B, 0xFF06989A, 0xFFD3D7CF, 0xFF555753, 0xFFEF2929, 0xFF9AFF87, 0xFFFFFB5C, 0xFF43A8ED, 0xFFFF818A, 0xFF34E2E2, 0xFFEEEEEC),
    ),
    EVERFOREST(
        key = "everforest",
        displayName = "Everforest",
        background = 0xFF2D353B,
        foreground = 0xFFD3C6AA,
        cursor = 0xFFD3C6AA,
        ansi = longArrayOf(0xFF4B565C, 0xFFE67E80, 0xFFA7C080, 0xFFDBBC7F, 0xFF7FBBB3, 0xFFD699B6, 0xFF83C092, 0xFFD3C6AA, 0xFF5C6A72, 0xFFF85552, 0xFF8DA101, 0xFFDFA000, 0xFF3A94C5, 0xFFDF69BA, 0xFF35A77C, 0xFFDFDDC8),
    ),
    FLATLAND(
        key = "flatland",
        displayName = "Flatland",
        background = 0xFF1D1F21,
        foreground = 0xFFB8DBEF,
        cursor = 0xFFB8DBEF,
        ansi = longArrayOf(0xFF1D1D19, 0xFFF18339, 0xFF9FD364, 0xFFF4EF6D, 0xFF5096BE, 0xFF695ABC, 0xFFD63865, 0xFFFFFFFF, 0xFF1D1D19, 0xFFD22A24, 0xFFA7D42C, 0xFFFF8949, 0xFF61B9D0, 0xFF695ABC, 0xFFD63865, 0xFFFFFFFF),
    ),
    GITHUB(
        key = "github",
        displayName = "GitHub",
        background = 0xFF101216,
        foreground = 0xFF8B949E,
        cursor = 0xFFC9D1D9,
        ansi = longArrayOf(0xFF000000, 0xFFF78166, 0xFF56D364, 0xFFE3B341, 0xFF6CA4F8, 0xFFDB61A2, 0xFF2B7489, 0xFFFFFFFF, 0xFF4D4D4D, 0xFFF78166, 0xFF56D364, 0xFFE3B341, 0xFF6CA4F8, 0xFFDB61A2, 0xFF2B7489, 0xFFFFFFFF),
    ),
    IBM3270(
        key = "ibm3270",
        displayName = "IBM3270",
        background = 0xFF000000,
        foreground = 0xFFFDFDFD,
        cursor = 0xFFFDFDFD,
        ansi = longArrayOf(0xFF222222, 0xFFF01818, 0xFF24D830, 0xFFF0D824, 0xFF7890F0, 0xFFF078D8, 0xFF54E4E4, 0xFFA5A5A5, 0xFF888888, 0xFFEF8383, 0xFF7ED684, 0xFFEFE28B, 0xFFB3BFEF, 0xFFEFB3E3, 0xFF9CE2E2, 0xFFFFFFFF),
    ),
    IC_GREEN_PPL(
        key = "ic_green_ppl",
        displayName = "IC Green PPL",
        background = 0xFF3A3D3F,
        foreground = 0xFFD9EFD3,
        cursor = 0xFFD9EFD3,
        ansi = longArrayOf(0xFF1F1F1F, 0xFFFB002A, 0xFF339C24, 0xFF659B25, 0xFF149B45, 0xFF53B82C, 0xFF2CB868, 0xFFE0FFEF, 0xFF032710, 0xFFA7FF3F, 0xFF9FFF6D, 0xFFD2FF6D, 0xFF72FFB5, 0xFF50FF3E, 0xFF22FF71, 0xFFDAEFD0),
    ),
    KANAGAWA(
        key = "kanagawa",
        displayName = "Kanagawa",
        background = 0xFF1F1F28,
        foreground = 0xFFDCD7BA,
        cursor = 0xFFDCD7BA,
        ansi = longArrayOf(0xFF090618, 0xFFC34043, 0xFF76946A, 0xFFC0A36E, 0xFF7E9CD8, 0xFF957FB8, 0xFF6A9589, 0xFFDCD7BA, 0xFF727169, 0xFFE82424, 0xFF98BB6C, 0xFFE6C384, 0xFF7FB4CA, 0xFF938AA9, 0xFF7AA89F, 0xFFC8C093),
    ),
    MATERIAL(
        key = "material",
        displayName = "Material",
        background = 0xFF1E282C,
        foreground = 0xFFC3C7D1,
        cursor = 0xFF657B83,
        ansi = longArrayOf(0xFF073641, 0xFFEB606B, 0xFFC3E88D, 0xFFF7EB95, 0xFF80CBC3, 0xFFFF2490, 0xFFAEDDFF, 0xFFFFFFFF, 0xFF002B36, 0xFFEB606B, 0xFFC3E88D, 0xFFF7EB95, 0xFF7DC6BF, 0xFF6C71C3, 0xFF34434D, 0xFFFFFFFF),
    ),
    MONA_LISA(
        key = "mona_lisa",
        displayName = "Mona Lisa",
        background = 0xFF120B0D,
        foreground = 0xFFF7D66A,
        cursor = 0xFFF7D66A,
        ansi = longArrayOf(0xFF351B0E, 0xFF9B291C, 0xFF636232, 0xFFC36E28, 0xFF515C5D, 0xFF9B1D29, 0xFF588056, 0xFFF7D75C, 0xFF874228, 0xFFFF4331, 0xFFB4B264, 0xFFFF9566, 0xFF9EB2B4, 0xFFFF5B6A, 0xFF8ACD8F, 0xFFFFE598),
    ),
    MONO_CYAN(
        key = "mono_cyan",
        displayName = "Mono Cyan",
        background = 0xFF00222B,
        foreground = 0xFF00CCFF,
        cursor = 0xFF00CCFF,
        ansi = longArrayOf(0xFF003340, 0xFF00CCFF, 0xFF00CCFF, 0xFF00CCFF, 0xFF00CCFF, 0xFF00CCFF, 0xFF00CCFF, 0xFF00CCFF, 0xFF00CCFF, 0xFF00CCFF, 0xFF00CCFF, 0xFF00CCFF, 0xFF00CCFF, 0xFF00CCFF, 0xFF00CCFF, 0xFF00CCFF),
    ),
    MONOKAI_PRO(
        key = "monokai_pro",
        displayName = "Monokai Pro",
        background = 0xFF363537,
        foreground = 0xFFFDF9F3,
        cursor = 0xFFFDF9F3,
        ansi = longArrayOf(0xFF363537, 0xFFFF6188, 0xFFA9DC76, 0xFFFFD866, 0xFFFC9867, 0xFFAB9DF2, 0xFF78DCE8, 0xFFFDF9F3, 0xFF908E8F, 0xFFFF6188, 0xFFA9DC76, 0xFFFFD866, 0xFFFC9867, 0xFFAB9DF2, 0xFF78DCE8, 0xFFFDF9F3),
    ),
    OMNI(
        key = "omni",
        displayName = "Omni",
        background = 0xFF191622,
        foreground = 0xFFABB2BF,
        cursor = 0xFFABB2BF,
        ansi = longArrayOf(0xFF191622, 0xFFE96379, 0xFF67E480, 0xFFE89E64, 0xFF78D1E1, 0xFF988BC7, 0xFFFF79C6, 0xFFABB2BF, 0xFF000000, 0xFFE96379, 0xFF67E480, 0xFFE89E64, 0xFF78D1E1, 0xFF988BC7, 0xFFFF79C6, 0xFFFFFFFF),
    ),
    PARAISO_DARK(
        key = "paraiso_dark",
        displayName = "Paraiso Dark",
        background = 0xFF2F1E2E,
        foreground = 0xFFA39E9B,
        cursor = 0xFFA39E9B,
        ansi = longArrayOf(0xFF2F1E2E, 0xFFEF6155, 0xFF48B685, 0xFFFEC418, 0xFF06B6EF, 0xFF815BA4, 0xFF5BC4BF, 0xFFA39E9B, 0xFF776E71, 0xFFEF6155, 0xFF48B685, 0xFFFEC418, 0xFF06B6EF, 0xFF815BA4, 0xFF5BC4BF, 0xFFE7E9DB),
    ),
    PIXIEFLOSS(
        key = "pixiefloss",
        displayName = "Pixiefloss",
        background = 0xFF241F33,
        foreground = 0xFFD1CAE8,
        cursor = 0xFFD1CAE8,
        ansi = longArrayOf(0xFF2F2942, 0xFFFF857F, 0xFF48B685, 0xFFE6C000, 0xFFAE81FF, 0xFFEF6155, 0xFFC2FFDF, 0xFFF8F8F2, 0xFF75507B, 0xFFF1568E, 0xFF5ADBA2, 0xFFD5A425, 0xFFC5A3FF, 0xFFEF6155, 0xFFC2FFFF, 0xFFF8F8F0),
    ),
    POWERSHELL(
        key = "powershell",
        displayName = "Powershell",
        background = 0xFF052454,
        foreground = 0xFFF6F6F7,
        cursor = 0xFFF6F6F7,
        ansi = longArrayOf(0xFF000000, 0xFF7E0008, 0xFF098003, 0xFFC4A000, 0xFF010083, 0xFFD33682, 0xFF0E807F, 0xFF7F7C7F, 0xFF808080, 0xFFEF2929, 0xFF1CFE3C, 0xFFFEFE45, 0xFF268AD2, 0xFFFE13FA, 0xFF29FFFE, 0xFFC2C1C3),
    ),
    RELAXED(
        key = "relaxed",
        displayName = "Relaxed",
        background = 0xFF353A44,
        foreground = 0xFFD9D9D9,
        cursor = 0xFFD9D9D9,
        ansi = longArrayOf(0xFF151515, 0xFFBC5653, 0xFF909D63, 0xFFEBC17A, 0xFF6A8799, 0xFFB06698, 0xFFC9DFFF, 0xFFD9D9D9, 0xFF636363, 0xFFBC5653, 0xFFA0AC77, 0xFFEBC17A, 0xFF7EAAC7, 0xFFB06698, 0xFFACBBD0, 0xFFF7F7F7),
    ),
    SEA_SHELLS(
        key = "sea_shells",
        displayName = "Sea Shells",
        background = 0xFF09141B,
        foreground = 0xFFDEB88D,
        cursor = 0xFFDEB88D,
        ansi = longArrayOf(0xFF17384C, 0xFFD15123, 0xFF027C9B, 0xFFFCA02F, 0xFF1E4950, 0xFF68D4F1, 0xFF50A3B5, 0xFFDEB88D, 0xFF434B53, 0xFFD48678, 0xFF628D98, 0xFFFDD39F, 0xFF1BBCDD, 0xFFBBE3EE, 0xFF87ACB4, 0xFFFEE4CE),
    ),
    SOLARIZED(
        key = "solarized",
        displayName = "Solarized",
        background = 0xFF002B36,
        foreground = 0xFF839496,
        cursor = 0xFF839496,
        ansi = longArrayOf(0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFCF9A6B, 0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5, 0xFF657B83, 0xFFD87979, 0xFF88CF76, 0xFF657B83, 0xFF2699FF, 0xFFD33682, 0xFF43B8C3, 0xFFFDF6E3),
    ),
    SPACEDUST(
        key = "spacedust",
        displayName = "Spacedust",
        background = 0xFF0A1E24,
        foreground = 0xFFECF0C1,
        cursor = 0xFFECF0C1,
        ansi = longArrayOf(0xFF6E5346, 0xFFE35B00, 0xFF5CAB96, 0xFFE3CD7B, 0xFF0F548B, 0xFFE35B00, 0xFF06AFC7, 0xFFF0F1CE, 0xFF684C31, 0xFFFF8A3A, 0xFFAECAB8, 0xFFFFC878, 0xFF67A0CE, 0xFFFF8A3A, 0xFF83A7B4, 0xFFFEFFF1),
    ),
    SPRING(
        key = "spring",
        displayName = "Spring",
        background = 0xFF0A1E24,
        foreground = 0xFFECF0C1,
        cursor = 0xFFECF0C1,
        ansi = longArrayOf(0xFF000000, 0xFFFF4D83, 0xFF1F8C3B, 0xFF1FC95B, 0xFF1DD3EE, 0xFF8959A8, 0xFF3E999F, 0xFFFFFFFF, 0xFF000000, 0xFFFF0021, 0xFF1FC231, 0xFFD5B807, 0xFF15A9FD, 0xFF8959A8, 0xFF3E999F, 0xFFFFFFFF),
    ),
    TWILIGHT(
        key = "twilight",
        displayName = "Twilight",
        background = 0xFF141414,
        foreground = 0xFFFFFFD4,
        cursor = 0xFFFFFFD4,
        ansi = longArrayOf(0xFF141414, 0xFFC06D44, 0xFFAFB97A, 0xFFC2A86C, 0xFF44474A, 0xFFB4BE7C, 0xFF778385, 0xFFFFFFD4, 0xFF262626, 0xFFDE7C4C, 0xFFCCD88C, 0xFFE2C47E, 0xFF5A5E62, 0xFFD0DC8E, 0xFF8A989B, 0xFFFFFFD4),
    ),
    URPLE(
        key = "urple",
        displayName = "Urple",
        background = 0xFF1B1B23,
        foreground = 0xFF877A9B,
        cursor = 0xFF877A9B,
        ansi = longArrayOf(0xFF000000, 0xFFB0425B, 0xFF37A415, 0xFFAD5C42, 0xFF564D9B, 0xFF6C3CA1, 0xFF808080, 0xFF87799C, 0xFF5D3225, 0xFFFF6388, 0xFF29E620, 0xFFF08161, 0xFF867AED, 0xFFA05EEE, 0xFFEAEAEA, 0xFFBFA3FF),
    ),
    XTERM(
        key = "xterm",
        displayName = "Xterm",
        background = 0xFF000000,
        foreground = 0xFFFFFFFF,
        cursor = 0xFFFFFFFF,
        ansi = longArrayOf(0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00, 0xFF0000EE, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5, 0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00, 0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF),
    ),
}

fun ptyxisPaletteExtendedFromKey(key: String): PtyxisPaletteExtended? =
    PtyxisPaletteExtended.entries.firstOrNull { it.key == key }

/** Builds a Material 3 dark scheme from an extended Ptyxis palette using the standard ANSI role mapping. */
fun buildPtyxisExtendedColorScheme(palette: PtyxisPaletteExtended): ColorScheme {
    val bg = Color(palette.background)
    val fg = Color(palette.foreground)
    val primary = Color(palette.ansi[4]) // blue
    val secondary = Color(palette.ansi[5]) // magenta
    val tertiary = Color(palette.ansi[6]) // cyan
    val error = Color(palette.ansi[1]) // red
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

/** One entry per selectable Ptyxis palette (curated + extended), for driving a swatch picker UI. */
data class PtyxisEntry(val key: String, val displayName: String, val swatch: Color)

val ptyxisEntries: List<PtyxisEntry> =
    (PtyxisPalette.entries.map { PtyxisEntry(it.key, it.displayName, Color(it.primaryC)) } +
        PtyxisPaletteExtended.entries.map { PtyxisEntry(it.key, it.displayName, Color(it.ansi[4])) })
        .sortedBy { it.displayName }

/** Resolves any Ptyxis key (curated or extended) to a Material 3 scheme, defaulting to Nord. */
fun ptyxisColorScheme(key: String): ColorScheme =
    ptyxisPaletteFromKey(key)?.let { buildPtyxisColorScheme(it) }
        ?: ptyxisPaletteExtendedFromKey(key)?.let { buildPtyxisExtendedColorScheme(it) }
        ?: buildPtyxisColorScheme(PtyxisPalette.NORD)
