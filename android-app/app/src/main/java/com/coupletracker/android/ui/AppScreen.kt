package com.coupletracker.android.ui

import android.app.AppOpsManager
import android.app.BroadcastReceiver
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ProcessInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coupletracker.android.data.AppUsageRow
import com.coupletracker.android.data.LocationRow
import com.coupletracker.android.data.NetworkModule
import com.coupletracker.android.data.UserRepository
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
            Text("📱 应用动态", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
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

        Spacer(Modifier.height(16.dp))

        // ============= ② 手机状态 =============
        PhoneStatusCard(
            subjectId = subjectId,
            subjectName = subjectName,
            subjectIsMe = subjectIsMe,
            reloadKey = reloadKey
        )

        Spacer(Modifier.height(20.dp))

        // ============= ③ 历史打开记录 =============
        Text("🕒 最近打开记录", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
        Spacer(Modifier.height(10.dp))

        HistoryOpenList(
            subjectId = subjectId,
            subjectName = subjectName,
            reloadKey = reloadKey
        )

        Spacer(Modifier.height(24.dp))
        Text(
            "数据来自云端 app_usage 表（每 60 秒一条），自己的实时数据来自本地系统",
            fontSize = 10.sp, color = Color(0xFFA0AEC0),
            modifier = Modifier.align(Alignment.CenterHorizontally)
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

    // 本地实时查自己的前台 APP
    var fgPkg by remember { mutableStateOf("") }
    var fgName by remember { mutableStateOf("") }
    var fgCategory by remember { mutableStateOf("") }
    var fgStartAt by remember { mutableStateOf(0L) }
    var elapsedMinutes by remember { mutableStateOf(0) }

    // 远端查 TA 的（60 秒精度）
    var remoteAppName by remember { mutableStateOf("") }
    var remotePkg by remember { mutableStateOf("") }
    var remoteSeconds by remember { mutableStateOf(0) }
    var remoteUpdateAt by remember { mutableStateOf(0L) }

    // 自己：每 3 秒查一次前台 APP
    LaunchedEffect(subjectIsMe, reloadKey) {
        if (subjectIsMe) {
            if (!subjectHasPermission) return@LaunchedEffect
            while (isActive) {
                runCatching {
                    val now = System.currentTimeMillis()
                    val current = queryForegroundApp(ctx)
                    if (current != null && current.first.isNotEmpty()) {
                        val (pkg, name) = current
                        val cat = categoryOf(ctx, pkg)
                        if (pkg != fgPkg) {
                            fgPkg = pkg; fgName = name; fgCategory = cat; fgStartAt = now
                        }
                        elapsedMinutes = ((now - fgStartAt) / 60000L).toInt().coerceAtLeast(0)
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
                        NetworkModule.restService.getAppUsage(userId = subjectId, order = "created_at.desc", limit = 1)
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
                if (subjectIsMe) "🎯 我当前正在使用" else "🎯 $subjectName 当前正在使用",
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
                    Spacer(Modifier.height(2.dp))
                    Text("屏幕可能熄了 或 暂时没操作", fontSize = 11.sp, color = Color(0xFFA0AEC0))
                }
            } else if (!subjectIsMe && remoteAppName.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("🤔", fontSize = 36.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("${subjectName} 暂无使用记录", fontSize = 15.sp, color = Color(0xFF718096), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text("可能在休息 或 TA 还没授予使用情况访问权限", fontSize = 11.sp, color = Color(0xFFA0AEC0))
                }
            } else {
                // 有 APP 使用数据 → 大卡片展示
                val appEmoji = categoryEmoji(if (subjectIsMe) fgCategory else "")
                val appName = if (subjectIsMe) fgName else remoteAppName
                val category = if (subjectIsMe) fgCategory else ""
                val pkg = if (subjectIsMe) fgPkg else remotePkg
                val durationSec = if (subjectIsMe) elapsedMinutes * 60 else remoteSeconds
                val duration = if (subjectIsMe) formatDuration(durationSec) else formatDuration(durationSec)
                val accent = if (subjectIsMe) pink else blue

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(appEmoji, fontSize = 28.sp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            appName, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF2D3748), maxLines = 1
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (category.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = accent.copy(alpha = 0.12f)
                                ) {
                                    Text(category, fontSize = 11.sp, color = accent,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                if (subjectIsMe) "使用中 · 已 ${duration}" else "最近一次 · ${duration}",
                                fontSize = 12.sp, color = Color(0xFF718096)
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = accent.copy(alpha = 0.15f)
                        ) {
                            Text(
                                if (subjectIsMe) "🟢 实时" else "📡 云端",
                                fontSize = 10.sp, color = accent,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (subjectIsMe) pkg.takeLast(12).ifBlank { "-" } else relativeTime(remoteUpdateAt),
                            fontSize = 10.sp, color = Color(0xFFA0AEC0), maxLines = 1
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
                            userId = subjectId, order = "created_at.desc", limit = 1
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
            StatusChip(
                icon = if (isOnline) "🟢" else "🔴",
                label = if (isOnline) "在线" else "离线/可能关机",
                value = if (subjectIsMe) "实时" else relativeTime(taUpdatedAt),
                accent = if (isOnline) Color(0xFF2F855A) else Color(0xFFE53E3E),
                modifier = Modifier.weight(1f)
            )
            StatusChip(
                icon = "📍",
                label = "最后定位",
                value = if (subjectIsMe) "实时" else relativeTime(taUpdatedAt).ifBlank { "—" },
                accent = accent,
                modifier = Modifier.weight(1f)
            )
        }
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
            // 查今日 + 昨日的 app_usage（各 1000 条够用了）
            val (gte, lt) = dateRangeForDays(2)
            val resp = runCatching {
                NetworkModule.restService.getAppUsageInRange(
                    userId = subjectId,
                    createdAtGte = gte,
                    createdAtLt = lt,
                    order = "created_at.desc",
                    limit = 2000
                )
            }
            val r = resp.getOrNull()
            when {
                r == null -> loadError = resp.exceptionOrNull()?.message?.take(60) ?: "网络异常"
                !r.isSuccessful -> loadError = "HTTP " + r.code()
                else -> rows = aggregateOpens(r.body() ?: emptyList())
            }
            loading = false
        }
    }

    when {
        loading -> {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFE75480), modifier = Modifier.size(24.dp))
                }
            }
        }
        loadError != null -> {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("⚠️ 加载失败", fontSize = 14.sp, color = Color(0xFFE53E3E), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(loadError ?: "", fontSize = 11.sp, color = Color(0xFF718096))
                }
            }
        }
        rows.isEmpty() -> {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("📭", fontSize = 36.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("最近 2 天没有打开记录", fontSize = 13.sp, color = Color(0xFF718096))
                }
            }
        }
        else -> {
            rows.forEachIndexed { idx, open ->
                HistoryOpenRow(open = open)
                if (idx < rows.lastIndex) {
                    HorizontalDivider(color = Color(0xFFEDF2F7))
                }
            }
        }
    }
}

@Composable
private fun HistoryOpenRow(open: HistoryOpen) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            open.emoji, fontSize = 22.sp,
            modifier = Modifier.width(36.dp)
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "打开了 ${open.appName}",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2D3748)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                open.timeLabel,
                fontSize = 11.sp, color = Color(0xFF718096)
            )
        }
        Text(
            open.duration,
            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE75480)
        )
    }
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
        nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
        nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
            // 进一步区分 5G/4G/3G
            when (Build.VERSION.SDK_INT) {
                in 31..Int.MAX_VALUE -> "蜂窝" // Android 12+ 直接用这个
                else -> "移动网络"
            }
        }
        nc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "蓝牙"
        nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
        nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "无网络"
    }
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
