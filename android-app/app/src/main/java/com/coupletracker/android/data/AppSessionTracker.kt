package com.coupletracker.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * UI 层持久状态单例 + 上报双保险。
 * 
 * 解决的问题：
 * 1. BottomNavigation 切 Tab → remember/rememberSaveable 会丢 → 用 Kotlin object
 * 2. 后台 TrackerService 可能被杀 → UI 层直接上报到 Supabase
 */
object AppSessionTracker {

    // ===== 心情 =====
    private val _mood = MutableStateFlow("😐")
    val mood = _mood.asStateFlow()
    fun setMood(emoji: String) { _mood.value = emoji }

    // ===== 当前 APP 会话 =====
    private var sessionStartAt = 0L
    private var sessionReportAt = 0L   // 上次上报时间
    private var sessionReportSec = 0   // 上次上报后累计秒数（用于计算增量）
    
    private val _currentPkg = MutableStateFlow("")
    private val _currentName = MutableStateFlow("")
    private val lastUploadedAt = ConcurrentHashMap<String, Long>()  // 每个 pkg 单独记录
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    /** 上报状态（UI 调试用） */
    private val _lastReportStatus = MutableStateFlow("等待上报...")
    val lastReportStatus = _lastReportStatus.asStateFlow()

    /** 设置当前 APP —— 同时触发 UI 层上报（双保险） */
    fun setCurrentApp(pkg: String, name: String) {
        if (pkg.isEmpty()) return
        
        if (pkg != _currentPkg.value) {
            // APP 切换 → 补报前一个 APP（如果 ≥ 10 秒）
            if (_currentPkg.value.isNotEmpty() && sessionStartAt > 0) {
                val prevSec = ((System.currentTimeMillis() - sessionStartAt) / 1000).toInt()
                if (prevSec >= 10) {
                    uploadUsage(_currentPkg.value, _currentName.value.ifBlank { _currentPkg.value }, prevSec)
                }
            }
            _currentPkg.value = pkg
            _currentName.value = name
            sessionStartAt = System.currentTimeMillis()
            sessionReportAt = sessionStartAt
        } else {
            _currentName.value = name
        }
        
        // 每 15 秒定时上报当前 APP（UI 层双保险）
        val now = System.currentTimeMillis()
        if (now - sessionReportAt >= 15_000L) {
            val sec = ((now - sessionReportAt) / 1000).toInt().coerceAtLeast(1)
            sessionReportAt = now
            uploadUsage(pkg, name.ifBlank { pkg }, sec)
        }
    }

    fun sessionSeconds(): Int {
        if (sessionStartAt == 0L) return 0
        return ((System.currentTimeMillis() - sessionStartAt) / 1000).toInt().coerceAtLeast(0)
    }

    /** UI 层直接 POST 到 Supabase（和后台 TrackerService 互相独立的双保险） */
    private fun isSystemNoisePkg(pkg: String?): Boolean {
    if (pkg.isNullOrBlank()) return true
    val p = pkg.lowercase()
    if (p.contains("launcher") || p.contains("systemui") || p.contains("desk") || p.contains("homescreen")) return true
    if (p.contains("inputmethod") || p.contains("ime") || p.contains("input.")
        || p.contains("sougou") || p.contains("sogou") || p.contains("baidu.input")
        || p.contains("iflytek") || p.contains("讯飞")) return true
    if (p.contains("uiautomator") || p.contains("statusbar") || p.contains("navigationbar")
        || p.contains("keyguard") || p.contains("lockscreen") || p.contains("powerui")
        || p.contains("notifications") || p.contains("system.dialog")) return true
    if (p.contains("packageinstaller") || p.contains("permissioncontroller")) return true
    if (p.length < 5 || !p.contains('.')) return true
    return false
}

private fun uploadUsage(pkg: String, name: String, seconds: Int) {
    if (isSystemNoisePkg(pkg)) { _lastReportStatus.value = "跳过系统噪音 "; return }
    scope.launch {
            runCatching {
                val user = UserRepository.get().getUser()
                val userId = user?.id ?: run {
                    _lastReportStatus.value = "⚠️ 未登录，跳过上报"
                    return@runCatching
                }
                
                // 防止同一个 pkg 短时间内重复上报（> 10 秒才允许再次上报）
                val key = pkg
                val lastUp = lastUploadedAt[key] ?: 0L
                if (System.currentTimeMillis() - lastUp < 5_000L) return@runCatching
                lastUploadedAt[key] = System.currentTimeMillis()
                
                val resp = NetworkModule.restService.reportAppUsage(
                    AppUsageRow(
                        user_id = userId,
                        couple_id = null,
                        package_name = pkg,
                        app_name = name,
                        category = categorizePkg(pkg),
                        usage_seconds = seconds
                    )
                )
                _lastReportStatus.value = when {
                    resp.isSuccessful -> "✅ 上报成功 · $name ${seconds}s"
                    else -> "⚠️ 上报失败 HTTP ${resp.code()}"
                }
            }.onFailure { e ->
                _lastReportStatus.value = "⚠️ 上报异常：${e.message?.take(40) ?: ""}"
            }
        }
    }
    
    private fun categorizePkg(pkg: String): String = when {
        pkg.contains("tencent.mm") || pkg.contains("wechat") || pkg.contains("mobileqq") -> "社交"
        pkg.contains("douyin") || pkg.contains("aweme") || pkg.contains("bilibili") -> "视频"
        pkg.contains("netease.cloud") || pkg.contains("qqmusic") || pkg.contains("kugou") -> "音乐"
        pkg.contains("taobao") || pkg.contains("jd") || pkg.contains("pinduoduo") -> "购物"
        pkg.contains("coupletracker") -> "其他"
        else -> "其他"
    }
}

