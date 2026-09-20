// ============================================================================
// 包声明：本文件属于 data 子包，专门放数据层相关的类
// ============================================================================
package com.coupletracker.android.data

// ----------------------------------------------------------------------------
// 导入区域
// ----------------------------------------------------------------------------

// 协程：用于异步上报，避免阻塞 UI 线程
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers          // Dispatchers.IO = 后台 IO 线程
import kotlinx.coroutines.SupervisorJob        // 监督任务：一个子协程失败不会影响其他
import kotlinx.coroutines.flow.MutableStateFlow // 可变状态流（UI 订阅的"数据源"）
import kotlinx.coroutines.flow.asStateFlow      // 转为只读 StateFlow 暴露给外部
import kotlinx.coroutines.launch                // 启动新协程
// 线程安全的 HashMap，用于多协程环境下安全地读写"上次上报时间"
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 * UI 层持久状态单例 + 上报双保险。
 * ============================================================================
 *
 * 【它要解决什么问题？】
 *
 * 1. BottomNavigation 切 Tab 时，Compose 的 remember / rememberSaveable 会丢失状态
 *    → 用 Kotlin object（单例）保存状态，整个 App 生命周期都在。
 *
 * 2. 后台 TrackerService 可能被系统杀掉（尤其是国产 ROM 后台清理）
 *    → 这里直接在 UI 层向 Supabase 上报，作为"双保险"，
 *       和后台服务互不依赖，谁活着谁就报，确保数据不丢。
 *
 * 【为什么用 object？】
 *   Kotlin 的 object = 全局唯一的单例，无需手动 new，类名直接调用方法。
 * ============================================================================
 */
object AppSessionTracker {

    // ===========================================================================
    // 一、心情状态：用户当前的心情 emoji，UI 可订阅显示
    // ===========================================================================

    // 私有可变 StateFlow，默认值 "😐"（中性表情）
    // 下划线开头是 Kotlin 惯例：表示"可变的内部实现版本"
    private val _mood = MutableStateFlow("😐")

    // 对外暴露只读版本，UI 通过 collect 订阅变化
    val mood = _mood.asStateFlow()

    // 修改心情：UI 调用 setMood("😊") 即可，会自动通知订阅者
    fun setMood(emoji: String) { _mood.value = emoji }


    // ===========================================================================
    // 二、当前 APP 会话状态：记录"当前正在用哪个 APP，从何时开始"
    // ===========================================================================

    // 当前 APP 的会话起始时间（毫秒时间戳），0L 表示尚未开始任何会话
    private var sessionStartAt = 0L

    // 上次上报时间（用于增量计算）
    private var sessionReportAt = 0L   // 上次上报时间

    // 上次上报后累计秒数（用于计算增量）
    private var sessionReportSec = 0   // 上次上报后累计秒数（用于计算增量）

    // 当前 APP 的包名（如 com.tencent.mm）和用户可读名称（如 "微信"）
    // 用 StateFlow 包装，是为了让 UI 自动响应 APP 切换
    private val _currentPkg = MutableStateFlow("")
    private val _currentName = MutableStateFlow("")

    // 每个 APP 包名 → 上次上报到服务器的时间
    // 用 ConcurrentHashMap 是因为多协程并发读写，普通 HashMap 会出问题
    private val lastUploadedAt = ConcurrentHashMap<String, Long>()  // 每个 pkg 单独记录

    // 本类专用的协程作用域：
    // - SupervisorJob：子协程挂了不会拖垮整个作用域
    // - Dispatchers.IO：在 IO 线程跑，避免阻塞主线程
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 上报状态（UI 调试用）
     * 比如 "✅ 上报成功 · 微信 30s" 或 "⚠️ 上报失败 HTTP 401"
     * UI 可以显示这个字符串让用户看到上报情况
     */
    private val _lastReportStatus = MutableStateFlow("等待上报...")
    val lastReportStatus = _lastReportStatus.asStateFlow()

    // ===========================================================================
    // 三、核心方法：设置当前正在使用的 APP
    // ===========================================================================

    /**
     * 设置当前 APP —— 只负责 UI 显示和 APP 切换时补报，
     * 不做定时上报（避免和后台双报）
     *
     * 【设计意图】
     * - 定时上报交给后台 AppUsageMonitor（每 15 秒一次），这里不重复做
     * - 但 APP 切换的瞬间，本类要补报前一个 APP 的使用时长，避免漏报
     *
     * @param pkg  APP 包名，例如 "com.tencent.mm"
     * @param name APP 用户可读名称，例如 "微信"
     */
    fun setCurrentApp(pkg: String, name: String) {
        // 包名为空 → 数据无效，直接返回
        if (pkg.isEmpty()) return

        if (pkg != _currentPkg.value) {
            // ===================================================================
            // 情况 A：检测到 APP 切换 —— 补报前一个 APP 的使用时长
            // ===================================================================
            // APP 切换 → 补报前一个 APP（如果 ≥ 10 秒）
            if (_currentPkg.value.isNotEmpty() && sessionStartAt > 0) {
                // 计算前一个 APP 的使用时长（秒）
                val prevSec = ((System.currentTimeMillis() - sessionStartAt) / 1000).toInt()
                // 只上报 ≥ 10 秒的，避免短时间切换产生噪音数据
                if (prevSec >= 10) {
                    // 名称兜底：如果没有可读名称，就用包名作为名字
                    // window_start 传旧会话的真实打开时刻（此刻 sessionStartAt 尚未被重置）
                    uploadUsage(_currentPkg.value, _currentName.value.ifBlank { _currentPkg.value }, prevSec, sessionStartAt)
                }
            }
            // 切换到新 APP：更新包名、名称、重置会话起始时间
            _currentPkg.value = pkg
            _currentName.value = name
            sessionStartAt = System.currentTimeMillis()
            sessionReportAt = sessionStartAt
        } else {
            // ===================================================================
            // 情况 B：还是同一个 APP —— 只更新可读名称（包名没变）
            // ===================================================================
            _currentName.value = name
        }
        // ❌ 不做定时上报 —— 后台 AppUsageMonitor 每 15 秒已经在上报了
        // 之前这里也每 15 秒上报 → 和后台双报 → 时长翻倍！
    }

    /**
     * 返回当前 APP 已使用的秒数
     * 如果还没启动会话，返回 0
     * coerceAtLeast(0) 保证不会返回负数（系统时间回拨时可能出现）
     */
    fun sessionSeconds(): Int {
        if (sessionStartAt == 0L) return 0
        return ((System.currentTimeMillis() - sessionStartAt) / 1000).toInt().coerceAtLeast(0)
    }

    // ===========================================================================
    // 四、上报逻辑：UI 层直接 POST 到 Supabase（和后台 TrackerService 互相独立的双保险）
    // ===========================================================================

    /**
     * 判断给定的包名是否属于"系统噪音"APP —— 这些不该上报给服务器
     *
     * 【噪音 APP 举例】
     * - 桌面 Launcher（如桌面、启动器）：用户不算在"使用 App"
     * - 输入法（搜狗、百度、讯飞）：弹键盘不算"用 App"
     * - 系统 UI（状态栏、导航栏、锁屏）：纯系统界面
     * - 安装器、权限控制器：用户点几下就消失
     *
     * @return true = 是噪音，应跳过；false = 是真实 APP，应上报
     */
    private fun isSystemNoisePkg(pkg: String?): Boolean {
        // 空白包名直接判噪音
    if (pkg.isNullOrBlank()) return true

    // 统一转小写，方便匹配（包名本身区分大小写，但关键词匹配不区分）
    val p = pkg.lowercase()

    // 桌面 / 启动器 / 系统UI / 主屏幕
    if (p.contains("launcher") || p.contains("systemui") || p.contains("desk") || p.contains("homescreen")) return true

    // 各类输入法（IME）—— 搜狗、百度、讯飞等
    if (p.contains("inputmethod") || p.contains("ime") || p.contains("input.")
        || p.contains("sougou") || p.contains("sogou") || p.contains("baidu.input")
        || p.contains("iflytek") || p.contains("讯飞")) return true

    // 系统 UI 组件：状态栏、导航栏、锁屏、电源 UI、通知弹窗等
    if (p.contains("uiautomator") || p.contains("statusbar") || p.contains("navigationbar")
        || p.contains("keyguard") || p.contains("lockscreen") || p.contains("powerui")
        || p.contains("notifications") || p.contains("system.dialog")) return true

    // 安装器、权限控制器：用户偶尔触发，不算真实使用
    if (p.contains("packageinstaller") || p.contains("permissioncontroller")) return true

    // 包名太短或不含点号 → 大概率不是有效 APP 包名（合法包名至少两层，如 com.xxx）
    if (p.length < 5 || !p.contains('.')) return true

    // 以上都不是 → 真实 APP
    return false
}

    /**
     * 把 APP 使用记录上报到服务器（Supabase）
     *
     * 【执行流程】
     * 1. 先判断是不是系统噪音 APP，是 → 跳过
     * 2. 启动协程，在 IO 线程异步执行：
     *    a) 取当前用户；未登录 → 跳过
     *    b) 防重复：5 秒内同一个包名不重复上报
     *    c) 调用 NetworkModule 发 HTTP POST
     *    d) 根据 HTTP 响应更新 lastReportStatus
     * 3. 任何异常都被 runCatching 捕获，更新失败状态，避免崩溃
     *
     * @param pkg     APP 包名
     * @param name    APP 可读名称
     * @param seconds 本次使用时长（秒）
     * @param windowStartMs 会话真实打开时刻（毫秒），写入 window_start 字段
     */
    private fun uploadUsage(pkg: String, name: String, seconds: Int, windowStartMs: Long? = null) {
        // 第 1 步：系统噪音直接跳过，并通知 UI
        if (isSystemNoisePkg(pkg)) { _lastReportStatus.value = "跳过系统噪音 "; return }

        // 第 2 步：在 IO 线程启动协程，整个上报过程异步执行，不阻塞调用方
        scope.launch {
            // runCatching：把内部代码包起来，任何异常都走 onFailure，不会让 App 崩溃
            runCatching {
                // (a) 取当前登录用户
                val user = UserRepository.get().getUser()
                val userId = user?.id ?: run {
                    // 未登录 → 设置状态并退出本次上报
                    _lastReportStatus.value = "⚠️ 未登录，跳过上报"
                    return@runCatching
                }

                // (b) 防止同一个 pkg 短时间内重复上报（> 5 秒才允许再次上报）
                val key = pkg
                val lastUp = lastUploadedAt[key] ?: 0L
                if (System.currentTimeMillis() - lastUp < 5_000L) return@runCatching
                // 记录本次上报时间，作为下次去重的基准
                lastUploadedAt[key] = System.currentTimeMillis()

                // (c) 构造上报数据并调用网络层
                val resp = NetworkModule.restService.reportAppUsage(
                    AppUsageInsert(
                        user_id = userId,         // 当前用户 ID
                        couple_id = null,         // 情侣 ID 暂未填充（后端可由 user 反查）
                        package_name = pkg,       // APP 包名
                        app_name = name,           // APP 可读名称
                        category = categorizePkg(pkg),  // 自动分类（社交/视频/...）
                        usage_seconds = seconds,   // 本次使用时长
                        // window_start = 会话真实打开时刻（ISO 8601 UTC）
                        window_start = windowStartMs?.let {
                            java.time.Instant.ofEpochMilli(it).toString()
                        }
                    )
                )

                // (d) 根据响应结果更新 UI 状态
                _lastReportStatus.value = when {
                    resp.isSuccessful -> "✅ 上报成功 · $name ${seconds}s"
                    else -> "⚠️ 上报失败 HTTP ${resp.code()}"
                }
            }.onFailure { e ->
                // 网络异常 / 解析异常等，统一在此捕获
                // take(40)：截断错误信息长度，避免 UI 上太长
                _lastReportStatus.value = "⚠️ 上报异常：${e.message?.take(40) ?: ""}"
            }
        }
    }

    // ===========================================================================
    // 五、APP 分类：根据包名粗略判断 APP 所属类别
    // ===========================================================================

    /**
     * 根据包名关键词，给 APP 打一个类别标签（社交/视频/音乐/购物/其他）
     *
     * 【实现思路】
     * 通过 contains 匹配包名特征，给业务侧一个粗粒度的分类，
     * 用于后续统计或过滤展示。匹配不到 → "其他"。
     *
     * @param pkg APP 包名
     * @return 类别字符串，例如 "社交"、"视频"
     */
    private fun categorizePkg(pkg: String): String = when {
        // 微信 (com.tencent.mm) / WeChat / 手机 QQ
        pkg.contains("tencent.mm") || pkg.contains("wechat") || pkg.contains("mobileqq") -> "社交"
        // 抖音 / 抖音极速版 (aweme) / B站
        pkg.contains("douyin") || pkg.contains("aweme") || pkg.contains("bilibili") -> "视频"
        // 网易云音乐 / QQ音乐 / 酷狗
        pkg.contains("netease.cloud") || pkg.contains("qqmusic") || pkg.contains("kugou") -> "音乐"
        // 淘宝 / 京东 / 拼多多
        pkg.contains("taobao") || pkg.contains("jd") || pkg.contains("pinduoduo") -> "购物"
        // 本应用自身
        pkg.contains("coupletracker") -> "其他"
        // 其他所有 APP
        else -> "其他"
    }
}

