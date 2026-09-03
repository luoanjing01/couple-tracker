package com.coupletracker.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coupletracker.android.data.AppUsageRow
import com.coupletracker.android.data.NetworkModule
import com.coupletracker.android.data.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen() {
    val user by UserRepository.get().userFlow.collectAsState(initial = null)
    val myCode = user?.coupleCode.orEmpty()
    val myId = user?.id.orEmpty()

    var dayOffset by remember { mutableStateOf(0) } // 0=今天, 1=昨天, 2=前天
    var showPartner by remember { mutableStateOf(false) }

    var partnerId by remember { mutableStateOf<String?>(null) }
    var partnerName by remember { mutableStateOf("") }
    var partnerLoaded by remember { mutableStateOf(false) }

    var rows by remember { mutableStateOf<List<AppUsageRow>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

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

    LaunchedEffect(dayOffset, showPartner, partnerId, myId, reloadKey) {
        val targetId: String = (if (showPartner) partnerId else myId) ?: ""
        if (targetId.isBlank()) {
            rows = emptyList()
            loading = (showPartner && !partnerLoaded)
            return@LaunchedEffect
        }
        loading = true; loadError = null
        withContext(Dispatchers.IO) {
            val (gte, lt) = dateRangeFor(dayOffset)
            val resp = runCatching {
                NetworkModule.restService.getAppUsageInRange(
                    userId = "eq.$targetId",
                    createdAtFilter = "and($gte,$lt)"
                )
            }
            val r = resp.getOrNull()
            when {
                r == null -> {
                    loadError = resp.exceptionOrNull()?.message?.take(60) ?: "网络异常"
                    rows = emptyList()
                }
                !r.isSuccessful -> {
                    loadError = "HTTP " + r.code()
                    rows = emptyList()
                }
                else -> {
                    // 过滤系统噪音（桌面/输入法等）
                    rows = (r.body() ?: emptyList()).filter { !isStatsNoise(it.package_name) }
                }
            }
            loading = false
        }
    }

    // ===== 聚合 =====
    val zone = ZoneId.systemDefault()
    val byApp = remember(rows) {
        rows.groupBy { it.package_name }
            .map { (pkg, list) ->
                AppStat(
                    packageName = pkg,
                    appName = list.firstOrNull { !it.app_name.isNullOrBlank() }?.app_name ?: pkg,
                    category = list.firstOrNull { !it.category.isNullOrBlank() }?.category ?: "其他",
                    totalSeconds = list.sumOf { it.usage_seconds }
                )
            }
            .sortedByDescending { it.totalSeconds }
    }
    val totalSec = byApp.sumOf { it.totalSeconds }
    val topApp = byApp.firstOrNull()
    val maxSec = (byApp.maxOfOrNull { it.totalSeconds } ?: 1).coerceAtLeast(1)

    // 按小时聚合（0..23）
    val hourBuckets = remember(rows) {
        val hb = IntArray(24) { 0 }
        for (row in rows) {
            val lt = runCatching {
                java.time.Instant.parse(row.created_at).atZone(zone).toLocalDateTime()
            }.getOrNull() ?: continue
            hb[lt.hour] += row.usage_seconds
        }
        hb
    }

    val pink = Color(0xFFE75480)
    val blue = Color(0xFF667EEA)
    val bg = Color(0xFFFDF2F8)
    val mainColor = if (showPartner) blue else pink

    Column(
        Modifier
            .fillMaxSize()
            .background(bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("📊 每日统计", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { reloadKey++ }) {
                Text("🔄 刷新", color = blue, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        // 3 天切换
        val days = listOf(0 to "今日", 1 to "昨天", 2 to "前天")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            days.forEach { (offset, label) ->
                FilterChip(
                    selected = dayOffset == offset,
                    onClick = { dayOffset = offset },
                    label = { Text(label, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = mainColor.copy(alpha = 0.15f),
                        selectedLabelColor = mainColor
                    )
                )
            }
        }

        Spacer(Modifier.height(8.dp))

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
                        selectedContainerColor = pink.copy(alpha = 0.15f),
                        selectedLabelColor = pink
                    )
                )
                FilterChip(
                    selected = showPartner,
                    onClick = { showPartner = true },
                    label = { Text("TA · " + partnerName, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = blue.copy(alpha = 0.15f),
                        selectedLabelColor = blue
                    )
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        val subjectName = if (showPartner) (partnerName.ifBlank { "TA" }) else (user?.displayName ?: "我")

        // ===== 使用时长大卡 + 柱状图 =====
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    subjectName + " · " + dateLabel(dayOffset),
                    fontSize = 13.sp, color = Color(0xFF718096)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (totalSec > 0) formatDuration(totalSec) else "暂无记录",
                    fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = mainColor
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📱 使用 APP 数", fontSize = 12.sp, color = Color(0xFF718096))
                    Spacer(Modifier.width(6.dp))
                    Text("" + byApp.size + " 个", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2D3748))
                    Spacer(Modifier.width(18.dp))
                    Text("⭐ 最常用", fontSize = 12.sp, color = Color(0xFF718096))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        topApp?.let { categoryEmoji(it.category) + " " + it.appName } ?: "-",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2D3748),
                        maxLines = 1
                    )
                }

                // ===== 24小时柱状图 =====
                if (totalSec > 0) {
                    Spacer(Modifier.height(16.dp))
                    HourBarChart(hourBuckets = hourBuckets, color = mainColor)
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // ===== APP 排行 =====
        Text("🏆 APP 使用排行", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
        Spacer(Modifier.height(10.dp))

        when {
            loading -> {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = mainColor, modifier = Modifier.size(24.dp))
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
                        Text("⚠️ 加载失败", fontSize = 15.sp, color = Color(0xFFE53E3E), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(loadError ?: "", fontSize = 11.sp, color = Color(0xFF718096))
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = { reloadKey++ }) { Text("重试", color = blue) }
                    }
                }
            }
            byApp.isEmpty() -> {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("😴", fontSize = 40.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("这一天没有使用记录", fontSize = 14.sp, color = Color(0xFF718096))
                    }
                }
            }
            else -> {
                byApp.forEachIndexed { idx, app ->
                    val pct = if (totalSec > 0) app.totalSeconds * 100 / totalSec else 0
                    AppRankRow(
                        rank = idx + 1,
                        emoji = categoryEmoji(app.category),
                        name = app.appName,
                        category = app.category,
                        duration = formatDuration(app.totalSeconds),
                        percent = pct,
                        barFraction = app.totalSeconds.toFloat() / maxSec,
                        barColor = mainColor
                    )
                    if (idx < byApp.lastIndex) Spacer(Modifier.height(8.dp))
                }
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

// ============================================================================
// 24小时柱状图
// ============================================================================
@Composable
private fun HourBarChart(hourBuckets: IntArray, color: Color) {
    val maxBucket = hourBuckets.maxOrNull() ?: 0
    if (maxBucket == 0) return
    // Y轴刻度：分钟
    val yMaxMin = ((maxBucket / 60 + 9) / 10 * 10).coerceAtLeast(10)
    val yMaxSec = yMaxMin * 60

    Column {
        Row(Modifier.height(120.dp)) {
            // Y轴标签
            Column(
                Modifier.width(38.dp).fillMaxHeight(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${yMaxMin}分", fontSize = 9.sp, color = Color(0xFFA0AEC0))
                Text("${yMaxMin / 2}分", fontSize = 9.sp, color = Color(0xFFA0AEC0))
                Text("0", fontSize = 9.sp, color = Color(0xFFA0AEC0))
            }
            Spacer(Modifier.width(4.dp))
            // 柱状区
            Box(
                Modifier.weight(1f).fillMaxHeight()
            ) {
                // 背景横线
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    repeat(3) { HorizontalDivider(color = Color(0xFFEDF2F7), thickness = 0.5.dp) }
                }
                // 24根柱子
                Row(
                    Modifier.fillMaxWidth().fillMaxHeight(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(1.5.dp)
                ) {
                    for (h in 0..23) {
                        val sec = hourBuckets[h]
                        val f = (sec.toFloat() / yMaxSec).coerceIn(0f, 1f)
                        val hDp = (116 * f).dp
                        Box(
                            Modifier.weight(1f).fillMaxWidth().height(116.dp),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                Modifier.fillMaxWidth(0.6f).height(hDp)
                                    .background(color, RoundedCornerShape(2.dp))
                            )
                        }
                    }
                }
            }
        }
        // X轴标签
        Row(Modifier.fillMaxWidth().padding(start = 42.dp)) {
            listOf("0时" to 0, "6时" to 6, "12时" to 12, "18时" to 18).forEach { (label, h) ->
                Box(
                    Modifier.weight(if (h == 0) 6f else 6f),
                    contentAlignment = if (h == 0) Alignment.CenterStart else Alignment.Center
                ) {
                    Text(label, fontSize = 10.sp, color = Color(0xFFA0AEC0))
                }
            }
            // 补齐到24
            Box(Modifier.weight(6f)) {}
        }
    }
}

@Composable
private fun AppRankRow(
    rank: Int,
    emoji: String,
    name: String,
    category: String,
    duration: String,
    percent: Int,
    barFraction: Float,
    barColor: Color
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (rank <= 3) "🏅" else rank.toString(),
                    fontSize = 16.sp,
                    modifier = Modifier.width(28.dp)
                )
                Text(emoji, fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF2D3748),
                        maxLines = 1
                    )
                    Text(category, fontSize = 11.sp, color = Color(0xFF718096))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(duration, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
                    Text("" + percent + "%", fontSize = 11.sp, color = Color(0xFFA0AEC0))
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().height(6.dp)
                    .background(barColor.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
            ) {
                Box(
                    Modifier.fillMaxWidth(barFraction.coerceIn(0.02f, 1f)).height(6.dp)
                        .background(barColor, RoundedCornerShape(3.dp))
                )
            }
        }
    }
}

private data class AppStat(
    val packageName: String,
    val appName: String,
    val category: String,
    val totalSeconds: Int
)

private fun dateRangeFor(dayOffset: Int): Pair<String, String> {
    val zone = ZoneId.systemDefault()
    val date = LocalDate.now(zone).minusDays(dayOffset.toLong())
    val start = date.atStartOfDay(zone).toInstant().toString()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toString()
    return "gte." + start to "lt." + end
}

private fun dateLabel(dayOffset: Int): String = when (dayOffset) {
    0 -> "今日"
    1 -> "昨天"
    2 -> "前天"
    else -> dayOffset.toString() + "天前"
}

private fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 && m > 0 -> "" + h + "小时" + m + "分"
        h > 0 -> "" + h + "小时"
        m > 0 -> "" + m + "分钟"
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

/** 系统噪音：桌面/输入法/系统UI，统计里不计算 */
private fun isStatsNoise(pkg: String?): Boolean {
    if (pkg.isNullOrBlank()) return true
    val p = pkg.lowercase()
    if (p.contains("launcher") || p.contains("systemui") || p.contains("desk") || p.contains("homescreen")) return true
    if (p.contains("inputmethod") || p.contains("ime") || p.contains("input.")
        || p.contains("sougou") || p.contains("sogou") || p.contains("baidu.input")
        || p.contains("iflytek") || p.contains("讯飞")) return true
    if (p.contains("uiautomator") || p.contains("statusbar") || p.contains("navigationbar")
        || p.contains("keyguard") || p.contains("lockscreen") || p.contains("powerui")) return true
    if (p.contains("packageinstaller") || p.contains("permissioncontroller")) return true
    if (p.length < 5 || !p.contains('.')) return true
    return false
}
