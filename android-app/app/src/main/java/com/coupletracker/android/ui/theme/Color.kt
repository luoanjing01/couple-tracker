package com.coupletracker.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 潮汐卡片设计系统 — 配色 Tokens
 *
 * 与 concept-d-tidal-cards-v3.html 中的 CSS 变量一一对应。
 * 命名保持语义化（coral/cream/mint），便于代码与设计稿互相印证。
 */

// ===== 背景与基础色 =====
val Cream = Color(0xFFFFF8F0)          // 页面背景顶
val Peach = Color(0xFFFFE4D1)          // 页面背景底 / 图标背景
val Sand = Color(0xFFD8C7BA)           // 未配对 / 禁用态
val MapBg = Color(0xFFF5E6D3)          // 地图底色
val MapWater = Color(0xFFE8D9C0)       // 地图水系
val MapPark = Color(0xFFE0D0B5)        // 地图公园
val MapRoad = Color(0xFFEFE2D0)        // 地图道路

// ===== 主色：珊瑚橙 =====
val Coral = Color(0xFFFF8B7B)          // 主色（看 TA 时）
val CoralSoft = Color(0xFFFFB5A7)      // 渐变副色
val CoralDeep = Color(0xFFFF7A6A)      // 深珊瑚（开屏渐变顶）
val Rose = Color(0xFFF4A6A0)           // 玫红辅助

// ===== 辅色：薄荷青 =====
val Mint = Color(0xFF7ECEC0)           // 辅色（看自己时 / 轨迹按钮）
val MintSoft = Color(0xFFA8DDD2)       // 薄荷浅
val MintDeep = Color(0xFF3A9E91)       // 薄荷深（文字 / 按钮）

// ===== 文字色 =====
val Ink = Color(0xFF3D2E2A)            // 主文字
val InkSoft = Color(0xFF6B5851)        // 副文字
val Muted = Color(0xFFA89890)          // 弱化文字 / 标签
val Line = Color(0x143D2E2A)           // 分割线（8% 透明度）

// ===== 状态色 =====
val StatusGreen = Color(0xFF43C672)    // 在线 / 正常
val StatusAmber = Color(0xFFD68842)    // 警告 / 几分钟前
val StatusGray = Color(0xFFA89890)     // 离线

// ===== 智能手环卡片渐变色 =====
val HeartStart = Color(0xFFFF6B7E)
val HeartEnd = Color(0xFFFFA0B0)
val SleepStart = Color(0xFF6B7FBF)
val SleepEnd = Color(0xFF9DAFDD)
val StepsStart = Color(0xFF3F8E80)
val StepsEnd = Color(0xFF7ECEC0)
val StressStart = Color(0xFFA8855E)
val StressEnd = Color(0xFFD4B58F)

// ===== 玻璃态 =====
val GlassWhite = Color(0xC7FFFFFF)     // rgba(255,255,255,0.78)
val GlassStrongWhite = Color(0xF2FFFFFF) // rgba(255,255,255,0.95)
val CardBg = Color(0x99FFFFFF)         // rgba(255,255,255,0.6) 卡片底
val ChipBg = Color(0x1FFF8B7B)         // rgba(255,139,123,0.12) 珊瑚 chip 底
val ChipBgCool = Color(0x267ECEC0)     // rgba(126,206,192,0.15) 薄荷 chip 底
val FieldBg = Color(0xB3FFFFFF)        // rgba(255,255,255,0.7) 输入框底

// ===== 阴影（用于 shadow  modifier 的 ambient/spot color） =====
val ShadowCard = Color(0x1A3D2E2A)     // rgba(61,46,42,0.10)
val ShadowBubble = Color(0x4DFF8B7B)   // rgba(255,139,123,0.30)
val ShadowButton = Color(0x59FF8B7B)   // rgba(255,139,123,0.35)

// ===== 白色 =====
val PureWhite = Color(0xFFFFFFFF)
