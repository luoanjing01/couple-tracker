package com.coupletracker.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 潮汐卡片设计系统 — Material3 主题
 *
 * 设计稿只定义了浅色方案（奶油/珊瑚/薄荷），因此 darkTheme 下也复用同一套浅色配色，
 * 保证 App 任意时刻的观感与设计稿一致。
 */
private val TidalColorScheme = lightColorScheme(
    primary = Coral,
    onPrimary = PureWhite,
    primaryContainer = ChipBg,
    onPrimaryContainer = Coral,
    secondary = Mint,
    onSecondary = PureWhite,
    secondaryContainer = ChipBgCool,
    onSecondaryContainer = MintDeep,
    tertiary = Rose,
    background = Cream,
    onBackground = Ink,
    surface = PureWhite,
    onSurface = Ink,
    surfaceVariant = CardBg,
    onSurfaceVariant = InkSoft,
    outline = Muted,
    outlineVariant = Line,
    error = Color(0xFFE15A4E),
    onError = PureWhite,
    scrim = Color(0x52000000)
)

@Composable
fun TidalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 状态栏是否使用深色图标（浅色背景时用 true）。Splash/Login 的渐变 hero 上会传 false。
    darkStatusBarIcons: Boolean = true,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 全透明状态栏，让页面背景延伸到状态栏区域（沉浸式）
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                darkStatusBarIcons
        }
    }
    MaterialTheme(
        colorScheme = TidalColorScheme,
        typography = TidalTypography,
        content = content
    )
}
