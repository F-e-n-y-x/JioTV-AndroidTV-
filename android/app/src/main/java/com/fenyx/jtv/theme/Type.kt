package com.fenyx.jtv.theme

import androidx.tv.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// TV type scale — sized up from the phone Material 3 defaults for a 10-foot ("across the room") UI.
// Previously only bodyLarge was set, so every other style fell back to phone-sized defaults that are
// hard to read from a couch. Filling the whole scale here lifts legibility across every screen with
// zero changes at the call sites (they all read MaterialTheme.typography.*).
val Typography =
  Typography(
    displayLarge = TextStyle(
      fontFamily = JtvFonts.text, fontWeight = FontWeight.Bold,
      fontSize = 48.sp, lineHeight = 56.sp, letterSpacing = 0.sp,
    ),
    displayMedium = TextStyle(
      fontFamily = JtvFonts.text, fontWeight = FontWeight.Bold,
      fontSize = 40.sp, lineHeight = 48.sp, letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
      fontFamily = JtvFonts.text, fontWeight = FontWeight.Bold,
      fontSize = 34.sp, lineHeight = 42.sp, letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
      fontFamily = JtvFonts.text, fontWeight = FontWeight.SemiBold,
      fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
      fontFamily = JtvFonts.text, fontWeight = FontWeight.SemiBold,
      fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
      fontFamily = JtvFonts.text, fontWeight = FontWeight.SemiBold,
      fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
      fontFamily = JtvFonts.text, fontWeight = FontWeight.SemiBold,
      fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
      fontFamily = JtvFonts.text, fontWeight = FontWeight.Medium,
      fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
      fontFamily = JtvFonts.text, fontWeight = FontWeight.Medium,
      fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
      fontFamily = JtvFonts.text, fontWeight = FontWeight.Normal,
      fontSize = 18.sp, lineHeight = 26.sp, letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
      fontFamily = JtvFonts.text, fontWeight = FontWeight.Normal,
      fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
      fontFamily = JtvFonts.text, fontWeight = FontWeight.Normal,
      fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
      fontFamily = JtvFonts.text, fontWeight = FontWeight.Medium,
      fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
      fontFamily = JtvFonts.text, fontWeight = FontWeight.Medium,
      fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
      fontFamily = JtvFonts.text, fontWeight = FontWeight.Medium,
      fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp,
    ),
  )
