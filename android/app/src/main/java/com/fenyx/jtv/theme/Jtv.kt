package com.fenyx.jtv.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.fenyx.jtv.R

/** v2 "Everyday" colour tokens. Names match the approved mockups (docs/v2/visual-research/mockup-src). */
@Immutable
data class JtvColors(
    val isDark: Boolean,
    val bg: Color,
    val s1: Color,
    val s2: Color,
    val line: Color,
    val tx: Color,
    val t2: Color,
    val t3: Color,
    val inv: Color,
    val invTx: Color,
    val acc: Color,
    val accTx: Color,
    val plate: Color,
    val error: Color,
)

val JtvDark = JtvColors(
    isDark = true,
    bg = Color(0xFF111113), s1 = Color(0xFF19191C), s2 = Color(0xFF222226), line = Color(0xFF2C2C31),
    tx = Color(0xFFECECEE), t2 = Color(0xFFA8A8B0), t3 = Color(0xFF8E8E97),
    inv = Color(0xFFECECEE), invTx = Color(0xFF111113),
    acc = Color(0xFFF0A12E), accTx = Color(0xFF141414), plate = Color(0xFF202024), error = Color(0xFFF2726B),
)

val JtvLight = JtvColors(
    isDark = false,
    bg = Color(0xFFF2F2F4), s1 = Color(0xFFFFFFFF), s2 = Color(0xFFE7E7EA), line = Color(0xFFDCDCE0),
    tx = Color(0xFF141416), t2 = Color(0xFF50505A), t3 = Color(0xFF686872),
    inv = Color(0xFF141416), invTx = Color(0xFFFFFFFF),
    acc = Color(0xFFE28E0B), accTx = Color(0xFF141414), plate = Color(0xFF1B1B1F), error = Color(0xFFC4332B),
)

/** Anek Latin, subset to Latin + punctuation (≈48 KB per weight). `wide` is the extended cut for numbers. */
object JtvFonts {
    val text = FontFamily(
        Font(R.font.jtv_anek_regular, FontWeight.Normal),
        Font(R.font.jtv_anek_semibold, FontWeight.SemiBold),
        Font(R.font.jtv_anek_bold, FontWeight.Bold),
    )
    val wide = FontFamily(Font(R.font.jtv_anek_wide_bold, FontWeight.Bold))
}

enum class FormFactor { Tv, Phone, Tablet }

val LocalJtvColors = staticCompositionLocalOf { JtvDark }
val LocalFormFactor = staticCompositionLocalOf { FormFactor.Tv }

object Jtv {
    val colors: JtvColors @Composable @ReadOnlyComposable get() = LocalJtvColors.current
    val form: FormFactor @Composable @ReadOnlyComposable get() = LocalFormFactor.current
    val isTv: Boolean @Composable @ReadOnlyComposable get() = LocalFormFactor.current == FormFactor.Tv
}

/** The player is always dark, whatever the app theme. */
@Composable
fun JtvDarkOnly(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalJtvColors provides JtvDark, content = content)
}
