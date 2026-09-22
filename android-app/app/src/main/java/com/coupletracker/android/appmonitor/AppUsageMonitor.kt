// ===== 包声明：声明本文件所属的包路径 =====
// 包名 com.coupletracker.android.appmonitor 表示这是情侣追踪 App 中负责"应用使用监控"模块的代码
package com.coupletracker.android.appmonitor

// ===== 导入依赖：下面这些 import 把 Android 系统服务和项目内其他模块的类引入到本文件中使用 =====
import android.app.AppOpsManager          // AppOpsManager：Android 的"应用操作权限"管理器，用来检查 App 是否有某项权限
import android.app.usage.UsageEvents       // UsageEvents：表示"使用事件"（如 App 切到前台/后台）的系统类
import android.app.usage.UsageStatsManager // UsageStatsManager：核心类，可以查询用户使用 App 的统计数据
import android.content.Context              // Context：Android 的上下文，几乎是所有系统功能调用的入口（可以拿到系统服务）
import android.content.Intent               // Intent：Android 中用来"打开某个界面"的意图对象
import android.content.pm.ApplicationInfo   // ApplicationInfo：保存某个 App 的元信息（名称、类别等）
import android.os.PowerManager              // PowerManager：电源管理器，可以判断屏幕亮/灭状态
import android.os.Process                   // Process：可获取当前 App 的 UID 等进程信息
import com.coupletracker.android.data.AppUsageInsert  // AppUsageRow：项目自定义的数据类，对应后端 app_usage 表的一行记录
import com.coupletracker.android.data.NetworkModule // NetworkModule：项目自定义的网络模块，封装了所有 HTTP 接口
import com.coupletracker.android.data.UserRepository // UserRepository：项目自定义的用户仓库，用来获取当前登录用户
import kotlinx.coroutines.*                 // 协程相关：Job、CoroutineScope、Dispatchers、delay、isActive 等
import kotlinx.coroutines.flow.MutableStateFlow // MutableStateFlow：可变的状态流，用于向 UI 推送数据变化
import kotlinx.coroutines.flow.asStateFlow       // asStateFlow：把可变流转成只读流，防止外部修改

/**
 * 应用使用监控器：基于系统 UsageStatsManager
 * - 每2秒检查一次前台APP
 * - 每60秒把当前APP的使用时长汇总上报到 Supabase app_usage 表
 */
// ===== 类定义 =====
// class AppUsageMonitor 是本文件的核心类。
// 构造参数说明：
//   - context: Context：Android 上下文，用来获取系统服务（如 UsageStatsManager）
//   - scope: CoroutineScope：协程作用域，决定这个监控器在哪个生命周期范围内运行协程
// 两个参数都用 private val 修饰，意味着它们会被保存为成员变量，且只能在类内部访问
class AppUsageMonitor(private val context: Context, private val scope: CoroutineScope) {

    // ===== 以下是成员变量（属性）声明区 =====

    // usm：使用统计管理器，是本类的核心系统服务，用来查询"哪个 App 在前台"等数据
    // getSystemService 通过名字拿到系统服务，as 强转成具体类型
    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    // pm：包管理器，用来通过包名查询 App 的名称、图标、类别等元信息
    private val pm = context.packageManager

    // powerMgr：电源管理器，isInteractive 属性可判断屏幕当前是否点亮
    private val powerMgr = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    // job：协程任务句柄，start() 时创建，stop() 时取消；为空表示当前没有在监控
    private var job: Job? = null

    // lastPackage：记录"上一次"查询到的前台 App 包名，用来判断 App 是否切换
    private var lastPackage: String = ""

    // lastEventTs：UsageEvents 查询游标（毫秒）。
    // 【为什么需要游标？】旧实现每次固定查"最近 60 秒"窗口里的 MOVE_TO_FOREGROUND 事件：
    //   用户在同一个 App 里停留超过 60 秒后，窗口内没有任何新前台事件 → 返回 null
    //   → 上层直接 return → 计时和上报中断，长会话被切碎，"打开时间"显示错乱。
    // 行业成熟做法：维护游标只增量查询，窗口内无新事件时保持当前 App 不变。
    private var lastEventTs: Long = 0L

    // lastReportAt：上次上报后端的时间戳（毫秒），用来控制上报频率（每 15 秒一次）
    private var lastReportAt: Long = 0L
    /** 累计使用时长（毫秒），从当前 APP 打开开始算，APP 切换就重置 */
    private var sessionStartAt: Long = 0L
    /** 熄屏时累计暂停的时间，恢复后扣除 */
    private var screenOffSince: Long = 0L
    /** 当前是否熄屏 */
    // isScreenOn：对外只读（private set），UI 可以观察当前屏幕是否亮着
    var isScreenOn: Boolean = true
        private set

    // _currentApp：内部可变状态流，保存当前前台 App 的 (包名, 应用名) 二元组，初始为 null
    private val _currentApp = MutableStateFlow<Pair<String, String>?>(null)
    // currentApp：对外暴露的只读流，UI 订阅它即可实时显示当前正在用的 App
    val currentApp = _currentApp.asStateFlow()

    /** 当前 APP 的累计使用时长（秒），UI 直接读这个就不会丢了 */
    // _currentSessionSeconds：内部可变状态流，保存当前 App 已使用秒数，UI 订阅后可实时刷新计时器
    private val _currentSessionSeconds = MutableStateFlow(0)
    // currentSessionSeconds：对外只读流，UI 直接读它即可显示当前会话累计秒数
    val currentSessionSeconds = _currentSessionSeconds.asStateFlow()

    // ===== 权限检查方法：判断用户是否授予了"使用情况访问权限" =====
    // 这个权限是读取 App 使用数据的前提，没授权监控器就什么都查不到
    fun hasUsagePermission(): Boolean {
        // 拿到 AppOpsManager（应用操作管理器），它负责检查"特殊权限"的状态
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        // 根据 Android 版本选择不同的检查方法：Q（Android 10）以上用 unsafeCheckOpNoThrow，更老版本用废弃的 checkOpNoThrow
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // 新方法 unsafeCheckOpNoThrow：不会抛异常，返回当前权限状态码
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.packageName)
        } else {
            // 老方法 checkOpNoThrow：已废弃但仍可用，用 @Suppress 关掉编译器警告
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.packageName)
        }
        // 如果返回值是 MODE_ALLOWED，说明用户已授权；否则说明未授权
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // ===== 创建跳转到"使用情况访问权限"系统设置页的 Intent =====
    // 调用 startActivity(createUsageSettingsIntent()) 即可引导用户去授权
    fun createUsageSettingsIntent(): Intent =
        Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS) // 系统自带的"使用情况访问"设置页 Action
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // 加上 NEW_TASK 标志，因为从非 Activity 上下文启动需要新任务栈

    // ===== 启动监控：开一个协程循环，定时检查并上报 =====
    // pollMs：轮询间隔，默认 2000 毫秒（即 2 秒查一次）
    fun start(pollMs: Long = 2000L) {
        // 如果已经有任务在跑，直接返回，避免重复启动
        if (job?.isActive == true) return
        // 初始化"上次上报时间"为现在，确保第一次轮询不会立即触发上报
        lastReportAt = System.currentTimeMillis()
        // 在协程作用域内启动一个协程，Dispatchers.IO 表示跑在 IO 线程池（适合做网络/系统调用）
        job = scope.launch(Dispatchers.IO) {
            // while(isActive) 是协程里常见的"循环 + 延时"模式，isActive 在协程被取消时会变 false
            while (isActive) {
                // runCatching 包一层：即使 checkAndReport() 抛异常也不会让循环崩溃
                runCatching { checkAndReport() }
                // 等 pollMs 毫秒后再进下一轮
                delay(pollMs)
            }
        }
    }

    // ===== 停止监控：取消协程任务并清空引用 =====
    fun stop() { job?.cancel(); job = null }

    // ===== 核心方法：检查并上报（每 2 秒被调一次） =====
    // 这是整个监控器的"大脑"，负责：判断屏幕状态、检测 App 切换、定时上报、实时更新秒数
    private fun checkAndReport() {
        // 没权限就不做事，直接退出
        if (!hasUsagePermission()) return
        // now：当前时间戳（毫秒），后续所有计时都基于它
        val now = System.currentTimeMillis()

        // ===== 屏幕状态检测 =====
        // powerMgr.isInteractive：true 表示屏幕亮着并可交互，false 表示熄屏
        val screenOn = powerMgr.isInteractive
        // 把屏幕状态写到对外变量，UI 可观察
        isScreenOn = screenOn
        if (!screenOn) {
            // 熄屏 → 记录熄屏开始时间，暂停计时，不上报
            // 第一次进入熄屏时，记下熄屏开始的时刻
            if (screenOffSince == 0L) screenOffSince = now
            // 熄屏期间把累计秒数清零（UI 上会显示 0）
            _currentSessionSeconds.tryEmit(0)
            // 熄屏直接返回，下面的逻辑都不执行
            return
        }
        // 亮屏 → 如果刚从熄屏恢复，把熄屏期间的时间扣掉
        // 如果之前熄过屏（screenOffSince > 0）且当前有正在计时的会话（sessionStartAt > 0）
        if (screenOffSince > 0L && sessionStartAt > 0L) {
            // 把 sessionStartAt 往后移（相当于跳过熄屏时间）
            // 思路：把会话开始时间往后挪"熄屏持续时长"那么久，这样 (now - sessionStartAt) 就自动扣掉了熄屏期间
            sessionStartAt += (now - screenOffSince)
            // 重置熄屏计时器
            screenOffSince = 0L
        }

        // 查询当前前台 App 的包名，查不到就退出
        val fg = getForegroundPackage() ?: return
        if (fg.isEmpty()) return

        // ===== App 切换检测 =====
        if (fg != lastPackage) {
            // ⚠️ APP 切换了 → 补报前一个 APP「距上次上报的增量」，再重置
            // 【成熟做法】只报增量（now - lastReportAt），不报会话累计（now - sessionStartAt）：
            //   会话期间的时长已被每 15 秒的定时增量覆盖，再报累计会同一段时间计两次，
            //   导致云端一小时超过 60 分钟（iOS/数字健康等统计软件均以物理上限为准）。
            if (lastPackage.isNotEmpty() && sessionStartAt > 0) {
                // 计算距上次上报的未上报秒数
                val pendingSeconds = ((now - lastReportAt) / 1000).toInt()
                // 只上报 ≥10 秒的，过滤掉短暂切换（比如误触）
                if (pendingSeconds >= 10) {
                    val prevPkg = lastPackage
                    // window_start 传旧会话的真实打开时刻（此刻 sessionStartAt 尚未被重置）
                    reportOnce(prevPkg, pendingSeconds, sessionStartAt)
                }
            }
            // 切换后，把"上次包名"更新为新 App，并重置会话起点和上报时间
            lastPackage = fg
            lastReportAt = now
            sessionStartAt = now
            // 新会话开始，累计秒数清零
            _currentSessionSeconds.tryEmit(0)
        }

        // 实时更新累计秒数（只算亮屏时间）
        // 把 (now - sessionStartAt) 转成秒，推给 UI 流，UI 订阅后会自动刷新
        if (sessionStartAt > 0) {
            _currentSessionSeconds.tryEmit(((now - sessionStartAt) / 1000).toInt())
        }

        // 每 15 秒上报一次当前 APP 的使用时长（仅亮屏时）
        // 距离上次上报超过 15 秒，就再报一次
        if (now - lastReportAt >= 15_000L) {
            // 计算自上次上报以来的秒数，coerceAtLeast(1) 保证至少 1 秒（避免上报 0 秒）
            val elapsedSeconds = ((now - lastReportAt) / 1000).toInt().coerceAtLeast(1)
            // 刷新上报时间戳
            lastReportAt = now
            // 上报当前 App 这段时间的使用秒数（window_start = 会话真实打开时刻）
            reportOnce(fg, elapsedSeconds, sessionStartAt)
        }
    }

    /** 上报一次（抽出来复用，APP 切换补报 + 定时上报都走这里） */
    // ===== 上报方法：把某 App 的使用时长发到后端 =====
    // 参数：
    //   - pkg：App 包名（如 "com.tencent.mm"）
    //   - seconds：使用时长（秒）
    //   - windowStartMs：会话真实打开时刻（毫秒）。写入 window_start 字段，
    //     让"历史打开记录"显示的是真实打开时间，而不是上报创建时间
    private fun reportOnce(pkg: String, seconds: Int, windowStartMs: Long? = null) {
    // 不记录系统桌面/输入法等噪音
    // 先过滤掉系统 App（桌面、输入法等），这些不算"使用 App"
    if (isSystemNoisePkg(pkg)) return
    // 拿到 App 的友好名称和分类（如 "微信" + "社交"）
    val (appName, category) = getAppMeta(pkg)
        // 立即把当前 App 信息推给 UI 流（让 UI 实时显示"正在使用：微信"）
        _currentApp.tryEmit(pkg to appName)
        // 启动一个独立协程做网络上报，避免阻塞主循环
        scope.launch(Dispatchers.IO) {
            // 拿当前登录用户，没登录就不上报
            val user = UserRepository.get().getUser()
            val userId = user?.id ?: return@launch
            // runCatching 包一层，网络异常也不会让协程崩溃
            val resp = runCatching {
                // 调用网络模块的 reportAppUsage 接口，把数据发到后端 app_usage 表
                NetworkModule.restService.reportAppUsage(
                    // 构造一行数据
                    AppUsageInsert(
                        user_id = userId,        // 用户 ID
                        couple_id = null,        // 情侣 ID（这里没用，传 null）
                        package_name = pkg,      // 包名
                        app_name = appName,      // App 名称
                        category = category,     // 分类
                        usage_seconds = seconds, // 使用时长（秒）
                        // window_start = 会话真实打开时刻（ISO 8601 UTC），
                        // 查询端优先用它作为"打开时间"；为 null 时服务端默认 now()
                        window_start = windowStartMs?.let {
                            java.time.Instant.ofEpochMilli(it).toString()
                        }
                    )
                )
            }
            // resp.getOrNull()：成功返回 Response，失败返回 null
            val http = resp.getOrNull()
            // 根据上报结果更新状态文案，UI 可观察 lastAppReportStatus 显示"上报成功/失败"
            NetworkModule.lastAppReportStatus.value =
                when {
                    // http == null 表示抛了异常（如网络断了）
                    http == null -> "APP上报异常：${resp.exceptionOrNull()?.message?.take(40).orEmpty()}"
                    // HTTP 状态码非 2xx 视为失败
                    !http.isSuccessful -> "APP上报失败 HTTP ${http.code()}：${http.errorBody()?.let { runCatching { it.string().take(60) }.getOrNull().orEmpty() }}"
                    // 成功
                    else -> "APP上报成功 · $appName ${seconds}s"
                }
        }
    }

    // ===== 查询当前前台 App 包名（游标增量版） =====
    // 通过 UsageStatsManager 查询使用事件，找出最后一次切到前台的那个 App。
    //
    // 【游标机制】
    //   - 首次启动：查最近 10 分钟的大窗口，确定当前前台 App
    //   - 之后每次：只查"上次游标之后"的增量事件，并推进游标
    //   - 窗口内无新事件：说明前台 App 没有变化 → 返回 lastPackage（保持当前会话，
    //     长会话不再中断）—— 这是修复"打开时间错乱/上报中断"的关键
    private fun getForegroundPackage(): String? {
        val end = System.currentTimeMillis()
        // 首次查 10 分钟大窗口；之后从游标位置继续（游标处留 1ms 避免重复处理同一事件）
        val begin = if (lastEventTs > 0L) lastEventTs + 1L else end - 600_000L
        if (begin >= end) return lastPackage.ifEmpty { null }  // 游标已追平当前时间 → 无新事件
        val events = usm.queryEvents(begin, end)
        // 复用一个 Event 对象（避免循环里反复创建对象，是性能优化写法）
        val ev = UsageEvents.Event()
        var latestFg: String? = null  // 记录最新的前台包名
        var latestTime = 0L           // 记录最新前台事件的时间戳
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            // 所有类型事件都推进游标（前台/后台切换都算"已消费"）
            if (ev.timeStamp > lastEventTs) lastEventTs = ev.timeStamp
            // 只关心"切到前台"事件，且时间戳要比已记录的更新（取最新一个）
            if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                ev.timeStamp > latestTime) {
                latestTime = ev.timeStamp
                latestFg = ev.packageName
            }
        }
        // 窗口内没有新的前台事件 → 前台 App 未变化，保持上一次的结果
        return latestFg ?: lastPackage.ifEmpty { null }
    }

    // ===== 获取 App 的友好名称和分类 =====
    // 返回值是 Pair<App名称, 分类>，如 ("微信", "社交")
    private fun getAppMeta(pkg: String): Pair<String, String> {
        // runCatching 包一层，万一包名无效也不会让程序崩溃
        return runCatching {
            // 通过 PackageManager 拿到这个 App 的 ApplicationInfo
            val info = pm.getApplicationInfo(pkg, 0)
            // 拿到 App 的用户可见名称（如"微信"），转成字符串
            val name = pm.getApplicationLabel(info).toString()
            // 根据 Android 系统给 App 标的官方 category 映射成中文分类
            val cat = when (info.category) {
                ApplicationInfo.CATEGORY_GAME -> "游戏"
                ApplicationInfo.CATEGORY_SOCIAL -> "社交"
                ApplicationInfo.CATEGORY_VIDEO -> "视频"
                ApplicationInfo.CATEGORY_AUDIO -> "音乐"
                ApplicationInfo.CATEGORY_NEWS -> "新闻"
                ApplicationInfo.CATEGORY_MAPS -> "地图"
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> "效率"
                ApplicationInfo.CATEGORY_IMAGE -> "图像"
                // 如果系统没标分类，就用包名自己猜一个（兜底方法）
                else -> categorizeByPackage(pkg)
            }
            // Kotlin 的中缀写法：name to cat 等价于 Pair(name, cat)
            name to cat
        // 如果上面流程出异常，就用包名当名称，分类标"其他"
        }.getOrElse { pkg to "其他" }
    }

    // ===== 判断是否是"系统噪音"包名 =====
    // 系统桌面、输入法、状态栏等不算用户真正在用 App，要过滤掉，否则会把"用户在用桌面"也算成使用时长
    private fun isSystemNoisePkg(pkg: String?): Boolean {
    // 空包名直接算噪音
    if (pkg.isNullOrBlank()) return true
    // 转小写方便匹配
    val p = pkg.lowercase()
    // 桌面/启动器相关（如各种 Launcher、SystemUI）
    if (p.contains("launcher") || p.contains("systemui") || p.contains("desk") || p.contains("homescreen")) return true
    // 输入法相关（搜狗、百度输入法、讯飞等）
    if (p.contains("inputmethod") || p.contains("ime") || p.contains("input.")
        || p.contains("sougou") || p.contains("sogou") || p.contains("baidu.input")
        || p.contains("iflytek") || p.contains("讯飞")) return true
    // 系统 UI 组件（状态栏、导航栏、锁屏、电源界面、通知、对话框等）
    if (p.contains("uiautomator") || p.contains("statusbar") || p.contains("navigationbar")
        || p.contains("keyguard") || p.contains("lockscreen") || p.contains("powerui")
        || p.contains("notifications") || p.contains("system.dialog")) return true
    // 安装器和权限弹窗（系统级，不算用户主动用）
    if (p.contains("packageinstaller") || p.contains("permissioncontroller")) return true
    // 包名太短或不含点号（正常包名都像 com.xxx.yyy），直接判噪音
    if (p.length < 5 || !p.contains('.')) return true
    // 都没匹配上，说明是正常用户 App
    return false
}

// ===== 按包名猜分类（兜底方法） =====
// 当 Android 系统没给 App 标官方 category 时，通过包名关键词猜一个中文分类
private fun categorizeByPackage(pkg: String): String = when {
        // 社交：微信、QQ
        pkg.contains("wechat") || pkg.contains("tencent.mm")
                || pkg.contains("qq") -> "社交"
        // 视频：抖音、B 站、快手
        pkg.contains("douyin") || pkg.contains("aweme")
                || pkg.contains("bilibili") || pkg.contains("kuaishou") -> "视频"
        // 音乐：网易云、QQ 音乐、Spotify、酷狗、酷我
        pkg.contains("netease.cloud") || pkg.contains("qqmusic")
                || pkg.contains("spotify") || pkg.contains("kugou")
                || pkg.contains("kuwo") -> "音乐"
        // 购物：淘宝、天猫、京东、拼多多
        pkg.contains("taobao") || pkg.contains("tmall")
                || pkg.contains("jd") || pkg.contains("pinduoduo") -> "购物"
        // 生活：美团、饿了么、大众点评
        pkg.contains("meituan") || pkg.contains("ele")
                || pkg.contains("dianping") -> "生活"
        // 浏览器：Chrome、其他浏览器
        pkg.contains("chrome") || pkg.contains("browser") -> "浏览器"
        // 桌面：Launcher、SystemUI
        pkg.contains("launcher") || pkg.contains("systemui") -> "桌面"
        // 都没匹配上，归为"其他"
        else -> "其他"
    }

    // ===== 一个暂时没用上的占位属性 =====
    // @Suppress("unused") 压住"未使用"的编译警告
    // get() = false 表示这个属性永远返回 false，目前没有实际作用，留作扩展
    @Suppress("unused")
    private val isSystemApp: Boolean get() = false
}


