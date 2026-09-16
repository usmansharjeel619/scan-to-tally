package com.acme.scantotally.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Designed for a warehouse, not a desk.
 *
 * High contrast for bad overhead lighting, large type readable at arm's length
 * while holding a box, and semantic colours that mean one thing only: green is
 * accepted, amber needs review, red is set-it-aside. Those three are never used
 * decoratively anywhere in the app.
 */

val Teal = Color(0xFF0E5A6B)
val TealLight = Color(0xFF4FB6CC)
val AcceptGreen = Color(0xFF1A6F46)
val AcceptGreenBg = Color(0xFFE2F0E8)
val FlagAmber = Color(0xFF92600A)
val FlagAmberBg = Color(0xFFF7EDD9)
val RejectRed = Color(0xFFA4322A)
val RejectRedBg = Color(0xFFF8E6E3)
val Ink = Color(0xFF12171A)
val InkMuted = Color(0xFF465057)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2EEF1),
    onPrimaryContainer = Teal,
    secondary = Color(0xFF0B7D94),
    background = Color(0xFFF2F3F5),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF6F7F8),
    onSurfaceVariant = InkMuted,
    error = RejectRed,
    onError = Color.White,
    errorContainer = RejectRedBg,
    onErrorContainer = RejectRed,
    outline = Color(0xFFD5DADE),
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF002A33),
    primaryContainer = Color(0xFF12303A),
    onPrimaryContainer = TealLight,
    secondary = Color(0xFF7FD4E6),
    background = Color(0xFF101417),
    onBackground = Color(0xFFE6EAEC),
    surface = Color(0xFF171C20),
    onSurface = Color(0xFFE6EAEC),
    surfaceVariant = Color(0xFF1D2328),
    onSurfaceVariant = Color(0xFFA7B2B8),
    error = Color(0xFFEA8D84),
    onError = Color(0xFF3A0F0C),
    errorContainer = Color(0xFF37191A),
    onErrorContainer = Color(0xFFEA8D84),
    outline = Color(0xFF2B3339),
)

/**
 * Bigger than Material's defaults throughout. A figure that has to be read at
 * arm's length, in a hurry, while holding something heavy, is not a 14sp figure.
 */
private val WarehouseTypography = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 46.sp),
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
)

/** Box serials and part numbers are read character by character; monospace. */
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp)

/** Minimum touch target. Gloves, not fingertips. */
val TouchTarget = 56.dp

@Composable
fun ScanToTallyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = WarehouseTypography,
        content = content,
    )
}
