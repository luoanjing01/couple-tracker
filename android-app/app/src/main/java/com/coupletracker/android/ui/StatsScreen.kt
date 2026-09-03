package com.coupletracker.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIos
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coupletracker.android.data.AppUsageRow
import com.coupletracker.android.data.NetworkModule
import com.coupletracker.android.data.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen() {
    val user by UserRepository.get().userFlow.collectAsState(initial = null)
    val myCode = user?.coupleCode.orEmpty()
    val myId = user?.id.orEmpty()

    // 0=每天, 1=近7天
    var tabIdx by remember { mutableStateOf(0) }
    // 每天模式：0=今天, -1=昨天, ...；近7天模式：表示以哪天为一周结束（0=以今天为周日）
    var dayOffset by remember { mutableStateOf(0) }
    var showPartner by remember { mutableStateOf(false) }
    var showCategory by remember { mutableStateOf(false) }

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

    // 加载：tabIdx=0 每天 → 查指定一天；tabIdx=1 近7天 → 查 7 天
    LaunchedEffect(tabIdx, dayOffset, showPartner, partnerId, myId, reloadKey) {
        val targetId: String = (if (showPartner) partnerId else myId) ?: ""
        if (targetId.isBlank()) {
            rows = emptyList(); loading = (showPartner && !partnerLoaded); return@LaunchedEffect
        }
        loading = true; loadError = null
        withContext(Dispatchers.IO) {
            val zone = ZoneId.systemDefault()
            // 每天模式：只查这一天
            // 近7天模式：查 (endDayOffset - 6) 到 endDayOffset 这 7 天
            val endDate = LocalDate.now(zone).minusDays(dayOffset.toLong())
            val startDate = if (tabIdx == 0) endDate else endDate.minusDays(6)
            val start = startDate.atStartOfDay(zone).toInstant().toString()
            val end = endDate.plusDays(1).atStartOfDay(zone).toInstant().toString()
            val resp = runCatching {
                NetworkModule.restService.getAppUsageInRange(
                    userId = "eq.$targetId",
                    createdAtFilter = "and(gte.$start,lt.$end)"
                )
            }
            val r = resp.getOrNull()
            when {
                r == null -> { loadError = resp.exceptionOrNull()?.message?.take(60) ?: "网络异常"; rows = emptyList() }
                !r.isSuccessful -> { loadError = "HTTP " + r.code(); rows = emptyList() }
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
    val now = LocalDateTime.now(zone)
    val endDate = LocalDate.now(zone).minusDays(dayOffset.toLong())
    val subjectName = if (showPartner) (partnerName.ifBlank { "TA" }) else (user?.displayName ?: "我")
    val mainColor = if (showPartner) Color(0xFF667EEA) else Color(0xFFE75480)

    val (byApp, totalSec, hourBuckets, dayBuckets, appCount, updatedLabel) = remember(rows, tabIdx, endDate, now) {
        val byApp = rows.groupBy { it.package_name }
            .map { (pkg, list) ->
                AppStat(
                    packageName = pkg,
                    appName = list.firstOrNull { !it.app_name.isNullOrBlank() }?.app_name ?: pkg,
                    category = list.firstOrNull { !it.category.isNullOrBlank() }?.category ?: "其他",
                    totalSeconds = list.sumOf { it.usage_seconds }
                )
            }
            .sortedByDescending { it.totalSeconds }
        val total = byApp.sumOf { it.totalSeconds }
        val appCnt = byApp.size

        // 按小时聚合（0..23）
        val hb = IntArray(24) { 0 }
        // 按天聚合（如果近7天则 7 天，单日模式无所谓）
        val db = IntArray(7) { 0 }
        val startOfRange = if (tabIdx == 0) endDate else endDate.minusDays(6)

        for (row in rows) {
            val lt = runCatching {
                java.time.Instant.parse(row.created_at).atZone(zone).toLocalDateTime()
            }.getOrNull() ?: continue
            hb[lt.hour] += row.usage_seconds
            // 计算相对 startOfRange 的天数索引 (0..6)
            val rel = lt.toLocalDate().toEpochDay() - startOfRange.toEpochDay()
            if (rel in 0..6) db[rel.toInt()] += row.usage_seconds
        }

        val label = buildString {
            append("使用时长（截至 ")
            if (tabIdx == 0) {
                if (endDate == LocalDate.now(zone)) append("今天 ")
                else if (endDate == LocalDate.now(zone).minusDays(1)) append("昨天 ")
                append(now.format(DateTimeFormatter.ofPattern("HH:mm")))
            } else {
                append(startOfRange.format(DateTimeFormatter.ofPattern("MM/dd")))
                append(" ~ ")
                append(endDate.format(DateTimeFormatter.ofPattern("MM/dd")))
            }
            append(")")
        }

        Quintuple(byApp, total, hb, db, appCnt, label)
    }

    val maxSec = (byApp.maxOfOrNull { it.totalSeconds } ?: 1).coerceAtLeast(1)

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F8FA))
            .verticalScroll(rememberScrollState())
    ) {
        // ===== 顶部：每天 / 近7天 =====
        TabRow(
            selectedTabIndex = tabIdx,
            containerColor = Color.White,
            contentColor = mainColor,
            divider = { HorizontalDivider(thickness = 0.dp, color = Color.Transparent) },
            indicator = { tabPositions ->
                TabRowDefaults.Indicator(
                    Modifier.tabIndicatorOffset(tabPositions[tabIdx]),
                    color = mainColor,
                    height = 2.dp
                )
            }
        ) {
            listOf("每天", "近7天").forEachIndexed { idx, title ->
                Tab(
                    selected = tabIdx == idx,
                    onClick = { tabIdx = idx; if (idx == 1) dayOffset = 0 },
                    selectedContentColor = Color(0xFF1A202C),
                    unselectedContentColor = Color(0xFF718096),
                    text = {
                        Text(
                            title,
                            fontSize = 15.sp,
                            fontWeight = if (tabIdx == idx) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        // ===== 配对方切换（和应用 Tab 同风格）=====
        if (partnerId != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !showPartner,
                    onClick = { showPartner = false },
                    label = { Text("我", fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFE75480).copy(alpha = 0.12f),
                        selectedLabelColor = Color(0xFFE75480)
                    )
                )
                FilterChip(
                    selected = showPartner,
                    onClick = { showPartner = true },
                    label = { Text("TA · $partnerName", fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF667EEA).copy(alpha = 0.12f),
                        selectedLabelColor = Color(0xFF667EEA)
                    )
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { reloadKey++ }) {
                    Text("🔄 刷新", color = Color(0xFF667EEA), fontSize = 12.sp)
                }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { reloadKey++ }) {
                    Text("🔄 刷新", color = Color(0xFF667EEA), fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // ===== 日期切换：左箭头 / 日期 / 右箭头 =====
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(
                onClick = { dayOffset = (dayOffset + 1).coerceAtMost(30) },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.ArrowBackIos,
                    contentDescription = "前一天",
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFF4A5568)
                )
            }

            Spacer(Modifier.width(14.dp))
            val dateTitle = remember(tabIdx, dayOffset, endDate) {
                val fmt1 = DateTimeFormatter.ofPattern("M月d日")
                if (tabIdx == 0) {
                    when {
                        dayOffset == 0 -> "${endDate.format(fmt1)}（今天）"
                        dayOffset == 1 -> "${endDate.format(fmt1)}（昨天）"
                        else -> endDate.format(fmt1)
                    }
                } else {
                    val s = endDate.minusDays(6).format(fmt1)
                    val e = endDate.format(fmt1)
                    "$s ~ $e"
                }
            }
            Text(dateTitle, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A202C))
            Spacer(Modifier.width(14.dp))

            IconButton(
                onClick = { if (dayOffset > 0) dayOffset-- },
                enabled = dayOffset > 0 || tabIdx == 1 && dayOffset > 0,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.ArrowForwardIos,
                    contentDescription = "后一天",
                    modifier = Modifier.size(14.dp),
                    tint = if (dayOffset > 0) Color(0xFF4A5568) else Color(0xFFCBD5E0)
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Box(Modifier.padding(horizontal = 16.dp)) {
            when {
                loading -> StatsLoadingCard()
                loadError != null -> StatsErrorCard(loadError) { reloadKey++ }
                else -> {
                    Column {
                        // ===== 使用时长大卡 =====
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    updatedLabel,
                                    fontSize = 12.sp,
                                    color = Color(0xFF718096)
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (totalSec > 0) formatStatDuration(totalSec) else "0分钟",
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF1A202C)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = Color(0xFFA0AEC0),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(Modifier.height(14.dp))

                                // ===== 柱状图 =====
                                if (tabIdx == 0) {
                                    HourlyChart(hourBuckets = hourBuckets, color = mainColor)
                                } else {
                                    WeeklyChart(dayBuckets = dayBuckets, endDate = endDate, color = mainColor)
                                }

                                Spacer(Modifier.height(16.dp))

                                // ===== 使用 APP 数量 =====
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("📱 使用 APP", fontSize = 12.sp, color = Color(0xFF718096))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "$appCount 个",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1A202C)
                                    )
                                    Spacer(Modifier.width(20.dp))
                                    Text(
                                        "🏅 最常用 ${byApp.firstOrNull()?.appName ?: "—"}",
                                        fontSize = 12.sp,
                                        color = Color(0xFF718096),
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        // ===== 使用排行 =====
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                        ) {
                            Text(
                                "使用排行",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1A202C)
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = { showCategory = !showCategory }
                            ) {
                                Text(
                                    if (showCategory) "隐藏类别" else "显示类别",
                                    fontSize = 12.sp,
                                    color = Color(0xFF667EEA),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))

                        if (byApp.isEmpty()) {
                            EmptyStatCard()
                        } else {
                            byApp.forEachIndexed { idx, app ->
                                val pct = if (totalSec > 0) app.totalSeconds * 100 / totalSec else 0
                                val barFraction = app.totalSeconds.toFloat() / maxSec
                                AppRankRow2(
                                    rank = idx + 1,
                                    emoji = statEmoji(app.category),
                                    name = app.appName,
                                    category = app.category,
                                    showCategory = showCategory,
                                    duration = formatStatDuration(app.totalSeconds),
                                    percent = pct,
                                    barFraction = barFraction,
                                    barColor = mainColor,
                                    isTop3 = idx < 3
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

// ============================================================================
// 📊 每日柱状图（24 小时）
// ============================================================================
@Composable
private fun HourlyChart(hourBuckets: IntArray, color: Color) {
    val maxBucket = hourBuckets.maxOrNull() ?: 0
    // Y 轴刻度：取最近的 5 分钟倍数，最少 10 分钟（显示上限）
    val yMaxMin = ((maxBucket / 60 + 9) / 10 * 10).coerceAtLeast(10)
    val yMaxSec = yMaxMin * 60
    val barAreaHeight = 120.dp
    val labelH = 16.sp.value.toInt().dp
    val chartHeight = barAreaHeight + labelH * 2 + 10.dp

    Column {
        Row(Modifier.height(barAreaHeight + 16.dp)) {
            // Y 轴标签（右对齐）
            Column(
                Modifier
                    .width(42.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${yMaxMin}分钟", fontSize = 9.sp, color = Color(0xFFA0AEC0))
                Text("${yMaxMin / 2}分钟", fontSize = 9.sp, color = Color(0xFFA0AEC0))
                Text("0", fontSize = 9.sp, color = Color(0xFFA0AEC0))
            }

            Spacer(Modifier.width(6.dp))

            // 柱状区
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                // 背景横线
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    repeat(3) { HorizontalDivider(color = Color(0xFFEDF2F7), thickness = 0.5.dp) }
                }

                // 24 个柱子
                Row(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(top = 0.dp, bottom = 0.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(1.5.dp)
                ) {
                    for (h in 0..23) {
                        val sec = hourBuckets[h]
                        val f = (sec.toFloat() / yMaxSec).coerceIn(0f, 1f)
                        val hDp = (116 * f).dp
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .height(116.dp),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(0.62f)
                                    .height(hDp)
                                    .background(color, RoundedCornerShape(2.dp))
                            )
                        }
                    }
                }
            }
        }

        // X 轴小时标签（0/6/12/18 时）
        Row(Modifier.fillMaxWidth().padding(start = 48.dp)) {
            for (h in 0..23 step 6) {
                // 每个小时占 weight=1，24个。我们要在 h=0,6,12,18 位置放标签
                if (h == 0) {
                    Box(Modifier.weight(6f), contentAlignment = Alignment.CenterStart) {
                        Text("0时", fontSize = 10.sp, color = Color(0xFFA0AEC0))
                    }
                } else {
                    Box(Modifier.weight(6f), contentAlignment = Alignment.Center) {
                        Text("${h}时", fontSize = 10.sp, color = Color(0xFFA0AEC0))
                    }
                }
            }
            // 补 24 以后的收尾（让最后一栏对齐）
        }
    }
}

// ============================================================================
// 📊 近 7 天柱状图
// ============================================================================
@Composable
private fun WeeklyChart(dayBuckets: IntArray, endDate: LocalDate, color: Color) {
    val zone = ZoneId.systemDefault()
    val start = endDate.minusDays(6)
    val labels = (0..6).map { i ->
        val d = start.plusDays(i.toLong())
        val dow = when (d.dayOfWeek) {
            DayOfWeek.MONDAY -> "一"
            DayOfWeek.TUESDAY -> "二"
            DayOfWeek.WEDNESDAY -> "三"
            DayOfWeek.THURSDAY -> "四"
            DayOfWeek.FRIDAY -> "五"
            DayOfWeek.SATURDAY -> "六"
            DayOfWeek.SUNDAY -> "日"
        }
        val today = LocalDate.now(zone)
        val suffix = if (d == today) "(今)" else if (d == today.minusDays(1)) "(昨)" else ""
        (d.format(DateTimeFormatter.ofPattern("MM/dd"))) to "$dow$suffix"
    }

    val maxBucket = dayBuckets.maxOrNull() ?: 0
    val yMaxMin = ((maxBucket / 3600 + 1)).coerceAtLeast(1) // 至少 1 小时
    val yMaxSec = yMaxMin * 3600
    val barAreaHeight = 120.dp

    Column {
        Row(Modifier.height(barAreaHeight + 16.dp)) {
            Column(
                Modifier
                    .width(42.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${yMaxMin}小时", fontSize = 9.sp, color = Color(0xFFA0AEC0))
                Text("${yMaxMin / 2}小时", fontSize = 9.sp, color = Color(0xFFA0AEC0))
                Text("0", fontSize = 9.sp, color = Color(0xFFA0AEC0))
            }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    repeat(3) { HorizontalDivider(color = Color(0xFFEDF2F7), thickness = 0.5.dp) }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (i in 0..6) {
                        val sec = dayBuckets[i]
                        val f = (sec.toFloat() / yMaxSec).coerceIn(0f, 1f)
                        val hDp = (116 * f).dp
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .height(116.dp),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(0.55f)
                                    .height(hDp)
                                    .background(color, RoundedCornerShape(3.dp))
                            )
                        }
                    }
                }
            }
        }
        // X 轴
        Row(Modifier.fillMaxWidth().padding(start = 48.dp)) {
            for (i in 0..6) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(labels[i].second, fontSize = 10.sp, color = Color(0xFFA0AEC0))
                }
            }
        }
    }
}

// ============================================================================
// 🏅 APP 排行条目（新风格：左图标+名字 / 右时长，下面进度条）
// ============================================================================
@Composable
private fun AppRankRow2(
    rank: Int,
    emoji: String,
    name: String,
    category: String,
    showCategory: Boolean,
    duration: String,
    percent: Int,
    barFraction: Float,
    barColor: Color,
    isTop3: Boolean
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 排名
                val rankBg = when (rank) {
                    1 -> Color(0xFFFFD700)
                    2 -> Color(0xFFC0C0C0)
                    3 -> Color(0xFFCD7F32)
                    else -> Color.Transparent
                }
                Text(
                    if (rank <= 3) "" else rank.toString(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF718096),
                    modifier = Modifier.width(22.dp)
                )
                // 图标
                Text(emoji, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                // 名字 + 分类
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1A202C),
                        maxLines = 1
                    )
                    if (showCategory) {
                        Text(category, fontSize = 11.sp, color = Color(0xFFA0AEC0))
                    }
                }
                // 时长
                Text(
                    duration,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A202C)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(30.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(5.dp)
                        .background(barColor.copy(alpha = 0.1f), RoundedCornerShape(2.dp))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(barFraction.coerceIn(0.02f, 1f))
                            .height(5.dp)
                            .background(barColor, RoundedCornerShape(2.dp))
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "$percent%",
                    fontSize = 10.sp,
                    color = Color(0xFFA0AEC0),
                    modifier = Modifier.width(32.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun StatsLoadingCard() {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFFE75480), modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun StatsErrorCard(msg: String?, onRetry: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("⚠️ 加载失败", fontSize = 13.sp, color = Color(0xFFE53E3E), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(msg ?: "", fontSize = 11.sp, color = Color(0xFF718096))
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onRetry) { Text("重试", color = Color(0xFF667EEA), fontSize = 12.sp) }
        }
    }
}

@Composable
private fun EmptyStatCard() {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("😴", fontSize = 32.sp)
            Spacer(Modifier.height(6.dp))
            Text("暂无使用记录", fontSize = 13.sp, color = Color(0xFF718096))
        }
    }
}

// ============================================================================
// 数据类 + 辅助函数
// ============================================================================

data class Quintuple<A, B, C, D, E, F>(val a: A, val b: B, val c: C, val d: D, val e: E, val f: F)

private data class AppStat(
    val packageName: String,
    val appName: String,
    val category: String,
    val totalSeconds: Int
)

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

private fun formatStatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 && m > 0 -> "" + h + "小时" + m + "分钟"
        h > 0 -> "" + h + "小时"
        m > 0 -> "" + m + "分钟"
        else -> "<1分钟"
    }
}

private fun statEmoji(category: String): String = when (category) {
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
