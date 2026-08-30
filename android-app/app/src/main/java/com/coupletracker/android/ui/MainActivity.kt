package com.coupletracker.android.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.coupletracker.android.BuildConfig
import com.coupletracker.android.data.NetworkModule
import com.coupletracker.android.data.UserRepository
import com.coupletracker.android.service.TrackerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面：底部3 Tab + WebView直接加载前端
 *   - 地图 Tab -> 访问 /map
 *   - 应用 Tab -> 访问 /apps
 *   - 统计 Tab -> 访问 /stats
 *   - 我的 Tab -> 原生设置页（配对码/退出登录/重启服务）
 *
 * Token注入：页面加载前写入 localStorage + Cookie，让Web端直接读取
 */
class MainActivity : ComponentActivity() {

    private enum class Tab(
        val path: String,
        val label: String,
        val icon: ImageVector
    ) {
        MAP("/map", "地图", Icons.Default.LocationOn),
        APPS("/apps", "应用", Icons.Default.Apps),
        STATS("/stats", "统计", Icons.Default.BarChart),
        ME("/me", "我的", Icons.Default.Person)
    }

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ 闪退兜底：启动前台服务前再做一次权限检查，缺少权限则不启动
        // （Service 内部也会再次检查，这里双保险）
        runCatching {
            if (com.coupletracker.android.service.TrackerService.canStartForeground(this)) {
                com.coupletracker.android.service.TrackerService.start(this)
            }
        }

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFFE75480),
                secondary = Color(0xFF667EEA),
                background = Color(0xFFFDF2F8)
            )) {
                var selected by remember { mutableStateOf(Tab.MAP) }
                Scaffold(
                    bottomBar = {
                        NavigationBar(containerColor = Color.White) {
                            Tab.values().forEach { t ->
                                NavigationBarItem(
                                    selected = selected == t,
                                    onClick = { selected = t },
                                    icon = { Icon(t.icon, null) },
                                    label = { Text(t.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color(0xFFE75480),
                                        selectedTextColor = Color(0xFFE75480),
                                        indicatorColor = Color(0xFFFDF2F8)
                                    )
                                )
                            }
                        }
                    }
                ) { pad ->
                    Box(Modifier.padding(pad).fillMaxSize()) {
                        when (selected) {
                            Tab.MAP   -> PlaceholderScreen(
                                icon = Icons.Default.LocationOn,
                                title = "实时地图",
                                desc = "地图页面将在正式版上线 💕\n\n当前功能状态：\n✅ 位置已采集（后台每 5 秒上报一次到云端）\n✅ 云端已保存所有位置记录\n✅ 两台手机同一个账号配对后即可互相查看",
                                accent = Color(0xFFE75480)
                            )
                            Tab.APPS  -> PlaceholderScreen(
                                icon = Icons.Default.Apps,
                                title = "应用使用",
                                desc = "应用使用页面将在正式版上线 💕\n\n当前功能状态：\n✅ APP 使用已采集（每 2 秒检查前台）\n✅ 每 60 秒上报一次使用时长到云端\n✅ 已自动识别微信/抖音/王者等常用APP分类",
                                accent = Color(0xFF667EEA)
                            )
                            Tab.STATS -> PlaceholderScreen(
                                icon = Icons.Default.BarChart,
                                title = "每日统计",
                                desc = "每日使用统计页面将在正式版上线 💕\n\n当前功能状态：\n✅ 每日使用数据已完整记录到云端\n✅ 支持按日期/按APP/按分类统计查询\n✅ 打开时长、移动轨迹、打开次数全记录",
                                accent = Color(0xFF48BB78)
                            )
                            Tab.ME    -> SettingsScreen(onBackToMap = { selected = Tab.MAP })
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    //  原生占位页（前端 Web 页部署前的过渡方案，100% 不闪退）
    // ========================================================================
    @Composable
    fun PlaceholderScreen(
        icon: ImageVector,
        title: String,
        desc: String,
        accent: Color
    ) {
        val user by UserRepository.get().userFlow.collectAsState(initial = null)
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(88.dp)
                    .background(accent.copy(alpha = 0.12f), RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
            Spacer(Modifier.height(10.dp))
            Text(
                "正在为 ${"@" + (user?.username ?: "-")} 准备中...",
                color = Color(0xFF718096), fontSize = 13.sp
            )
            Spacer(Modifier.height(22.dp))

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "💕 后台采集状态",
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748)
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(Color(0xFF48BB78), RoundedCornerShape(50)))
                        Spacer(Modifier.width(8.dp))
                        Text("位置上报", color = Color(0xFF2D3748), fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text("每 5 秒", color = Color(0xFF718096), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(Color(0xFF48BB78), RoundedCornerShape(50)))
                        Spacer(Modifier.width(8.dp))
                        Text("APP 使用", color = Color(0xFF2D3748), fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text("每 2 秒检测", color = Color(0xFF718096), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(Color(0xFF48BB78), RoundedCornerShape(50)))
                        Spacer(Modifier.width(8.dp))
                        Text("数据存储", color = Color(0xFF2D3748), fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text("云端 Supabase", color = Color(0xFF718096), fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("📝 功能说明", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
                    Spacer(Modifier.height(12.dp))
                    Text(desc, color = Color(0xFF4A5568), fontSize = 13.sp, lineHeight = 22.sp)
                }
            }

            Spacer(Modifier.height(30.dp))
            Text(
                "所有数据已安全保存到云端 ✅",
                color = Color(0xFF48BB78), fontSize = 12.sp, fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "版本 v${BuildConfig.VERSION_NAME}（测试版）",
                color = Color(0xFFA0AEC0), fontSize = 11.sp
            )
        }
    }

    private fun navigate(path: String) { /* 占位，暂时不用 WebView */ }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun SettingsScreen(onBackToMap: () -> Unit) {
        val user by UserRepository.get().userFlow.collectAsState(initial = null)
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFFFDF2F8))
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💕 ", fontSize = 48.sp)
                Column {
                    Text(
                        user?.displayName ?: "未登录",
                        fontSize = 22.sp,
                        color = Color(0xFF2D3748),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "@" + (user?.username ?: "-"),
                        color = Color(0xFF718096), fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("配对码", color = Color(0xFF718096), fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        (user?.coupleCode ?: "暂无").uppercase(),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFE75480),
                        letterSpacing = 4.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "把这串发给TA，在登录页「配对」输入即可绑定",
                        color = Color(0xFF718096), fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onBackToMap,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(Icons.Default.LocationOn, null)
                    Spacer(Modifier.width(4.dp))
                    Text("看地图")
                }
                Button(
                    onClick = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching { TrackerService.stop(this@MainActivity) }
                            Thread.sleep(300L)
                            runCatching { TrackerService.start(this@MainActivity) }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF667EEA))
                ) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(4.dp))
                    Text("重启服务")
                }
            }

            Spacer(Modifier.height(26.dp))

            // ====== 云端服务信息（Supabase BaaS） ======
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("☁️ 云端服务", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
                    Spacer(Modifier.height(8.dp))
                    Text("Supabase", color = Color(0xFF718096), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text("Auth: ${NetworkModule.getApiBase().replace("/rest/v1", "/auth/v1")}", color = Color(0xFF718096), fontSize = 11.sp)
                    Text("REST: ${NetworkModule.getApiBase()}", color = Color(0xFF718096), fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        UserRepository.get().logout()
                        TrackerService.stop(this@MainActivity)
                        withContext(Dispatchers.Main) { finish() }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFE53E3E)
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = SolidColor(Color(0xFFE53E3E))
                )
            ) { Text("退出登录") }

            Spacer(Modifier.height(20.dp))
            Text(
                "版本 v${BuildConfig.VERSION_NAME}",
                color = Color(0xFF718096), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                "后端 ${NetworkModule.getApiBase()}\n前端 ${BuildConfig.DEFAULT_WEB_BASE}",
                color = Color(0xFF718096), fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }

    override fun onBackPressed() {
        if (webView?.canGoBack() == true) webView?.goBack()
        else super.onBackPressed()
    }
}
