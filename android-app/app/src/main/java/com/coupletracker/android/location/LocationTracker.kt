package com.coupletracker.android.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.coupletracker.android.data.NetworkModule
import com.coupletracker.android.data.UserRepository
import kotlinx.coroutines.*

/**
 * GPS/网络定位追踪器：基于 Android 原生 LocationManager（不依赖GMS）
 *
 * 🔴 为什么不用 FusedLocationProvider？
 *    FusedLocationProvider 属于 Google Mobile Services (GMS)，
 *    国内 iQOO / VIVO / OPPO / 华为 / 小米 等手机默认不带 GMS 包，
 *    导致 requestLocationUpdates 注册成功但回调永不触发，lastLocation.await() 永远 null，
 *    最终 locations 表 0 条记录 → 地图永远"等待位置..."。
 *    改用原生 LocationManager 后，100% 国产机兼容。
 *
 * ✅ 定位策略：GPS_PROVIDER（高精度室外）+ NETWORK_PROVIDER（室内/WiFi/基站）双开，
 *    任一有新结果都回调，取"更新的/更准的"优先。
 * ✅ 默认每 8 秒上报一次（3 秒内位移<5m则跳过，省电省流量）
 * ✅ 启动时立刻读取所有 provider 的 lastKnownLocation 选出最新的 force 上报，
 *    保证地图立刻有位置，不等 provider 下一次扫描。
 */
class LocationTracker(private val context: Context, private val scope: CoroutineScope) {

    private val locMgr by lazy { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }

    // GPS + Network 双监听器
    private var gpsListener: LocationListener? = null
    private var netListener: LocationListener? = null

    private var lastLocation: Location? = null
    private var lastReportAt = 0L
    @Volatile private var batteryPct: Int? = null

    /** TrackerService 每10秒把电量缓存到这里，上报位置时顺便带上 */
    fun setBatteryCache(pct: Int) { batteryPct = pct }

    fun hasPermission(): Boolean {
        val fine   = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    suspend fun start(intervalMs: Long = 8000L) {
        if (!hasPermission()) {
            NetworkModule.lastLocationReportStatus.value =
                "⚠️ 无定位权限：系统设置 → CoupleTracker → 权限 → 定位 → 选择【始终允许】"
            return
        }
        val providersEnabled = mutableListOf<String>()
        runCatching {
            if (locMgr.isProviderEnabled(LocationManager.GPS_PROVIDER))     providersEnabled += LocationManager.GPS_PROVIDER
        }
        runCatching {
            if (locMgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) providersEnabled += LocationManager.NETWORK_PROVIDER
        }
        if (providersEnabled.isEmpty()) {
            NetworkModule.lastLocationReportStatus.value =
                "⚠️ 定位功能未开启：请打开手机「位置信息/GPS」开关（仅权限授权还不够）"
        }

        // —— 第一步：启动时立刻读取 lastKnownLocation（所有provider，取最新），force 上报 ——
        runCatching { pickBestLastKnown() }?.let { report(it, force = true) }

        // —— 第二步：订阅 GPS_PROVIDER 定期更新（高精度室外）——
        if (providersEnabled.contains(LocationManager.GPS_PROVIDER)) {
            gpsListener = object : LocationListener {
                override fun onLocationChanged(loc: Location) { report(loc, force = false) }
                override fun onProviderDisabled(provider: String) {}
                override fun onProviderEnabled(provider: String)  {}
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            runCatching {
                locMgr.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    intervalMs,    // minTime: 最少间隔 ms
                    0f,            // minDistance: 0 米（我们自行在 report() 里过滤）
                    gpsListener!!,
                    Looper.getMainLooper()
                )
            }
        }

        // —— 第三步：订阅 NETWORK_PROVIDER 定期更新（室内/WiFi/基站兜底）——
        if (providersEnabled.contains(LocationManager.NETWORK_PROVIDER)) {
            netListener = object : LocationListener {
                override fun onLocationChanged(loc: Location) { report(loc, force = false) }
                override fun onProviderDisabled(provider: String) {}
                override fun onProviderEnabled(provider: String)  {}
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            runCatching {
                locMgr.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    intervalMs,
                    0f,
                    netListener!!,
                    Looper.getMainLooper()
                )
            }
        }
    }

    fun stop() {
        gpsListener?.let { runCatching { locMgr.removeUpdates(it) } }; gpsListener = null
        netListener?.let { runCatching { locMgr.removeUpdates(it) } }; netListener = null
    }

    // ========================================================================
    //  工具：从所有 provider 读取 lastKnownLocation，挑"最新且有经纬度"的
    // ========================================================================
    @SuppressLint("MissingPermission")
    private fun pickBestLastKnown(): Location? {
        var best: Location? = null
        val candidates = listOfNotNull(
            runCatching { locMgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)     }.getOrNull(),
            runCatching { locMgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
            runCatching { locMgr.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()
        )
        for (l in candidates) {
            if (l == null) continue
            if (best == null) best = l
            else if (l.time > best.time) best = l
        }
        return best
    }

    // ========================================================================
    //  节流 + 上报云端
    // ========================================================================
    private fun report(loc: Location, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && lastLocation != null) {
            val delta = now - lastReportAt
            val moved = loc.distanceTo(lastLocation!!)
            if (delta < 3000 && moved < 5f) return   // 3秒内移动不足5米 → 省电跳过
        }
        lastLocation = loc; lastReportAt = now
        val isMoving = (loc.hasSpeed() && loc.speed > 0.5f)

        scope.launch(Dispatchers.IO) {
            val user = UserRepository.get().getUser()
            val userId = user?.id ?: return@launch
            // ✅ couple_id 传 null：未配对用户也能写库（之前 FK 已删除）
            val resp = runCatching {
                NetworkModule.restService.reportLocation(
                    com.coupletracker.android.data.LocationRow(
                        user_id       = userId,
                        couple_id     = null,
                        latitude      = loc.latitude,
                        longitude     = loc.longitude,
                        accuracy      = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                        speed         = if (loc.hasSpeed())  loc.speed.toDouble()  else null,
                        battery_level = batteryPct,
                        is_moving     = isMoving
                    )
                )
            }
            val http = resp.getOrNull()
            NetworkModule.lastLocationReportStatus.value =
                when {
                    http == null -> "位置上报异常：${resp.exceptionOrNull()?.message?.take(40).orEmpty()}"
                    !http.isSuccessful -> {
                        val errBody = runCatching { http.errorBody()?.string()?.take(60) }.getOrNull().orEmpty()
                        "位置上报失败 HTTP ${http.code()}：$errBody"
                    }
                    else -> "位置上报成功 · ${String.format("%.4f",loc.latitude)},${String.format("%.4f",loc.longitude)}"
                }
        }
    }
}
