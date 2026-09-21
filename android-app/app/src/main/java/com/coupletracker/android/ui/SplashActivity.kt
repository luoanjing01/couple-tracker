package com.coupletracker.android.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.coupletracker.android.BuildConfig
import com.coupletracker.android.data.UserRepository
import com.coupletracker.android.service.TrackerService
import com.coupletracker.android.ui.theme.CoralDeep
import com.coupletracker.android.ui.theme.CoralSoft
import com.coupletracker.android.ui.theme.TidalTheme
import com.coupletracker.android.ui.tidal.TidalLogoMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 启动屏（潮汐设计版）。
 *
 * 视觉：160° 珊瑚渐变底 + 装饰圆环/光斑 + 玻璃质感双心 Logo + 底部波浪 loader。
 * 逻辑：展示约 1.2s，期间读取登录状态，然后跳转 MainActivity（已登录）或
 * LoginActivity（未登录），并 finish 自己避免回退。
 *
 * 说明：继承 ComponentActivity，以便直接使用 activity-compose 的 setContent 扩展。
 */
class SplashActivity : androidx.activity.ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸式：内容延伸到状态栏/导航栏区域
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            TidalTheme(darkStatusBarIcons = false) {
                SplashScreen()
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            val loggedIn = runBlocking(Dispatchers.IO) {
                UserRepository.get().isLoggedIn()
            }
            if (loggedIn) {
                runCatching { TrackerService.start(this@SplashActivity) }
                startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            } else {
                startActivity(Intent(this@SplashActivity, LoginActivity::class.java))
            }
            finish()
        }, SPLASH_DELAY_MS)
    }

    private companion object {
        // 启动屏停留时长：过短会让波浪动画一闪而过，1.2s 兼顾品牌展示与启动速度
        private const val SPLASH_DELAY_MS = 1200L
    }
}

/** 开屏渐变（与设计稿 --grad-deep 一致：160deg, #FF7A6A → #FFB5A7 60% → #FFD4C4） */
private val SplashGradient = Brush.linearGradient(
    colorStops = arrayOf(
        0.0f to CoralDeep,
        0.6f to CoralSoft,
        1.0f to Color(0xFFFFD4C4)
    )
)

@Composable
private fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplashGradient)
    ) {
        // ===== 装饰圆环 =====
        Ring(modifier = Modifier.offset(x = 160.dp, y = (-100).dp), size = 520.dp, alpha = 0.18f)
        Ring(modifier = Modifier.offset(x = (-120).dp, y = 520.dp), size = 380.dp, alpha = 0.14f)
        Ring(modifier = Modifier.offset(x = 60.dp, y = 140.dp), size = 220.dp, alpha = 0.10f)

        // ===== 柔光斑 =====
        Blob(modifier = Modifier.offset(x = 240.dp, y = 80.dp), size = 180.dp, color = Color(0xFFFFD4C4))
        Blob(modifier = Modifier.offset(x = 30.dp, y = 560.dp), size = 160.dp, color = Color(0xFFFFE4D1))

        // ===== 中央内容：Logo + 名称 =====
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 玻璃质感 Logo 容器
            Box(
                modifier = Modifier
                    .size(108.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color.White.copy(alpha = 0.18f))
                    .border(1.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(32.dp)),
                contentAlignment = Alignment.Center
            ) {
                TidalLogoMark(size = 64.dp)
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = "潮汐",
                color = Color.White,
                fontSize = 46.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "CHAO XIA · TIDE",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.4.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "两人世界，一屏相连",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.1.sp
            )
        }

        // ===== 底部：波浪 loader + 版本号 =====
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            WaveLoader()
            Spacer(Modifier.height(14.dp))
            Text(
                text = "VERSION ${BuildConfig.VERSION_NAME} · 让爱更近",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.8.sp
            )
        }
    }
}

/** 装饰圆环：只有描边的空心圆 */
@Composable
private fun Ring(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp, alpha: Float) {
    Box(
        modifier = modifier
            .size(size)
            .border(1.5.dp, Color.White.copy(alpha = alpha), CircleShape)
    )
}

/** 柔光斑：半透明实心圆（模拟 blur 的光晕效果） */
@Composable
private fun Blob(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp, color: Color) {
    Box(
        modifier = modifier
            .size(size)
            .alpha(0.5f)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.55f))
    )
}

/**
 * 波浪 loader：5 根竖条按 0.4→1→0.4 做 scaleY 呼吸动画，
 * 每根延迟 150ms 依次起伏，模拟潮汐波浪。
 */
@Composable
private fun WaveLoader() {
    val transition = rememberInfiniteTransition(label = "wave")
    // 5 根条各自的动画进度
    val scales = List(5) { index ->
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
                // 用 initialStartOffset 实现 CSS animation-delay 的效果
                initialStartOffset = androidx.compose.animation.core.StartOffset(index * 150)
            ),
            label = "wave$index"
        )
    }
    // 各条基准高度（与设计稿一致：8/14/18/14/8）
    val heights = listOf(8.dp, 14.dp, 18.dp, 14.dp, 8.dp)

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(18.dp)
    ) {
        heights.forEachIndexed { index, h ->
            val scale by scales[index]
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(h)
                    .graphicsLayer {
                        scaleY = scale
                        // 以底部为缩放锚点，竖条向上生长
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                    }
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White)
            )
        }
    }
}
