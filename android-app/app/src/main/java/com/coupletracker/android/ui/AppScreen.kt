// =====================================================================
// 文件：AppScreen.kt
// 作用：情侣追踪 App 的「状态」主界面（方案 D · 潮汐卡片风格）。
//       这是用户看到的主页面，包含 3 个模块：
//         ① 当前正在使用（大卡片，实时显示对方/自己正在玩的 App）
//         ② 手机状态（电量/网络/在线状态/心情，网格小卡片）
//         ③ 智能手环（占位卡片，标记「正在开发」）
// 作者：coupletracker 团队
// =====================================================================

// package 声明：声明本文件所属的包路径，对应文件夹层级
package com.coupletracker.android.ui

// ---- Android 系统服务相关 import（用于读取电量、网络、App 使用情况等系统信息）----
import android.app.AppOpsManager              // 用于检查「使用情况访问权限」
import android.content.BroadcastReceiver      // 广播接收器基类（监听电量变化、亮熄屏）
import android.app.usage.UsageEvents           // App 使用事件（前台/后台切换）
import android.app.usage.UsageStatsManager     // 查询 App 使用统计的核心 API
import android.content.Context                // Android 上下文，访问系统服务的入口
import android.content.Intent                 // 意图对象，用于注册广播
import android.content.IntentFilter            // 广播过滤器，指定要监听哪些广播
import android.content.pm.ApplicationInfo     // App 信息（包名、分类等）
import android.net.ConnectivityManager        // 网络连接管理器
import android.net.NetworkCapabilities        // 网络能力描述（WiFi/蜂窝等）
import android.net.NetworkRequest             // 网络请求构建器
import android.net.wifi.WifiManager           // WiFi 管理（读取 SSID）
import android.os.BatteryManager              // 电池信息常量
import android.os.Build                        // 系统版本信息（用于 API 兼容判断）
import android.os.PowerManager                 // 电源管理（判断亮屏/熄屏）
import android.os.Process                      // 进程信息（拿本应用 UID）

// ---- Jetpack Compose UI 框架相关 import ----
import androidx.compose.foundation.background               // 背景色修饰符
import androidx.compose.foundation.clickable                // 可点击修饰符
import androidx.compose.foundation.layout.*                 // 布局相关（Box/Column/Row/Spacer 等）
import androidx.compose.foundation.layout.ExperimentalLayoutApi  // 实验性布局 API（FlowRow 需要）
import androidx.compose.foundation.rememberScrollState      // 记住滚动位置
import androidx.compose.foundation.shape.RoundedCornerShape // 圆角形状
import androidx.compose.foundation.verticalScroll           // 纵向滚动修饰符
import androidx.compose.material.pullrefresh.PullRefreshIndicator      // 下拉刷新指示器（旧 Material API）
import androidx.compose.material.pullrefresh.pullRefresh               // 下拉刷新修饰符
import androidx.compose.material.pullrefresh.rememberPullRefreshState  // 下拉刷新状态记忆

import androidx.compose.material.icons.Icons                // 图标库（本文件未直接使用，保留以便扩展）

import androidx.compose.material3.*                          // Material3 组件（Card/Button/Text 等）
import androidx.compose.runtime.*                            // Compose 运行时（remember/mutableStateOf 等）
import androidx.compose.runtime.saveable.rememberSaveable    // 可保存的状态（横屏旋转后保留）
import androidx.compose.ui.Alignment                         // 对齐方式（居中、顶部等）
import androidx.compose.ui.Modifier                          // 修饰符链（Compose 的核心装饰机制）
import androidx.compose.ui.graphics.Color                    // 颜色定义
import androidx.compose.ui.graphics.Brush                   // 渐变画刷（方案 D）
import androidx.compose.ui.platform.LocalContext              // 获取当前 Android Context
import androidx.compose.ui.text.font.FontWeight               // 字重（粗细）
import androidx.compose.ui.unit.dp                            // 密度无关像素单位
import androidx.compose.ui.unit.sp                            // 缩放像素单位（字体大小）

// ---- 项目内部数据层 import ----
import com.coupletracker.android.data.AppUsageRow       // App 使用记录数据类（对应云端 app_usage 表）
import com.coupletracker.android.data.LocationRow       // 位置记录数据类（对应云端 locations 表）
import com.coupletracker.android.data.AppSessionTracker // App 会话追踪单例（进程内累计时长、当前心情）
import com.coupletracker.android.data.NetworkModule     // 网络模块（Retrofit/Supabase 客户端）
import com.coupletracker.android.data.UserRepository    // 用户仓库（管理当前登录用户信息）
import com.coupletracker.android.service.TrackerService // 后台追踪服务（上报位置/使用情况）

// ---- Kotlin 协程相关 import ----
import kotlinx.coroutines.Dispatchers     // 协程调度器（IO=后台线程，Main=主线程）
import kotlinx.coroutines.delay            // 协程延迟（非阻塞式 sleep）
import kotlinx.coroutines.isActive         // 判断协程是否仍在运行
import kotlinx.coroutines.launch           // 启动协程
import kotlinx.coroutines.withContext      // 切换协程上下文（线程切换）

// ---- Java 时间相关 import ----
import java.time.LocalDate               // 日期（年-月-日）
import java.time.ZoneId                  // 时区 ID
import java.time.format.DateTimeFormatter // 日期时间格式化器

/**
 * 应用 Tab：3 个模块
 *   ① 🎯 当前正在使用（实时大卡片）
 *   ② 📱 手机状态（电量/网络/在线状态）
 *   ③ ⌚ 智能手环（占位卡片，正在开发）
 *
 * 数据源：
 *   - 看自己：UsageStatsManager + BatteryManager + ConnectivityManager 本地实时
 *   - 看 TA：Supabase app_usage + locations 表（有 1-2 分钟延迟，正常）
 *
 * 【初学者理解】
 *   @Composable：标记这是一个 Compose 可组合函数，可以像组件一样使用。
 *   @OptIn(...ExperimentalMaterial3Api...)：声明要使用 Material3 实验性 API（如下拉刷新）。
 *   「AppScreen()」没有参数，因为它是页面级根组件，所需数据在内部自行收集。
 *   Compose 函数的特点：状态变化会自动触发 UI 重绘，所以重点是管理「状态」。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
fun AppScreen() {
    // ---- 1. 获取基础上下文和当前登录用户 ----
    val ctx = LocalContext.current                                // 当前 Android Context，用于访问系统服务
    val user by UserRepository.get().userFlow.collectAsState(initial = null) // 订阅登录用户流，初次为 null
    val myId = user?.id.orEmpty()                                  // 当前用户 ID（可能为空字符串）
    val myCode = user?.coupleCode.orEmpty()                        // 当前用户的配对码（旧机制）

    // ---- 2. 定义 UI 状态变量（用 remember + mutableStateOf 保持 Compose 状态）----
    // 说明：Compose 用「状态驱动 UI」，状态变化会自动重绘对应组件。
    var showPartner by remember { mutableStateOf(false) }         // 是否正在查看对方（默认看自己）
    var partnerId by remember { mutableStateOf<String?>(null) }  // 对方用户 ID（未配对时为 null）
    var partnerName by remember { mutableStateOf("") }            // 对方昵称
    var partnerLoaded by remember { mutableStateOf(false) }       // 对方信息是否加载完毕
    var reloadKey by remember { mutableStateOf(0) }               // 刷新钥匙，+1 后子组件会重新拉数据

    // ---- 3. 下拉刷新配置 ----
    var isRefreshing by remember { mutableStateOf(false) }        // 当前是否在下拉刷新
    val scrollState = rememberScrollState()                       // 记住滚动位置
    val refreshScope = rememberCoroutineScope()                   // 创建协程作用域（用于在 Composable 里启动协程）
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,                                // 绑定刷新状态
        onRefresh = {
            // 用户下拉触发刷新时的回调
            isRefreshing = true
            reloadKey++                                            // 让所有子卡片重新拉取数据
            refreshScope.launch {
                delay(1500)                                       // 至少转 1.5 秒动画，避免刷新太快闪退感
                isRefreshing = false                              // 关闭刷新指示器
            }
        }
    )

    // ---- 4. 查询配对对方的信息（启动时执行一次，myCode/myId 变化时重新执行）----
    // ✅ 优先用 partner_id 查（配对码不再共享，每个人有独立码）
    //    兼容旧数据：没 partner_id 的用 couple_code 查
    //
    // 【LaunchedEffect】= Compose 的副作用 API：参数变化时执行一次，离开时取消。
    LaunchedEffect(myCode, myId) {
        // 先清空旧值，准备重新加载
        partnerId = null; partnerName = ""; partnerLoaded = false
        val myPartnerId = user?.partnerId                          // 自己的「对方 ID」字段
        // UUID 正则：8-4-4-4-12 的十六进制字符串（如 550e8400-e29b-41d4-a716-446655440000）
        val uuidRe = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
        if (!myPartnerId.isNullOrBlank() && uuidRe.matches(myPartnerId)) {
            // 走网络请求拿对方资料，必须切到 IO 线程避免阻塞 UI
            withContext(Dispatchers.IO) {
                runCatching {
                    // 通过 REST 查 profile 表，按 id 过滤
                    NetworkModule.restService.getProfile(id = myPartnerId)
                }.getOrNull()?.body()?.firstOrNull()?.let { p ->
                    // 查到了：保存 ID 和昵称（昵称为空时回退用 username）
                    partnerId = p.id
                    partnerName = p.nickname.ifBlank { p.username }
                }
                partnerLoaded = true                              // 标记加载完成（无论查没查到）
            }
        } else {
            // 没有 partner_id → 直接标记完成
            partnerLoaded = true
        }
    }

    // ---- 5. 计算当前要展示的主体（自己 or 对方）----
    val subjectId = (if (showPartner) partnerId else myId) ?: ""                       // 展示目标的用户 ID
    val subjectName = if (showPartner) partnerName.ifBlank { "TA" } else (user?.displayName ?: "我") // 展示名字
    val subjectIsMe = !showPartner                                                      // 当前是否在看自己

    // ---- 6. 页面根容器：奶油渐变背景（方案 D · 潮汐卡片）+ 下拉刷新支持 ----
    Box(
        Modifier
            .fillMaxSize()                                         // 占满整个屏幕
            .background(Brush.verticalGradient(listOf(Color(0xFFFFF8F0), Color(0xFFFFE4D1))))  // 奶油 → 蜜桃渐变
            .pullRefresh(pullRefreshState)                         // 让本容器支持下拉刷新手势
    ) {
        // 下拉刷新指示器（顶部转圈圈）
        PullRefreshIndicator(
            refreshing = isRefreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),     // 顶部居中
            contentColor = Color(0xFFFF8B7B)                      // 珊瑚粉主题
        )

        // 主内容列，纵向滚动
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)                     // 启用纵向滚动
                .padding(horizontal = 16.dp, vertical = 18.dp)   // 内边距
        ) {
        // ---- 顶部标题 + 切换按钮 ----
        // 一行：左边标题，右边切换按钮（看自己/看 TA）
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (showPartner) "$subjectName 的手机状态" else "我的手机状态",
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3D2E2A)
            )
            Spacer(Modifier.weight(1f))                            // 弹性空白把按钮推到右边
            // 切换按钮始终显示：有配对→切换查看对方，无配对→提示
            Button(
                onClick = {
                    if (partnerId == null) {
                        // 无配对，不切换（按钮只是提示状态）
                    } else {
                        showPartner = !showPartner; reloadKey++   // 切换并刷新
                    }
                },
                shape = RoundedCornerShape(20.dp),                // 圆角药丸形按钮
                colors = ButtonDefaults.buttonColors(
                    // 颜色随状态变化：未配对沙灰 / 看对方薄荷绿 / 看自己珊瑚粉
                    containerColor = if (partnerId == null) Color(0xFFD8C7BA)
                    else if (showPartner) Color(0xFF3A9E91) else Color(0xFFFF8B7B)
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    // 文本随状态变化：未配对"💤 未配对" / 看对方时显示"👤 我" / 看自己时显示"💕 TA"
                    if (partnerId == null) "💤 未配对"
                    else if (showPartner) "👤 我" else "💕 TA",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Spacer(Modifier.height(10.dp))                            // 模块之间留白

        if (partnerId != null) {
            Spacer(Modifier.height(4.dp))                         // 已配对时多留一点白
        }

        // ============= ① 手机状态 =============
        // 2x2 网格小卡片：电量、网络、状态、心情
        PhoneStatusCard(
            subjectId = subjectId,
            subjectName = subjectName,
            subjectIsMe = subjectIsMe,
            reloadKey = reloadKey
        )

        Spacer(Modifier.height(8.dp))

        // ============= ② 当前正在使用 =============
        // 渐变珊瑚大卡片，显示主体当前正在玩的 App（或休息中）
        CurrentAppCard(
            subjectId = subjectId,
            subjectName = subjectName,
            subjectIsMe = subjectIsMe,
            // 看自己时需要检查是否有「使用情况访问」权限；看对方时云端已有，默认 true
            subjectHasPermission = if (subjectIsMe) localHasUsagePermission(ctx) else true,
            reloadKey = reloadKey
        )

        Spacer(Modifier.height(14.dp))

        // ============= ③ 智能手环（占位：正在开发）=============
        BandSection()

        Spacer(Modifier.height(20.dp))
        }  // closes Column
    }      // closes Box
}          // closes AppScreen

// =====================================================================
// ① 🎯 当前正在使用 —— 大卡片
// 这个 Composable 渲染一张大卡片，展示主体（自己或对方）当前正在玩的 App。
//   - 看自己：本地每 3 秒查一次前台 App + 时长
//   - 看对方：每 15 秒拉一次云端最新记录
// 当无活动 / 熄屏 / 没权限时，会显示对应的提示文案与图标。
// =====================================================================
@Composable
private fun CurrentAppCard(
    subjectId: String,              // 展示目标的用户 ID
    subjectName: String,            // 展示名字（"我" / 对方昵称 / "TA"）
    subjectIsMe: Boolean,           // 是否在看自己
    subjectHasPermission: Boolean,  // 是否有「使用情况访问」权限（仅自己时检查）
    reloadKey: Int                  // 刷新钥匙，变化时重新启动轮询
) {
    val ctx = LocalContext.current

    // 本地实时查自己的前台 APP（名字/分类）
    var fgPkg by remember { mutableStateOf("") }       // 当前前台 App 包名
    var fgName by remember { mutableStateOf("") }       // 当前前台 App 显示名（如「微信」）
    var fgCategory by remember { mutableStateOf("") }   // 当前前台 App 分类（如「社交」）

    // ✅ 自己的累计时长直接读 AppSessionTracker 单例（进程存活就不丢）
    var sessionSeconds by remember { mutableStateOf(0) } // 当前 App 已用秒数
    // 熄屏状态
    var screenOn by remember { mutableStateOf(true) }    // 屏幕是否点亮

    // 远端查 TA 的（60 秒精度）
    var remoteAppName by remember { mutableStateOf("") } // 对方当前 App 名
    var remotePkg by remember { mutableStateOf("") }     // 对方当前 App 包名
    var remoteSeconds by remember { mutableStateOf(0) }  // 对方该 App 已用秒数
    var remoteUpdateAt by remember { mutableStateOf(0L) } // 对方记录的最后更新时间戳

    // 自己：每 3 秒查一次前台 APP 名字 + 时长 + 屏幕状态
    // 【轮询逻辑】用 while(isActive) delay(3000) 形成无限循环，每 3 秒刷新一次
    LaunchedEffect(subjectIsMe, reloadKey) {
        if (subjectIsMe) {
            if (!subjectHasPermission) return@LaunchedEffect  // 没权限就不查
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager // 拿电源服务判断亮屏
            while (isActive) {
                screenOn = pm.isInteractive                          // 是否亮屏
                if (screenOn) {
                    runCatching {
                        // 查询当前前台 App，返回 (包名, 显示名)
                        val current = queryForegroundApp(ctx)
                        if (current != null && current.first.isNotEmpty()) {
                            val (pkg, name) = current
                            val cat = categoryOf(ctx, pkg)            // 推断 App 分类
                            if (pkg != fgPkg) {
                                // 切换了 App → 更新包名、显示名、分类
                                fgPkg = pkg; fgName = name; fgCategory = cat
                            } else {
                                fgName = name                          // 同 App → 只更新显示名
                            }
                            // ✅ 写入单例 + 读秒数
                            AppSessionTracker.setCurrentApp(pkg, name)
                            sessionSeconds = AppSessionTracker.sessionSeconds()
                        }
                    }
                } else {
                    // 熄屏 → 清空当前正在玩
                    fgPkg = ""; fgName = ""; sessionSeconds = 0
                }
                delay(3000)                                            // 等 3 秒再查
            }
        }
    }

    // TA：每 15 秒拉一次云端 app_usage 最新记录
    // 【远端逻辑】通过 REST 查询 Supabase 的 app_usage 表，按创建时间倒序取一条
    LaunchedEffect(subjectIsMe, subjectId, reloadKey) {
        if (!subjectIsMe && subjectId.isNotBlank()) {
            while (isActive) {
                withContext(Dispatchers.IO) {                        // 切到 IO 线程做网络请求
                    runCatching {
                        // 构造查询：user_id = 当前对方 ID，按 created_at 倒序，只取 1 条
                        NetworkModule.restService.getAppUsage(userId = "eq.$subjectId", order = "created_at.desc", limit = 1)
                    }.getOrNull()?.body()?.firstOrNull()?.let { row ->
                        remotePkg = row.package_name
                        remoteAppName = row.app_name ?: row.package_name
                        remoteSeconds = row.usage_seconds
                        remoteUpdateAt = parseIsoTime(row.created_at)  // 把 ISO 字符串转毫秒时间戳
                    }
                }
                delay(15_000)                                         // 等 15 秒再拉
            }
        }
    }

    // 方案 D：渐变「正在使用」卡片（珊瑚粉 → 柔珊瑚），白色文字
    val nowPlayingBrush = Brush.linearGradient(listOf(Color(0xFFFF8B7B), Color(0xFFFFB5A7)))
    Box(
        Modifier
            .fillMaxWidth()
            .background(nowPlayingBrush, RoundedCornerShape(18.dp))
            .padding(20.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            // ✅ 30分钟无活动 → 标题显示"正在休息"
            val now = System.currentTimeMillis()
            // 判断是否空闲 30 分钟：自己看屏幕状态 + 是否有前台 App；对方看最后更新时间是否超过 30 分钟
            val isIdle30min = if (subjectIsMe) {
                !screenOn || fgPkg.isEmpty()
            } else {
                remoteUpdateAt == 0L || (now - remoteUpdateAt) > 30 * 60 * 1000
            }
            Text(
                // 顶部小标题：根据是否空闲、是否是自己显示不同文案
                if (isIdle30min) (if (subjectIsMe) "正在休息" else "$subjectName 正在休息") else (if (subjectIsMe) "正在玩" else "$subjectName 正在玩"),
                fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))

            // ---- 卡片正文：根据不同状态展示不同内容（if/else if 链）----
            if (subjectIsMe && !subjectHasPermission) {
                // 没权限 —— 引导去开
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("⚠️ 未授予「使用情况访问」权限", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("去手机设置 → 应用 → 特殊权限 → 使用情况访问 → 允许 小世界",
                        fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f))
                }
            } else if (subjectIsMe && !screenOn) {
                // 熄屏状态：显示月亮 emoji + 提示
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("🌙", fontSize = 36.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("${subjectName} 熄屏中", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            } else if (subjectIsMe && fgPkg.isEmpty()) {
                // 亮屏但没查到前台 App：可能在桌面/切换中
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("💤", fontSize = 36.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("${subjectName} 正在休息", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            } else if (!subjectIsMe && isIdle30min) {
                // TA 30分钟无活动 → 正在休息
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("💤", fontSize = 36.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("${subjectName} 正在休息", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            } else if (!subjectIsMe && remoteAppName.isEmpty()) {
                // 对方暂无云端记录（可能没启动后台服务/没联网）
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("🤔", fontSize = 36.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("${subjectName} 暂无使用记录", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            } else {
                // 有 APP 使用数据 → 左图标、右名字+时长
                val appEmoji = categoryEmoji(if (subjectIsMe) fgCategory else "")  // 分类对应 emoji
                val appName = if (subjectIsMe) fgName else remoteAppName             // 显示名
                val durationSec = if (subjectIsMe) sessionSeconds else remoteSeconds // 已用秒数
                val duration = formatDuration(durationSec)                          // 格式化如 "1h 23m"

                // 一行布局：左边 emoji 图标，右边 App 名 + 时长
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 左：半透明白底圆角块里放 emoji
                    Box(
                        Modifier.size(52.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) { Text(appEmoji, fontSize = 26.sp) }
                    Spacer(Modifier.width(14.dp))
                    // 右：App 名 + 时长描述
                    Column(Modifier.weight(1f)) {
                        Text(
                            appName, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                            color = Color.White, maxLines = 1
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            // 自己显示"已使用 X" / 对方显示"最近一次 · X"
                            if (subjectIsMe) "已使用 $duration" else "最近一次 · $duration",
                            fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
    }
}

// =====================================================================
// ② 📱 手机状态 —— 网格小卡片
// 这个 Composable 渲染一张含 4 个小卡片的网格：电量 / 网络 / 状态（开关机/熄屏/充电） / 心情。
//   - 看自己：注册系统广播（电量、亮熄屏）+ 网络回调，实时获取
//   - 看对方：每 20 秒拉一次云端 locations 表最新一条，根据时间戳判断在线
// 心情卡片只有看自己时可点击，从弹窗选择 emoji，写入单例 AppSessionTracker。
// =====================================================================
@OptIn(ExperimentalLayoutApi::class)   // FlowRow（流式布局）属于实验性 API，需声明 OptIn
@Composable
private fun PhoneStatusCard(
    subjectId: String,
    subjectName: String,
    subjectIsMe: Boolean,
    reloadKey: Int
) {
    val ctx = LocalContext.current

    // 本地状态：自己的设备状态
    var batteryPct by remember { mutableStateOf(0) }       // 电量百分比 0-100
    var isCharging by remember { mutableStateOf(false) }  // 是否在充电
    var networkType by remember { mutableStateOf("") }    // 网络类型描述（如 "WiFi · xx"、"移动数据"）
    var online by remember { mutableStateOf(true) }        // 是否在线
    var screenOn by remember { mutableStateOf(true) }      // 屏幕是否点亮

    // TA 的设备状态（来自 device_status 心跳表；对方未升级新版时回退 locations 推断）
    var taBattery by remember { mutableStateOf<Int?>(null) } // 对方电量（可空）
    var taCharging by remember { mutableStateOf(false) }     // 对方是否在充电（心跳上报）
    var taUpdatedAt by remember { mutableStateOf(0L) }        // 对方最后心跳/上报时间戳
    var taNetworkType by remember { mutableStateOf<String?>(null) } // 对方网络类型（wifi/cellular/none）
    var taWifiSsid by remember { mutableStateOf<String?>(null) }    // 对方 WiFi 名称
    var taScreenOn by remember { mutableStateOf(true) }             // 对方屏幕是否点亮
    var taHasDeviceStatus by remember { mutableStateOf(false) }     // 对方心跳表是否可用（false=旧版客户端）

    // 当前心情（AppSessionTracker 单例，进程存活就不丢）
    val moodEmoji by AppSessionTracker.mood.collectAsState()  // 订阅心情流，自动刷新
    var showMoodDialog by remember { mutableStateOf(false) }  // 是否显示心情选择弹窗
    val moodOptions = listOf("😀","🥰","😎","😴","😠","🥺","🤔","🎉","💪","💔") // 10 个候选心情

    // 自己：注册广播 + 网络监听
    // 【实现思路】LaunchedEffect 启动时立即查一次 + 注册广播；进入循环保持协程活跃
    // 离开 Composable 时通过 unregisterReceiver / unregisterNetworkCallback 清理
    LaunchedEffect(subjectIsMe, reloadKey) {
        if (subjectIsMe) {
            // 立即查一次当前状态（避免初次渲染空白）
            batteryPct = getBatteryPct(ctx)
            isCharging = getBatteryCharging(ctx)
            networkType = getNetworkType(ctx)
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager  // 电源服务判断亮屏
            screenOn = pm.isInteractive

            // 注册电量变化监听（系统广播 ACTION_BATTERY_CHANGED 是粘性的，注册即拿到当前值）
            val batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    batteryPct = getBatteryPct(context ?: ctx)
                    isCharging = getBatteryCharging(context ?: ctx)
                }
            }
            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ctx.registerReceiver(batteryReceiver, batteryFilter)

            // 熄屏/亮屏监听
            val screenReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> screenOn = false
                        Intent.ACTION_SCREEN_ON -> screenOn = true
                    }
                }
            }
            val screenFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            ctx.registerReceiver(screenReceiver, screenFilter)

            // 网络变化监听（ConnectivityManager.NetworkCallback 比广播更实时）
            val connMgr = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val netCallback = object : ConnectivityManager.NetworkCallback() {
                // 网络能力变化（如从 WiFi 切到 4G）
                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    nc: NetworkCapabilities
                ) {
                    networkType = getNetworkType(ctx)
                }
                // 网络完全丢失
                override fun onLost(network: android.net.Network) {
                    networkType = "无网络"
                }
            }
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            connMgr.registerNetworkCallback(req, netCallback)

            // 定时刷新在线状态（自己永远在线）
            online = true

            // 【保持协程存活】while + delay 形成阻塞，避免离开 LaunchedEffect 触发清理
            // 一旦 Composable 卸载，isActive 变 false，循环退出，下面 unregister 才会执行
            while (isActive) { delay(30_000) }
            // 清理：注销所有监听器，避免内存泄漏
            runCatching { ctx.unregisterReceiver(batteryReceiver) }
            runCatching { ctx.unregisterReceiver(screenReceiver) }
            runCatching { connMgr.unregisterNetworkCallback(netCallback) }
        }
    }

    // TA：优先读 device_status 心跳表（60s 心跳，含电量/充电/WiFi/屏幕状态）；
    //     查不到行（对方客户端未升级）→ 回退旧逻辑：locations 最后一条 < 5 分钟 = 在线
    // 【为什么换数据源？】位置上报在"手机静止/后台被杀"时会断更，导致误显示离线；
    //   心跳与位置解耦，只要 App 活着就持续 upsert —— Life360 类产品的通用做法。
    LaunchedEffect(subjectIsMe, subjectId, reloadKey) {
        if (!subjectIsMe && subjectId.isNotBlank()) {
            while (isActive) {
                withContext(Dispatchers.IO) {
                    // ① 优先查心跳表（表不存在/对方未上报 → null → 走回退）
                    val ds = runCatching {
                        NetworkModule.restService.getDeviceStatus("eq.$subjectId")
                            .body()?.firstOrNull()
                    }.getOrNull()
                    if (ds != null) {
                        taHasDeviceStatus = true
                        taBattery = ds.battery_level ?: taBattery  // 心跳没采到电量时保留旧值
                        taCharging = ds.is_charging
                        taNetworkType = ds.network_type
                        taWifiSsid = ds.wifi_ssid
                        taScreenOn = ds.screen_on
                        taUpdatedAt = parseIsoTime(ds.updated_at)   // 心跳时间戳
                        // 心跳距今 < 30 分钟视为在线（显示层再细分"X 分钟前"）
                        online = (System.currentTimeMillis() - taUpdatedAt) < 30 * 60_000L
                    } else {
                        // ② 回退：对方未升级新版 → 查询对方最新一条位置记录（旧逻辑）
                        taHasDeviceStatus = false
                        runCatching {
                            NetworkModule.restService.getUserLocations(
                                userId = "eq.$subjectId", order = "created_at.desc", limit = 1
                            )
                        }.getOrNull()?.body()?.firstOrNull()?.let { loc ->
                            taBattery = loc.battery_level                // 云端上报的电量
                            taUpdatedAt = parseIsoTime(loc.created_at)    // 上报时间戳
                            // 判断在线：最后一条位置记录超过 5 分钟 → 离线
                            online = (System.currentTimeMillis() - taUpdatedAt) < 5 * 60_000L
                        }
                    }
                }
                delay(20_000)                                          // 20 秒后再拉
            }
        }
    }

    // 主题色：看自己用粉色，看对方用蓝色
    val pink = Color(0xFFFF8B7B)
    val blue = Color(0xFF3A9E91)
    val accent = if (subjectIsMe) pink else blue

    // 计算展示用的电量/充电状态/网络/在线
    val batPct = if (subjectIsMe) batteryPct else taBattery ?: 0     // 电量百分比
    val charging = if (subjectIsMe) isCharging else taCharging       // 是否在充电
    // 网络描述：自己读本机实时状态；对方读云端心跳（device_status 表），
    // 对方未升级新版客户端时保持旧文案
    val net = if (subjectIsMe) networkType else when {
        !taHasDeviceStatus -> "（云端未记录）"              // 旧版对方客户端 → 保持旧文案
        !taWifiSsid.isNullOrBlank() -> "WiFi · $taWifiSsid" // 拿到 SSID → 显示具体 WiFi 名
        taNetworkType == "wifi" -> "WiFi"                    // 权限受限拿不到 SSID → 只显示 WiFi
        taNetworkType == "cellular" -> "移动数据"
        taNetworkType == "none" -> "无网络"
        else -> "未知"
    }
    val isOnline = if (subjectIsMe) true else online                  // 在线状态

    // 主列：标题 + 两行网格（每行 2 个小卡片）
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 第一行：电量 + 网络
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusChip(
                icon = if (charging) "🔌" else "🔋",                // 充电中显示插头 emoji，否则电池
                label = "电量",
                value = "$batPct%" + if (charging) " 充电中" else "",
                accent = accent,
                modifier = Modifier.weight(1f)                       // 平分宽度
            )
            StatusChip(
                // 根据 net 字符串选 emoji：5G/4G 显示信号塔，WiFi 显示路由，无网络显示禁止
                icon = if (net.contains("5")) "📶" else if (net.contains("WiFi") || net.contains("wifi")) "📡" else if (net.contains("无")) "🚫" else "🌐",
                label = "网络",
                value = if (net.isBlank()) "加载中..." else net,
                accent = accent,
                modifier = Modifier.weight(1f)
            )
        }
        // 第二行：状态 + 心情
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 状态：在线 / 熄屏 / 充电中 / X分钟前 / 离线 — label 统一叫"状态"
            // 【行业惯例】（Life360 风格）按对方心跳距今分级：
            //   < 2 分钟 → 在线（再细分 熄屏/充电中）；< 30 分钟 → "X 分钟前"；≥ 30 分钟 → 离线。
            //   不再武断显示"关机"——App 无法区分"关机"和"后台被杀"，统一用"离线"表达。
            val statusIcon: String
            val statusValue: String
            val statusAccent: Color
            // 对方心跳距今的分钟数（仅在 device_status 心跳可用时用于分级）
            val taDiffMin = ((System.currentTimeMillis() - taUpdatedAt) / 60_000L).toInt()
            when {
                // —— 对方（新版客户端，有心跳表）：按心跳新鲜度分级 ——
                !subjectIsMe && taHasDeviceStatus && taDiffMin >= 30 -> {
                    statusIcon = "🔴"
                    statusValue = "离线"
                    statusAccent = Color(0xFFE53E3E)
                }
                !subjectIsMe && taHasDeviceStatus && taDiffMin >= 2 -> {
                    statusIcon = "🟠"
                    statusValue = "$taDiffMin 分钟前"
                    statusAccent = Color(0xFFDD6B20)
                }
                !subjectIsMe && taHasDeviceStatus && !taScreenOn -> {
                    statusIcon = "🌙"
                    statusValue = "熄屏"
                    statusAccent = Color(0xFF6B7FBF)
                }
                !subjectIsMe && taHasDeviceStatus && charging -> {
                    statusIcon = "🔌"
                    statusValue = "充电中"
                    statusAccent = Color(0xFF38A169)
                }
                !subjectIsMe && taHasDeviceStatus -> {
                    statusIcon = "🟢"
                    statusValue = "在线"
                    statusAccent = Color(0xFF2F855A)
                }
                // —— 对方（旧版客户端，无心跳表，回退位置 5 分钟推断）——
                !subjectIsMe && !isOnline -> {
                    statusIcon = "🔴"
                    statusValue = "离线"
                    statusAccent = Color(0xFFE53E3E)
                }
                !subjectIsMe -> {
                    statusIcon = "🟢"
                    statusValue = "在线"
                    statusAccent = Color(0xFF2F855A)
                }
                // —— 自己：本机状态实时可知，保持原有逻辑 ——
                !screenOn -> {
                    statusIcon = "🌙"
                    statusValue = "熄屏"
                    statusAccent = Color(0xFF6B7FBF)
                }
                charging -> {
                    statusIcon = "🔌"
                    statusValue = "充电中"
                    statusAccent = Color(0xFF38A169)
                }
                else -> {
                    statusIcon = "🟢"
                    statusValue = "开机"
                    statusAccent = Color(0xFF2F855A)
                }
            }
            StatusChip(
                icon = statusIcon,
                label = "状态",
                value = statusValue,
                accent = statusAccent,                              // 状态卡用专属颜色（绿/紫/红）
                modifier = Modifier.weight(1f)
            )
            // 心情卡 —— 自己可点击选 emoji，对方则不可点击（只展示）
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.65f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.weight(1f).then(
                    // 通过 then 拼接不同 modifier，看自己时加 clickable
                    if (subjectIsMe) Modifier.clickable { showMoodDialog = true } else Modifier
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("💖", fontSize = 16.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("当前心情", fontSize = 11.sp, color = Color(0xFFA89890))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        moodEmoji, fontSize = 22.sp,
                        modifier = Modifier.wrapContentSize(),
                        maxLines = 1
                    )
                }
            }
        }
    }

    // 心情选择对话框（仅自己能选）
    // 【初学者理解】AlertDialog 是 Material3 内置组件：title/text/confirmButton 三个槽位
    if (showMoodDialog && subjectIsMe) {
        AlertDialog(
            onDismissRequest = { showMoodDialog = false },          // 点外部 / 返回键关闭
            title = { Text("选个心情") },
            text = {
                // FlowRow 是流式布局：自动换行排列 emoji
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    moodOptions.forEach { emoji ->
                        // 每个 emoji 是一个可点击 Surface，选中的有粉色背景
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (emoji == moodEmoji) pink.copy(alpha = 0.15f) else Color.Transparent,
                            modifier = Modifier.size(44.dp).clickable {
                                // 点击：写入单例 + 关闭弹窗
                                AppSessionTracker.setMood(emoji); showMoodDialog = false
                            }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(emoji, fontSize = 24.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMoodDialog = false }) { Text("取消") }
            }
        )
    }
}

// =====================================================================
// 🧩 StatusChip —— 单个小状态卡片（电量/网络/状态等）
// 这是一个「可复用」的 Composable：传入图标、标签、值、颜色即可渲染一张小卡片。
// 一次定义，多次复用，减少重复代码。
// =====================================================================
@Composable
private fun StatusChip(
    icon: String,                  // emoji 图标，如 "🔋"、"📶"
    label: String,                 // 标签，如 "电量"、"网络"
    value: String,                 // 值，如 "80%"、"WiFi · MyHome"
    accent: Color,                 // 主题色（值文本颜色）
    modifier: Modifier = Modifier  // 外部修饰符（如 weight(1f) 平分宽度）
) {
    Card(
        shape = RoundedCornerShape(14.dp),                        // 圆角
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.65f)),  // 半透明白（方案 D）
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier                                        // 应用外部传入的修饰符
    ) {
        Column(Modifier.padding(12.dp)) {
            // 第一行：图标 + 标签
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 11.sp, color = Color(0xFFA89890))
            }
            Spacer(Modifier.height(4.dp))
            // 第二行：值（加粗，使用主题色）
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = accent, maxLines = 1)
        }
    }
}

// =====================================================================
// ⌚ BandSection —— 智能手环占位区（方案 D）
// 展示心率/睡眠/步数/压力 4 张渐变卡片；功能尚未接入，统一标记「正在开发」。
// 后续接入真实手环 SDK 时，把 "--" 与占位文案替换为真实数据即可。
// =====================================================================
@Composable
private fun BandSection() {
    // 手环卡片配色（与方案 D 设计稿一致）
    data class BandSpec(val label: String, val ico: String, val colors: List<Color>)
    val tiles = listOf(
        BandSpec("心率", "♥", listOf(Color(0xFFFF6B7E), Color(0xFFFFA0B0))),
        BandSpec("睡眠", "☾", listOf(Color(0xFF6B7FBF), Color(0xFF9DAFDD))),
        BandSpec("步数", "⚡", listOf(Color(0xFF3F8E80), Color(0xFF7ECEC0))),
        BandSpec("压力", "○", listOf(Color(0xFFA8855E), Color(0xFFD4B58F)))
    )
    Column {
        // 标题行：左「⌚ 智能手环」，右「正在开发」徽章
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "⌚ 智能手环", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFFA89890), letterSpacing = 1.sp
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .background(Color(0xFFFFE4D1), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text("正在开发", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFF8B7B))
            }
        }
        Spacer(Modifier.height(10.dp))
        // 2x2 网格：两行两列渐变卡片
        tiles.chunked(2).forEach { rowTiles ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowTiles.forEach { t ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(88.dp)
                            .background(Brush.linearGradient(t.colors), RoundedCornerShape(14.dp))
                            .padding(12.dp)
                    ) {
                        // 右上角半透明大图标（装饰）
                        Text(
                            t.ico, fontSize = 18.sp, color = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.align(Alignment.TopEnd)
                        )
                        Column(Modifier.align(Alignment.TopStart)) {
                            Text(t.label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.9f))
                            Spacer(Modifier.height(2.dp))
                            Text("--", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Text(
                            "正在开发", fontSize = 10.sp, color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.align(Alignment.BottomStart)
                        )
                    }
                }
                // 奇数个时补一个空位保持两列对齐（本例 4 个不会触发）
                if (rowTiles.size < 2) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// =====================================================================
// 🔧 辅助函数 —— 本地设备查询工具
// 这些函数都是「普通 Kotlin 函数」（非 @Composable），用于查系统状态。
// 在 Compose 里通过 LaunchedEffect 调用它们。
// =====================================================================

/**
 * 本地查询前台 APP（UsageStatsManager）
 *
 * 【实现思路】查询最近 2 分钟内的 App 使用事件，找出最后一次「MOVE_TO_FOREGROUND」
 *   （即某个 App 切到前台）事件，返回它的包名 + 显示名。
 *
 * @param ctx Android Context
 * @return Pair<包名, 显示名>，如 ("com.tencent.mm", "微信")；查不到返回 null
 */
private fun queryForegroundApp(ctx: Context): Pair<String, String>? {
    // 拿 UsageStatsService（需要「使用情况访问」权限）
    val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val pm = ctx.packageManager                                       // 用于查 App 名字
    val end = System.currentTimeMillis()
    val begin = end - 120_000L                                        // 查最近 2 分钟的事件
    val events = usm.queryEvents(begin, end)                           // 拿事件迭代器
    val ev = UsageEvents.Event()                                      // 复用单个事件对象
    var latestFg: String? = null                                       // 最新前台 App 包名
    var latestTime = 0L                                               // 该事件发生时间
    while (events.hasNextEvent()) {
        events.getNextEvent(ev)                                       // 把下一个事件填到 ev
        // 只关心「切到前台」事件，并且取时间最新的那条
        if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && ev.timeStamp > latestTime) {
            latestTime = ev.timeStamp; latestFg = ev.packageName
        }
    }
    if (latestFg.isNullOrBlank()) return null                         // 2 分钟内无前台切换 → null
    // 通过 PackageManager 把包名翻译成中文显示名（如 com.tencent.mm → "微信"）
    val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(latestFg, 0)).toString() }
        .getOrDefault(latestFg)                                       // 翻译失败就回退用包名
    return latestFg to name                                            // Kotlin 的 to 操作符构造 Pair
}

/**
 * 检查本应用是否被授予「使用情况访问」权限
 *
 * 【关键】AppOpsManager 检查 OPSTR_GET_USAGE_STATS 这个 op 是否被允许。
 *   - Android Q（10）以上用 unsafeCheckOpNoThrow（更宽松的检查）
 *   - 低版本用 checkOpNoThrow（已废弃但仍可用）
 *
 * @return true 表示有权限，可以查前台 App
 */
private fun localHasUsagePermission(ctx: Context): Boolean {
    val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    // 根据 SDK 版本选不同方法，Q 以上用 unsafeCheckOpNoThrow
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    } else {
        @Suppress("DEPRECATION")                                       // 抑制废弃警告
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED                          // 是否被允许
}

/**
 * 获取电量百分比（一次性查询，不监听变化）
 *
 * 【实现】注册一个 null Receiver 拿到最近的粘性广播 ACTION_BATTERY_CHANGED，
 *   从中读 level / scale 计算百分比。
 *
 * @return 0-100 的电量百分比；查不到返回 -1
 */
private fun getBatteryPct(ctx: Context): Int {
    // 传 null 作为 Receiver 可以拿到「粘性广播」最后一次的值而不真正注册监听
    val im = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
    val level = im.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)         // 当前电量
    val scale = im.getIntExtra(BatteryManager.EXTRA_SCALE, -1)         // 总刻度（一般 100）
    return if (level < 0 || scale <= 0) -1 else (level * 100 / scale)
}

/**
 * 是否在充电（包括充满状态）
 *
 * @return true 表示正在充电或已充满
 */
private fun getBatteryCharging(ctx: Context): Boolean {
    val im = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
    val status = im.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    return status == BatteryManager.BATTERY_STATUS_CHARGING ||
           status == BatteryManager.BATTERY_STATUS_FULL
}

/**
 * 获取当前网络类型描述
 *
 * 【实现】通过 ConnectivityManager 拿当前活动网络的 NetworkCapabilities，
 *   根据它的 transport 类型返回字符串。
 *
 * @return 如 "WiFi · MyHome"、"移动数据"、"无网络"
 */
private fun getNetworkType(ctx: Context): String {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val nc = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "无网络"  // 无活动网络
    return when {
        nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
            // WiFi 还要尝试拿 SSID（WiFi 名字）
            val ssid = getWifiSsidMultiAttempt(ctx)
            if (ssid != null) "WiFi · $ssid" else "WiFi"
        }
        nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"  // 蜂窝/4G/5G
        nc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "蓝牙"
        nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
        nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "无网络"
    }
}

/**
 * 多层尝试获取 WiFi SSID（不同 Android 版本/ROM 权限差异大）
 *
 * 【为什么这么麻烦？】Android 各版本对 WiFi SSID 的访问权限一直在收紧，
 *   没有一个统一方法能在所有机型上拿到。这里依次尝试 3 种方案，哪种能用就用哪种。
 *
 * @return WiFi 名字（不含引号），全部失败返回 null
 */
private fun getWifiSsidMultiAttempt(ctx: Context): String? {
    // 方案 1: WifiManager.connectionInfo (大多数场景可用)
    runCatching {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ssid = wm.connectionInfo?.ssid                       // 系统返回的 SSID 带引号
        // 过滤无效值：<unknown ssid> 是 Android 在没权限时返回的占位符
        if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>" && ssid != "0x") {
            val cleaned = ssid.removeSurrounding("\"")            // 去掉首尾引号
            if (cleaned.isNotBlank()) return cleaned
        }
    }

    // 方案 2: NetworkCapabilities 里可能有 WiFi 信息（Android 12+）
    runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nc = cm.getNetworkCapabilities(cm.activeNetwork)
        // 反射拿 wifi Ssid —— Android 12 后隐藏了但 ROM 可能还能拿到
        val f = nc?.javaClass?.getDeclaredField("ssid")
        if (f != null) {
            f.isAccessible = true                                 // 反射访问私有字段
            val v = f.get(nc) as? String
            if (!v.isNullOrBlank()) {
                val cleaned = v.removeSurrounding("\"")
                if (cleaned.isNotBlank() && cleaned != "<unknown ssid>") return cleaned
            }
        }
    }

    // 方案 3: 反射 WifiManager mWifiInfo (最老的方法，有些 ROM 还是有效)
    runCatching {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mWifiInfoField = wm.javaClass.getDeclaredMethod("getConnectionInfo")
        val wifiInfo = mWifiInfoField.invoke(wm)                   // 反射调用 getConnectionInfo()
        if (wifiInfo != null) {
            val ssidField = wifiInfo.javaClass.getDeclaredField("mSSID")
            ssidField.isAccessible = true
            val ssid = ssidField.get(wifiInfo) as? String
            if (!ssid.isNullOrBlank()) {
                val cleaned = ssid.removeSurrounding("\"")
                if (cleaned.isNotBlank() && cleaned != "<unknown ssid>") return cleaned
            }
        }
    }

    // 全挂了 → 返回 null，上层显示 "WiFi" 不带名字
    return null
}

/**
 * package → category 映射（简化版）
 *
 * 【实现】先查系统 ApplicationInfo.category（Android 8+ 自带分类），
 *   系统没分类的回退到 categorizeByPkg（按包名关键字猜）。
 *
 * @return 分类字符串，如 "社交"、"游戏"
 */
private fun categoryOf(ctx: Context, pkg: String): String {
    return runCatching {
        val info = ctx.packageManager.getApplicationInfo(pkg, 0)
        when (info.category) {
            ApplicationInfo.CATEGORY_GAME -> "游戏"
            ApplicationInfo.CATEGORY_SOCIAL -> "社交"
            ApplicationInfo.CATEGORY_VIDEO -> "视频"
            ApplicationInfo.CATEGORY_AUDIO -> "音乐"
            ApplicationInfo.CATEGORY_NEWS -> "新闻"
            ApplicationInfo.CATEGORY_MAPS -> "地图"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "效率"
            ApplicationInfo.CATEGORY_IMAGE -> "图像"
            else -> categorizeByPkg(pkg)                          // 系统没分类 → 按包名猜
        }
    }.getOrDefault(categorizeByPkg(pkg))                          // 出异常也按包名猜
}


/**
 * 系统噪音 APP：桌面、输入法、系统UI、锁屏、虚拟按键等，不记录也不展示
 *
 * 【为什么？】这些 App 不算用户「主动使用」，过滤掉避免污染历史记录。
 *
 * @return true 表示是噪音 App，应被忽略
 */
fun isSystemNoisePkg(pkg: String?): Boolean {
    if (pkg.isNullOrBlank()) return true
    val p = pkg.lowercase()                                       // 统一小写做匹配
    // 桌面 / 启动器
    if (p.contains("launcher") || p.contains("systemui") || p.contains("desk") || p.contains("homescreen")) return true
    // 输入法
    if (p.contains("inputmethod") || p.contains("ime") || p.contains("input.")
        || p.contains("sougou") || p.contains("sogou") || p.contains("baidu.input")
        || p.contains("iflytek") || p.contains("讯飞")) return true
    // 系统 UI / 状态栏 / 通知
    if (p.contains("uiautomator") || p.contains("statusbar") || p.contains("navigationbar")
        || p.contains("keyguard") || p.contains("lockscreen") || p.contains("powerui")
        || p.contains("notifications") || p.contains("system.dialog")) return true
    // 设置 / 包安装器
    if (p.contains("packageinstaller") || p.contains("permissioncontroller")) return true
    // 包名显示异常（识别失败的 ?? API 这种）
    if (p.length < 5) return true                                  // 包名太短，多半是异常
    if (!p.contains('.')) return true                             // 包名没点分隔，异常
    return false
}

/**
 * 按包名关键字猜 App 分类（兜底方案）
 *
 * 【实现】根据包名里的关键字（如 "tencent.mm" 是微信）匹配常见 App。
 */
private fun categorizeByPkg(pkg: String): String = when {
    pkg.contains("tencent.mm") || pkg.contains("qq") -> "社交"   // 微信/QQ
    pkg.contains("douyin") || pkg.contains("aweme") || pkg.contains("bilibili") || pkg.contains("kuaishou") -> "视频"  // 抖音/B站/快手
    pkg.contains("netease.cloud") || pkg.contains("qqmusic") || pkg.contains("kugou") || pkg.contains("kuwo") -> "音乐"  // 网易云/QQ音乐/酷狗/酷我
    pkg.contains("taobao") || pkg.contains("tmall") || pkg.contains("jd") || pkg.contains("pinduoduo") -> "购物"  // 淘宝/天猫/京东/拼多多
    pkg.contains("meituan") || pkg.contains("ele") -> "生活"     // 美团/饿了么
    pkg.contains("launcher") || pkg.contains("systemui") -> "桌面"
    else -> "其他"
}

/**
 * 取一条 app_usage 记录代表"打开时刻"的时间戳：
 *   优先 window_start（新版客户端上报的会话真实打开时刻），
 *   旧数据 window_start 为 null 时回退 created_at（上报创建时间）。
 */
private fun rowTs(row: AppUsageRow): Long = parseIsoTime(row.window_start ?: row.created_at)

/**
 * 聚合 app_usage 行：同一个 APP 连续记录 → 只保留第一条（打开时刻）
 *
 * 【为什么？】云端每分钟上报一条 app_usage，连续玩 30 分钟会有 30 条。
 *   我们不希望时间线上有 30 条「打开了微信」，所以聚合成 1 条「会话」。
 *
 * 【算法】按时间正序遍历：
 *   - 如果包名变了，或者距上一条 > 3 分钟 → 视为新会话，把上一会话存档
 *   - 否则视为同一会话，累加 usage_seconds
 * 最后按时间倒序返回（最新的在前）。
 */
fun aggregateOpens(rows: List<AppUsageRow>): List<HistoryOpen> {
    if (rows.isEmpty()) return emptyList()
    val sorted = rows.sortedBy { rowTs(it) } // 时间正序（旧 → 新）
    val result = mutableListOf<HistoryOpen>()
    var lastPkg = ""                                              // 上一条记录的包名
    var lastStart = 0L                                            // 当前会话的起始时间戳
    var totalSeconds = 0                                          // 当前会话累计秒数

    for (row in sorted) {
        val ts = rowTs(row)                                       // 当前记录的打开时间戳（window_start 优先）
        val pkg = row.package_name
        // 计算距上一条的间隔（毫秒）
        val gap = if (lastPkg.isNotEmpty()) (ts - lastStart) else 0
        if (pkg != lastPkg || gap > 180_000L) {                   // 换 APP 或间隔 > 3 分钟 → 新会话
            if (lastPkg.isNotEmpty()) {
                // 把已累积的上一会话存档
                result.add(makeHistoryOpen(lastPkg, rows, lastStart, totalSeconds))
            }
            lastPkg = pkg; lastStart = ts; totalSeconds = row.usage_seconds
        } else {
            // 同一会话 → 累加秒数
            totalSeconds += row.usage_seconds
        }
    }
    // 把最后一个会话也存档
    if (lastPkg.isNotEmpty()) result.add(makeHistoryOpen(lastPkg, rows, lastStart, totalSeconds))
    // 按时间倒序返回（最新的在前）
    return result.sortedByDescending { it.openAt }
}

/**
 * 构造一个 HistoryOpen 对象（聚合会话的最终形态）
 *
 * @param pkg 包名
 * @param allRows 所有原始记录（用于查 App 名字和分类）
 * @param startAt 会话开始时间戳
 * @param totalSec 会话总秒数
 */
private fun makeHistoryOpen(pkg: String, allRows: List<AppUsageRow>, startAt: Long, totalSec: Int): HistoryOpen {
    // 从所有行里找该包名对应的第一条记录，拿它的 app_name 和 category
    val firstRow = allRows.firstOrNull { it.package_name == pkg }
    val appName = firstRow?.app_name ?: pkg                       // 没记录就用包名
    val cat = firstRow?.category ?: categorizeByPkg(pkg)          // 没分类就猜
    val timeLabel = formatTimeLabel(startAt)                      // 友好的中文时间标签
    return HistoryOpen(
        packageName = pkg, appName = appName, category = cat,
        openAt = startAt, timeLabel = timeLabel,
        duration = formatDuration(totalSec),                       // 时长描述，如 "5分钟"
        emoji = categoryEmoji(cat),                                 // 分类对应 emoji
        totalSeconds = totalSec                            // 会话总时长（秒）
    )
}

/**
 * 历史打开记录的数据类
 *
 * 【data class】Kotlin 的数据类，自动生成 equals/hashCode/toString/copy 等方法。
 * 主要用于在 UI 中传递一条聚合后的「打开事件」。
 *
 * @property packageName 包名，如 "com.tencent.mm"
 * @property appName 显示名，如 "微信"
 * @property category 分类，如 "社交"
 * @property openAt 打开时间戳（毫秒）
 * @property timeLabel 友好时间标签，如 "今天 19:38 打开"
 * @property duration 时长描述，如 "5分钟"
 * @property emoji 分类对应 emoji，如 "💬"
 */
data class HistoryOpen(
    val packageName: String,
    val appName: String,
    val category: String,
    val openAt: Long,
    val timeLabel: String,
    val duration: String,
    val emoji: String,
    val totalSeconds: Int = 0   // 会话总时长（秒）：统计页「最近打开」进度条用
)

/**
 * 计算最近 N 天的日期范围（用于 Supabase 查询的过滤条件）
 *
 * 【返回】Pair(start, end)，格式是 Supabase REST 过滤语法：
 *   - "gte.ISO时间戳" 表示 >= start
 *   - "lt.ISO时间戳" 表示 < end
 * 比如 dateRangeForDays(1) 返回今天 0 点到明天 0 点的范围。
 */
private fun dateRangeForDays(days: Int): Pair<String, String> {
    val zone = ZoneId.systemDefault()
    val date = LocalDate.now(zone).minusDays(days.toLong())       // 起 N 天前
    val start = date.atStartOfDay(zone).toInstant().toString()    // N 天前 0 点
    val end = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toString()  // 明天 0 点
    return "gte." + start to "lt." + end
}

/**
 * 把 ISO 8601 时间字符串解析为毫秒时间戳
 *
 * 【为什么用 runCatching？】后端返回的时间格式可能不规范，避免解析异常崩溃。
 *
 * @return 毫秒时间戳；空或解析失败返回 0
 */
private fun parseIsoTime(iso: String?): Long {
    if (iso.isNullOrBlank()) return 0L
    // 【兼容性修复】Supabase/PostgREST 返回的 timestamptz 常带 "+00:00" 偏移后缀，
    // 而老版本 Android 的 Instant.parse 只认 "Z" 结尾，会解析失败返回 0，
    // 导致"打开时间空白 / 在线状态永远离线"。
    // 成熟做法：先用 OffsetDateTime（"Z" 和 "+00:00" 都支持），失败再回退 Instant。
    val normalized = iso.trim().replace(' ', 'T')   // 兜底：兼容空格分隔的日期时间
    return runCatching {
        java.time.OffsetDateTime.parse(normalized).toInstant().toEpochMilli()
    }.recoverCatching {
        java.time.Instant.parse(normalized).toEpochMilli()        // ISO 字符串 → 毫秒
    }.getOrDefault(0L)
}

/**
 * 把毫秒时间戳格式化为中文友好时间标签
 *
 * 【规则】
 *   - 今天 → "今天 19:38 打开"
 *   - 昨天 → "昨天 16:11 打开"
 *   - 更早 → "09-01 10:00 打开"
 *
 * @return 友好时间标签；时间戳无效返回空字符串
 */
private fun formatTimeLabel(epochMs: Long): String {
    if (epochMs <= 0) return ""
    val zone = ZoneId.systemDefault()
    val dt = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), zone)
    val today = LocalDate.now(zone)
    val yesterday = today.minusDays(1)
    val date = dt.toLocalDate()
    val time = dt.format(DateTimeFormatter.ofPattern("HH:mm"))   // 时:分
    return when (date) {
        today -> "今天 $time 打开"
        yesterday -> "昨天 $time 打开"
        else -> "${date.format(DateTimeFormatter.ofPattern("MM-dd"))} $time 打开"
    }
}

/**
 * 相对时间描述（如"刚刚"、"5分钟前"）
 *
 * @return 相对时间字符串；时间戳无效返回空
 */
fun relativeTime(epochMs: Long): String {
    if (epochMs <= 0) return ""
    val diff = System.currentTimeMillis() - epochMs              // 距今多久（毫秒）
    val mins = diff / 60000L
    val hours = mins / 60L
    val days = hours / 24L
    return when {
        mins < 1 -> "刚刚"
        mins < 60 -> "${mins}分钟前"
        hours < 24 -> "${hours}小时前"
        days < 7 -> "${days}天前"
        else -> "更久前"
    }
}

/**
 * 把秒数格式化为时长描述
 *
 * 【规则】
 *   - 0 秒 → "—"
 *   - 不足 1 分钟 → "<1分钟"
 *   - 不足 1 小时 → "5分钟"
 *   - 整点小时 → "2小时"
 *   - 小时 + 分钟 → "1h 23m"
 */
private fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "—"
    val h = seconds / 3600                                         // 整点小时
    val m = (seconds % 3600) / 60                                   // 剩余分钟
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}小时"
        m > 0 -> "${m}分钟"
        else -> "<1分钟"
    }
}

/**
 * 分类 → emoji 映射
 *
 * 用于在 UI 上用图标直观表示 App 分类。
 */
private fun categoryEmoji(category: String): String = when (category) {
    "社交" -> "💬"
    "视频" -> "🎬"
    "游戏" -> "🎮"
    "音乐" -> "🎵"
    "购物" -> "🛍️"
    "生活" -> "🏪"
    "浏览器" -> "🌐"
    "桌面" -> "📱"
    "效率" -> "📋"
    "新闻" -> "📰"
    "地图" -> "🗺️"
    "图像" -> "🖼️"
    else -> "📦"                                                  // 未知分类用箱子 emoji
}




