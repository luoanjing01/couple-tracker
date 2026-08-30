package com.coupletracker.android.appmonitor

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import com.coupletracker.android.data.NetworkModule
import com.coupletracker.android.data.model.ForegroundAppRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用使用监控器：基于系统 UsageStatsManager
 * - 每2秒检查一次前台APP（切换即上报 /api/app-usage/foreground）
 * - 每60秒心跳（/api/app-usage/heartbeat）刷新当前使用时长
 */
class AppUsageMonitor(private val context: Context, private val scope: CoroutineScope) {

    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val pm = context.packageManager
    private var job: Job? = null
    private var lastPackage: String = ""
    private var lastHbAt: Long = 0L

    private val _currentApp = MutableStateFlow<Pair<String, String>?>(null)
    val currentApp = _currentApp.asStateFlow()

    fun hasUsagePermission(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun createUsageSettingsIntent(): Intent =
        Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun start(pollMs: Long = 2000L) {
        if (job?.isActive == true) return
        lastHbAt = System.currentTimeMillis()
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { checkAndReport() }
                delay(pollMs)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private fun checkAndReport() {
        if (!hasUsagePermission()) return
        val now = System.currentTimeMillis()
        val fg = getForegroundPackage() ?: return
        if (fg.isEmpty() || fg == lastPackage) {
            if (now - lastHbAt >= 60_000L && lastPackage.isNotEmpty()) {
                lastHbAt = now
                scope.launch(Dispatchers.IO) {
                    runCatching { NetworkModule.api.heartbeatApp() }
                }
            }
            return
        }
        lastPackage = fg
        lastHbAt = now
        val (appName, category) = getAppMeta(fg)
        _currentApp.tryEmit(fg to appName)
        scope.launch(Dispatchers.IO) {
            runCatching {
                NetworkModule.api.reportForeground(
                    ForegroundAppRequest(
                        packageName = fg,
                        appName = appName,
                        appCategory = category
                    )
                )
            }
        }
    }

    private fun getForegroundPackage(): String? {
        val end = System.currentTimeMillis()
        val begin = end - 60_000L
        val events = usm.queryEvents(begin, end)
        val ev = UsageEvents.Event()
        var latestFg: String? = null
        var latestTime = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                ev.timeStamp > latestTime) {
                latestTime = ev.timeStamp
                latestFg = ev.packageName
            }
        }
        return latestFg
    }

    private fun getAppMeta(pkg: String): Pair<String, String> {
        return runCatching {
            val info = pm.getApplicationInfo(pkg, 0)
            val name = pm.getApplicationLabel(info).toString()
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val cat = when (info.category) {
                ApplicationInfo.CATEGORY_GAME -> "游戏"
                ApplicationInfo.CATEGORY_SOCIAL -> "社交"
                ApplicationInfo.CATEGORY_VIDEO -> "视频"
                ApplicationInfo.CATEGORY_AUDIO -> "音乐"
                ApplicationInfo.CATEGORY_NEWS -> "新闻"
                ApplicationInfo.CATEGORY_MAPS -> "地图"
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> "效率"
                ApplicationInfo.CATEGORY_IMAGE -> "图像"
                else -> categorizeByPackage(pkg)
            }
            name to cat
        }.getOrElse { pkg to "其他" }
    }

    private fun categorizeByPackage(pkg: String): String = when {
        pkg.contains("wechat") || pkg.contains("tencent.mm")
                || pkg.contains("qq") -> "社交"
        pkg.contains("douyin") || pkg.contains("aweme")
                || pkg.contains("bilibili") || pkg.contains("kuaishou") -> "视频"
        pkg.contains("netease.cloud") || pkg.contains("qqmusic")
                || pkg.contains("spotify") || pkg.contains("kugou")
                || pkg.contains("kuwo") -> "音乐"
        pkg.contains("taobao") || pkg.contains("tmall")
                || pkg.contains("jd") || pkg.contains("pinduoduo") -> "购物"
        pkg.contains("meituan") || pkg.contains("ele")
                || pkg.contains("dianping") -> "生活"
        pkg.contains("chrome") || pkg.contains("browser") -> "浏览器"
        pkg.contains("launcher") || pkg.contains("systemui") -> "桌面"
        else -> "其他"
    }

    @Suppress("unused")
    private val isSystemApp: Boolean get() = false // 保留字段，不再单独上报
}
