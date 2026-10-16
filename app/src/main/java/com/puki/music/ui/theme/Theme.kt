package com.puki.music.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class NuclearThemeId(val idKey: String, val displayName: String, val description: String) {
    DEFAULT("nuclear:default", "Nuclear Default", "Plum dark with Aurora violet"),
    AURORA("nuclear:aurora", "Aurora", "Deep indigo with neon magenta"),
    EMBER("nuclear:ember", "Ember", "Warm charcoal with fiery amber"),
    LAGOON("nuclear:lagoon", "Lagoon", "Abyssal ocean with vibrant cyan"),
    ARCTIC_MOSS("nuclear:arctic-moss", "Arctic Moss", "Deep forest slate with emerald green");

    companion object {
        fun fromKey(key: String): NuclearThemeId {
            return entries.firstOrNull { it.idKey == key } ?: DEFAULT
        }
    }
}

data class NuclearPalette(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val border: Color,
    val success: Color = Color(0xFF4ED37A),
    val error: Color = Color(0xFFFF5370)
)

val PaletteDefault = NuclearPalette(
    background = Color(0xFF141214),
    surface = Color(0xFF221A20),
    surfaceVariant = Color(0xFF2E222B),
    primary = Color(0xFFC273D6),
    secondary = Color(0xFF36D4D8),
    tertiary = Color(0xFF4ED37A),
    textPrimary = Color(0xFFEDEDED),
    textSecondary = Color(0xFFA69B9E),
    border = Color(0xFF3E2D38)
)

val PaletteAurora = NuclearPalette(
    background = Color(0xFF0F0C1B),
    surface = Color(0xFF1B162E),
    surfaceVariant = Color(0xFF271F42),
    primary = Color(0xFFDF65ED),
    secondary = Color(0xFF7085FF),
    tertiary = Color(0xFF38EFB5),
    textPrimary = Color(0xFFF2EFFF),
    textSecondary = Color(0xFFA59EC2),
    border = Color(0xFF3D3261)
)

val PaletteEmber = NuclearPalette(
    background = Color(0xFF141110),
    surface = Color(0xFF241B16),
    surfaceVariant = Color(0xFF33251D),
    primary = Color(0xFFFF7A29),
    secondary = Color(0xFFFFB347),
    tertiary = Color(0xFF55D67E),
    textPrimary = Color(0xFFF7EFE9),
    textSecondary = Color(0xFFAD9D94),
    border = Color(0xFF4A3428)
)

val PaletteLagoon = NuclearPalette(
    background = Color(0xFF0A1417),
    surface = Color(0xFF122227),
    surfaceVariant = Color(0xFF1A3037),
    primary = Color(0xFF2CD9D6),
    secondary = Color(0xFF4FA0FF),
    tertiary = Color(0xFF4EE38E),
    textPrimary = Color(0xFFE8F7F7),
    textSecondary = Color(0xFF90AAB0),
    border = Color(0xFF28464E)
)

val PaletteArcticMoss = NuclearPalette(
    background = Color(0xFF0D1410),
    surface = Color(0xFF16241D),
    surfaceVariant = Color(0xFF203228),
    primary = Color(0xFF50E88F),
    secondary = Color(0xFF8CE04F),
    tertiary = Color(0xFF38D5D8),
    textPrimary = Color(0xFFEAF5EF),
    textSecondary = Color(0xFF98ADA0),
    border = Color(0xFF2E4839)
)

fun getPaletteFor(themeId: NuclearThemeId): NuclearPalette {
    return when (themeId) {
        NuclearThemeId.DEFAULT -> PaletteDefault
        NuclearThemeId.AURORA -> PaletteAurora
        NuclearThemeId.EMBER -> PaletteEmber
        NuclearThemeId.LAGOON -> PaletteLagoon
        NuclearThemeId.ARCTIC_MOSS -> PaletteArcticMoss
    }
}

fun getNuclearPalette(themeId: NuclearThemeId): NuclearPalette = getPaletteFor(themeId)

fun createColorScheme(palette: NuclearPalette): ColorScheme {
    return darkColorScheme(
        primary = palette.primary,
        onPrimary = palette.background,
        secondary = palette.secondary,
        onSecondary = palette.background,
        tertiary = palette.tertiary,
        onTertiary = palette.background,
        background = palette.background,
        onBackground = palette.textPrimary,
        surface = palette.surface,
        onSurface = palette.textPrimary,
        surfaceVariant = palette.surfaceVariant,
        onSurfaceVariant = palette.textSecondary,
        outline = palette.border
    )
}

@Composable
fun NuclearTheme(
    themeId: NuclearThemeId = NuclearThemeId.DEFAULT,
    content: @Composable (NuclearPalette) -> Unit
) {
    val palette = getPaletteFor(themeId)
    val colorScheme = createColorScheme(palette)

    MaterialTheme(
        colorScheme = colorScheme,
        content = { content(palette) }
    )
}
