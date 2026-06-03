package com.fenyx.jtv.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val TvDarkColorScheme = darkColorScheme(
    primary = TvPrimary,
    onPrimary = TvOnPrimary,
    primaryContainer = TvPrimaryContainer,
    onPrimaryContainer = TvOnPrimaryContainer,
    secondary = TvSecondary,
    onSecondary = TvOnSecondary,
    background = TvDarkBackground,
    onBackground = TvOnBackground,
    surface = TvDarkSurface,
    onSurface = TvOnSurface,
    surfaceVariant = TvDarkSurfaceVariant,
    onSurfaceVariant = TvOnSurfaceVariant,
    error = TvError,
)

@Composable
fun JioTVGoTVTheme(
    content: @Composable () -> Unit,
) {
    // Android TV is always dark — force dark theme
    MaterialTheme(
        colorScheme = TvDarkColorScheme,
        content = content
    )
}
