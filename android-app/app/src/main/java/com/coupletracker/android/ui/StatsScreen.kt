package com.coupletracker.android.ui

// ============================================================================
// 文件说明：每日使用统计界面 (StatsScreen)
// ----------------------------------------------------------------------------
// 本文件是情侣互动 App 的"每日统计"页面,使用 Jetpack Compose 编写。
// 主要功能：
//   1. 显示当前用户或伴侣在"今天/昨天/前天"的 APP 使用时长
//   2. 提供 24 小时柱状图,展示一天内的使用分布
//   3. 列出 APP 使用排行,按使用时长降序展示
//   4. 支持下拉刷新和"我/TA"切换查看
// 阅读建议：先看顶部的导入部分,再从 StatsScreen 函数开始阅读。
// ============================================================================

// ---------- Android Compose 基础组件导入 ----------
import androidx.compose.foundation.background     // 背景色修饰符
import androidx.compose.foundation.lazy.LazyRow            // 横向滑动列表（最近打开窗口）
import androidx.compose.foundation.layout.*         // 布局相关 (Column/Row/Box/Spacer 等)
import androidx.compose.foundation.rememberScrollState  // 记住滚动位置
import androidx.compose.foundation.shape.RoundedCornerShape  // 圆角形状
import androidx.compose.foundation.verticalScroll  // 垂直滚动
// ---------- 下拉刷新相关 (Material 旧 API) ----------
import androidx.compose.material.pullrefresh.PullRefreshIndicator      // 下拉刷新动画指示器
import androidx.compose.material.pullrefresh.pullRefresh                // 下拉刷新修饰符
import androidx.compose.material.pullrefresh.rememberPullRefreshState   // 记住刷新状态
// ---------- Material3 组件库 (按钮、卡片、文本等) ----------
import androidx.compose.material3.*
// ---------- Compose 运行时 (状态管理、副作用) ----------
import androidx.compose.runtime.*
// ---------- UI 辅助 (对齐、修饰符、颜色、字体) ----------
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush                   // 渐变画刷（LIVE 卡片）
import androidx.compose.ui.draw.clip                        // 圆角裁剪
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp    // 尺寸单位
import androidx.compose.ui.unit.sp    // 字号单位
// ---------- 本项目的数据层 (网络请求与用户仓储) ----------
import com.coupletracker.android.data.AppUsageRow    // 单条 APP 使用记录的数据模型
import com.coupletracker.android.data.NetworkModule  // 网络模块入口
import com.coupletracker.android.data.UserRepository // 用户信息本地仓储
// ---------- Kotlin 协程 (异步任务) ----------
import kotlinx.coroutines.Dispatchers     // 线程调度器 (IO/主线程)
import kotlinx.coroutines.delay           // 延时函数
import kotlinx.coroutines.launch           // 启动协程
import kotlinx.coroutines.withContext      // 切换协程上下文
// ---------- Java 时间 API (日期计算) ----------
import java.time.LocalDate              // 日期 (不含时分秒)
import java.time.ZoneId                  // 时区
import java.time.format.DateTimeFormatter // 日期格式化 (本文件实际未使用)

// @OptIn: 声明使用实验性 API,避免编译器告警
//   ExperimentalMaterial3Api       : Material3 中尚未稳定的 API
//   ExperimentalMaterialApi        : 旧版下拉刷新 API (pullRefresh)
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material.ExperimentalMaterialApi::class)
// @Composable: 标记此函数为 Compose 可组合函数,可在 UI 树中使用
@Composable
fun StatsScreen(embedded: Boolean = false, showPartnerOverride: Boolean? = null) {
    // =========================================================================
    // 第一部分：状态初始化
    // =========================================================================

    // 从全局 UserRepository 获取当前登录用户;collectAsState 让 UI 自动响应数据变化
    val user by UserRepository.get().userFlow.collectAsState(initial = null)
    val myCode = user?.coupleCode.orEmpty()  // 我的配对码 (本页未直接使用,预留)
    val myId = user?.id.orEmpty()           // 我的用户 ID,用于查询自己的使用记录

    // dayOffset: 选中的日期偏移,0=今天, 1=昨天, 2=前天
    // remember: 在 recomposition (UI 重组) 之间保留值;mutableStateOf 让修改能触发 UI 刷新
    var dayOffset by remember { mutableStateOf(0) } // 0=今天, 1=昨天, 2=前天
    // showPartner: 是否正在查看伴侣的数据 (false=看自己, true=看 TA)
    var showPartnerLocal by remember { mutableStateOf(false) }
    // 潮汐卡片 v3：外部（顶部头像气泡）控制查看对象时，传入 override 接管切换
    val showPartner = showPartnerOverride ?: showPartnerLocal

    // 伴侣信息 (异步加载,可能为空)
    var partnerId by remember { mutableStateOf<String?>(null) }   // 伴侣的用户 ID
    var partnerName by remember { mutableStateOf("") }            // 伴侣昵称
    var partnerLoaded by remember { mutableStateOf(false) }       // 伴侣信息是否已加载完成

    // 使用记录列表与加载状态
    var rows by remember { mutableStateOf<List<AppUsageRow>>(emptyList()) }  // 当前展示的原始记录
    var loading by remember { mutableStateOf(false) }            // 是否正在加载
    var loadError by remember { mutableStateOf<String?>(null) } // 加载失败的错误信息
    var reloadKey by remember { mutableStateOf(0) }             // 改变它可强制重新加载 (用作"触发器")

    // =========================================================================
    // 第二部分：下拉刷新配置
    // =========================================================================
    // ---- 下拉刷新 ----
    var isRefreshing by remember { mutableStateOf(false) }  // 是否正在刷新动画显示中
    val scrollState = rememberScrollState()                 // 滚动位置状态 (用于内容可滚动)
    val refreshScope = rememberCoroutineScope()            // 协程作用域,在 Composable 中启动协程
    // rememberPullRefreshState: 创建下拉刷新所需的状态对象
    //   refreshing : 当前是否处于刷新中 (控制指示器显示)
    //   onRefresh  : 用户触发下拉刷新时执行的回调
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true              // 立刻显示刷新动画
            reloadKey++                        // 改变 reloadKey 触发下方 LaunchedEffect 重新拉数据
            refreshScope.launch {
                delay(1500)                  // 至少显示 1.5 秒动画,体验更自然
                isRefreshing = false          // 关闭刷新动画
            }
        }
    )

    // =========================================================================
    // 第三部分：加载伴侣信息
    // =========================================================================
    // LaunchedEffect: 当 key (此处为 myCode/myId) 变化时,执行一次块内代码
    //   作用：根据当前用户的 partnerId 字段,去服务器查询伴侣的资料 (id + 昵称)
    //   重新进入此页面或用户信息变化时,会重新加载
    LaunchedEffect(myCode, myId) {
        // 先重置伴侣相关状态,避免显示旧数据
        partnerId = null; partnerName = ""; partnerLoaded = false
        val myPartnerId = user?.partnerId
        // UUID 正则：用于校验 partnerId 是否是合法的 UUID 格式 (避免无效字符串请求服务器)
        val uuidRe = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
        if (!myPartnerId.isNullOrBlank() && uuidRe.matches(myPartnerId)) {
            // 切换到 IO 线程,避免网络请求阻塞主线程 (UI 线程)
            withContext(Dispatchers.IO) {
                // runCatching: 安全调用,出现异常不会让 App 崩溃;返回 Result 对象
                runCatching {
                    NetworkModule.restService.getProfile(id = myPartnerId)  // 通过 ID 查询伴侣资料
                }.getOrNull()?.body()?.firstOrNull()?.let { p ->  // 取返回列表的第一项
                    partnerId = p.id
                    partnerName = p.nickname.ifBlank { p.username }  // 昵称为空时退回到用户名
                }
                partnerLoaded = true  // 标记加载完成 (无论成功失败)
            }
        } else {
            // 没有 partnerId 或格式不合法,直接标记为加载完成
            partnerLoaded = true
        }
    }

    // =========================================================================
    // 第四部分：根据当前选择加载 APP 使用记录
    // =========================================================================
    // 此 LaunchedEffect 监听多个 key:
    //   dayOffset    : 日期切换时重拉
    //   showPartner  : 我/TA 切换时重拉
    //   partnerId    : 伴侣 ID 异步加载完成后重拉
    //   myId         : 自己 ID 变化时重拉
    //   reloadKey    : 下拉刷新 / 重试按钮触发时重拉
    LaunchedEffect(dayOffset, showPartner, partnerId, myId, reloadKey) {
        // 决定要查询谁的数据：看伴侣时用 partnerId,看自己时用 myId
        val targetId: String = (if (showPartner) partnerId else myId) ?: ""
        if (targetId.isBlank()) {
            // 目标 ID 为空 (例如查伴侣但伴侣 ID 尚未加载完成),清空数据并保持 loading 状态
            rows = emptyList()
            loading = (showPartner && !partnerLoaded)
            return@LaunchedEffect  // 直接退出本次副作用
        }
        loading = true; loadError = null  // 进入加载中,清空之前的错误信息
        withContext(Dispatchers.IO) {
            // 不用 and(gte,lt) 语法（PostgREST 会 400），直接拉全部再客户端过滤
            //   原因：PostgREST 的区间查询在某些情况下会返回 400 错误,所以这里一次性拉最近 1000 条
            val resp = runCatching {
                NetworkModule.restService.getAppUsage(
                    userId = "eq.$targetId",       // 等值过滤：只查这个用户
                    order = "created_at.desc",    // 按时间倒序 (新的在前)
                    limit = 1000                  // 最多 1000 条
                )
            }
            val r = resp.getOrNull()
            when {
                // 情况 1：网络异常或抛错
                r == null -> {
                    // 截取前 60 字符的错误信息,避免过长
                    loadError = resp.exceptionOrNull()?.message?.take(60) ?: "网络异常"
                    rows = emptyList()
                }
                // 情况 2：服务器返回但 HTTP 状态码非 2xx
                !r.isSuccessful -> {
                    loadError = "HTTP " + r.code()  // 例如 "HTTP 500"
                    rows = emptyList()
                }
                // 情况 3：成功拿到数据
                else -> {
                    val zone = ZoneId.systemDefault()                              // 系统时区
                    val targetDate = LocalDate.now(zone).minusDays(dayOffset.toLong())  // 目标日期 = 今天 - 偏移
                    // 客户端按日期过滤 + 过滤系统噪音
                    //   isStatsNoise: 过滤桌面/输入法/系统 UI 等不应统计的包名
                    //   日期比较：把记录的 UTC 时间转换成本地时区日期,看是否等于目标日期
                    rows = (r.body() ?: emptyList()).filter { row ->
                        !isStatsNoise(row.package_name) && runCatching {
                            java.time.Instant.parse(row.created_at).atZone(zone).toLocalDate() == targetDate
                        }.getOrDefault(false)
                    }
                }
            }
            loading = false  // 无论成功失败,加载阶段结束
        }
    }

    // =========================================================================
    // 第五部分：本地数据聚合 (把原始记录汇总成统计指标)
    // =========================================================================
    // ===== 聚合 =====
    val zone = ZoneId.systemDefault()  // 系统时区,用于把时间戳换算成本地时辰
    // byApp: 把 rows 按包名分组,累加使用时长,得到每个 APP 的统计对象
    // remember(rows): 当 rows 改变时才重新计算,避免每次重组都重复计算
    val byApp = remember(rows) {
        rows.groupBy { it.package_name }                       // 按 package_name 分组
            .map { (pkg, list) ->                              // 把每组转换成 AppStat
                AppStat(
                    packageName = pkg,
                    appName = list.firstOrNull { !it.app_name.isNullOrBlank() }?.app_name ?: pkg,  // 取第一个非空名称,否则用包名
                    category = list.firstOrNull { !it.category.isNullOrBlank() }?.category ?: "其他", // 取第一个非空分类,否则标"其他"
                    totalSeconds = list.sumOf { it.usage_seconds }                            // 该 APP 总使用秒数
                )
            }
            .sortedByDescending { it.totalSeconds }  // 按使用时长降序,最长的排第一
    }
    val totalSec = byApp.sumOf { it.totalSeconds }                          // 一天总使用秒数
    val topApp = byApp.firstOrNull()                                        // 使用时长最长的 APP
    val maxSec = (byApp.maxOfOrNull { it.totalSeconds } ?: 1).coerceAtLeast(1)  // 单 APP 最大时长,用作柱状图比例基准,至少为 1 避免除 0

    // =========================================================================
    // 第六部分：按小时聚合 (生成 24 小时柱状图所需的数据)
    // =========================================================================
    // 按小时聚合（0..23）
    // hourBuckets: 长度 24 的 IntArray,索引 0~23 分别对应该小时的总使用秒数
    val hourBuckets = remember(rows) {
        val hb = IntArray(24) { 0 }                                    // 初始化 24 个桶,全部填 0
        for (row in rows) {
            // 把记录的 UTC 时间字符串解析成本地时区的 LocalDateTime
            // runCatching 保护：解析失败则跳过这一行
            val lt = runCatching {
                java.time.Instant.parse(row.created_at).atZone(zone).toLocalDateTime()
            }.getOrNull() ?: continue
            hb[lt.hour] += row.usage_seconds   // 把时长累加到对应小时桶里
        }
        hb
    }

    // =========================================================================
    // 第七部分：颜色主题
    // =========================================================================
    val pink = Color(0xFFFF8B7B)             // 珊瑚粉 (代表"我")
    val blue = Color(0xFF3A9E91)             // 薄荷绿 (代表"TA" 伴侣)
    // 主色调：查看伴侣时用蓝色,查看自己时用粉色,UI 整体随之切换
    val mainColor = if (showPartner) blue else pink

    // =========================================================================
    // 第八部分：内容本体（内嵌模式与外框模式共用）
    // 方案 D v3 适配：embedded=true 时由外层潮汐抽屉（TidalHomeScreen）提供滚动与容器，
    // 本页只渲染内容列；embedded=false 时才使用自己的渐变背景 + 下拉刷新 + 滚动外壳。
    // =========================================================================
    @Composable
    fun Content() {
        // ---- 8.1 顶部标题栏 (标题 + 我/TA 切换按钮) ----
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("每日统计", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3D2E2A))
            Spacer(Modifier.weight(1f))  // 弹性空白,把后续元素推到右侧
            // 切换按钮（外部接管视角时隐藏，由顶部头像气泡切换）
            if (showPartnerOverride == null) Button(
                onClick = {
                    // 只有已加载到伴侣 ID 时才允许切换,否则按钮颜色为灰,按下也不响应
                    if (partnerId != null) {
                        showPartnerLocal = !showPartnerLocal; reloadKey++  // 切换状态并触发数据重拉
                    }
                },
                shape = RoundedCornerShape(20.dp),  // 圆角 20dp,呈胶囊状
                colors = ButtonDefaults.buttonColors(
                    // 按钮背景色：未配对→灰色;查看自己时→粉色;查看伴侣时→蓝色
                    containerColor = if (partnerId == null) Color(0xFFD8C7BA)
                    else if (showPartner) blue else pink
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)  // 按钮内边距
            ) {
                Text(
                    // 文案：未配对显示"💤 未配对";看自己时显示"💕 TA"(点击切到伴侣);看伴侣时显示"👤 我"(点击切回自己)
                    if (partnerId == null) "💤 未配对"
                    else if (showPartner) "👤 我" else "💕 TA",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Spacer(Modifier.height(12.dp))  // 标题栏与下方间距

        // ---- 8.2 日期选择器 (今日 / 昨天 / 前天) ----
        // 3 天切换
        val days = listOf(0 to "今日", 1 to "昨天", 2 to "前天")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)  // 子元素之间间距 8dp
        ) {
            days.forEach { (offset, label) ->
                // FilterChip: 可选中的小标签 (像 chip 一样)
                FilterChip(
                    selected = dayOffset == offset,         // 当前是否选中
                    onClick = { dayOffset = offset },          // 点击切换到对应日期
                    label = { Text(label, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = mainColor.copy(alpha = 0.15f),  // 选中背景 (主色 + 透明度)
                        selectedLabelColor = mainColor                          // 选中文字色 (主色)
                    )
                )
            }
        }

        Spacer(Modifier.height(8.dp))  // 与下方卡片间距

        if (partnerId != null) {
            Spacer(Modifier.height(4.dp))  // 配对时多加 4dp,留出视觉缓冲
        }

        // subjectName: 当前查看对象的名字 (伴侣用伴侣名,自己用 displayName 或"我")
        val subjectName = if (showPartner) (partnerName.ifBlank { "TA" }) else (user?.displayName ?: "我")

        // =========================================================================
        // 第九部分：使用时长大卡 + 24小时柱状图
        // =========================================================================
        // ===== 使用时长大卡 + 柱状图 =====
        Card(
            shape = RoundedCornerShape(18.dp),                       // 卡片圆角 18dp
            colors = CardDefaults.cardColors(containerColor = Color.White)  // 卡片背景白色
        ) {
            Column(Modifier.padding(18.dp)) {
                // 顶部小标题：对象名 · 日期标签 (如 "小明 · 今日")
                Text(
                    subjectName + " · " + dateLabel(dayOffset),
                    fontSize = 13.sp, color = Color(0xFFA89890)
                )
                Spacer(Modifier.height(6.dp))
                // 大字号总时长 (如 "3小时45分"),无数据则显示"暂无记录"
                Text(
                    if (totalSec > 0) formatDuration(totalSec) else "暂无记录",
                    fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = mainColor
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 左侧：使用 APP 数量
                    Text("📱 使用 APP 数", fontSize = 12.sp, color = Color(0xFFA89890))
                    Spacer(Modifier.width(6.dp))
                    Text("" + byApp.size + " 个", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF3D2E2A))
                    Spacer(Modifier.width(18.dp))
                    // 右侧：最常用 APP
                    Text("⭐ 最常用", fontSize = 12.sp, color = Color(0xFFA89890))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        // 有数据→"emoji + APP名";无数据→"-"
                        topApp?.let { categoryEmoji(it.category) + " " + it.appName } ?: "-",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF3D2E2A),
                        maxLines = 1  // 单行,避免超长 APP 名撑破布局
                    )
                }

                // ===== 24小时柱状图 =====
                // 仅当总时长 > 0 时才显示柱状图
                if (totalSec > 0) {
                    Spacer(Modifier.height(16.dp))
                    HourBarChart(hourBuckets = hourBuckets, color = mainColor)  // 调用自定义柱状图组件
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 最近打开：横向滑动窗口（自原「应用」页迁移至此，可一直滑动查看）----
        RecentOpensWindow(
            subjectId = (if (showPartner) partnerId else myId) ?: "",
            accent = mainColor,
            reloadKey = reloadKey
        )

        Spacer(Modifier.height(18.dp))  // 卡片与排行列表间距

        // =========================================================================
        // 第十部分：APP 使用排行 (按使用时长降序列出每个 APP)
        // =========================================================================
        // ===== APP 排行 =====
        Text("🏆 APP 使用排行", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3D2E2A))
        Spacer(Modifier.height(10.dp))

        // 根据当前状态显示不同内容 (loading / 错误 / 空数据 / 正常列表)
        when {
            // 状态 1：正在加载中,显示加载圈
            loading -> {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = mainColor, modifier = Modifier.size(24.dp))  // 转圈加载动画
                    }
                }
            }
            // 状态 2：加载失败,显示错误信息和"重试"按钮
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
                        Text(loadError ?: "", fontSize = 11.sp, color = Color(0xFFA89890))
                        Spacer(Modifier.height(10.dp))
                        // 点击重试：自增 reloadKey 触发上方 LaunchedEffect 重新加载
                        TextButton(onClick = { reloadKey++ }) { Text("重试", color = blue) }
                    }
                }
            }
            // 状态 3：成功但无数据,显示"这一天没有使用记录"
            byApp.isEmpty() -> {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("😴", fontSize = 40.sp)  // 大号 emoji 表达"今日无记录"
                        Spacer(Modifier.height(8.dp))
                        Text("这一天没有使用记录", fontSize = 14.sp, color = Color(0xFFA89890))
                    }
                }
            }
            // 状态 4：正常显示 APP 排行
            else -> {
                byApp.forEachIndexed { idx, app ->
                    // 计算该 APP 占总时长的百分比 (整数)
                    val pct = if (totalSec > 0) app.totalSeconds * 100 / totalSec else 0
                    // 调用单行组件渲染
                    AppRankRow(
                        rank = idx + 1,                                                     // 排名从 1 开始
                        emoji = categoryEmoji(app.category),                                // 类别 emoji
                        name = app.appName,                                                 // APP 名
                        category = app.category,                                            // 分类名
                        duration = formatDuration(app.totalSeconds),                        // 时长文本
                        percent = pct,                                                      // 百分比
                        barFraction = app.totalSeconds.toFloat() / maxSec,                  // 柱状比例 (相对最长 APP)
                        barColor = mainColor                                                // 柱状颜色
                    )
                    // 排行项之间留 8dp 间距,但最后一项之后不加间距
                    if (idx < byApp.lastIndex) Spacer(Modifier.height(8.dp))
                }
            }
        }

        Spacer(Modifier.height(20.dp))  // 列表底部留白
    }      // closes Content

    // =========================================================================
    // 第九部分：根容器（内嵌模式 = 纯内容列；独立模式 = 渐变背景 + 下拉刷新 + 滚动）
    // =========================================================================
    if (embedded) {
        Column(Modifier.fillMaxWidth()) { Content() }
    } else {
        Box(
            Modifier
                .fillMaxSize()           // 占满整个屏幕
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFFFFE4D1))))  // 奶油 → 蜜桃渐变（方案 D）
                .pullRefresh(pullRefreshState)  // 绑定下拉刷新 (让此 Box 内的下拉可触发刷新)
        ) {
            // 下拉刷新动画指示器 (顶部小圆圈),固定在 Box 顶部居中
            PullRefreshIndicator(
                refreshing = isRefreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                contentColor = mainColor
            )

            // 主内容区：垂直滚动的列表
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)                  // 启用垂直滚动
                    .padding(horizontal = 16.dp, vertical = 18.dp)  // 内边距,左右 16dp,上下 18dp
            ) { Content() }
        }
    }
}

// ============================================================================
// 最近打开 —— 水平滑动窗口（方案 D · 潮汐卡片）
// ----------------------------------------------------------------------------
// 从云端拉取该用户最近 100 条 app_usage 记录，用 AppScreen.kt 里的 aggregateOpens
// 聚合成「打开会话」，以横向可滑动的卡片流展示（最新一条若仍在进行中会高亮为
// 渐变珊瑚色 + LIVE 标签）。数据逻辑与原「应用页 · 最近打开记录」完全一致，
// 仅把纵向时间线换成横向滑窗，解决统计页空间拥挤问题。
// ============================================================================
@Composable
private fun RecentOpensWindow(
    subjectId: String,
    accent: Color,
    reloadKey: Int
) {
    // 列表数据 + 加载状态
    var opens by remember { mutableStateOf<List<HistoryOpen>>(emptyList()) }  // 聚合后的打开会话
    var loading by remember { mutableStateOf(false) }                        // 是否正在加载
    var loadError by remember { mutableStateOf<String?>(null) }              // 加载失败错误信息

    // 进入/参数变化时拉一次数据（与原 HistoryOpenList 同一数据源）
    LaunchedEffect(subjectId, reloadKey) {
        if (subjectId.isBlank()) { opens = emptyList(); return@LaunchedEffect }
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
                !r.isSuccessful -> loadError = "HTTP " + r.code()
                else -> opens = aggregateOpens(r.body() ?: emptyList())
            }
            loading = false
        }
    }

    // 窗口容器：半透明白卡（与状态页 status-tile 同一质感）
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.65f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            // 标题行：左「🕘 最近打开」，右滑动提示
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🕘 最近打开", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFFA89890), letterSpacing = 1.sp
                )
                Spacer(Modifier.weight(1f))
                Text("左右滑动查看 →", fontSize = 10.sp, color = Color(0xFFA89890))
            }
            Spacer(Modifier.height(10.dp))

            when {
                // 加载中：小转圈
                loading -> Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent, modifier = Modifier.size(20.dp))
                }
                // 加载失败：红色错误文案
                loadError != null -> Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                    Text("⚠️ 加载失败：" + loadError, fontSize = 11.sp, color = Color(0xFFE53E3E))
                }
                // 空数据
                opens.isEmpty() -> Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                    Text("📭 暂无打开记录", fontSize = 12.sp, color = Color(0xFFA89890))
                }
                // 正常：横向滑动卡片流（LazyRow 按需组合，列表再长也不卡）
                else -> {
                    val nowMs = System.currentTimeMillis()
                    val maxSec = (opens.maxOfOrNull { it.totalSeconds } ?: 1).coerceAtLeast(1)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(opens.size) { idx ->
                            val open = opens[idx]
                            // 最新一条且会话结束时间距今 < 3 分钟 → 视为「正在使用」高亮卡
                            val isNow = idx == 0 &&
                                (nowMs - (open.openAt + open.totalSeconds * 1000L)) < 3 * 60_000L
                            RecentOpenCard(open = open, isNow = isNow, maxSec = maxSec, accent = accent)
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// RecentOpenCard —— 单张「最近打开」卡片（固定宽 124dp）
//   - isNow=true：渐变珊瑚底 + 白字 + LIVE 标签
//   - 否则：白底 + 墨色字 + 珊瑚进度条
// ============================================================================
@Composable
private fun RecentOpenCard(open: HistoryOpen, isNow: Boolean, maxSec: Int, accent: Color) {
    // 进度条比例：相对本次列表中时长最长的一条（至少 4% 保证可见）
    val frac = (open.totalSeconds.toFloat() / maxSec).coerceIn(0.04f, 1f)
    val contentColor = if (isNow) Color.White else Color(0xFF3D2E2A)
    val subColor = if (isNow) Color.White.copy(alpha = 0.85f) else Color(0xFFA89890)
    Box(
        Modifier
            .width(124.dp)
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (isNow) Modifier.background(Brush.linearGradient(listOf(Color(0xFFFF8B7B), Color(0xFFFFB5A7))))
                else Modifier.background(Color.White)
            )
            .padding(12.dp)
    ) {
        Column {
            // 顶部：emoji 图标 + 名称/时间
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(32.dp)
                        .background(
                            if (isNow) Color.White.copy(alpha = 0.25f) else Color(0xFFFFE4D1),
                            RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) { Text(open.emoji, fontSize = 14.sp) }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(open.appName, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = contentColor, maxLines = 1)
                    Text(
                        if (isNow) "正在使用" else relativeTime(open.openAt),
                        fontSize = 10.sp, color = subColor, maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // 时长比例条
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(
                        if (isNow) Color.White.copy(alpha = 0.25f) else Color(0x143D2E2A),
                        RoundedCornerShape(2.dp)
                    )
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(frac)
                        .height(4.dp)
                        .background(if (isNow) Color.White else accent, RoundedCornerShape(2.dp))
                )
            }
            Spacer(Modifier.height(6.dp))
            // 底部：时长 + LIVE 标签
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(open.duration, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = subColor)
                Spacer(Modifier.weight(1f))
                if (isNow) {
                    Box(
                        Modifier
                            .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) { Text("LIVE", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                }
            }
        }
    }
}


// ============================================================================
// 24小时柱状图 (HourBarChart)
// ----------------------------------------------------------------------------
// 入参：
//   hourBuckets : 长度 24 的 IntArray,索引 0~23 对应每小时的累计使用秒数
//   color       : 柱子的颜色 (随"我/TA"主色调变化)
// 渲染结构：左边 Y 轴刻度 + 右边 24 根柱子 + 下方 X 轴刻度
// ============================================================================
@Composable
private fun HourBarChart(hourBuckets: IntArray, color: Color) {
    // 找到 24 小时桶中的最大值,用作 Y 轴最大刻度
    val maxBucket = hourBuckets.maxOrNull() ?: 0
    // 全是 0 时不显示图表 (调用方已用 totalSec>0 兜底,这里二次保险)
    if (maxBucket == 0) return
    // Y轴刻度：分钟
    //   把最大值除以 60 得到分钟,向上取整到 10 的倍数 (例如 12 分 → 20 分,便于刻度)
    //   coerceAtLeast(10): 至少 10 分钟,避免数值过小看不出刻度
    val yMaxMin = ((maxBucket / 60 + 9) / 10 * 10).coerceAtLeast(10)
    val yMaxSec = yMaxMin * 60  // 把分钟换算回秒,用作柱子高度比例的分母

    Column {
        // 主图表区：固定高度 120dp
        Row(Modifier.height(120.dp)) {
            // ---------- Y轴标签 (左侧 38dp 宽) ----------
            // Y轴标签
            Column(
                Modifier.width(38.dp).fillMaxHeight(),
                horizontalAlignment = Alignment.End,           // 右对齐,贴着柱状图左侧
                verticalArrangement = Arrangement.SpaceBetween // 顶部/中间/底部均匀分布
            ) {
                Text("${yMaxMin}分", fontSize = 9.sp, color = Color(0xFFA0AEC0))      // 顶刻度
                Text("${yMaxMin / 2}分", fontSize = 9.sp, color = Color(0xFFA0AEC0))  // 中刻度
                Text("0", fontSize = 9.sp, color = Color(0xFFA0AEC0))                 // 底刻度
            }
            Spacer(Modifier.width(4.dp))  // Y 轴与柱状区之间留 4dp 空白
            // ---------- 柱状区 (剩余空间,使用 weight 撑满) ----------
            // 柱状区
            Box(
                Modifier.weight(1f).fillMaxHeight()
            ) {
                // 背景横线 (3 条等距横线,模拟坐标系网格)
                // 背景横线
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    repeat(3) { HorizontalDivider(color = Color(0xFFEDF2F7), thickness = 0.5.dp) }
                }
                // 24根柱子 (每根占相等的宽度)
                // 24根柱子
                Row(
                    Modifier.fillMaxWidth().fillMaxHeight(),
                    verticalAlignment = Alignment.Bottom,                    // 柱子从底部向上长
                    horizontalArrangement = Arrangement.spacedBy(1.5.dp)     // 柱子之间间距 1.5dp
                ) {
                    for (h in 0..23) {
                        val sec = hourBuckets[h]                       // 当前小时的总秒数
                        val f = (sec.toFloat() / yMaxSec).coerceIn(0f, 1f)  // 比例 (0~1),限制到 [0,1] 避免溢出
                        val hDp = (116 * f).dp                         // 柱子实际高度 (最大 116dp,留 4dp 给圆角)
                        Box(
                            Modifier.weight(1f).fillMaxWidth().height(116.dp),
                            contentAlignment = Alignment.BottomCenter  // 内容 (实际柱子) 贴底居中
                        ) {
                            // 实际柱子 (宽度只占父 Box 的 60%,留出空隙)
                            Box(
                                Modifier.fillMaxWidth(0.6f).height(hDp)
                                    .background(color, RoundedCornerShape(2.dp))  // 圆角 2dp
                            )
                        }
                    }
                }
            }
        }
        // ---------- X轴标签 (底部时刻 0/6/12/18) ----------
        // X轴标签
        //   左侧 start=42dp 用于对齐 Y 轴宽度 (38dp) + Spacer (4dp)
        Row(Modifier.fillMaxWidth().padding(start = 42.dp)) {
            listOf("0时" to 0, "6时" to 6, "12时" to 12, "18时" to 18).forEach { (label, h) ->
                Box(
                    // 每个标签占据 6 小时的空间,均匀分布
                    Modifier.weight(if (h == 0) 6f else 6f),
                    contentAlignment = if (h == 0) Alignment.CenterStart else Alignment.Center
                ) {
                    Text(label, fontSize = 10.sp, color = Color(0xFFA0AEC0))
                }
            }
            // 补齐到24
            // 0~18 已占 18 小时,补一个 6 小时空白让总权重为 24,确保前面对齐
            Box(Modifier.weight(6f)) {}
        }
    }
}

// ============================================================================
// 单个 APP 排行行 (AppRankRow)
// ----------------------------------------------------------------------------
// 入参：
//   rank        : 排名 (1, 2, 3, ...)
//   emoji       : 类别对应的 emoji
//   name        : APP 名
//   category    : 分类名
//   duration    : 时长文本 (如 "1小时20分")
//   percent     : 占总时长百分比
//   barFraction : 柱状比例 (0.0~1.0),决定柱子长度
//   barColor    : 柱子颜色
// ============================================================================
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
        shape = RoundedCornerShape(14.dp),                        // 卡片圆角 14dp
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(14.dp)) {
            // 上半部分：排名 + emoji + 名称 + 分类 + 时长 + 百分比
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 排名：前 3 名显示 🏅,其他显示数字
                Text(
                    if (rank <= 3) "🏅" else rank.toString(),
                    fontSize = 16.sp,
                    modifier = Modifier.width(28.dp)
                )
                Text(emoji, fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                // 左侧文字列：APP 名 (主) + 分类 (副)
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF3D2E2A),
                        maxLines = 1
                    )
                    Text(category, fontSize = 11.sp, color = Color(0xFFA89890))
                }
                // 右侧数值列：时长 (主) + 百分比 (副)
                Column(horizontalAlignment = Alignment.End) {
                    Text(duration, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3D2E2A))
                    Text("" + percent + "%", fontSize = 11.sp, color = Color(0xFFA0AEC0))
                }
            }
            Spacer(Modifier.height(8.dp))
            // 下半部分：进度条 (外层背景 + 内层填色)
            // 外层：浅色背景 (主色 + 12% 透明度),表示满进度槽
            Box(
                Modifier.fillMaxWidth().height(6.dp)
                    .background(barColor.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
            ) {
                // 内层：实际进度 (主色),宽度按 barFraction 计算
                //   coerceIn(0.02f, 1f): 最小 2%,避免完全看不见
                Box(
                    Modifier.fillMaxWidth(barFraction.coerceIn(0.02f, 1f)).height(6.dp)
                        .background(barColor, RoundedCornerShape(3.dp))
                )
            }
        }
    }
}

// ============================================================================
// AppStat: 单个 APP 的聚合统计模型 (仅在文件内部使用,故 private)
// ============================================================================
private data class AppStat(
    val packageName: String,   // APP 包名 (唯一标识)
    val appName: String,       // APP 显示名 (可能为包名兜底)
    val category: String,      // APP 分类 (社交/视频/...)
    val totalSeconds: Int      // 该 APP 当日累计使用秒数
)

// ============================================================================
// 工具函数区
// ============================================================================

// dateLabel: 把 dayOffset 转成中文日期标签
//   0→今日, 1→昨天, 2→前天, 其他→"N天前"
private fun dateLabel(dayOffset: Int): String = when (dayOffset) {
    0 -> "今日"
    1 -> "昨天"
    2 -> "前天"
    else -> dayOffset.toString() + "天前"
}

// formatDuration: 把秒数转成易读的中文时长文本
//   例如 3900 → "1小时5分";3600 → "1小时";300 → "5分钟";30 → "<1分钟"
private fun formatDuration(seconds: Int): String {
    val h = seconds / 3600            // 小时部分 (整除 3600)
    val m = (seconds % 3600) / 60      // 分钟部分 (去掉小时后的余数,再除以 60)
    return when {
        h > 0 && m > 0 -> "" + h + "小时" + m + "分"  // 既有小时又有分钟
        h > 0 -> "" + h + "小时"                       // 只有小时
        m > 0 -> "" + m + "分钟"                       // 只有分钟
        else -> "<1分钟"                                // 不足 1 分钟
    }
}

// categoryEmoji: 把分类名映射成 emoji 图标,方便快速识别
//   未知分类 → 📦 (包裹图标,默认值)
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
// isStatsNoise: 判断某个包名是否是"系统噪音",应从统计中过滤掉
//   目的：排除桌面、输入法、系统 UI 等"非主动使用"的进程,让统计更聚焦用户真实行为
//   返回 true 表示是噪音,需要过滤
private fun isStatsNoise(pkg: String?): Boolean {
    // 空字符串 → 噪音
    if (pkg.isNullOrBlank()) return true
    val p = pkg.lowercase()  // 统一小写,便于匹配
    // 桌面 / 启动器 / 系统 UI
    if (p.contains("launcher") || p.contains("systemui") || p.contains("desk") || p.contains("homescreen")) return true
    // 输入法 (中文输入法关键字)
    if (p.contains("inputmethod") || p.contains("ime") || p.contains("input.")
        || p.contains("sougou") || p.contains("sogou") || p.contains("baidu.input")
        || p.contains("iflytek") || p.contains("讯飞")) return true
    // 系统 UI / 锁屏 / 状态栏 / 导航栏
    if (p.contains("uiautomator") || p.contains("statusbar") || p.contains("navigationbar")
        || p.contains("keyguard") || p.contains("lockscreen") || p.contains("powerui")) return true
    // 系统安装器 / 权限控制器
    if (p.contains("packageinstaller") || p.contains("permissioncontroller")) return true
    // 太短或不含点的包名一般是系统组件,也排除
    if (p.length < 5 || !p.contains('.')) return true
    return false
}
