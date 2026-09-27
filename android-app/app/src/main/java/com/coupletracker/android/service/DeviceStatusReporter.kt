// =============================================================================
// 【文件说明】
// 设备状态上报器（DeviceStatusReporter）—— 事件驱动 + 智能降频版。
//
// 【它解决什么问题？】
// 旧版"手机状态"靠 locations 表最后一条位置记录的时间戳推断对方是否在线：
//   - 后台服务被国产 ROM 杀掉 / 手机静止不上报位置 → 超过 5 分钟误显示"关机"
//   - 云端没有网络/WiFi 字段 → 对方网络永远显示"云端未记录"
//
// 【上报策略】（对标 Life360 / Zenly 的省电做法：状态与位置解耦 + 按活跃度降频）
//   1. 活跃期（亮屏 或 移动中）：每 5 分钟定时上报一次
//   2. 疑似睡眠（熄屏 + 30 分钟无移动、无 App 切换）：每 20 分钟上报一次
//   3. 深度睡眠（熄屏 + 60 分钟无任何变化）：每 30 分钟上报一次
//   4. 事件触发立即上报：App 切换 / 移动 ≥25 米 / 手动刷新 / 亮熄屏 / 网络切换
//      （事件带 10 秒防抖，避免广播风暴打满网络）
//   5. 每用户一行（user_id 主键 upsert），云端永远只保留"最新状态"
//
// 【失败处理】
//   - 上报失败：保留最后一条采集数据在本地（供 WebView 读取），不重试、不排队，
//     等下一个调度周期再报新数据，避免离线期间请求堆积。
//   - lastReportAt 只在上报成功时更新（语义 = 云端最后一次收到数据的时刻）。
//
// 【本地数据暴露】
//   companion 提供 getLastStatusJson() / getCurrentMode() / reportStatusNow()，
//   WebView 的 JS 桥（CoupleTrackerNative）可直接静态调用，无需拿到实例。
//
// 【安全兜底】所有系统调用/网络请求均 runCatching 包裹，绝不让服务崩溃。
// =============================================================================

package com.coupletracker.android.service

import android.app.usage.UsageEvents                // 使用事件（检测前台 App 切换）
import android.app.usage.UsageStatsManager          // 使用统计管理器（增量轮询前台事件）
import android.content.BroadcastReceiver            // 广播接收器（监听电量变化、亮熄屏）
import android.content.Context                      // Android 上下文
import android.content.Intent                       // 意图对象
import android.content.IntentFilter                 // 广播过滤器
import android.net.ConnectivityManager              // 网络连接管理器
import android.net.NetworkCapabilities              // 网络能力描述（WiFi/蜂窝等）
import android.net.NetworkRequest                   // 网络监听请求构建器
import android.net.wifi.WifiManager                 // WiFi 管理（读取 SSID）
import android.os.BatteryManager                    // 电池信息
import android.os.Build                             // 系统版本（ACTIVITY_RESUMED 需要 API 29+）
import android.os.PowerManager                      // 电源管理（判断亮屏/熄屏）
import com.coupletracker.android.data.DeviceStatusUpsert  // 上报请求体
import com.coupletracker.android.data.NetworkModule       // 网络模块
import com.coupletracker.android.data.UserRepository      // 用户仓库
import com.coupletracker.android.location.LocationTracker // 位置追踪器（读取移动状态）
import com.coupletracker.android.ui.MainActivity          // 读取 isAppForeground 标记
import android.util.Log                              // 系统日志（上报失败时输出到 logcat）
import kotlinx.coroutines.*                         // 协程
import org.json.JSONObject                          // 组装 JSON（WebView 本地读取用）
import java.time.Instant                            // UTC 时间戳

/**
 * 设备状态上报器（事件驱动 + 智能降频）
 *
 * @param context Android 上下文（TrackerService 传 this）
 * @param scope   协程作用域（跟随 TrackerService 生命周期，服务销毁时自动取消）
 * @param locationTracker 位置追踪器（用于读取"是否在移动"状态，可空）
 */
class DeviceStatusReporter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val locationTracker: LocationTracker? = null
) {

    // 日志 TAG（logcat 过滤用）
    private val TAG = "DeviceStatusReporter"

    // 调度协程句柄；为空表示未启动
    private var heartbeatJob: Job? = null

    // 上次【成功】上报时间（毫秒）——仅成功才更新；语义 = 云端最后一次收到数据的时刻
    @Volatile private var lastReportAt: Long = 0L

    // 上次【尝试】上报时间（毫秒，含失败）——调度与防抖都以它为准：
    //   失败也更新 → 失败后不会在下一个 tick 立刻重试，避免离线时请求堆积
    @Volatile private var lastAttemptAt: Long = 0L

    // 上报中标记：防止事件触发 / 定时触发 / 手动刷新并发触发同一时刻多次上报
    @Volatile private var reporting = false

    // 最近一次"用户活动"时间戳（App 切换 / 移动 / 亮熄屏 / 手动刷新）——睡眠判定的计时起点
    @Volatile private var lastActivityAt: Long = 0L

    // 已见的最近一次移动时间戳（与 LocationTracker.lastMovingAtStatic 对比，识别"新移动事件"）
    @Volatile private var lastSeenMovingAt: Long = 0L

    // UsageEvents 查询游标 + 已知前台包名（App 切换检测用）
    private var lastUsageCheckAt: Long = 0L
    private var lastFgPackage: String = ""

    // 广播/回调是否已注册（防止重复注册）
    @Volatile private var listenersRegistered = false

    /** 上报模式：活跃期 / 疑似睡眠 / 深度睡眠 */
    enum class ReportMode { ACTIVE, SUSPECT_SLEEP, DEEP_SLEEP }

    companion object {
        // ---- 智能降频参数 ----
        private const val ACTIVE_INTERVAL_MS = 300_000L          // 活跃期：每 5 分钟
        private const val SUSPECT_SLEEP_INTERVAL_MS = 1_200_000L // 疑似睡眠：每 20 分钟
        private const val DEEP_SLEEP_INTERVAL_MS = 1_800_000L    // 深度睡眠：每 30 分钟
        private const val SUSPECT_SLEEP_IDLE_MS = 1_800_000L     // 熄屏 + 30 分钟无活动 → 疑似睡眠
        private const val DEEP_SLEEP_IDLE_MS = 3_600_000L        // 熄屏 + 60 分钟无变化 → 深度睡眠
        private const val EVENT_DEBOUNCE_MS = 10_000L            // 事件触发防抖：10 秒内最多一次
        private const val TICK_ACTIVE_MS = 10_000L               // 亮屏时调度 tick（快速感知 App 切换）
        private const val TICK_IDLE_MS = 30_000L                 // 熄屏时调度 tick（省电，事件感知最迟 30 秒）

        // ---- WebView 本地数据暴露（静态，无需实例）----
        @Volatile private var instance: DeviceStatusReporter? = null
        @Volatile private var lastSnapshot: DeviceStatusUpsert? = null   // 最后一条采集数据（成败都保留）
        @Volatile private var lastReportAtStatic: Long = 0L              // 最后一次成功上报时刻

        /**
         * 供 WebView 读取：最近一次采集的设备状态 + 上报元信息（JSON 字符串）。
         * 字段：battery_level / is_charging / network_type / wifi_ssid / screen_on /
         *      is_moving / foreground_package / updated_at（以上来自最后一次采集，可能未成功上云），
         *      has_snapshot / last_report_at / last_report_age_ms / mode / next_interval_ms（元信息）。
         * 从未采集过时返回只含元信息的 JSON。
         */
        fun getLastStatusJson(): String = runCatching {
            val json = JSONObject()
            val s = lastSnapshot
            json.put("has_snapshot", s != null)
            if (s != null) {
                json.put("battery_level", s.battery_level ?: JSONObject.NULL)
                json.put("is_charging", s.is_charging)
                json.put("network_type", s.network_type ?: JSONObject.NULL)
                json.put("wifi_ssid", s.wifi_ssid ?: JSONObject.NULL)
                json.put("screen_on", s.screen_on)
                json.put("is_moving", s.is_moving)
                json.put("foreground_package", s.foreground_package ?: JSONObject.NULL)
                json.put("updated_at", s.updated_at)
            }
            val now = System.currentTimeMillis()
            json.put("last_report_at", lastReportAtStatic)
            json.put("last_report_age_ms", if (lastReportAtStatic > 0L) now - lastReportAtStatic else -1L)
            val inst = instance
            if (inst != null) {
                val mode = inst.currentMode(now)
                json.put("mode", inst.modeName(mode))
                json.put("next_interval_ms", inst.intervalOf(mode))
            } else {
                json.put("mode", "unknown")
                json.put("next_interval_ms", -1L)
            }
            json.toString()
        }.getOrDefault("{}")

        /** 供 WebView 读取：当前上报模式（active / suspected_sleep / deep_sleep / unknown） */
        fun getCurrentMode(): String {
            val inst = instance ?: return "unknown"
            return runCatching { inst.modeName(inst.currentMode(System.currentTimeMillis())) }
                .getOrDefault("unknown")
        }

        /** 供 WebView 调用：手动刷新 → 立即上报一次（前端"刷新"按钮事件触发） */
        fun reportStatusNow() {
            runCatching { instance?.refreshNow() }
        }
    }

    // ---- 系统广播接收器：电量变化 + 亮熄屏（注册一次，回调里触发"事件即报"）----
    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                // 亮/熄屏 = 用户活动：重置睡眠时钟 + 立即上报（内部有防抖）
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> {
                    lastActivityAt = System.currentTimeMillis()
                    reportThrottled("screen")
                }
                // 电量/充电状态变化：只触发上报，不算用户活动（不影响睡眠判定）
                else -> reportThrottled("battery")
            }
        }
    }

    // ---- 网络回调：WiFi/蜂窝切换时触发"事件即报"（不算用户活动）----
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: android.net.Network, nc: NetworkCapabilities) {
            reportThrottled("network")
        }
        override fun onLost(network: android.net.Network) {
            reportThrottled("network")
        }
    }

    /**
     * 启动调度：立即上报一次 + 注册状态变化监听 + 进入智能调度循环
     * 重复调用安全（幂等）：已在运行则直接返回
     */
    fun start() {
        if (heartbeatJob?.isActive == true) return
        registerListeners()
        instance = this
        val now = System.currentTimeMillis()
        // 初始化活动时钟/游标：从启动时刻开始计"无活动时长"，避免历史数据误判
        lastActivityAt = now
        lastUsageCheckAt = now
        lastSeenMovingAt = LocationTracker.lastMovingAtStatic
        heartbeatJob = scope.launch(Dispatchers.IO) {
            reportNow("start")   // 启动立即报一次，保证云端立刻有最新状态
            while (isActive) {
                delay(currentTickMs())
                runCatching { tick() }
            }
        }
    }

    /** 停止调度并注销监听（服务销毁时调用） */
    fun stop() {
        runCatching { heartbeatJob?.cancel() }
        heartbeatJob = null
        unregisterListeners()
        if (instance === this) instance = null
    }

    // ===========================================================================
    // 外部事件入口（事件驱动）
    // ===========================================================================

    /** 手动刷新（WebView/前端"刷新"按钮）：立即上报，不受防抖限制；同时重置睡眠时钟 */
    fun refreshNow() {
        lastActivityAt = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) { reportNow("manual") }
    }

    /** 外部事件钩子：App 切换（如 AppUsageMonitor 检测到前台 App 变化时可调用） */
    fun notifyAppSwitch() {
        lastActivityAt = System.currentTimeMillis()
        reportThrottled("app_switch_ext")
    }

    /** 外部事件钩子：检测到移动（位移 ≥25 米） */
    fun notifyMovement() {
        lastActivityAt = System.currentTimeMillis()
        reportThrottled("movement_ext")
    }

    // ===========================================================================
    // 内部实现：智能调度
    // ===========================================================================

    /**
     * 调度 tick：每 currentTickMs() 执行一次
     * 优先级：事件检测（App 切换 / 新移动）→ 立即上报；否则按当前模式的间隔定时上报
     */
    private suspend fun tick() {
        val now = System.currentTimeMillis()

        // ① 事件检测：App 切换（UsageStats 增量轮询；无"使用情况访问"权限时静默跳过）
        if (detectAppSwitch(now)) {
            lastActivityAt = now
            reportThrottled("app_switch")
            return
        }

        // ② 事件检测：发生新移动
        //    LocationTracker 位移 ≥25 米的回调会刷新 lastMovingAtStatic，这里对比识别新事件
        val mv = LocationTracker.lastMovingAtStatic
        if (mv > lastSeenMovingAt) {
            lastSeenMovingAt = mv
            lastActivityAt = now
            reportThrottled("movement")
            return
        }

        // ③ 定时调度：距上次尝试达到当前模式的间隔 → 上报
        val mode = currentMode(now)
        if (now - lastAttemptAt >= intervalOf(mode)) {
            reportNow("scheduled:${modeName(mode)}")
        }
    }

    /** 当前调度 tick 间隔：亮屏 10 秒（快速感知 App 切换）；熄屏 30 秒（省电） */
    private fun currentTickMs(): Long = if (isScreenOn()) TICK_ACTIVE_MS else TICK_IDLE_MS

    /**
     * 判定当前上报模式：
     *   - 亮屏 或 最近 30 秒内有移动 → 活跃期
     *   - 熄屏且距最近活动 ≥60 分钟 → 深度睡眠
     *   - 熄屏且距最近活动 ≥30 分钟 → 疑似睡眠
     *   - 熄屏但刚熄不久（<30 分钟）→ 仍按活跃期频率，直到进入疑似睡眠
     */
    private fun currentMode(now: Long): ReportMode {
        if (isScreenOn()) return ReportMode.ACTIVE
        if (locationTracker?.isMovingRecently() == true) return ReportMode.ACTIVE
        val idleMs = now - lastActivityAt
        return when {
            idleMs >= DEEP_SLEEP_IDLE_MS -> ReportMode.DEEP_SLEEP
            idleMs >= SUSPECT_SLEEP_IDLE_MS -> ReportMode.SUSPECT_SLEEP
            else -> ReportMode.ACTIVE
        }
    }

    /** 各模式对应的上报间隔 */
    private fun intervalOf(mode: ReportMode): Long = when (mode) {
        ReportMode.ACTIVE -> ACTIVE_INTERVAL_MS
        ReportMode.SUSPECT_SLEEP -> SUSPECT_SLEEP_INTERVAL_MS
        ReportMode.DEEP_SLEEP -> DEEP_SLEEP_INTERVAL_MS
    }

    /** 模式名（WebView/日志展示用） */
    private fun modeName(mode: ReportMode): String = when (mode) {
        ReportMode.ACTIVE -> "active"
        ReportMode.SUSPECT_SLEEP -> "suspected_sleep"
        ReportMode.DEEP_SLEEP -> "deep_sleep"
    }

    /** 屏幕是否亮着（独立 runCatching，失败按熄屏处理） */
    private fun isScreenOn(): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isInteractive
    }.getOrDefault(false)

    /**
     * App 切换检测：增量轮询 UsageStatsManager 的前台事件
     * 返回 true = 自上次检查以来前台 App 发生了变化（事件触发立即上报）
     * 无"使用情况访问"权限 / ROM 限制时 queryEvents 返回空 → 静默返回 false
     */
    @Suppress("DEPRECATION")
    private fun detectAppSwitch(now: Long): Boolean {
        val from = lastUsageCheckAt
        lastUsageCheckAt = now
        if (from <= 0L) return false   // 首次调用只建立游标
        return runCatching {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = usm.queryEvents(from, now)
            val ev = UsageEvents.Event()
            var latestPkg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                // MOVE_TO_FOREGROUND：API <29 的前台事件；ACTIVITY_RESUMED：API 29+ 的前台事件
                val isForeground = ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
                if (isForeground) latestPkg = ev.packageName
            }
            val pkg = latestPkg
            if (pkg != null && pkg != lastFgPackage) {
                lastFgPackage = pkg
                true
            } else false
        }.getOrDefault(false)
    }

    // ===========================================================================
    // 内部实现：监听注册 / 防抖 / 上报
    // ===========================================================================

    /** 注册系统广播 + 网络回调（只注册一次） */
    private fun registerListeners() {
        if (listenersRegistered) return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)   // 电量/充电状态变化
                addAction(Intent.ACTION_SCREEN_ON)          // 亮屏
                addAction(Intent.ACTION_SCREEN_OFF)         // 熄屏
            }
            // 系统保护广播豁免 RECEIVER_NOT_EXPORTED 标志（Android 13+ 兼容）
            context.registerReceiver(systemReceiver, filter)
        }
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            cm.registerNetworkCallback(req, netCallback)
        }
        listenersRegistered = true
    }

    /** 注销监听（runCatching 包裹，重复注销/未注册都不会崩） */
    private fun unregisterListeners() {
        if (!listenersRegistered) return
        runCatching { context.unregisterReceiver(systemReceiver) }
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(netCallback)
        }
        listenersRegistered = false
    }

    /**
     * 事件即报（防抖：距上次【尝试】< 10 秒则跳过，避免广播风暴打满网络；
     * 防抖看 lastAttemptAt 而不是 lastReportAt —— 失败后也不允许立刻再来一次）
     */
    private fun reportThrottled(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastAttemptAt < EVENT_DEBOUNCE_MS) return
        scope.launch(Dispatchers.IO) { reportNow("event:$reason") }
    }

    /**
     * 采集当前设备状态并 upsert 到云端（核心方法）
     *
     * 失败处理约定：
     *   - lastAttemptAt 在尝试发起时即更新 → 失败后等下一个调度周期，不立即重试、不排队堆积
     *   - 采集结果无论成败都写入 lastSnapshot（保留最后一条数据，供 WebView 本地读取）
     *   - lastReportAt / lastReportAtStatic 只在上报成功时更新
     *
     * @param reason 触发原因（start / scheduled:模式 / event:xxx / manual），仅用于日志
     */
    private suspend fun reportNow(reason: String) {
        if (reporting) return   // 上一次还没报完，跳过（防并发）
        reporting = true
        lastAttemptAt = System.currentTimeMillis()
        try {
            // 未登录就不上报（ upsert 需要 user_id ）——单独提取，不算错误，不进日志
            val userId = runCatching { UserRepository.get().getUser()?.id }.getOrNull() ?: return

            // ① 采集设备状态（每步独立 runCatching，单点失败不影响其他字段）
            val body = collectStatus(userId)

            // ② 保留最后一条数据到本地（无论上报成败，WebView 都能读到最新采集值）
            lastSnapshot = body

            // ③ upsert 到云端（updated_at 用客户端当前 UTC 时间，语义=采集时刻）
            //    单独 try/catch：失败只打日志，不重试、不排队，等下一个调度周期报新数据
            try {
                val resp = NetworkModule.restService.upsertDeviceStatus(body)
                if (resp.isSuccessful) {
                    // 只有成功才更新 lastReportAt（语义 = 云端最后一次收到数据的时刻）
                    lastReportAt = System.currentTimeMillis()
                    lastReportAtStatic = lastReportAt
                } else {
                    // HTTP 错误（如 401 未登录、500 服务器错、404 表不存在）→ 输出日志，方便排查
                    Log.e(
                        TAG,
                        "upsert 失败(reason=$reason): HTTP ${resp.code()} ${resp.message() ?: ""}"
                    )
                }
            } catch (e: Exception) {
                // 网络异常（如 UnknownHostException、SocketTimeoutException）→ 输出日志，方便排查
                Log.e(
                    TAG,
                    "upsert 异常(reason=$reason): ${e.javaClass.simpleName}: ${e.message ?: ""}"
                )
            }
        } finally {
            reporting = false
        }
    }

    /**
     * 采集当前设备状态，组装成上报请求体
     * 每个采集项独立 runCatching（单点失败不影响其他字段）
     */
    private fun collectStatus(userId: String): DeviceStatusUpsert {
        // ① 电量 + 充电状态（粘性广播读取，无需长期监听）
        val battery = runCatching {
            val bi = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
            val status = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            Pair(pct, charging)
        }.getOrNull() ?: Pair(null, false)

        // ② 网络类型 + WiFi SSID
        val net = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nc = cm.getNetworkCapabilities(cm.activeNetwork)
            val type = when {
                nc == null -> "none"
                nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
            val ssid = if (type == "wifi") readWifiSsid() else null
            Pair(type, ssid)
        }.getOrNull() ?: Pair("none", null)

        // ③ 屏幕亮/灭
        val screenOn = isScreenOn()

        // ④ 是否在移动（从 LocationTracker 读取最近 30 秒内的位移/速度判定）
        val isMoving = locationTracker?.isMovingRecently() ?: false

        // ⑤ 前台 App 包名：小世界自身在前台时为本包名，否则为 null（不暴露其他 App 包名）
        //    状态卡逻辑：foreground_package == 本包名 → "在线"，否则亮屏/熄屏
        val foregroundPkg = if (MainActivity.isAppForeground) context.packageName else null

        return DeviceStatusUpsert(
            user_id = userId,
            battery_level = battery.first,
            is_charging = battery.second,
            network_type = net.first,
            wifi_ssid = net.second,
            screen_on = screenOn,
            is_moving = isMoving,
            foreground_package = foregroundPkg,
            updated_at = Instant.now().toString()
        )
    }

    /**
     * 读取当前 WiFi SSID（多层尝试，适配不同 Android 版本/ROM 的权限收紧）
     * 需要定位权限（本 App 已有）；全部失败返回 null
     */
    private fun readWifiSsid(): String? {
        // 方案 1：WifiManager.connectionInfo（最常用）
        runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ssid = wm.connectionInfo?.ssid
            if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>" && ssid != "0x") {
                val cleaned = ssid.removeSurrounding("\"")
                if (cleaned.isNotBlank()) return cleaned
            }
        }
        // 方案 2：反射 mSSID（老 ROM 兜底）
        runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wm.javaClass.getDeclaredMethod("getConnectionInfo").invoke(wm)
            if (wifiInfo != null) {
                val f = wifiInfo.javaClass.getDeclaredField("mSSID")
                f.isAccessible = true
                val ssid = f.get(wifiInfo) as? String
                if (!ssid.isNullOrBlank()) {
                    val cleaned = ssid.removeSurrounding("\"")
                    if (cleaned.isNotBlank() && cleaned != "<unknown ssid>") return cleaned
                }
            }
        }
        return null
    }
}
