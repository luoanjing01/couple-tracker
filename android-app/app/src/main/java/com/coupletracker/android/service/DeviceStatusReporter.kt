// =============================================================================
// 【文件说明】
// 设备状态心跳上报器（DeviceStatusReporter）。
//
// 【它解决什么问题？】
// 旧版"手机状态"靠 locations 表最后一条位置记录的时间戳推断对方是否在线：
//   - 后台服务被国产 ROM 杀掉 / 手机静止不上报位置 → 超过 5 分钟误显示"关机"
//   - 云端没有网络/WiFi 字段 → 对方网络永远显示"云端未记录"
//
// 【行业做法】（Life360 / Zenly 等定位类 App 的通用方案）
// 设备状态与位置上报解耦：
//   1. 60 秒定时心跳：无论是否移动，持续 upsert 自己的状态到 device_status 表
//   2. 状态变化即报：充电插拔、亮熄屏、网络切换时立即上报（带 10 秒防抖）
//   3. 每用户一行（user_id 主键 upsert），云端永远只保留"最新状态"
//
// 【安全兜底】所有系统调用/网络请求均 runCatching 包裹，绝不让服务崩溃。
// =============================================================================

package com.coupletracker.android.service

import android.content.BroadcastReceiver          // 广播接收器（监听电量变化、亮熄屏）
import android.content.Context                    // Android 上下文
import android.content.Intent                     // 意图对象
import android.content.IntentFilter               // 广播过滤器
import android.net.ConnectivityManager            // 网络连接管理器
import android.net.NetworkCapabilities            // 网络能力描述（WiFi/蜂窝等）
import android.net.NetworkRequest                 // 网络监听请求构建器
import android.net.wifi.WifiManager               // WiFi 管理（读取 SSID）
import android.os.BatteryManager                  // 电池信息
import android.os.PowerManager                    // 电源管理（判断亮屏/熄屏）
import com.coupletracker.android.data.DeviceStatusUpsert  // 上报请求体
import com.coupletracker.android.data.NetworkModule       // 网络模块
import com.coupletracker.android.data.UserRepository      // 用户仓库
import kotlinx.coroutines.*                       // 协程
import java.time.Instant                          // UTC 时间戳

/**
 * 设备状态心跳上报器
 *
 * @param context Android 上下文（TrackerService 传 this）
 * @param scope   协程作用域（跟随 TrackerService 生命周期，服务销毁时自动取消）
 */
class DeviceStatusReporter(private val context: Context, private val scope: CoroutineScope) {

    // 心跳协程句柄；为空表示未启动
    private var heartbeatJob: Job? = null

    // 上次上报时间（毫秒），用于"变化即报"的防抖
    @Volatile private var lastReportAt: Long = 0L

    // 广播/回调是否已注册（防止重复注册）
    @Volatile private var listenersRegistered = false

    // ---- 系统广播接收器：电量变化 + 亮熄屏（注册一次，回调里触发"变化即报"）----
    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            // 电量变化 / 亮熄屏 → 立即上报一次（内部有防抖）
            reportThrottled()
        }
    }

    // ---- 网络回调：WiFi/蜂窝切换时触发"变化即报" ----
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: android.net.Network, nc: NetworkCapabilities) {
            reportThrottled()
        }
        override fun onLost(network: android.net.Network) {
            reportThrottled()
        }
    }

    /**
     * 启动心跳：60 秒定时上报 + 注册状态变化监听
     * 重复调用安全（幂等）：已在运行则直接返回
     */
    fun start() {
        if (heartbeatJob?.isActive == true) return
        registerListeners()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                reportNow()
                delay(60_000L)   // 60 秒心跳（Life360 类产品的常见心跳间隔）
            }
        }
    }

    /** 停止心跳并注销监听（服务销毁时调用） */
    fun stop() {
        runCatching { heartbeatJob?.cancel() }
        heartbeatJob = null
        unregisterListeners()
    }

    // ===========================================================================
    // 内部实现
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

    /** 变化即报（防抖：距上次上报 < 10 秒则跳过，避免广播风暴打满网络） */
    private fun reportThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastReportAt < 10_000L) return
        scope.launch(Dispatchers.IO) { reportNow() }
    }

    /**
     * 采集当前设备状态并 upsert 到云端（核心方法）
     * 每一步采集都独立 runCatching，单点失败不影响其他字段
     */
    private suspend fun reportNow() {
        runCatching {
            // 未登录就不上报（ upsert 需要 user_id ）
            val userId = UserRepository.get().getUser()?.id ?: return

            // ① 电量 + 充电状态（粘性广播读取，无需长期监听）
            val batteryIntent = context.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else null
            val chargeStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = chargeStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                    chargeStatus == BatteryManager.BATTERY_STATUS_FULL

            // ② 网络类型 + WiFi SSID
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nc = cm.getNetworkCapabilities(cm.activeNetwork)
            val networkType = when {
                nc == null -> "none"
                nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
            // 仅在 WiFi 下尝试读 SSID；读不到（权限收紧）则为 null，显示方会降级为"WiFi"
            val wifiSsid = if (networkType == "wifi") readWifiSsid() else null

            // ③ 屏幕亮/灭
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val screenOn = pm.isInteractive

            // ④ upsert 到云端（updated_at 用客户端当前 UTC 时间，语义=心跳时刻）
            NetworkModule.restService.upsertDeviceStatus(
                DeviceStatusUpsert(
                    user_id = userId,
                    battery_level = batteryPct,
                    is_charging = isCharging,
                    network_type = networkType,
                    wifi_ssid = wifiSsid,
                    screen_on = screenOn,
                    updated_at = Instant.now().toString()
                )
            )
            lastReportAt = System.currentTimeMillis()
        }
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
