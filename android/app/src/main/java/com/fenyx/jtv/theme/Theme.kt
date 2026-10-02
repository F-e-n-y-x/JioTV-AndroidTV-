package com.fenyx.jtv.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

private fun tvScheme(c: JtvColors) = if (c.isDark) darkColorScheme(
    primary = c.acc, onPrimary = c.accTx, primaryContainer = TvPrimaryContainer, onPrimaryContainer = TvOnPrimaryContainer,
    secondary = c.t2, onSecondary = c.bg, background = c.bg, onBackground = c.tx,
    surface = c.s1, onSurface = c.tx, surfaceVariant = c.s2, onSurfaceVariant = c.t2,
    inverseSurface = c.inv, inverseOnSurface = c.invTx, border = c.line, error = c.error,
) else lightColorScheme(
    primary = c.acc, onPrimary = c.accTx, primaryContainer = c.s2, onPrimaryContainer = c.tx,
    secondary = c.t2, onSecondary = c.bg, background = c.bg, onBackground = c.tx,
    surface = c.s1, onSurface = c.tx, surfaceVariant = c.s2, onSurfaceVariant = c.t2,
    inverseSurface = c.inv, inverseOnSurface = c.invTx, border = c.line, error = c.error,
)

/**
 * App theme. [themeMode] is the stored setting ("system" | "dark" | "light" | null). TV defaults to dark
 * (living rooms, OLED/LCD bloom); phone and tablet follow the system unless the user picks one.
 */
@Composable
fun JioTVGoTVTheme(
    form: FormFactor = FormFactor.Tv,
    themeMode: String? = null,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        "system" -> systemDark
        else -> form == FormFactor.Tv || systemDark
    }
    val colors = if (dark) JtvDark else JtvLight
    val scheme = remember(colors) { tvScheme(colors) }
    CompositionLocalProvider(LocalJtvColors provides colors, LocalFormFactor provides form) {
        MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
    }
}
