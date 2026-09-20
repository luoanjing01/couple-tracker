// =============================================================================
// 【文件说明】
// 这是"小世界"APP 的核心后台服务 TrackerService。
// 它是一个 Android "前台 Service"（带通知栏的服务），只要用户登录就会一直运行，
// 负责持续上报用户的位置信息和 APP 使用情况给后端服务器。
//
// 对初学者的几个关键概念：
// 1. Service：Android 四大组件之一，用于在后台执行长时间运行的任务。
// 2. 前台 Service：带有一个常驻通知栏的服务，系统一般不会杀掉它。
// 3. Coroutine（协程）：Kotlin 的轻量级线程方案，用于异步任务。
// 4. runCatching：Kotlin 内置函数，把可能抛异常的代码包起来，避免崩溃。
// =============================================================================

// 声明本文件所属的包名（命名空间），所有类都放在 com.coupletracker.android.service 包下
package com.coupletracker.android.service

// -----------------------------------------------------------------------------
// 以下是一系列 import，告诉编译器我们要用到哪些系统/三方类
// -----------------------------------------------------------------------------

// Android 运行时权限相关：用来声明/检查应用所需的敏感权限（如通知、定位）
import android.Manifest
// 通知渠道：Android 8.0+ 必须为通知创建"渠道"，用户可以按渠道管理通知
import android.app.NotificationChannel
// 通知管理器：负责发送通知、管理通知渠道
import android.app.NotificationManager
// PendingIntent：一种"延迟执行的 Intent"，常用于通知点击后跳转 Activity
import android.app.PendingIntent
// Service：所有后台服务的基类，本类的父类
import android.app.Service
// Context：Android 应用的上下文环境，几乎所有的系统调用都需要它
import android.content.Context
// Intent：用于组件之间通信的"意图"对象，可启动服务/活动、传递参数
import android.content.Intent
// IntentFilter：用于过滤广播的过滤器（这里用于监听电量变化的系统广播）
import android.content.IntentFilter
// PackageManager：用于查询应用自身的权限授予情况
import android.content.pm.PackageManager
// BatteryManager：用于读取电池电量信息的系统服务
import android.os.BatteryManager
// Build：包含设备/系统版本信息，常用于判断 Android 版本号
import android.os.Build
// IBinder：用于进程间通信（IPC）的接口；本服务不支持绑定，返回 null
import android.os.IBinder
// ActivityCompat：兼容版的权限检查工具，可在低版本 Android 上使用
import androidx.core.app.ActivityCompat
// NotificationCompat：兼容版的通知构建器，支持在不同 Android 版本上一致显示
import androidx.core.app.NotificationCompat
// R：本应用的资源引用类，里面包含字符串、图片等资源的 ID
import com.coupletracker.android.R
// AppUsageMonitor：本项目自定义的"APP 使用时长监控器"
import com.coupletracker.android.appmonitor.AppUsageMonitor
// UserRepository：本项目自定义的"用户数据仓库"，提供当前用户信息
import com.coupletracker.android.data.UserRepository
// LocationTracker：本项目自定义的"位置追踪器"，负责上报位置
import com.coupletracker.android.location.LocationTracker
// MainActivity：主界面 Activity，点击通知时跳转到这里
import com.coupletracker.android.ui.MainActivity
// kotlinx.coroutines：Kotlin 协程库，* 表示导入全部常用类（Job、CoroutineScope、launch 等）
import kotlinx.coroutines.*

/**
 * 小世界前台服务
 * - 只要用户登录就保持运行，持续上报位置和APP使用情况
 * - 开机自启、APP更新自启、APP被杀死后尝试自恢复
 * - 使用 CoroutineScope + SupervisorJob 管理子协程
 *
 * ✅ 闪退兜底：所有可能抛异常的地方全部 runCatching 包裹，
 *    缺权限、网络不好、SDK 报错 → 全部静默降级，绝不崩主进程
 */
// class TrackerService : Service()
//   - class：声明一个类
//   - : Service()：继承自 Android 的 Service 基类，括号表示调用父类构造方法
class TrackerService : Service() {

    // ---------------------------------------------------------------------------
    // 【成员变量区】
    // 这些是整个服务运行期间需要保持的对象引用。全部用 private 限制外部访问。
    // ---------------------------------------------------------------------------

    // serviceScope：本服务专属的"协程作用域"。
    //   - SupervisorJob()：监督式 Job，子协程崩了不会拖垮其他兄弟协程
    //   - Dispatchers.Default：在后台线程池执行（适合 CPU 密集任务）
    //   - CoroutineName(...)：给协程起个名字，方便调试时识别
    // 所有后台任务都通过这个 scope 启动，方便统一在 onDestroy 时取消
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("TrackerService")
    )
    // 位置追踪器实例；nullable 因为初始化可能失败（缺权限等），用 runCatching 包住后赋 null
    private var locationTracker: LocationTracker? = null
    // APP 使用监控器实例；同样可能初始化失败，所以也是 nullable
    private var appMonitor: AppUsageMonitor? = null
    // 设备状态心跳上报器（电量/充电/WiFi/网络/屏幕 → device_status 表，60s 心跳+变化即报）
    private var deviceStatusReporter: DeviceStatusReporter? = null
    // 电量监控协程的 Job 句柄，用来随时取消该协程
    private var batteryJob: Job? = null
    // 位置采集协程的 Job 句柄，用来在频率变化时先取消旧的再起新的
    private var locationJob: Job? = null
    // APP 使用采集协程的 Job 句柄，作用同上
    private var appJob: Job? = null
    // 标记 onCreate 是否正常跑完，没跑完则在 onStartCommand 时补初始化
    private var createdSafely: Boolean = false

    // ---------------------------------------------------------------------------
    // onBind：Service 的抽象方法，必须实现。
    // 返回 null 表示本服务不支持"绑定"（bindService），只支持"启动"（startService）。
    // 参数 intent 是绑定时传入的意图，本服务不用它。
    // ---------------------------------------------------------------------------
    override fun onBind(intent: Intent?): IBinder? = null

    // ===========================================================================
    // 【onCreate】服务创建时调用一次，做初始化工作。
    //   - super.onCreate()：先调用父类的初始化逻辑（必须）
    //   - 整体包在 runCatching 里：即使初始化任何一步抛异常，也不会让服务崩溃。
    // ===========================================================================
    override fun onCreate() {
        super.onCreate()
        runCatching {
            // ---------------------------------------------------------------------
            // 步骤 1：初始化两个核心采集器
            //   - LocationTracker：负责获取 GPS 位置并上报后端
            //   - AppUsageMonitor：负责统计用户在前台使用各 APP 的时长
            // 每个都用 runCatching {...}.getOrNull() 包裹：
            //   - 如果构造时抛异常（如缺权限、SDK 不存在）→ 返回 null，不中断后续流程
            //   - this：Service 自身可作为 Context 使用
            // ---------------------------------------------------------------------
            locationTracker = runCatching { LocationTracker(this, serviceScope) }.getOrNull()
            appMonitor   = runCatching { AppUsageMonitor(this, serviceScope) }.getOrNull()
            deviceStatusReporter = runCatching { DeviceStatusReporter(this, serviceScope) }.getOrNull()

            // ---------------------------------------------------------------------
            // 步骤 2：尝试变成"前台服务"（必须显示一个常驻通知）
            //   - canStartForeground()：检查是否有权限（Android 13+ 需要通知权限）
            //   - 如果没权限就跳过，本服务降级成普通后台服务（不会被系统优先保护）
            //   - startForeground(id, notification)：真正变成前台服务
            //   - NOTIF_ID：通知的唯一 ID，之后用这个 ID 更新通知文本
            // ---------------------------------------------------------------------
            if (canStartForeground()) {
                runCatching {
                    startForeground(NOTIF_ID, buildNotification("💕 正在连接服务..."))
                }
            }

            // ---------------------------------------------------------------------
            // 步骤 3：启动一个监听协程，动态响应用户登录状态和采集频率变化
            //   - serviceScope.launch { ... }：在协程作用域里启动一个新协程
            //   - 里面也包一层 runCatching，任何异常都不会拖垮协程
            // ---------------------------------------------------------------------
            serviceScope.launch {
                runCatching {
                    // 获取用户数据仓库的单例实例
                    val repo = UserRepository.get()
                    // 用 flow.combine 同时监听 3 个数据流：
                    //   1) userFlow：当前登录用户（null 表示未登录）
                    //   2) locationIntervalSecFlow：位置采集频率（秒）
                    //   3) appIntervalSecFlow：APP 使用采集频率（秒）
                    // 任意一个流变化都会触发一次组合，得到一个 Triple<(user),(locSec),(appSec)>
                    kotlinx.coroutines.flow.combine(
                        repo.userFlow,
                        repo.locationIntervalSecFlow,
                        repo.appIntervalSecFlow
                    ) { user, locSec, appSec -> Triple(user, locSec, appSec) }
                        // .collect：持续收集上面的组合流；每当有新值就会进入这个块
                        // 解构语法 (user, locSec, appSec) 把 Triple 拆开成三个变量
                        .collect { (user, locSec, appSec) ->
                            // 内层 runCatching：单次采集失败不会让整个监听协程结束
                            runCatching {
                                if (user != null) {
                                    // 已登录：更新通知文案显示当前用户昵称
                                    //   - user.nickname.ifBlank { user.username }：
                                    //     如果昵称为空就用用户名代替（防止显示空白）
                                    if (canStartForeground()) {
                                        runCatching {
                                            updateNotification("💕 已登录 · ${user.nickname.ifBlank { user.username }}")
                                        }
                                    }
                                    // 位置采集：把秒转成毫秒，并保证至少 2 秒（2000ms）一次
                                    //   - coerceAtLeast(x)：如果当前值小于 x 则用 x
                                    val locMs = (locSec * 1000L).coerceAtLeast(2000L)
                                    restartLocation(locMs)
                                    // APP 使用采集：转毫秒，至少 1 秒一次
                                    val appMs = (appSec * 1000L).coerceAtLeast(1000L)
                                    restartAppMonitor(appMs)
                                    // 启动电量监控（每 10 秒读一次电量，塞进位置上报里顺便传）
                                    startBatteryMonitor()
                                    // 启动设备状态心跳（60s 心跳 + 状态变化即报 → device_status 表）
                                    // 这是"手机状态/在线状态/WiFi"展示的数据源，与位置上报解耦，
                                    // 即使定位被系统限制，心跳仍能维持对方看到"在线"
                                    runCatching { deviceStatusReporter?.start() }
                                } else {
                                    // 用户未登录 → 停止心跳并结束本服务
                                    runCatching { deviceStatusReporter?.stop() }
                                    stopSelf()
                                }
                            }
                        }
                }
            }
            // 全部初始化正常跑完，打上标记，给 onStartCommand 用
            createdSafely = true
        }
    }

    // ===========================================================================
    // 【restartLocation】重启位置采集协程
    // 场景：用户在设置里改了采集频率，需要先停掉旧协程，再以新频率启动一个新协程。
    //
    // 参数 intervalMs：采集间隔，单位毫秒。
    // ===========================================================================
    private fun restartLocation(intervalMs: Long) {
        runCatching {
            // 1. 如果之前有位置采集协程在跑，先取消它（避免重复上报）
            locationJob?.cancel()
            // 2. 拿到 locationTracker 实例；如果是 null（初始化失败过），
            //    用 Elvis 操作符 ?: return 直接返回，不继续
            val tracker = locationTracker ?: return
            // 3. 检查定位权限是否授予；没权限就不启动，避免系统报错
            if (tracker.hasPermission()) {
                // 4. 启动新协程，保存其 Job 句柄到 locationJob（方便下次取消）
                //    Dispatchers.Default：在后台线程池执行
                locationJob = serviceScope.launch(Dispatchers.Default) {
                    // 先停掉 tracker 内部的旧任务（清理资源）
                    runCatching { tracker.stop() }
                    // 再用新的间隔启动采集
                    runCatching { tracker.start(intervalMs) }
                }
            }
        }
    }

    // ===========================================================================
    // 【restartAppMonitor】重启 APP 使用监控协程
    // 逻辑跟 restartLocation 几乎一样，只是操作的是 AppUsageMonitor。
    //
    // 参数 pollMs：轮询间隔，单位毫秒。
    // ===========================================================================
    private fun restartAppMonitor(pollMs: Long) {
        runCatching {
            // 取消旧的 APP 采集协程
            appJob?.cancel()
            // 取出 monitor 实例，null 就直接返回
            val mon = appMonitor ?: return
            // 检查是否拥有"使用情况访问权限"（特殊权限，需用户在系统设置里授予）
            if (mon.hasUsagePermission()) {
                // 启动新协程，保存 Job 句柄
                appJob = serviceScope.launch(Dispatchers.Default) {
                    runCatching { mon.stop() }   // 先清理旧任务
                    runCatching { mon.start(pollMs) }  // 再以新间隔启动
                }
            }
        }
    }

    // ===========================================================================
    // 【canStartForeground（实例版）】
    // 判断本服务是否满足变身为"前台服务"的权限要求。
    // 直接委托给 companion 里的静态版（传入 this 即当前 Context）。
    // 具体权限规则见 companion object 中的 canStartForeground(ctx) 注释。
    // ===========================================================================
    /** 启动前台服务所需权限：Android 13+ 需要 POST_NOTIFICATIONS；Android 14 location 类型需要定位权限 */
    private fun canStartForeground() = canStartForeground(this)

    // ===========================================================================
    // 【onStartCommand】每次外部调用 startService() / startForegroundService() 都会触发
    //   - intent：启动时传入的意图，可携带 action 区分不同操作
    //   - flags：系统附加的启动标志位（本服务不处理）
    //   - startId：本次启动的唯一编号，用于停止时区分（本服务不显式使用）
    //
    // 返回值 START_STICKY：服务被系统杀死后会自动重启，但 intent 为 null。
    //   - 这种"自动重启"是 Android 给后台服务的容灾机制之一。
    // ===========================================================================
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 分支 A：如果外部发送了 ACTION_STOP 意图 → 主动停止本服务
        //   - intent?.action == ACTION_STOP：检查意图的 action 字段
        //   - stopSelf()：服务自尽
        //   - START_NOT_STICKY：返回这个值告诉系统"杀掉后别重启"
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 🔴 关键修复：如果 onCreate 时因权限不够跳过了 startForeground，
        //    但后来权限被授予 → onStartCommand 会在 MainActivity 再次 startService 时触发，
        //    此时补调 startForeground() 让服务真正变成前台服务，否则会被系统杀掉
        if (canStartForeground()) {
            runCatching {
                // 获取通知管理器系统服务
                //   - getSystemService(NOTIFICATION_SERVICE)：拿到 NotificationManager
                //   - as NotificationManager：强制类型转换
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                // 检查本应用的通知渠道是否已经存在；不存在则兜底创建一个
                //   - getString(R.string.tracker_channel_id)：从资源文件读渠道 ID 字符串
                //   - 用 runCatching 包一层：极端情况下 getNotificationChannel 也可能抛异常
                if (runCatching { nm.getNotificationChannel(getString(R.string.tracker_channel_id)) }.getOrNull() == null) {
                    // 兜底：渠道还没建（极端情况），赶紧建一个
                    //   - Android 8.0（O）及以上版本必须创建通知渠道，否则通知不显示
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        nm.createNotificationChannel(NotificationChannel(
                            getString(R.string.tracker_channel_id),       // 渠道 ID（唯一）
                            getString(R.string.tracker_channel_name),    // 用户可见的渠道名
                            NotificationManager.IMPORTANCE_LOW             // 重要等级：低（不响铃）
                        ).apply {
                            // apply{} 块：在创建渠道后设置描述文案
                            description = getString(R.string.tracker_channel_desc)
                        })
                    }
                }
                // 如果 onCreate 因异常没跑完，这里补做初始化
                if (!createdSafely) {
                    // onCreate 可能因为异常没跑完 → 现在补初始化
                    runCatching {
                        locationTracker = runCatching { LocationTracker(this, serviceScope) }.getOrNull()
                        appMonitor = runCatching { AppUsageMonitor(this, serviceScope) }.getOrNull()
                        deviceStatusReporter = runCatching { DeviceStatusReporter(this, serviceScope) }.getOrNull()
                        runCatching { deviceStatusReporter?.start() }   // 兜底：补初始化后立即启动心跳
                        // 把实例引用也存到 companion 的静态变量里，方便 UI 层直接读取
                        Companion.appMonitor = appMonitor
                    }
                    createdSafely = true
                }
                // 补调 startForeground()：让本服务真正变成前台服务
                runCatching {
                    startForeground(NOTIF_ID, buildNotification("💕 正在连接服务..."))
                }
            }
        }
        // 🔴 兜底：无条件重启 AppUsageMonitor（不要等 combine flow 触发）
        //   - 服务被系统重启后，flow 可能还没就绪，先硬启动一次保证至少在采集
        //   - 固定 4 秒轮询一次（4000ms）
        val mon = appMonitor
        if (mon != null && mon.hasUsagePermission()) {
            runCatching { appJob?.cancel() }  // 先取消旧协程
            runCatching { mon.stop() }         // 清理内部状态
            runCatching { mon.start(4000L) }   // 以 4 秒间隔启动
        }
        return START_STICKY
    }

    // ===========================================================================
    // 【onDestroy】服务被销毁时调用（无论是 stopSelf、stopService 还是系统回收）
    //   - 这里要释放所有资源：取消协程、停止采集器、停止电量监控
    //   - 每一步都包 runCatching：保证即使某一步出错，后续清理仍能执行
    // ===========================================================================
    override fun onDestroy() {
        runCatching { deviceStatusReporter?.stop() } // 停止设备状态心跳（先停，避免取消 scope 后再发请求）
        runCatching { serviceScope.cancel() }   // 取消整个协程作用域（会连带取消所有子协程）
        runCatching { locationTracker?.stop() } // 停止位置采集
        runCatching { appMonitor?.stop() }      // 停止 APP 使用监控
        runCatching { batteryJob?.cancel() }    // 取消电量监控协程
        runCatching { super.onDestroy() }       // 最后调用父类的 onDestroy
    }

    // ===========================================================================
    // 【电量监控相关】
    // 目的：每 10 秒读一次电量百分比，缓存在 locationTracker 里，
    //       等下次位置上报时顺便一起发给服务器（省一次独立网络请求）。
    // ===========================================================================

    // --- 电量监控，更新到定位里顺便上报 ---
    private fun startBatteryMonitor() {
        runCatching {
            // 如果电量监控协程已经在跑（isActive == true），直接返回，不重复启动
            if (batteryJob?.isActive == true) return
            // 在 IO 线程池启动一个循环协程
            //   - Dispatchers.IO：适合 IO 操作（如读电量、网络），线程池较大
            batteryJob = serviceScope.launch(Dispatchers.IO) {
                // while (isActive)：协程没被取消就一直循环
                while (isActive) {
                    runCatching {
                        // 读取当前电量百分比
                        val pct = getBatteryPct()
                        // 把电量塞进 locationTracker 的缓存里
                        locationTracker?.setBatteryCache(pct)
                    }
                    // 等待 10 秒后再读下一次
                    //   - delay() 是协程的"非阻塞"等待，不会卡住线程
                    delay(60_000L)  // 轻量化：电量变化很慢，60 秒读一次足够（原 10 秒）
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // 【getBatteryPct】读取当前电池电量百分比（0~100），失败返回 -1
    // 采用"双保险"策略：方案 A 失败就尝试方案 B。
    // ---------------------------------------------------------------------------
    private fun getBatteryPct(): Int {
        return runCatching {
            // 方案 A：通过 BatteryManager 系统服务直接读容量
            //   - Context.BATTERY_SERVICE：电池服务的名字
            //   - as BatteryManager：强转
            //   - BATTERY_PROPERTY_CAPACITY：返回 0~100 的整数百分比
            //   - takeIf { it > 0 }：如果值 > 0 就保留，否则返回 null
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it > 0 }
        }.getOrNull() ?: runCatching {
            // 方案 B：方案 A 失败时，用"粘性广播"读取最近的电量变化广播
            //   - IntentFilter(Intent.ACTION_BATTERY_CHANGED)：电量变化的广播 action
            //   - registerReceiver(null, ifilter)：传 null receiver 表示只读取最近的粘性广播，
            //     不会真正注册一个长期 receiver（这是系统的特殊用法）
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = registerReceiver(null, ifilter)
            // 从广播 extras 里取出 level（当前电量）和 scale（最大刻度）
            //   - getIntExtra(key, default)：从 Intent 取 int 值，没有时返回 default
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            // 计算百分比：level * 100 / scale；非法情况返回 null
            if (level < 0 || scale <= 0) null else (level * 100 / scale)
        }.getOrNull() ?: -1  // 两种方案都失败 → 返回 -1 表示未知
    }

    // ===========================================================================
    // 【通知栏相关】
    // 前台服务必须显示一个常驻通知，下面两个函数负责构造和更新通知。
    // ===========================================================================

    // --- 通知栏 ---
    // 构造一个 Notification 对象。参数 text 是通知正文。
    private fun buildNotification(text: String): android.app.Notification {
        // 创建点击通知后要打开的 Intent，目标是 MainActivity
        val intent = Intent(this, MainActivity::class.java).apply {
            // 设置启动标志位：
            //   - FLAG_ACTIVITY_SINGLE_TOP：如果 MainActivity 已在栈顶则不新建实例
            //   - FLAG_ACTIVITY_CLEAR_TOP：清掉它上面的所有 Activity
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        // 把上面的 Intent 包装成 PendingIntent，点击通知时由系统代为执行
        //   - 第 1 个参数：Context
        //   - 第 2 个参数：requestCode，本应用传 0 即可
        //   - 第 3 个参数：目标 Intent
        //   - 第 4 个参数：标志位
        //     - FLAG_IMMUTABLE：Android 12+ 必须指定可变性，IMMUTABLE 表示 PendingIntent 不可变
        //     - FLAG_UPDATE_CURRENT：如果已存在相同 requestCode 的 PendingIntent，更新其 extras
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 用 Builder 链式构造 Notification
        return NotificationCompat.Builder(this, getString(R.string.tracker_channel_id))  // 指定通知渠道
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)  // 状态栏小图标（系统自带定位图标）
            .setColor(0xFFE75480.toInt())                         // 图标染色（粉色，ARGB 转 Int）
            .setContentTitle(getString(R.string.tracker_notif_title))  // 通知标题
            .setContentText(text)                                 // 通知正文
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))  // 展开式样式（显示更长文本）
            .setContentIntent(pi)                                  // 点击跳转的 PendingIntent
            .setOngoing(true)                                      // 常驻通知（用户不可滑动清除）
            .setOnlyAlertOnce(true)                                // 只首次提示，更新时不再次响铃/震动
            .setPriority(NotificationCompat.PRIORITY_LOW)          // 低优先级，不打扰用户
            .build()                                               // 构造完成
    }

    // 更新已显示通知的文本（用同一个 NOTIF_ID）
    //   - nm.notify(id, notification)：用相同 id 重新发一个通知，会覆盖旧的
    private fun updateNotification(text: String) {
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        }
    }

    // ===========================================================================
    // 【companion object】Kotlin 的"伴生对象"
    //   - 类似 Java 的 static 块：里面定义的常量和方法可以通过类名直接访问
    //     例如：TrackerService.start(ctx)
    //   - 常用于：常量、工厂方法、单例静态引用等
    // ===========================================================================
    companion object {
        // 通知的唯一 ID。系统通过这个 ID 区分不同通知，
        // 用同一个 ID 多次 notify() 会覆盖旧通知（实现"更新通知"的效果）。
        // 10086 是随手挑的一个数字，只要在本应用内唯一即可。
        private const val NOTIF_ID = 10086
        // 停止服务的 action 字符串常量
        //   - 通过 Intent 的 action 字段告诉服务"现在请你停止"
        const val ACTION_STOP = "com.coupletracker.ACTION_STOP_SERVICE"

        /** 静态引用 AppUsageMonitor，UI 层直接读后台数据（累计时长不丢） */
        // @Volatile：保证多线程可见性。一个线程写入后，其他线程立即看到新值
        //   - 因为 service 进程和 UI 可能并发读写这个引用
        @Volatile var appMonitor: com.coupletracker.android.appmonitor.AppUsageMonitor? = null

        // =========================================================================
        // 【canStartForeground（静态版）】判断是否满足变成前台服务的权限要求
        // 参数 ctx：调用方传入的 Context（可以是 Activity、Application 等）
        // 返回值：true = 可以变成前台服务；false = 缺权限，不能变
        //
        // 权限规则分两段：
        //   1. Android 13（TIRAMISU）+：必须授予 POST_NOTIFICATIONS 通知权限
        //   2. Android 14（UPSIDE_DOWN_CAKE）+：如果服务声明了 foregroundServiceType=location，
        //      还必须授予至少一种定位权限（精确或粗略）
        // =========================================================================
        /** 启动前台服务所需权限（静态版，供 Activity 提前检查） */
        fun canStartForeground(ctx: Context): Boolean {
            // 段 1：Android 13+ 检查通知权限
            //   - Build.VERSION_CODES.TIRAMISU：Android 13 的代号常量
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // ActivityCompat.checkSelfPermission(ctx, permission)
                //   - 返回 PERMISSION_GRANTED（已授予）或 PERMISSION_DENIED（未授予）
                if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) return false
            }
            // Android 14 规定：foregroundServiceType=location 时必须已授予至少粗略定位权限
            // 段 2：Android 14+ 检查定位权限
            //   - Build.VERSION_CODES.UPSIDE_DOWN_CAKE：Android 14 的代号常量
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // ACCESS_FINE_LOCATION：精确位置权限（GPS 级别）
                val fine = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                // ACCESS_COARSE_LOCATION：粗略位置权限（基站/WiFi 级别）
                val coarse = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
                // 精确和粗略都没授予 → 不满足
                if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }
            // 所有条件都满足
            return true
        }

        // =========================================================================
        // 【start】启动本服务的便捷方法，供外部（如 Activity）调用
        //   - 全程 runCatching 包裹：哪怕启动失败也不会让调用方崩溃
        // 参数 ctx：调用方 Context
        // =========================================================================
        /** 启动服务：全部异常吞掉 → 永不闪退 */
        fun start(ctx: Context) {
            runCatching {
                // 构造启动本服务的 Intent
                val i = Intent(ctx, TrackerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Android 8.0+：启动前台服务必须用 startForegroundService()，
                    // 而不是普通的 startService()。系统会要求服务在 5 秒内调用 startForeground()
                    ctx.startForegroundService(i)
                } else {
                    // 低版本直接 startService() 即可
                    ctx.startService(i)
                }
            }
        }

        // =========================================================================
        // 【stop】停止本服务的便捷方法
        //   - 通过设置 Intent.action = ACTION_STOP，
        //     让 onStartCommand 识别到这是停止意图，进而调用 stopSelf()
        // 参数 ctx：调用方 Context
        // =========================================================================
        fun stop(ctx: Context) {
            runCatching {
                // 构造停止服务的 Intent，并标记 action
                ctx.stopService(Intent(ctx, TrackerService::class.java).apply { action = ACTION_STOP })
            }
        }
    }
}

