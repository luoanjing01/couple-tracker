// ============================================================================
// TidalHomeScreen.kt —— 潮汐卡片 v3 主界面（可拖拽底部抽屉）
//
// 对应设计稿 concept-d-tidal-cards-v3.html：
//   背景层：全屏 Leaflet 地图（由调用方传入 mapContent，WebView 缓存在 MainActivity）
//   顶部层：「我」「TA」头像气泡（52dp 圆形 + 3dp 白边 + 右下状态绿点）
//   抽屉层：BottomSheetScaffold，收起 200dp / 展开 = 屏高 - 125dp（不挡头像气泡）
//           把手 36×4dp + pill tabs（位置/状态/统计/我的，滚动锚点联动）
//           + 纵向滚动四个区块
//   底部层：点状导航（与滚动位置联动，点击跳对应区块）
//   彩蛋：收起态时抽屉上方有上下浮动的 ↑ 拖拽提示
//
// 数据绑定：
//   位置区块 -> locations 表（TA 最新位置 + 距你距离 + 导航/轨迹 chips）
//   状态区块 -> AppScreen(embedded)（device_status + app_usage）
//   统计区块 -> StatsScreen(embedded)（app_usage 聚合）
//   我的区块 -> SettingsScreen(embedded)（配对卡 + 采集频率 + 账号管理）
// ============================================================================
package com.coupletracker.android.ui.tidal

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coupletracker.android.data.LocationRow
import com.coupletracker.android.data.NetworkModule
import com.coupletracker.android.data.UserRepository
import com.coupletracker.android.ui.AppScreen
import com.coupletracker.android.ui.StatsScreen
import com.coupletracker.android.ui.TidalMapBridge
import com.coupletracker.android.ui.theme.ChipBg
import com.coupletracker.android.ui.theme.ChipBgCool
import com.coupletracker.android.ui.theme.Coral
import com.coupletracker.android.ui.theme.CoralSoft
import com.coupletracker.android.ui.theme.GlassStrongWhite
import com.coupletracker.android.ui.theme.Ink
import com.coupletracker.android.ui.theme.InkSoft
import com.coupletracker.android.ui.theme.Mint
import com.coupletracker.android.ui.theme.MintDeep
import com.coupletracker.android.ui.theme.MintSoft
import com.coupletracker.android.ui.theme.Muted
import com.coupletracker.android.ui.theme.Peach
import com.coupletracker.android.ui.theme.PureWhite
import com.coupletracker.android.ui.theme.StatusGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================================
// 抽屉 pill tab 标题（同时也是四个滚动锚点）
// ============================================================================
private val TidalTabs = listOf("位置", "状态", "统计", "我的")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TidalHomeScreen(
    mapContent: @Composable () -> Unit,
    settingsContent: @Composable (backToMap: () -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()

    // ---- 抽屉状态：默认收起（peek），禁止隐藏 ----
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    // ---- 内容滚动状态 + 各区块锚点偏移（onGloballyPositioned 实时记录）----
    val scrollState = rememberScrollState()
    val sectionTops = remember { mutableListOf(0, 0, 0, 0).toIntArray() }
    val sectionTopsVersion = remember { mutableIntStateOf(0) }   // 偏移更新时 +1 触发派生重算

    // 当前激活 tab：滚动位置越过哪个锚点，就高亮哪个（scroll-margin-top ≈ 50dp）
    val activeTab by remember {
        derivedStateOf {
            sectionTopsVersion.intValue  // 订阅版本号
            val y = scrollState.value
            var idx = 0
            for (i in TidalTabs.indices) {
                if (sectionTops[i] - 60 <= y) idx = i
            }
            idx
        }
    }
    // 展开态高度 = 屏高 - 125dp（设计稿：625 / 750，不挡顶部头像气泡）
    val screenH = LocalConfiguration.current.screenHeightDp
    val sheetMaxH = (screenH - 125).coerceAtLeast(360)

    // 跳到指定锚点（先展开抽屉，再滚动）
    fun jumpTo(i: Int) {
        scope.launch {
            sheetState.expand()
            scrollState.animateScrollTo((sectionTops[i] - 8).coerceAtLeast(0))
        }
    }

    // 「我的」页里的「看地图」按钮：收起抽屉回地图
    val backToMap: () -> Unit = {
        scope.launch { sheetState.partialExpand() }
    }

    Box(Modifier.fillMaxSize()) {
        // ====================================================================
        // 抽屉层 + 背景层
        // ====================================================================
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = 200.dp,
            sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            sheetContainerColor = GlassStrongWhite,
            sheetShadowElevation = 18.dp,
            sheetDragHandle = { SheetHandle() },
            sheetContent = {
                Column(Modifier.fillMaxWidth().height(sheetMaxH.dp)) {
                    // ======== pill tabs（圆角 999，选中白底珊瑚字）========
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .background(Color(0x0F3D2E2A), RoundedCornerShape(999.dp))
                            .padding(3.dp)
                    ) {
                        TidalTabs.forEachIndexed { i, label ->
                            val active = activeTab == i
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (active) PureWhite else Color.Transparent)
                                    .clickable { jumpTo(i) }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (active) Coral else InkSoft
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    // ======== 四个锚点区块（纵向滚动，全部常驻组合不销毁）========
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(scrollState)
                            .padding(horizontal = 16.dp)
                    ) {
                        // ① 位置（peek 卡片：收起态也可见）
                        Box(Modifier.trackAnchor(sectionTops, 0, sectionTopsVersion)) {
                            LocationSection(
                                onShowTrack = { scope.launch { sheetState.partialExpand() } },
                                onGoPair = { jumpTo(3) }
                            )
                        }
                        // ② 状态（手机状态 + 正在使用 + 智能手环）
                        Box(Modifier.trackAnchor(sectionTops, 1, sectionTopsVersion)) {
                            Column {
                                SectionTitle("📱 手机状态")
                                AppScreen(embedded = true)
                            }
                        }
                        // ③ 统计（每日统计 + 应用排行 + 最近打开）
                        Box(Modifier.trackAnchor(sectionTops, 2, sectionTopsVersion)) {
                            StatsScreen(embedded = true)
                        }
                        // ④ 我的（配对卡 + 采集频率 + 账号管理）
                        Box(Modifier.trackAnchor(sectionTops, 3, sectionTopsVersion)) {
                            Column {
                                SectionTitle("👤 我的")
                                settingsContent(backToMap)
                            }
                        }
                        // 底部留白：给点状导航腾出空间（设计稿 sheet-content padding-bottom 60px）
                        Spacer(Modifier.height(64.dp))
                    }
                }
            }
        ) {
            // 背景层：全屏地图（铺在抽屉底下，不吃 innerPadding）
            Box(Modifier.fillMaxSize()) { mapContent() }
        }

        // ====================================================================
        // 状态栏可读性 scrim（主流地图 App 方案：高德/滴滴同款）：
        // 状态栏保持透明沉浸式，但顶部叠一层 白色→透明 的纵向渐变，
        // 保证时间/电量等深色图标在浅色地图瓦片上始终清晰可读。
        // ====================================================================
        Box(
            Modifier
                .fillMaxWidth()
                .height(88.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.92f),
                            Color.White.copy(alpha = 0.55f),
                            Color.Transparent
                        )
                    )
                )
        )

        // ====================================================================
        // 顶部头像气泡（我 = 薄荷渐变，TA = 珊瑚渐变，常驻最上层）
        // ====================================================================
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 12.dp)
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            AvatarBubble(text = "我", isMe = true)
            AvatarBubble(text = "TA", isMe = false)
        }

        // ====================================================================
        // 底部点状导航（与滚动联动，点击跳转）
        // ====================================================================
        PageDots(
            count = TidalTabs.size,
            active = activeTab,
            onClick = { jumpTo(it) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
        )
    }
}

// ============================================================================
// 锚点追踪：记录每个区块在滚动内容 Column 内的 Y 偏移
// （boundsInParent 相对直接父容器 = 滚动内容 Column，不受滚动量影响，天然稳定）
// ============================================================================
private fun Modifier.trackAnchor(
    tops: IntArray,
    index: Int,
    version: androidx.compose.runtime.MutableIntState
): Modifier = this.onGloballyPositioned { coords ->
    val y = coords.boundsInParent().top.toInt()
    if (tops[index] != y) {
        tops[index] = y
        version.intValue++
    }
}

// ============================================================================
// 拖拽把手：36×4dp 圆角灰条
// ============================================================================
@Composable
private fun SheetHandle() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0x2E3D2E2A))
        )
    }
}

// ============================================================================
// 区块小标题：11sp 大写加粗弱化字（对应 .section-title）
// ============================================================================
@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = Muted,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.1.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 10.dp)
    )
}

// ============================================================================
// 头像气泡：52dp 圆形 + 渐变 + 3dp 白边 + 右下状态绿点（对应 .bubble）
// ============================================================================
@Composable
private fun AvatarBubble(text: String, isMe: Boolean) {
    Box(Modifier.size(52.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .shadow(6.dp, CircleShape)
                .background(
                    Brush.linearGradient(
                        if (isMe) listOf(Mint, MintSoft) else listOf(Coral, CoralSoft)
                    ),
                    CircleShape
                )
                .border(3.dp, PureWhite, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(text, color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        // 状态绿点（右下 1px 偏移，2dp 白边）
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 1.dp, y = 1.dp)
                .size(12.dp)
                .background(StatusGreen, CircleShape)
                .border(2.dp, PureWhite, CircleShape)
        )
    }
}

// ============================================================================
// 底部点状导航：8dp 圆点 / 选中 24dp 珊瑚胶囊（对应 .page-dots）
// ============================================================================
@Composable
private fun PageDots(
    count: Int,
    active: Int,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { i ->
            val isActive = i == active
            Box(
                Modifier
                    .size(width = if (isActive) 24.dp else 8.dp, height = 8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isActive) Coral else Color(0x333D2E2A))
                    .clickable { onClick(i) }
            )
        }
    }
}

// ============================================================================
// 位置区块（peek 卡片）
// ----------------------------------------------------------------------------
// 数据绑定（设计稿 + ApiService）：
//   - TA 最新位置：locations 表 getUserLocations(eq.partnerId, limit=1)，30s 轮询
//   - 我的最新位置：同上（用于算「距你 x km」，Location.distanceBetween）
//   - 更新于 X 分钟前：created_at → now
//   - eta 胶囊：无路径规划 API，用距离 ÷ 30km/h 估算车程约 X 分钟
//   - 🚗 导航 chip：系统 geo: Intent（高德/百度/系统地图均可接住，成熟方案）
//   - 📍 轨迹 chip：收起抽屉露出地图（轨迹线由 WebView 前端渲染）
// ============================================================================
@Composable
private fun LocationSection(
    onShowTrack: () -> Unit,
    onGoPair: () -> Unit
) {
    val ctx = LocalContext.current
    val user by UserRepository.get().userFlow.collectAsState(initial = null)
    val myId = user?.id
    val partnerId = user?.partnerId

    var partnerName by remember { mutableStateOf("TA") }
    var partnerLoc by remember { mutableStateOf<LocationRow?>(null) }
    var myLoc by remember { mutableStateOf<LocationRow?>(null) }
    // 轨迹开关状态：true=地图上正在显示两人当天轨迹
    var trackOn by remember { mutableStateOf(false) }

    // ---- 轮询：每 30 秒拉一次双方最新位置（看 TA = Supabase 远端数据）----
    LaunchedEffect(myId, partnerId) {
        while (true) {
            withContext(Dispatchers.IO) {
                if (!partnerId.isNullOrBlank()) {
                    runCatching {
                        NetworkModule.restService.getProfile(id = partnerId)
                    }.getOrNull()?.body()?.firstOrNull()?.let {
                        partnerName = it.nickname.ifBlank { it.username }
                    }
                    runCatching {
                        NetworkModule.restService.getUserLocations(userId = "eq.$partnerId", limit = 1)
                    }.getOrNull()?.body()?.firstOrNull()?.let { partnerLoc = it }
                } else {
                    partnerLoc = null
                }
                if (!myId.isNullOrBlank()) {
                    runCatching {
                        NetworkModule.restService.getUserLocations(userId = "eq.$myId", limit = 1)
                    }.getOrNull()?.body()?.firstOrNull()?.let { myLoc = it }
                }
            }
            delay(30_000L)
        }
    }

    // ---- 派生：距你距离（米）----
    val distMeters: Float? = partnerLoc?.let { p ->
        myLoc?.let { m ->
            val out = FloatArray(1)
            runCatching {
                android.location.Location.distanceBetween(
                    m.latitude, m.longitude, p.latitude, p.longitude, out
                )
            }
            out[0]
        }
    }

    // ---- 派生：更新时间（与地图前端同逻辑：timestamp || created_at）----
    // 成熟解析：epoch 毫秒 / ISO OffsetDateTime（+00:00）/ Instant（Z）三路兼容
    fun parseTimeMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        if (s.all { it.isDigit() }) return s.toLongOrNull()
        val normalized = s.replace(' ', 'T')
        return runCatching {
            java.time.OffsetDateTime.parse(normalized).toInstant().toEpochMilli()
        }.recoverCatching {
            java.time.Instant.parse(normalized).toEpochMilli()
        }.getOrNull()
    }
    fun agoText(row: LocationRow?): String {
        val t = parseTimeMs(row?.timestamp) ?: parseTimeMs(row?.created_at) ?: return "未知"
        val m = ((System.currentTimeMillis() - t) / 60_000L).coerceAtLeast(0)
        return when {
            m < 1 -> "刚刚"
            m < 60 -> "${m} 分钟前"
            else -> "${m / 60} 小时前"
        }
    }

    // ---- 文案装配 ----
    val paired = !partnerId.isNullOrBlank()
    val titleText = when {
        !paired -> "还没和 TA 配对"
        partnerLoc == null -> "$partnerName 的位置"
        else -> "$partnerName 在这里"
    }
    val subText = when {
        !paired -> "去「我的」页输入 TA 的配对码即可绑定"
        partnerLoc == null -> "等待对方上报位置…"
        else -> buildString {
            if (partnerLoc?.is_moving == true) append("移动中 · ")
            append("更新于 ${agoText(partnerLoc)}")
            distMeters?.let { d ->
                append(
                    if (d < 1000f) " · 距你 ${d.toInt()} m"
                    else " · 距你 ${"%.1f".format(d / 1000f)} km"
                )
            }
        }
    }
    // eta 胶囊：估算车程（30km/h ≈ 500m/分钟）；<100m 认为就在附近
    val etaText: String? = when {
        !paired || partnerLoc == null || distMeters == null -> null
        distMeters < 100f -> "就在附近"
        else -> "约 ${(distMeters / 500f).toInt().coerceAtLeast(1)} 分钟"
    }

    Column(Modifier.fillMaxWidth()) {
        // ======== peek-place：图标 + 地名 + 副文案 + eta 胶囊 ========
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Peach),
                contentAlignment = Alignment.Center
            ) {
                Text("📍", fontSize = 16.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(titleText, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(1.dp))
                Text(subText, fontSize = 11.sp, color = Muted)
            }
            if (etaText != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Coral)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(etaText, color = PureWhite, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // ======== peek-chips：🚗 导航 / 📍 轨迹 / 💑 去配对 ========
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (paired) {
                TidalChip(text = "🚗 导航", bg = ChipBg, fg = Coral) {
                    val p = partnerLoc ?: return@TidalChip
                    runCatching {
                        val uri = Uri.parse(
                            "geo:${p.latitude},${p.longitude}?q=${p.latitude},${p.longitude}($partnerName)"
                        )
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                }
                // 轨迹开关：按下显示两人当天轨迹并收起抽屉看地图，再按取消显示
                TidalChip(
                    text = if (trackOn) "✕ 取消轨迹" else "📍 轨迹",
                    bg = if (trackOn) ChipBg else ChipBgCool,
                    fg = if (trackOn) Coral else MintDeep
                ) {
                    trackOn = !trackOn
                    // 通过 JS 桥调前端 toggleTodayTracks（页面未就绪时前端静默忽略）
                    TidalMapBridge.eval(
                        "try{window.toggleTodayTracks&&window.toggleTodayTracks($trackOn);}catch(e){}"
                    )
                    if (trackOn) onShowTrack()   // 收起抽屉露出地图
                }
            } else {
                TidalChip(text = "💑 去配对", bg = ChipBgCool, fg = MintDeep) { onGoPair() }
            }
        }
    }
}

// ============================================================================
// 小胶囊 chip（对应 .chip / .chip.cool）
// ============================================================================
@Composable
private fun TidalChip(
    text: String,
    bg: Color,
    fg: Color,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}
