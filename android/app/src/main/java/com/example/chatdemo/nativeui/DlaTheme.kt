package com.example.chatdemo.nativeui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 可切换的配色：深色（默认）/ 浅色 / 跟随系统。
 *
 * 所有界面通过 [DlaColors] 取色，切主题时整棵树一起变；
 * Markdown 那个隔离 WebView 会读 `isDark` 生成对应配色。
 */
data class DlaPalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val mint: Color,
    val warm: Color,
    val text: Color,
    val textDim: Color,
    val error: Color,
    val line: Color,
    val onAccent: Color,
)

val DarkPalette = DlaPalette(
    isDark = true,
    background = Color(0xFF0A1312),
    surface = Color(0xFF101C1A),
    surfaceHigh = Color(0xFF162623),
    mint = Color(0xFF7FE7C4),
    warm = Color(0xFFFFC48A),
    text = Color(0xFFE6F1ED),
    textDim = Color(0xFF8FA6A0),
    error = Color(0xFFFF8A7A),
    line = Color(0xFF1E3230),
    onAccent = Color(0xFF06201A),
)

val LightPalette = DlaPalette(
    isDark = false,
    background = Color(0xFFF4F7F6),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFE9F0EE),
    mint = Color(0xFF11886B),
    warm = Color(0xFFB4690E),
    text = Color(0xFF10201D),
    textDim = Color(0xFF5B6F6A),
    error = Color(0xFFC0392B),
    line = Color(0xFFDCE5E2),
    onAccent = Color(0xFFFFFFFF),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** 兼容旧写法：所有颜色都能像常量一样用，但会随主题变化 */
object DlaColors {
    val isDark: Boolean @Composable get() = LocalPalette.current.isDark
    val Background: Color @Composable get() = LocalPalette.current.background
    val Surface: Color @Composable get() = LocalPalette.current.surface
    val SurfaceHigh: Color @Composable get() = LocalPalette.current.surfaceHigh
    val Mint: Color @Composable get() = LocalPalette.current.mint
    val Warm: Color @Composable get() = LocalPalette.current.warm
    val Text: Color @Composable get() = LocalPalette.current.text
    val TextDim: Color @Composable get() = LocalPalette.current.textDim
    val Error: Color @Composable get() = LocalPalette.current.error
    val Line: Color @Composable get() = LocalPalette.current.line
    val OnAccent: Color @Composable get() = LocalPalette.current.onAccent
}

@Composable
fun DlaTheme(mode: String = "dark", content: @Composable () -> Unit) {
    val palette = when (mode) {
        "light" -> LightPalette
        "system" -> if (isSystemDark()) DarkPalette else LightPalette
        else -> DarkPalette
    }
    val scheme = if (palette.isDark) {
        darkColorScheme(
            primary = palette.mint,
            onPrimary = palette.onAccent,
            secondary = palette.warm,
            onSecondary = palette.onAccent,
            background = palette.background,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceHigh,
            onSurfaceVariant = palette.textDim,
            outline = palette.line,
            error = palette.error,
        )
    } else {
        lightColorScheme(
            primary = palette.mint,
            onPrimary = palette.onAccent,
            secondary = palette.warm,
            onSecondary = palette.onAccent,
            background = palette.background,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceHigh,
            onSurfaceVariant = palette.textDim,
            outline = palette.line,
            error = palette.error,
        )
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Composable
private fun isSystemDark(): Boolean =
    androidx.compose.foundation.isSystemInDarkTheme()
