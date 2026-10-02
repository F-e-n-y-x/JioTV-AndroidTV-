package com.fenyx.jtv.theme

import androidx.compose.ui.graphics.Color

// v1 colour names, re-pointed at the v2 "Everyday" dark palette so screens that still use them match the
// new look. New code reads the theme-aware tokens from [Jtv.colors] instead (light + dark).
val TvDarkBackground = Color(0xFF111113)
val TvDarkSurface = Color(0xFF19191C)
val TvDarkSurfaceVariant = Color(0xFF222226)

val TvPrimary = Color(0xFFF0A12E)
val TvPrimaryContainer = Color(0xFF4A3412)
val TvOnPrimary = Color(0xFF141414)
val TvOnPrimaryContainer = Color(0xFFFCE9CC)

val TvSecondary = Color(0xFF6FBF73)
val TvOnSecondary = Color(0xFF111113)

val TvOnBackground = Color(0xFFECECEE)
val TvOnSurface = Color(0xFFECECEE)
val TvOnSurfaceVariant = Color(0xFFA8A8B0)

val TvError = Color(0xFFF2726B)
val TvLiveRed = Color(0xFFE5484D)
val TvOnlineGreen = Color(0xFF6FBF73)

val TvFocusBorder = Color(0xFFECECEE)
val TvSelectedGlow = Color(0x33F0A12E)
