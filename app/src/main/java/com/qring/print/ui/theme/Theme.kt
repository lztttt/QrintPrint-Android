package com.qring.print.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── 品牌色（两种模式一致）────────────────────────────────
val BRAND = Color(0xFF7C5CE6)
val BRAND_PRESSED = Color(0xFF6A4BD4)

// ── 状态卡渐变 ──────────────────────────────────────────
val CARD_GRAD_START = Color(0xFF6E4CE8)
val CARD_GRAD_MID = Color(0xFF8B5CF6)
val CARD_GRAD_END = Color(0xFFA855F7)
val CARD_GRAD_OFF_START = Color(0xFF9AA0AC)
val CARD_GRAD_OFF_END = Color(0xFFB4B9C4)

// ── 状态卡内部（叠在渐变上的半透明）──────────────────────
val ON_CARD_TILE = Color(0x38FFFFFF)
val ON_CARD_SUBTITLE = Color(0xBFFFFFFF)
val ON_CARD_DIVIDER = Color(0x2EFFFFFF)
val ON_CARD_MUTED = Color(0x73FFFFFF)

// ── 语义色 ──────────────────────────────────────────────
val ONLINE = Color(0xFF3DDC84)
val WARNING = Color(0xFFFFB020)
val DANGER = Color(0xFFFF4D4F)

// ── 快速打印宫格瓷砖 ──────────────────────────────────────
val TILE_AMBER = Color(0xFFF7C873)
val TILE_MINT = Color(0xFF8FD9B6)
val TILE_BLUE = Color(0xFF9CC4EF)
val TILE_LILAC = Color(0xFFBDA8F0)
val TILE_ICON = Color.White

// ── 画布编辑器 ──────────────────────────────────────────
val ACTION_BLUE = Color(0xFF3A7BFF)
val SELECT_OUTLINE = Color(0xFF7C5CE6)
val HANDLE_FILL = Color(0xFF7C5CE6)
val HANDLE_EDGE = Color.White

// ── 浅色模式 ──────────────────────────────────────────────
private val LightColors = lightColorScheme(
    primary = BRAND,
    onPrimary = Color.White,
    primaryContainer = BRAND.copy(alpha = 0.12f),
    onPrimaryContainer = BRAND,
    secondary = Color(0xFF6B5DD3),
    onSecondary = Color.White,
    background = Color(0xFFF2F3F5),
    onBackground = Color(0xFF1A1A1A),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF2F3F5),
    onSurfaceVariant = Color(0xFF9A9AA0),
    outline = Color(0xFFD8DCE3),
    outlineVariant = Color(0xFFE0E0E0),
    error = DANGER,
    onError = Color.White,
)

// ── 深色模式 ──────────────────────────────────────────────
private val DarkColors = darkColorScheme(
    primary = BRAND,
    onPrimary = Color.White,
    primaryContainer = BRAND.copy(alpha = 0.3f),
    onPrimaryContainer = Color(0xFFE0D6FF),
    secondary = Color(0xFF9F8FEF),
    onSecondary = Color.White,
    background = Color(0xFF212224),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFF9AA0AC),
    outline = Color(0xFF3D3D3D),
    outlineVariant = Color(0xFF333333),
    error = DANGER,
    onError = Color.White,
)

@Composable
fun QringPrintTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        typography = QringTypography,
        content = content
    )
}

// ── Typography ──────────────────────────────────────────

val QringTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 57.sp),
    displayMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 45.sp),
    displaySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 36.sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 32.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 28.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 24.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

// ── 动态取色 Helper ──────────────────────────────────────

object QringPalette {
    val pageBg: Color
        @Composable get() = MaterialTheme.colorScheme.background
    val surface: Color
        @Composable get() = MaterialTheme.colorScheme.surface
    val surfaceSunken: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceVariant
    val textPrimary: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface
    val textSecondary: Color
        @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val offline: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF3A3A3A) else Color(0xFFD8DCE3)
    val pressed: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF383838) else Color(0xFFEDEEF0)
    val paper: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF2E2E2E) else Color.White
    val trashColor: Color
        @Composable get() = if (isSystemInDarkTheme()) Color.White else Color(0xFF1A1A1A)
    val paperEdge: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF3D3D3D) else Color(0xFFD8DCE3)
    val elementHint: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0x33FFFFFF) else Color(0x33000000)
}
