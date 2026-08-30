package com.coupletracker.android.appmonitor

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Process
import com.coupletracker.android.data.AppUsageRow
import com.coupletracker.android.data.NetworkModule
import com.coupletracker.android.data.UserRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用使用监控器：基于系统 UsageStatsManager
 * - 每2秒检查一次前台APP
 * - 每60秒把当前APP的使用时长汇总上报到 Supabase app_usage 表
 */
class AppUsageMonitor(private val context: Context, private val scope: CoroutineScope) {

    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val pm = context.packageManager
    private var job: Job? = null
    private var lastPackage: String = ""
    private var lastReportAt: Long = 0L

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
        lastReportAt = System.currentTimeMillis()
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
        if (fg.isEmpty()) return

        if (fg != lastPackage) {
            // APP 切换了 → 把旧 APP 的使用时长结算一下（简化：切换时也上报一次 0 时长，方便统计）
            lastPackage = fg
            lastReportAt = now
        }

        // 每 60 秒上报一次当前 APP 的使用时长
        if (now - lastReportAt >= 60_000L) {
            val elapsedSeconds = ((now - lastReportAt) / 1000).toInt().coerceAtLeast(1)
            lastReportAt = now
            val (appName, category) = getAppMeta(fg)
            _currentApp.tryEmit(fg to appName)
            scope.launch(Dispatchers.IO) {
                val user = UserRepository.get().getUser()
                val userId = user?.id ?: return@launch
                val coupleId = userId // 简化处理
                runCatching {
                    NetworkModule.restService.reportAppUsage(
                        AppUsageRow(
                            user_id = userId,
                            couple_id = coupleId,
                            package_name = fg,
                            app_name = appName,
                            category = category,
                            usage_seconds = elapsedSeconds
                        )
                    )
                }
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
    private val isSystemApp: Boolean get() = false
}
