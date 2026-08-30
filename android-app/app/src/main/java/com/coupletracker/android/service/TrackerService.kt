package com.coupletracker.android.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.coupletracker.android.R
import com.coupletracker.android.TrackerApp
import com.coupletracker.android.appmonitor.AppUsageMonitor
import com.coupletracker.android.data.NetworkModule
import com.coupletracker.android.data.UserRepository
import com.coupletracker.android.location.LocationTracker
import com.coupletracker.android.ui.MainActivity
import kotlinx.coroutines.*

/**
 * 情侣实时报备前台服务
 * - 只要用户登录就保持运行，持续上报位置和APP使用情况
 * - 开机自启、APP更新自启、APP被杀死后尝试自恢复
 * - 使用 CoroutineScope + SupervisorJob 管理子协程
 */
class TrackerService : Service() {

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("TrackerService")
    )
    private lateinit var locationTracker: LocationTracker
    private lateinit var appMonitor: AppUsageMonitor
    private var batteryJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationTracker = LocationTracker(this, serviceScope)
        appMonitor = AppUsageMonitor(this, serviceScope)
        startForeground(NOTIF_ID, buildNotification("💕 正在连接服务..."))
        serviceScope.launch {
            UserRepository.get().userFlow.collect { user ->
                if (user != null) {
                    updateNotification("💕 已登录 · ${user.nickname.ifBlank { user.username }}")
                    // ⚡ 位置和APP使用数据会通过 Supabase REST API 自动上报
                    // 启动定位（5秒一次）
                    if (locationTracker.hasPermission()) {
                        locationTracker.start(5000L)
                    }
                    // 启动APP监控（2秒一次）
                    if (appMonitor.hasUsagePermission()) {
                        appMonitor.start(2000L)
                    }
                    // 启动电量检测（10秒一次）
                    startBatteryMonitor()
                } else {
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        runCatching { locationTracker.stop() }
        runCatching { appMonitor.stop() }
        batteryJob?.cancel()
        super.onDestroy()
    }

    // --- 电量监控，更新到定位里顺便上报 ---
    private fun startBatteryMonitor() {
        if (batteryJob?.isActive == true) return
        batteryJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                val pct = getBatteryPct()
                locationTracker.setBatteryCache(pct)
                delay(10_000L)
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
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val NOTIF_ID = 10086
        const val ACTION_STOP = "com.coupletracker.ACTION_STOP_SERVICE"

        fun start(ctx: Context) {
            val i = Intent(ctx, TrackerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TrackerService::class.java).apply { action = ACTION_STOP })
        }
    }
}
