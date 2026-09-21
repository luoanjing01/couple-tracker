package com.coupletracker.android.ui.tidal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 潮汐双心波浪 Logo —— 与设计稿 concept-d-splash-login.html 中的 SVG 1:1 对应。
 *
 * SVG viewBox 为 64×64，包含：
 *   - 左右两颗相向的爱心（白色描边 + 半透明白填充）
 *   - 底部一条波浪线（Q/T 二次贝塞尔）
 *   - 顶部一颗小圆点
 *
 * @param tint 描边与圆点颜色（开屏/登录 hero 上为白色）
 * @param fill 爱心内部填充色（半透明白）
 */
@Composable
fun TidalLogoMark(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    tint: Color = Color.White,
    fill: Color = Color.White.copy(alpha = 0.18f)
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        // 以 64 为基准做等比缩放
        val s = w / 64f
        fun x(v: Float) = v * s
        fun y(v: Float) = v * s

        val stroke = Stroke(
            width = 2.5f * s,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )

        // 左心：M22 20 C18 20 14 23 14 28 C14 34 22 40 26 43 C26 43 32 38 32 32
        val leftHeart = Path().apply {
            moveTo(x(22f), y(20f))
            cubicTo(x(18f), y(20f), x(14f), y(23f), x(14f), y(28f))
            cubicTo(x(14f), y(34f), x(22f), y(40f), x(26f), y(43f))
            cubicTo(x(26f), y(43f), x(32f), y(38f), x(32f), y(32f))
        }
        // 右心：M42 20 C46 20 50 23 50 28 C50 34 42 40 38 43 C38 43 32 38 32 32
        val rightHeart = Path().apply {
            moveTo(x(42f), y(20f))
            cubicTo(x(46f), y(20f), x(50f), y(23f), x(50f), y(28f))
            cubicTo(x(50f), y(34f), x(42f), y(40f), x(38f), y(43f))
            cubicTo(x(38f), y(43f), x(32f), y(38f), x(32f), y(32f))
        }
        drawPath(leftHeart, color = fill)
        drawPath(rightHeart, color = fill)
        drawPath(leftHeart, color = tint, style = stroke)
        drawPath(rightHeart, color = tint, style = stroke)

        // 波浪：M16 50 Q22 46 28 50 T40 50 T52 50
        val wave = Path().apply {
            moveTo(x(16f), y(50f))
            quadraticBezierTo(x(22f), y(46f), x(28f), y(50f))
            // T 指令 = 反射前一个控制点
            quadraticBezierTo(x(34f), y(54f), x(40f), y(50f))
            quadraticBezierTo(x(46f), y(46f), x(52f), y(50f))
        }
        drawPath(wave, color = tint, style = stroke)

        // 顶部圆点：circle(32,14,r=2.5)
        drawCircle(color = tint, radius = 2.5f * s, center = Offset(x(32f), y(14f)))
    }
}
