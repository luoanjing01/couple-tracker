package com.coupletracker.android.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.coupletracker.android.R
import com.coupletracker.android.appmonitor.AppUsageMonitor
import com.coupletracker.android.data.UserRepository
import com.coupletracker.android.location.LocationTracker
import com.coupletracker.android.ui.MainActivity
import kotlinx.coroutines.*

/**
 * 情侣实时报备前台服务
 * - 只要用户登录就保持运行，持续上报位置和APP使用情况
 * - 开机自启、APP更新自启、APP被杀死后尝试自恢复
 * - 使用 CoroutineScope + SupervisorJob 管理子协程
 *
 * ✅ 闪退兜底：所有可能抛异常的地方全部 runCatching 包裹，
 *    缺权限、网络不好、SDK 报错 → 全部静默降级，绝不崩主进程
 */
class TrackerService : Service() {

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("TrackerService")
    )
    private var locationTracker: LocationTracker? = null
    private var appMonitor: AppUsageMonitor? = null
    private var batteryJob: Job? = null
    private var locationJob: Job? = null
    private var appJob: Job? = null
    private var createdSafely: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            // 1. 初始化定位和 APP 监控（失败不影响继续）
            locationTracker = runCatching { LocationTracker(this, serviceScope) }.getOrNull()
            appMonitor   = runCatching { AppUsageMonitor(this, serviceScope) }.getOrNull()

            // 2. 启动前台服务（若缺少通知权限 → 降级成后台服务，不崩）
            if (canStartForeground()) {
                runCatching {
                    startForeground(NOTIF_ID, buildNotification("💕 正在连接服务..."))
                }
            }

            // 3. 监听用户登录态 + 采集频率 → 动态启停 tracker
            serviceScope.launch {
                runCatching {
                    val repo = UserRepository.get()
                    // 同时组合 3 个流：用户态、位置频率、APP频率
                    kotlinx.coroutines.flow.combine(
                        repo.userFlow,
                        repo.locationIntervalSecFlow,
                        repo.appIntervalSecFlow
                    ) { user, locSec, appSec -> Triple(user, locSec, appSec) }
                        .collect { (user, locSec, appSec) ->
                            runCatching {
                                if (user != null) {
                                    if (canStartForeground()) {
                                        runCatching {
                                            updateNotification("💕 已登录 · ${user.nickname.ifBlank { user.username }}")
                                        }
                                    }
                                    // 位置采集：频率变化时重启
                                    val locMs = (locSec * 1000L).coerceAtLeast(2000L)
                                    restartLocation(locMs)
                                    // APP 使用采集：频率变化时重启
                                    val appMs = (appSec * 1000L).coerceAtLeast(1000L)
                                    restartAppMonitor(appMs)
                                    // 电量检测（10秒一次）
                                    startBatteryMonitor()
                                } else {
                                    stopSelf()
                                }
                            }
                        }
                }
            }
            createdSafely = true
        }
    }

    /** 启动位置采集（若已有旧协程，先停后起） */
    private fun restartLocation(intervalMs: Long) {
        runCatching {
            locationJob?.cancel()
            val tracker = locationTracker ?: return
            if (tracker.hasPermission()) {
                locationJob = serviceScope.launch(Dispatchers.Default) {
                    runCatching { tracker.stop() }
                    runCatching { tracker.start(intervalMs) }
                }
            }
        }
    }

    /** 启动 APP 使用监控采集（若已有旧协程，先停后起） */
    private fun restartAppMonitor(pollMs: Long) {
        runCatching {
            appJob?.cancel()
            val mon = appMonitor ?: return
            if (mon.hasUsagePermission()) {
                appJob = serviceScope.launch(Dispatchers.Default) {
                    runCatching { mon.stop() }
                    runCatching { mon.start(pollMs) }
                }
            }
        }
    }

    /** 启动前台服务所需权限：Android 13+ 需要 POST_NOTIFICATIONS；Android 14 location 类型需要定位权限 */
    private fun canStartForeground() = canStartForeground(this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 🔴 关键修复：如果 onCreate 时因权限不够跳过了 startForeground，
        //    但后来权限被授予 → onStartCommand 会在 MainActivity 再次 startService 时触发，
        //    此时补调 startForeground() 让服务真正变成前台服务，否则会被系统杀掉
        if (canStartForeground()) {
            runCatching {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (runCatching { nm.getNotificationChannel(getString(R.string.tracker_channel_id)) }.getOrNull() == null) {
                    // 兜底：渠道还没建（极端情况），赶紧建一个
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        nm.createNotificationChannel(NotificationChannel(
                            getString(R.string.tracker_channel_id),
                            getString(R.string.tracker_channel_name),
                            NotificationManager.IMPORTANCE_LOW
                        ).apply { description = getString(R.string.tracker_channel_desc) })
                    }
                }
                if (!createdSafely) {
                    // onCreate 可能因为异常没跑完 → 现在补初始化
                    runCatching {
                        locationTracker = runCatching { LocationTracker(this, serviceScope) }.getOrNull()
                        appMonitor = runCatching { AppUsageMonitor(this, serviceScope) }.getOrNull()
                        Companion.appMonitor = appMonitor
                    }
                    createdSafely = true
                }
                runCatching {
                    startForeground(NOTIF_ID, buildNotification("💕 正在连接服务..."))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { serviceScope.cancel() }
        runCatching { locationTracker?.stop() }
        runCatching { appMonitor?.stop() }
        runCatching { batteryJob?.cancel() }
        runCatching { super.onDestroy() }
    }

    // --- 电量监控，更新到定位里顺便上报 ---
    private fun startBatteryMonitor() {
        runCatching {
            if (batteryJob?.isActive == true) return
            batteryJob = serviceScope.launch(Dispatchers.IO) {
                while (isActive) {
                    runCatching {
                        val pct = getBatteryPct()
                        locationTracker?.setBatteryCache(pct)
                    }
                    delay(10_000L)
                }
            }
        }
    }

    private fun getBatteryPct(): Int {
        return runCatching {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it > 0 }
        }.getOrNull() ?: runCatching {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = registerReceiver(null, ifilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level < 0 || scale <= 0) null else (level * 100 / scale)
        }.getOrNull() ?: -1
    }

    // --- 通知栏 ---
    private fun buildNotification(text: String): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, getString(R.string.tracker_channel_id))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setColor(0xFFE75480.toInt())
            .setContentTitle(getString(R.string.tracker_notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        }
    }

    companion object {
        private const val NOTIF_ID = 10086
        const val ACTION_STOP = "com.coupletracker.ACTION_STOP_SERVICE"

        /** 静态引用 AppUsageMonitor，UI 层直接读后台数据（累计时长不丢） */
        @Volatile var appMonitor: com.coupletracker.android.appmonitor.AppUsageMonitor? = null

        /** 启动前台服务所需权限（静态版，供 Activity 提前检查） */
        fun canStartForeground(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) return false
            }
            // Android 14 规定：foregroundServiceType=location 时必须已授予至少粗略定位权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val fine = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                val coarse = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
                if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }
            return true
        }

        /** 启动服务：全部异常吞掉 → 永不闪退 */
        fun start(ctx: Context) {
            runCatching {
                val i = Intent(ctx, TrackerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            }
        }

        fun stop(ctx: Context) {
            runCatching {
                ctx.stopService(Intent(ctx, TrackerService::class.java).apply { action = ACTION_STOP })
            }
        }
    }
}
