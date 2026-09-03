package com.coupletracker.android.ui

import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coupletracker.android.data.AppUsageRow
import com.coupletracker.android.data.LocationRow
import com.coupletracker.android.data.AppSessionTracker
import com.coupletracker.android.data.NetworkModule
import com.coupletracker.android.data.UserRepository
import com.coupletracker.android.service.TrackerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 应用 Tab：3 个模块
 *   ① 🎯 当前正在使用（实时大卡片）
 *   ② 📱 手机状态（电量/网络/在线状态）
 *   ③ 🕒 历史打开记录（打开时刻时间线）
 *
 * 数据源：
 *   - 看自己：UsageStatsManager + BatteryManager + ConnectivityManager 本地实时
 *   - 看 TA：Supabase app_usage + locations 表（有 1-2 分钟延迟，正常）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen() {
    val ctx = LocalContext.current
    val user by UserRepository.get().userFlow.collectAsState(initial = null)
    val myId = user?.id.orEmpty()
    val myCode = user?.coupleCode.orEmpty()

    var showPartner by remember { mutableStateOf(false) }
    var partnerId by remember { mutableStateOf<String?>(null) }
    var partnerName by remember { mutableStateOf("") }
    var partnerLoaded by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    // ---- 查配对对方 ----
    LaunchedEffect(myCode, myId) {
        partnerId = null; partnerName = ""; partnerLoaded = false
        if (myCode.isBlank()) { partnerLoaded = true; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            runCatching {
                NetworkModule.restService.getProfile(coupleCode = myCode)
            }.getOrNull()?.body()?.filter { it.id != myId }?.firstOrNull()?.let { p ->
                partnerId = p.id
                partnerName = p.nickname.ifBlank { p.username }
            }
            partnerLoaded = true
        }
    }

    val subjectId = (if (showPartner) partnerId else myId) ?: ""
    val subjectName = if (showPartner) partnerName.ifBlank { "TA" } else (user?.displayName ?: "我")
    val subjectIsMe = !showPartner

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFFDF2F8))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        // ---- 顶部标题 + 切换 ----
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("📱 应用动态", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { reloadKey++ }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = Color(0xFF667EEA))
            }
        }

        Spacer(Modifier.height(10.dp))

        // 只有配对了才有"看 TA"选项
        if (partnerId != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !showPartner,
                    onClick = { showPartner = false },
                    label = { Text("我", fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFE75480).copy(alpha = 0.15f),
                        selectedLabelColor = Color(0xFFE75480)
                    )
                )
                FilterChip(
                    selected = showPartner,
                    onClick = { showPartner = true },
                    label = { Text("TA · " + partnerName, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF667EEA).copy(alpha = 0.15f),
                        selectedLabelColor = Color(0xFF667EEA)
                    )
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        // ============= ① 当前正在使用 =============
        CurrentAppCard(
            subjectId = subjectId,
            subjectName = subjectName,
            subjectIsMe = subjectIsMe,
            subjectHasPermission = if (subjectIsMe) localHasUsagePermission(ctx) else true,
            reloadKey = reloadKey
        )

        Spacer(Modifier.height(8.dp))

        // ============= ② 手机状态 =============
        PhoneStatusCard(
            subjectId = subjectId,
            subjectName = subjectName,
            subjectIsMe = subjectIsMe,
            reloadKey = reloadKey
        )

        Spacer(Modifier.height(12.dp))

        // ============= ③ 历史打开记录 =============
        Text("🕒 最近打开记录", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
        Spacer(Modifier.height(6.dp))

        HistoryOpenList(
            subjectId = subjectId,
            subjectName = subjectName,
            reloadKey = reloadKey
        )
    }
}

// =====================================================================
// ① 🎯 当前正在使用 —— 大卡片
// =====================================================================
@Composable
private fun CurrentAppCard(
    subjectId: String,
    subjectName: String,
    subjectIsMe: Boolean,
    subjectHasPermission: Boolean,
    reloadKey: Int
) {
    val ctx = LocalContext.current

    // 本地实时查自己的前台 APP（名字/分类）
    var fgPkg by remember { mutableStateOf("") }
    var fgName by remember { mutableStateOf("") }
    var fgCategory by remember { mutableStateOf("") }

    // ✅ 自己的累计时长直接读 AppSessionTracker 单例（进程存活就不丢）
    var sessionSeconds by remember { mutableStateOf(0) }

    // 远端查 TA 的（60 秒精度）
    var remoteAppName by remember { mutableStateOf("") }
    var remotePkg by remember { mutableStateOf("") }
    var remoteSeconds by remember { mutableStateOf(0) }
    var remoteUpdateAt by remember { mutableStateOf(0L) }

    // 自己：每 3 秒查一次前台 APP 名字 + 时长
    LaunchedEffect(subjectIsMe, reloadKey) {
        if (subjectIsMe) {
            if (!subjectHasPermission) return@LaunchedEffect
            while (isActive) {
                runCatching {
                    val current = queryForegroundApp(ctx)
                    if (current != null && current.first.isNotEmpty()) {
                        val (pkg, name) = current
                        val cat = categoryOf(ctx, pkg)
                        if (pkg != fgPkg) {
                            fgPkg = pkg; fgName = name; fgCategory = cat
                        } else {
                            fgName = name
                        }
                        // ✅ 写入单例 + 读秒数
                        AppSessionTracker.setCurrentApp(pkg, name)
                        sessionSeconds = AppSessionTracker.sessionSeconds()
                    }
                }
                delay(3000)
            }
        }
    }

    // TA：每 15 秒拉一次云端 app_usage 最新记录
    LaunchedEffect(subjectIsMe, subjectId, reloadKey) {
        if (!subjectIsMe && subjectId.isNotBlank()) {
            while (isActive) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        NetworkModule.restService.getAppUsage(userId = "eq.$subjectId", order = "created_at.desc", limit = 1)
                    }.getOrNull()?.body()?.firstOrNull()?.let { row ->
                        remotePkg = row.package_name
                        remoteAppName = row.app_name ?: row.package_name
                        remoteSeconds = row.usage_seconds
                        remoteUpdateAt = parseIsoTime(row.created_at)
                    }
                }
                delay(15_000)
            }
        }
    }

    val pink = Color(0xFFE75480)
    val blue = Color(0xFF667EEA)

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                if (subjectIsMe) "� 正在玩" else "� $subjectName 正在玩",
                fontSize = 12.sp, color = Color(0xFF718096)
            )
            Spacer(Modifier.height(12.dp))

            if (subjectIsMe && !subjectHasPermission) {
                // 没权限 —— 引导去开
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("⚠️ 未授予「使用情况访问」权限", fontSize = 14.sp, color = Color(0xFFE53E3E), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("去手机设置 → 应用 → 特殊权限 → 使用情况访问 → 允许 CoupleTracker",
                        fontSize = 11.sp, color = Color(0xFF718096))
                }
            } else if (subjectIsMe && fgPkg.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("💤", fontSize = 36.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("${subjectName} 正在休息", fontSize = 15.sp, color = Color(0xFF718096), fontWeight = FontWeight.SemiBold)
                }
            } else if (!subjectIsMe && remoteAppName.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("🤔", fontSize = 36.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("${subjectName} 暂无使用记录", fontSize = 15.sp, color = Color(0xFF718096), fontWeight = FontWeight.SemiBold)
                }
            } else {
                // 有 APP 使用数据 → 左图标、右名字+时长
                val appEmoji = categoryEmoji(if (subjectIsMe) fgCategory else "")
                val appName = if (subjectIsMe) fgName else remoteAppName
                val durationSec = if (subjectIsMe) sessionSeconds else remoteSeconds
                val duration = formatDuration(durationSec)
                val accent = if (subjectIsMe) pink else blue

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        Modifier.size(52.dp).background(accent.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) { Text(appEmoji, fontSize = 26.sp) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            appName, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF2D3748), maxLines = 1
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (subjectIsMe) "已使用 $duration" else "最近一次 · $duration",
                            fontSize = 12.sp, color = Color(0xFF718096)
                        )
                    }
                }
            }
        }
    }
}

// =====================================================================
// ② 📱 手机状态 —— 网格小卡片
// =====================================================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneStatusCard(
    subjectId: String,
    subjectName: String,
    subjectIsMe: Boolean,
    reloadKey: Int
) {
    val ctx = LocalContext.current

    // 本地状态
    var batteryPct by remember { mutableStateOf(0) }
    var isCharging by remember { mutableStateOf(false) }
    var networkType by remember { mutableStateOf("") }
    var online by remember { mutableStateOf(true) }

    // TA 的最新位置（拿 battery_level + created_at 判断在线状态）
    var taBattery by remember { mutableStateOf<Int?>(null) }
    var taCharging by remember { mutableStateOf(false) }
    var taUpdatedAt by remember { mutableStateOf(0L) }

    // 当前心情（AppSessionTracker 单例，进程存活就不丢）
    val moodEmoji by AppSessionTracker.mood.collectAsState()
    var showMoodDialog by remember { mutableStateOf(false) }
    val moodOptions = listOf("😀","🥰","😎","😴","😠","🥺","🤔","🎉","💪","💔")

    // 自己：注册广播 + 网络监听
    LaunchedEffect(subjectIsMe, reloadKey) {
        if (subjectIsMe) {
            // 立即查一次
            batteryPct = getBatteryPct(ctx)
            isCharging = getBatteryCharging(ctx)
            networkType = getNetworkType(ctx)

            // 注册电量变化监听
            val batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    batteryPct = getBatteryPct(context ?: ctx)
                    isCharging = getBatteryCharging(context ?: ctx)
                }
            }
            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ctx.registerReceiver(batteryReceiver, batteryFilter)

            // 网络变化监听
            val connMgr = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val netCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    nc: NetworkCapabilities
                ) {
                    networkType = getNetworkType(ctx)
                }
                override fun onLost(network: android.net.Network) {
                    networkType = "无网络"
                }
            }
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            connMgr.registerNetworkCallback(req, netCallback)

            // 定时刷新在线状态（自己永远在线）
            online = true

            // 清理
            while (isActive) { delay(30_000) }
            runCatching { ctx.unregisterReceiver(batteryReceiver) }
            runCatching { connMgr.unregisterNetworkCallback(netCallback) }
        }
    }

    // TA：用 getUserLocations 按 user_id 过滤拿最新位置
    LaunchedEffect(subjectIsMe, subjectId, reloadKey) {
        if (!subjectIsMe && subjectId.isNotBlank()) {
            while (isActive) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        NetworkModule.restService.getUserLocations(
                            userId = "eq.$subjectId", order = "created_at.desc", limit = 1
                        )
                    }.getOrNull()?.body()?.firstOrNull()?.let { loc ->
                        taBattery = loc.battery_level
                        taUpdatedAt = parseIsoTime(loc.created_at)
                        // 判断在线：最后一条位置记录超过 5 分钟 → 离线/可能关机
                        online = (System.currentTimeMillis() - taUpdatedAt) < 5 * 60_000L
                    }
                }
                delay(20_000)
            }
        }
    }

    val pink = Color(0xFFE75480)
    val blue = Color(0xFF667EEA)
    val accent = if (subjectIsMe) pink else blue

    val batPct = if (subjectIsMe) batteryPct else taBattery ?: 0
    val charging = if (subjectIsMe) isCharging else taCharging
    val net = if (subjectIsMe) networkType else "（云端未记录）"
    val isOnline = if (subjectIsMe) true else online

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("📱 $subjectName 的手机状态", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusChip(
                icon = if (charging) "🔌" else "🔋",
                label = "电量",
                value = "$batPct%" + if (charging) " 充电中" else "",
                accent = accent,
                modifier = Modifier.weight(1f)
            )
            StatusChip(
                icon = if (net.contains("5")) "📶" else if (net.contains("WiFi") || net.contains("wifi")) "📡" else if (net.contains("无")) "🚫" else "🌐",
                label = "网络",
                value = if (net.isBlank()) "加载中..." else net,
                accent = accent,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 状态：开机 / 充电中 / 离线(可能关机) — label 统一叫"状态"
            val statusIcon: String
            val statusValue: String
            val statusAccent: Color
            when {
                !isOnline -> {
                    statusIcon = "🔴"
                    statusValue = "关机"
                    statusAccent = Color(0xFFE53E3E)
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
                accent = statusAccent,
                modifier = Modifier.weight(1f)
            )
            // 心情卡 —— 自己可点击选 emoji
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.weight(1f).then(
                    if (subjectIsMe) Modifier.clickable { showMoodDialog = true } else Modifier
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("💖", fontSize = 16.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("当前心情", fontSize = 11.sp, color = Color(0xFF718096))
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
    if (showMoodDialog && subjectIsMe) {
        AlertDialog(
            onDismissRequest = { showMoodDialog = false },
            title = { Text("选个心情") },
            text = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    moodOptions.forEach { emoji ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (emoji == moodEmoji) pink.copy(alpha = 0.15f) else Color.Transparent,
                            modifier = Modifier.size(44.dp).clickable {
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

@Composable
private fun StatusChip(
    icon: String,
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = modifier
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 11.sp, color = Color(0xFF718096))
            }
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = accent, maxLines = 1)
        }
    }
}

// =====================================================================
// ③ 🕒 历史打开记录 —— 列表
// =====================================================================
@Composable
private fun HistoryOpenList(
    subjectId: String,
    subjectName: String,
    reloadKey: Int
) {
    var rows by remember { mutableStateOf<List<HistoryOpen>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(subjectId, reloadKey) {
        if (subjectId.isBlank()) { rows = emptyList(); return@LaunchedEffect }
        loading = true; loadError = null
        withContext(Dispatchers.IO) {
            val resp = runCatching {
                NetworkModule.restService.getAppUsage(
                    userId = "eq.$subjectId",
                    order = "created_at.desc",
                    limit = 100
                )
            }
            val r = resp.getOrNull()
            when {
                r == null -> loadError = resp.exceptionOrNull()?.message?.take(60) ?: "网络异常"
                !r.isSuccessful -> {
                    val body = runCatching { r.errorBody()?.string()?.take(120) }.getOrNull() ?: ""
                    loadError = "HTTP " + r.code() + " — " + body
                }
                else -> rows = aggregateOpens(r.body() ?: emptyList())
            }
            loading = false
        }
    }

    when {
        loading -> {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFE75480), modifier = Modifier.size(20.dp))
            }
        }
        loadError != null -> {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                Text("⚠️ 加载失败：" + loadError, fontSize = 12.sp, color = Color(0xFFE53E3E))
            }
        }
        rows.isEmpty() -> {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📭", fontSize = 28.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("最近 24 小时没有打开记录", fontSize = 12.sp, color = Color(0xFF718096))
                }
            }
        }
        else -> {
            // 紧凑时间线 —— 每条一行，左 emoji + 中事件 + 右时间戳
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                rows.forEach { open ->
                    HistoryTimelineItem(open)
                }
            }
        }
    }
}

@Composable
private fun HistoryTimelineItem(open: HistoryOpen) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左：emoji 图标
        Text(open.emoji, fontSize = 18.sp, modifier = Modifier.width(28.dp))
        Spacer(Modifier.width(6.dp))
        // 中：事件描述
        Text(
            "打开了 ${open.appName}",
            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF2D3748),
            modifier = Modifier.weight(1f)
        )
        // 右：时间戳
        Text(
            open.timeLabelShort,
            fontSize = 11.sp, color = Color(0xFFA0AEC0)
        )
    }
}

private val HistoryOpen.timeLabelShort: String
    get() {
        val label = timeLabel
        // "今天 19:38 打开" → "19:38"
        // "昨天 16:11 打开" → "昨天 16:11"
        // "09-01 10:00 打开" → "09-01 10:00"
        return label
            .replace("打开", "")
            .trim()
            .replace("今天 ", "")  // 今天只显示时间
    }

// =====================================================================
// 🔧 辅助函数 —— 本地设备查询工具
// =====================================================================

/** 本地查询前台 APP（UsageStatsManager）*/
private fun queryForegroundApp(ctx: Context): Pair<String, String>? {
    val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val pm = ctx.packageManager
    val end = System.currentTimeMillis()
    val begin = end - 120_000L
    val events = usm.queryEvents(begin, end)
    val ev = UsageEvents.Event()
    var latestFg: String? = null
    var latestTime = 0L
    while (events.hasNextEvent()) {
        events.getNextEvent(ev)
        if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && ev.timeStamp > latestTime) {
            latestTime = ev.timeStamp; latestFg = ev.packageName
        }
    }
    if (latestFg.isNullOrBlank()) return null
    val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(latestFg, 0)).toString() }
        .getOrDefault(latestFg)
    return latestFg to name
}

private fun localHasUsagePermission(ctx: Context): Boolean {
    val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    } else {
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun getBatteryPct(ctx: Context): Int {
    val im = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
    val level = im.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = im.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level < 0 || scale <= 0) -1 else (level * 100 / scale)
}

private fun getBatteryCharging(ctx: Context): Boolean {
    val im = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
    val status = im.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    return status == BatteryManager.BATTERY_STATUS_CHARGING ||
           status == BatteryManager.BATTERY_STATUS_FULL
}

private fun getNetworkType(ctx: Context): String {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val nc = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "无网络"
    return when {
        nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
            val ssid = getWifiSsidMultiAttempt(ctx)
            if (ssid != null) "WiFi · $ssid" else "WiFi"
        }
        nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
        nc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "蓝牙"
        nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
        nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "无网络"
    }
}

/** 多层尝试获取 WiFi SSID（不同 Android 版本/ROM 权限差异大） */
private fun getWifiSsidMultiAttempt(ctx: Context): String? {
    // 方案 1: WifiManager.connectionInfo (大多数场景可用)
    runCatching {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ssid = wm.connectionInfo?.ssid
        if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>" && ssid != "0x") {
            val cleaned = ssid.removeSurrounding("\"")
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
            f.isAccessible = true
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
        val wifiInfo = mWifiInfoField.invoke(wm)
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

/** package → category 映射（简化版） */
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
            else -> categorizeByPkg(pkg)
        }
    }.getOrDefault(categorizeByPkg(pkg))
}


/** 系统噪音 APP：桌面、输入法、系统UI、锁屏、虚拟按键等，不记录也不展示 */
fun isSystemNoisePkg(pkg: String?): Boolean {
    if (pkg.isNullOrBlank()) return true
    val p = pkg.lowercase()
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
    if (p.length < 5) return true
    if (!p.contains('.')) return true
    return false
}

private fun categorizeByPkg(pkg: String): String = when {
    pkg.contains("tencent.mm") || pkg.contains("qq") -> "社交"
    pkg.contains("douyin") || pkg.contains("aweme") || pkg.contains("bilibili") || pkg.contains("kuaishou") -> "视频"
    pkg.contains("netease.cloud") || pkg.contains("qqmusic") || pkg.contains("kugou") || pkg.contains("kuwo") -> "音乐"
    pkg.contains("taobao") || pkg.contains("tmall") || pkg.contains("jd") || pkg.contains("pinduoduo") -> "购物"
    pkg.contains("meituan") || pkg.contains("ele") -> "生活"
    pkg.contains("launcher") || pkg.contains("systemui") -> "桌面"
    else -> "其他"
}

/** 聚合 app_usage 行：同一个 APP 连续记录 → 只保留第一条（打开时刻）*/
private fun aggregateOpens(rows: List<AppUsageRow>): List<HistoryOpen> {
    if (rows.isEmpty()) return emptyList()
    val sorted = rows.sortedBy { parseIsoTime(it.created_at) } // 时间正序
    val result = mutableListOf<HistoryOpen>()
    var lastPkg = ""
    var lastStart = 0L
    var totalSeconds = 0

    for (row in sorted) {
        val ts = parseIsoTime(row.created_at)
        val pkg = row.package_name
        val gap = if (lastPkg.isNotEmpty()) (ts - lastStart) else 0
        if (pkg != lastPkg || gap > 180_000L) { // 换 APP 或间隔 > 3 分钟 → 新会话
            if (lastPkg.isNotEmpty()) {
                result.add(makeHistoryOpen(lastPkg, rows, lastStart, totalSeconds))
            }
            lastPkg = pkg; lastStart = ts; totalSeconds = row.usage_seconds
        } else {
            totalSeconds += row.usage_seconds
        }
    }
    if (lastPkg.isNotEmpty()) result.add(makeHistoryOpen(lastPkg, rows, lastStart, totalSeconds))
    // 按时间倒序返回
    return result.sortedByDescending { it.openAt }
}

private fun makeHistoryOpen(pkg: String, allRows: List<AppUsageRow>, startAt: Long, totalSec: Int): HistoryOpen {
    val firstRow = allRows.firstOrNull { it.package_name == pkg }
    val appName = firstRow?.app_name ?: pkg
    val cat = firstRow?.category ?: categorizeByPkg(pkg)
    val timeLabel = formatTimeLabel(startAt)
    return HistoryOpen(
        packageName = pkg, appName = appName, category = cat,
        openAt = startAt, timeLabel = timeLabel,
        duration = formatDuration(totalSec),
        emoji = categoryEmoji(cat)
    )
}

data class HistoryOpen(
    val packageName: String,
    val appName: String,
    val category: String,
    val openAt: Long,
    val timeLabel: String,
    val duration: String,
    val emoji: String
)

private fun dateRangeForDays(days: Int): Pair<String, String> {
    val zone = ZoneId.systemDefault()
    val date = LocalDate.now(zone).minusDays(days.toLong())
    val start = date.atStartOfDay(zone).toInstant().toString()
    val end = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toString()
    return "gte." + start to "lt." + end
}

private fun parseIsoTime(iso: String?): Long {
    if (iso.isNullOrBlank()) return 0L
    return runCatching {
        java.time.Instant.parse(iso).toEpochMilli()
    }.getOrDefault(0L)
}

private fun formatTimeLabel(epochMs: Long): String {
    if (epochMs <= 0) return ""
    val zone = ZoneId.systemDefault()
    val dt = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), zone)
    val today = LocalDate.now(zone)
    val yesterday = today.minusDays(1)
    val date = dt.toLocalDate()
    val time = dt.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (date) {
        today -> "今天 $time 打开"
        yesterday -> "昨天 $time 打开"
        else -> "${date.format(DateTimeFormatter.ofPattern("MM-dd"))} $time 打开"
    }
}

private fun relativeTime(epochMs: Long): String {
    if (epochMs <= 0) return ""
    val diff = System.currentTimeMillis() - epochMs
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

private fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "—"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}小时"
        m > 0 -> "${m}分钟"
        else -> "<1分钟"
    }
}

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
    else -> "📦"
}




